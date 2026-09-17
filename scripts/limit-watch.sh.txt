#!/bin/bash
# drom-flow — Claude usage-limit watcher.
#
#   limit-watch.sh status            window usage, learned budget, percent, reset time
#   limit-watch.sh check             hook entry point: trigger + arm if >= threshold
#   limit-watch.sh arm [--reset EPOCH]   checkpoint runs, arm the hourly ping
#   limit-watch.sh disarm            clear armed state
#   limit-watch.sh ping              wake-up entry: still blocked? re-arm : resume
#   limit-watch.sh verify [--json]   evaluate the eight gates
#
# IMPORTANT: Claude Code exposes no live quota meter. The limit EVENT is exact (a
# synthetic transcript message carrying the reset time); the PERCENTAGE is an
# estimate against a budget learned from past limit events. Never present the
# estimate as a reading.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$REPO_ROOT/.claude/.state"
REPORT_DIR="$REPO_ROOT/reports"
BUDGET_FILE="$STATE/limit-budget.json"
ARMED_FILE="$STATE/limit-armed.json"
PCT="${LIMIT_WATCH_PCT:-97}"
MAX_PINGS="${LIMIT_WATCH_MAX_PINGS:-12}"
PING_INTERVAL="${LIMIT_WATCH_INTERVAL:-3600}"
mkdir -p "$STATE" "$REPORT_DIR"

log() { echo "[limit-watch] $*" >&2; }

transcript() {
  [[ -n "${CLAUDE_TRANSCRIPT:-}" && -f "${CLAUDE_TRANSCRIPT:-}" ]] && { echo "$CLAUDE_TRANSCRIPT"; return 0; }
  local d="$HOME/.claude/projects/$(echo "$REPO_ROOT" | sed 's|/|-|g')"
  [[ -d "$d" ]] || return 1
  local f; f="$(ls -t "$d"/*.jsonl 2>/dev/null | head -1)"
  [[ -n "$f" ]] && echo "$f"
}

# Core analysis: limit events + current-window billable usage.
# Billable EXCLUDES cache_read: it dominates raw counts (tens of millions per
# session) but is not what exhausts a session limit, so including it would make
# the percentage meaningless.
analyze() {
  local f="${1:-}" budget="${2:-0}"
  python3 - "$f" "$budget" <<'PY'
import json,re,sys,os,time
from datetime import datetime,timedelta
f,budget=sys.argv[1],float(sys.argv[2])
events=[]; turns=[]
if f and os.path.exists(f):
    for line in open(f,errors='replace'):
        if '"usage"' not in line and 'limit' not in line: continue
        try: o=json.loads(line)
        except Exception: continue
        m=o.get('message')
        if not isinstance(m,dict): continue
        ts=o.get('timestamp','')
        # limit event: synthetic assistant message carrying the reset time
        if m.get('model')=='<synthetic>':
            txt=' '.join(b.get('text','') for b in (m.get('content') or []) if isinstance(b,dict))
            mm=re.search(r"hit your (?:session|usage) limit.*?resets\s+([0-9]{1,2}:[0-9]{2}\s*(?:am|pm))\s*(?:\(([^)]+)\))?",txt,re.I)
            if mm: events.append({'ts':ts,'reset_clock':mm.group(1).strip(),'tz':(mm.group(2) or '').strip(),'text':txt[:120]})
            continue
        u=m.get('usage')
        if u: turns.append({'ts':ts,'billable':u.get('output_tokens',0)+u.get('input_tokens',0)+u.get('cache_creation_input_tokens',0)})

# Window = strictly AFTER the last limit event. With no events the window is the
# whole session — anchoring on the first turn and then using a strict > comparison
# silently drops that first turn (an off-by-one that shifts the percentage).
win_start=events[-1]['ts'] if events else ''
used=sum(t['billable'] for t in turns if t['ts']>win_start) if win_start else sum(t['billable'] for t in turns)

# Consumption per completed window, for budget learning.
# Limit events cluster: once a window is exhausted, every further attempt records
# another event minutes apart. Those gaps are re-hits, NOT full windows, and
# learning from them yields an absurdly low budget. Only count a gap as a real
# window if it is at least MIN_WINDOW_S long.
MIN_WINDOW_S=1800
def secs(a,b):
    from datetime import datetime
    try:
        fmt='%Y-%m-%dT%H:%M:%S.%f%z'
        pa=datetime.strptime(a.replace('Z','+0000'),fmt); pb=datetime.strptime(b.replace('Z','+0000'),fmt)
        return (pb-pa).total_seconds()
    except Exception: return 0
obs=[]; prev=''            # '' == before the first turn, so window 1 includes it
first_ts=turns[0]['ts'] if turns else ''
for e in events:
    tot=sum(t['billable'] for t in turns if (not prev or prev<t['ts']) and t['ts']<=e['ts'])
    span=secs(prev or first_ts, e['ts'])
    if tot>0 and span>=MIN_WINDOW_S: obs.append(tot)
    prev=e['ts']
# The in-flight window is a LOWER BOUND on the true budget: we have spent this
# much without being limited, so any learned budget below it is provably wrong.
lower_bound=used

def reset_epoch(ev):
    """Resolve the reset clock against the EVENT's own day, not today's.

    Anchoring on `now` makes a limit event from yesterday resolve to a reset later
    today, so a long-expired event looks permanently 'active' and the definitive
    trigger fires forever.
    """
    if not ev: return 0
    try:
        h,rest=ev['reset_clock'].split(':'); mnt=int(rest[:2]); h=int(h)
        ampm=rest[2:].strip().lower()
        if ampm.startswith('pm') and h!=12: h+=12
        if ampm.startswith('am') and h==12: h=0
        ev_local=datetime.strptime(ev['ts'].replace('Z','+0000'),'%Y-%m-%dT%H:%M:%S.%f%z').astimezone()
        cand=ev_local.replace(hour=h,minute=mnt,second=0,microsecond=0)
        if cand<ev_local: cand+=timedelta(days=1)   # reset is after the event
        return int(cand.timestamp())
    except Exception: return 0

re_ep=reset_epoch(events[-1] if events else None)
# The limit is only ACTIVE while its reset is still in the future.
limit_active = bool(re_ep and re_ep>int(time.time()))
pct = round(used/budget*100,1) if budget>0 else None
print(json.dumps({'window_start':win_start,'used_billable':used,'turns':len(turns),'lower_bound':lower_bound,
 'events':events,'event_count':len(events),'observations':obs,
 'budget':budget,'percent':pct,
 'last_reset_clock':events[-1]['reset_clock'] if events else '',
 'last_reset_epoch':re_ep,'limit_active':limit_active}))
PY
}

learned_budget() {
  python3 - "$BUDGET_FILE" <<'PY'
import json,os,sys
p=sys.argv[1]
if os.environ.get('CLAUDE_TOKEN_BUDGET'):
    print(os.environ['CLAUDE_TOKEN_BUDGET']); raise SystemExit
try: print(json.load(open(p)).get('learned_budget',0) or 0)
except Exception: print(0)
PY
}

# Fold newly observed window consumption into the learned budget (rolling median
# — robust to one anomalous window).
calibrate() {
  local f; f="$(transcript)" || return 0
  local a; a="$(analyze "$f" 0)"
  python3 - "$BUDGET_FILE" "$a" <<'PY'
import json,os,sys,statistics,time
p,a=sys.argv[1],json.loads(sys.argv[2])
obs=a.get('observations') or []
lb=int(a.get('lower_bound') or 0)
d={'observed':[],'learned_budget':0,'confidence':'low'}
if os.path.exists(p):
    try: d=json.load(open(p))
    except Exception: pass
d['observed']=obs
cand=int(statistics.median(obs)) if obs else 0
# A budget below the in-flight window's spend is provably wrong — we got that far
# without being limited. Take the larger, and say so in the confidence field.
d['learned_budget']=max(cand,lb,int(d.get('learned_budget') or 0))
d['lower_bound']=lb
d['confidence']=('high' if len(obs)>=3 else 'low') if cand>=lb else 'low-bounded'
d['updated']=int(time.time())
tmp=p+'.tmp'; json.dump(d,open(tmp,'w'),indent=2); os.replace(tmp,p)
print(json.dumps({'learned_budget':d['learned_budget'],'confidence':d['confidence'],'n':len(obs)}))
PY
}

cmd_status() {
  local f b a; f="$(transcript)" || { echo '{"state":"unknown","reason":"no transcript"}'; return 0; }
  calibrate >/dev/null
  b="$(learned_budget)"; a="$(analyze "$f" "$b")"
  python3 - "$a" "$BUDGET_FILE" "$PCT" <<'PY'
import json,sys,os,time
a=json.loads(sys.argv[1]); bf=sys.argv[2]; pct=float(sys.argv[3])
conf='low'
try: conf=json.load(open(bf)).get('confidence','low')
except Exception: pass
if os.environ.get('CLAUDE_TOKEN_BUDGET'): conf='explicit'   # user-supplied, trust it
p=a['percent']
# 'low-bounded' means the budget is merely what we have already spent, so the
# percentage is degenerate (always ~100%) and must not be treated as a reading.
if conf=='low-bounded': p=None; a['percent']=None; a['percent_note']='budget is a lower bound only — percentage not meaningful yet'
a.update({'confidence':conf,'threshold':pct,
          'state':('limited' if a.get('limit_active') else ('unknown' if p is None else ('over' if p>=pct else 'ok'))),
          'reset_in_s':max(0,a['last_reset_epoch']-int(time.time())) if a['last_reset_epoch'] else 0})
a.pop('observations',None); a.pop('events',None)
print(json.dumps(a,indent=2))
PY
}

# Arm: checkpoint every in-progress fleet run, record state, start the ping timer.
cmd_arm() {
  local reset=0
  while [[ $# -gt 0 ]]; do case $1 in --reset) reset="$2"; shift 2 ;; *) shift ;; esac; done
  local f b a wid; f="$(transcript)" || true
  b="$(learned_budget)"; a="$(analyze "${f:-}" "$b")"
  wid="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['window_start'] or 'none')" "$a")"

  # Idempotent: one arm per window, never stack schedules.
  if [[ -f "$ARMED_FILE" ]] && [[ "$(python3 -c "import json;print(json.load(open('$ARMED_FILE')).get('window_id',''))" 2>/dev/null)" == "$wid" ]]; then
    log "already armed for this window"; cat "$ARMED_FILE"; return 0
  fi

  # Hand off in-progress grok work so it finishes while Claude is blocked.
  local runs=()
  if [[ -d "$REPO_ROOT/.claude/.grok-fleet" ]]; then
    for d in "$REPO_ROOT"/.claude/.grok-fleet/*/; do
      [[ -d "$d/agents" ]] || continue
      local r; r="$(basename "$d")"
      bash "$REPO_ROOT/scripts/grok-fleet.sh" checkpoint --run-id "$r" >/dev/null 2>&1 && runs+=("$r")
    done
  fi
  [[ "$reset" == 0 ]] && reset="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['last_reset_epoch'])" "$a")"

  python3 - "$ARMED_FILE" "$wid" "$reset" "$PING_INTERVAL" "$MAX_PINGS" "${runs[@]:-}" <<'PY'
import json,os,sys,time
p,wid,reset,iv,mx=sys.argv[1],sys.argv[2],int(sys.argv[3]),int(sys.argv[4]),int(sys.argv[5])
runs=[r for r in sys.argv[6:] if r]
d={'armed_at':int(time.time()),'window_id':wid,'reset_epoch':reset,'interval_s':iv,
   'pings':0,'max_pings':mx,'runs':runs,
   'resume_cmd':'bash scripts/grok-fleet.sh resume --run-id <RUN>',
   'next_ping':int(time.time())+iv}
tmp=p+'.tmp'; json.dump(d,open(tmp,'w'),indent=2); os.replace(tmp,p)
print(json.dumps(d,indent=2))
PY
  # Tier-3 wake-up: detached timer drops a flag the hooks surface. Independent of
  # any harness scheduler, so it works even if the session ends.
  ( setsid nohup bash -c "sleep $PING_INTERVAL; touch '$STATE/limit-ping-due'" >/dev/null 2>&1 & ) 2>/dev/null
  log "armed: hourly ping (interval ${PING_INTERVAL}s), ${#runs[@]} run(s) checkpointed"
}

cmd_disarm() { rm -f "$ARMED_FILE" "$STATE/limit-ping-due"; log "disarmed"; }

cmd_ping() {
  [[ -f "$ARMED_FILE" ]] || { log "not armed"; return 0; }
  rm -f "$STATE/limit-ping-due"
  local now; now=$(date +%s)
  python3 - "$ARMED_FILE" "$now" <<'PY'
import json,os,sys,time
p,now=sys.argv[1],int(sys.argv[2])
d=json.load(open(p)); d['pings']=d.get('pings',0)+1
blocked = d['reset_epoch']>now if d.get('reset_epoch') else False
d['last_ping']=now; d['next_ping']=now+d['interval_s']
d['status']='blocked' if blocked else 'quota-likely-restored'
if d['pings']>=d['max_pings']: d['status']='giving-up'
tmp=p+'.tmp'; json.dump(d,open(tmp,'w'),indent=2); os.replace(tmp,p)
print(json.dumps({'ping':d['pings'],'status':d['status'],
                  'reset_in_s':max(0,d.get('reset_epoch',0)-now) if d.get('reset_epoch') else 0,
                  'runs':d.get('runs',[])}))
PY
  local st; st="$(python3 -c "import json;print(json.load(open('$ARMED_FILE'))['status'])")"
  if [[ "$st" == blocked ]]; then
    ( setsid nohup bash -c "sleep $PING_INTERVAL; touch '$STATE/limit-ping-due'" >/dev/null 2>&1 & ) 2>/dev/null
    log "still blocked — re-armed"
  elif [[ "$st" == giving-up ]]; then
    log "max pings reached — disarming"; cmd_disarm
  else
    log "quota likely restored — resume with: bash scripts/grok-fleet.sh resume --run-id <RUN>"
  fi
}

# Hook entry point. Must be cheap: bail out fast, never cost Claude tokens.
cmd_check() {
  local f b a p; f="$(transcript)" || return 0
  b="$(learned_budget)"
  [[ "$b" == 0 ]] && { calibrate >/dev/null 2>&1; b="$(learned_budget)"; }
  a="$(analyze "$f" "$b")"
  p="$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(d['percent'] if d['percent'] is not None else -1)" "$a")"
  local hit conf
  hit="$(python3 -c "import json,sys;print(1 if json.loads(sys.argv[1]).get('limit_active') else 0)" "$a")"
  conf="$(python3 -c "import json;print(json.load(open('$BUDGET_FILE')).get('confidence','low'))" 2>/dev/null || echo low)"
  # Definitive trigger always fires. The 97% estimate only fires when the budget is
  # a real observation — never when it is just a lower bound on spend so far.
  local pct_fire=1
  [[ "$conf" == "low-bounded" ]] && pct_fire=0
  [[ -n "${CLAUDE_TOKEN_BUDGET:-}" ]] && pct_fire=1
  if [[ "$hit" == 1 ]] || { [[ "$pct_fire" == 1 ]] && python3 -c "import sys;sys.exit(0 if float('$p')>=float('$PCT') else 1)"; }; then
    cmd_arm >/dev/null
    echo "LIMIT_WATCH: armed (percent=$p threshold=$PCT limit_event=$hit)"
  fi
}

case "${1:-status}" in
  status) shift; cmd_status "$@" ;;
  check)  shift; cmd_check "$@" ;;
  arm)    shift; cmd_arm "$@" ;;
  disarm) shift; cmd_disarm "$@" ;;
  ping)   shift; cmd_ping "$@" ;;
  calibrate) shift; calibrate ;;
  verify) shift; source "$REPO_ROOT/scripts/limit-verify.sh"; cmd_verify "$@" ;;
  *) echo "usage: limit-watch.sh {status|check|arm|disarm|ping|calibrate|verify}" >&2; exit 2 ;;
esac
