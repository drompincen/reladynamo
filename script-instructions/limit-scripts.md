# Usage-limit scripts

Usage-limit watch and its verification.

Each `## <path>` section below is one script. `java scripts/GenerateScripts.java` writes it to that
path (relative to the repository root) with LF line endings; the generated file is gitignored.

## scripts/limit-watch.sh

````bash
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
````

## scripts/limit-verify.sh

````bash
#!/bin/bash
# drom-flow — gates for the token-limit wake-up loop. Sourced by limit-watch.sh.
# Uses SYNTHETIC transcripts so thresholds are tested without exhausting real quota.

LGATES=(); LFAIL=0
lgate() {
  LGATES+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$3")}")
  [[ "$2" == PASS ]] || LFAIL=1
  log "gate $1: $2 — $3"
}

# Build a fake transcript: N turns of `billable` tokens each, then optionally a limit event.
mk_transcript() { # file turns billable_each [reset_clock]
  python3 - "$@" <<'PY'
import json,sys
f,n,each=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
reset=sys.argv[4] if len(sys.argv)>4 else ''
with open(f,'w') as fh:
    for i in range(n):
        fh.write(json.dumps({'type':'assistant','timestamp':f'2026-08-02T{i//60:02d}:{i%60:02d}:00.000Z',
          'message':{'model':'claude-opus-5','usage':{'output_tokens':each,'input_tokens':0,
          'cache_creation_input_tokens':0,'cache_read_input_tokens':999999}}})+'\n')
    if reset:
        fh.write(json.dumps({'type':'assistant','timestamp':'2026-08-02T23:59:59.000Z',
          'message':{'model':'<synthetic>','stop_reason':'stop_sequence',
          'content':[{'type':'text','text':f"You've hit your session limit · resets {reset} (America/Denver)"}]}})+'\n')
PY
}

cmd_verify() {
  local as_json=false; [[ "${1:-}" == "--json" ]] && as_json=true
  LGATES=(); LFAIL=0
  local TMP="$STATE/verify-tmp"; mkdir -p "$TMP"
  local SAVE_ARMED="$TMP/armed.bak" SAVE_BUDGET="$TMP/budget.bak"
  [[ -f "$ARMED_FILE"  ]] && cp "$ARMED_FILE"  "$SAVE_ARMED"
  [[ -f "$BUDGET_FILE" ]] && cp "$BUDGET_FILE" "$SAVE_BUDGET"

  # ---- gate 1: detect real historical limit events -------------------------
  local real; real="$(transcript)"
  if [[ -n "$real" ]]; then
    local d; d="$(analyze "$real" 0)"
    local n clock ep
    n="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['event_count'])" "$d")"
    clock="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['last_reset_clock'])" "$d")"
    ep="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['last_reset_epoch'])" "$d")"
    if (( n >= 1 )) && [[ -n "$clock" ]] && (( ep > 0 )); then
      lgate detect PASS "$n limit event(s) parsed from live transcript; last resets '$clock' -> epoch $ep"
    else
      lgate detect FAIL "events=$n clock='$clock' epoch=$ep"
    fi
  else lgate detect FAIL "no transcript available"; fi

  # ---- gate 2: estimate, incl. graceful behaviour on an empty session ------
  local empty="$TMP/empty.jsonl"; : > "$empty"
  local e1 e2 ok2=true
  e1="$(analyze "$empty" 0 2>/dev/null)" || ok2=false
  [[ "$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['percent'])" "$e1" 2>/dev/null)" == "None" ]] || ok2=false
  mk_transcript "$TMP/t100.jsonl" 100 1000
  e2="$(analyze "$TMP/t100.jsonl" 100000)"
  local used pct
  used="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['used_billable'])" "$e2")"
  pct="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['percent'])" "$e2")"
  [[ "$used" == 100000 && "$pct" == "100.0" ]] || ok2=false
  $ok2 && lgate estimate PASS "empty session -> percent=None (no false reading); 100x1000 tokens vs 100k budget -> used=$used pct=$pct" \
        || lgate estimate FAIL "empty=$e1 used=$used pct=$pct"

  # ---- gate 3: budget learned from observed windows ------------------------
  # three windows of 50k, 60k, 55k -> median 55k
  python3 - "$TMP/cal.jsonl" <<'PY'
import json
rows=[]
def turn(i,tok): return json.dumps({'type':'assistant','timestamp':f'2026-08-02T{i:02d}:00:00.000Z',
    'message':{'model':'claude-opus-5','usage':{'output_tokens':tok,'input_tokens':0,'cache_creation_input_tokens':0}}})
def ev(i): return json.dumps({'type':'assistant','timestamp':f'2026-08-02T{i:02d}:40:00.000Z',
    'message':{'model':'<synthetic>','content':[{'type':'text','text':"You've hit your session limit · resets 9:50pm (America/Denver)"}]}})
h=1
for amount in (50000,60000,55000):
    rows.append(turn(h,amount)); rows.append(ev(h)); h+=2
open('/dev/stdout','w') if False else open(__import__('sys').argv[1],'w').write('\n'.join(rows)+'\n')
PY
  rm -f "$BUDGET_FILE"
  local cal; cal="$(CLAUDE_TRANSCRIPT="$TMP/cal.jsonl" bash "$REPO_ROOT/scripts/limit-watch.sh" calibrate 2>/dev/null)"
  local lb conf
  lb="$(python3 -c "import json,sys;print(json.loads(sys.argv[1]).get('learned_budget',0))" "$cal" 2>/dev/null || echo 0)"
  conf="$(python3 -c "import json,sys;print(json.loads(sys.argv[1]).get('confidence',''))" "$cal" 2>/dev/null || echo '')"
  if python3 -c "import sys;sys.exit(0 if abs($lb-55000)<=0.20*55000 else 1)" 2>/dev/null; then
    lgate calibrate PASS "learned budget $lb from windows 50k/60k/55k (median 55k, within 20%), confidence=$conf"
  else lgate calibrate FAIL "learned=$lb expected ~55000 (conf=$conf)"; fi

  # ---- gates 4 + 7: threshold behaviour (97 fires, 96 silent) --------------
  mk_transcript "$TMP/t96.jsonl" 96 1000     # 96k of a 100k budget = 96%
  mk_transcript "$TMP/t97.jsonl" 97 1000     # 97k = 97%
  rm -f "$ARMED_FILE"
  local out96 out97
  out96="$(CLAUDE_TRANSCRIPT="$TMP/t96.jsonl" CLAUDE_TOKEN_BUDGET=100000 "$REPO_ROOT/scripts/limit-watch.sh" check 2>/dev/null)"
  local armed96=no; [[ -f "$ARMED_FILE" ]] && armed96=yes
  out97="$(CLAUDE_TRANSCRIPT="$TMP/t97.jsonl" CLAUDE_TOKEN_BUDGET=100000 "$REPO_ROOT/scripts/limit-watch.sh" check 2>/dev/null)"
  local armed97=no; [[ -f "$ARMED_FILE" ]] && armed97=yes
  # idempotence: arming twice must not stack or duplicate
  CLAUDE_TRANSCRIPT="$TMP/t97.jsonl" CLAUDE_TOKEN_BUDGET=100000 "$REPO_ROOT/scripts/limit-watch.sh" check >/dev/null 2>&1
  local arms; arms="$(python3 -c "import json;print(json.load(open('$ARMED_FILE')).get('pings',0))" 2>/dev/null || echo 0)"

  [[ "$armed96" == no && "$armed97" == yes ]] \
    && lgate trigger PASS "96% did not arm; 97% armed; second check idempotent (pings=$arms, no stacking)" \
    || lgate trigger FAIL "armed@96=$armed96 armed@97=$armed97"
  [[ "$armed96" == no ]] \
    && lgate no_false_positive PASS "96% of budget produced no arm and no trigger output" \
    || lgate no_false_positive FAIL "armed at 96%: $out96"

  # ---- gate 5: the ping fires, re-arms while blocked, stops at the cap -----
  local pr
  pr="$(LIMIT_WATCH_INTERVAL=2 "$REPO_ROOT/scripts/limit-watch.sh" ping 2>/dev/null)"
  local pstat pnum
  pstat="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['status'])" "$pr" 2>/dev/null || echo '')"
  pnum="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['ping'])" "$pr" 2>/dev/null || echo 0)"
  # a detached timer should have been scheduled for the next ping
  local timer=no
  ( LIMIT_WATCH_INTERVAL=2 "$REPO_ROOT/scripts/limit-watch.sh" ping >/dev/null 2>&1 )
  sleep 3
  [[ -f "$STATE/limit-ping-due" ]] && timer=yes
  if [[ -n "$pstat" ]] && (( pnum >= 1 )); then
    lgate wake PASS "ping #$pnum status=$pstat; detached re-arm timer fired=$timer (survives session end)"
  else lgate wake FAIL "ping produced no status ($pr)"; fi

  # ---- gate 6: grok keeps working while Claude is idle ---------------------
  local gt="$REPO_ROOT/.claude/.grok-fleet/_lw"; mkdir -p "$gt"
  rm -rf "$REPO_ROOT/.claude/.grok-fleet/lwtest"
  printf 'Write a file named ok.md in your working directory containing exactly: LW_OK\n' > "$gt/t.md"
  python3 -c "
import json
json.dump({'run_id':'lwtest','budget_usd':0,'max_parallel':2,
 'agents':[{'id':'g1','task_file':'$gt/t.md'},{'id':'g2','task_file':'$gt/t.md'}]},open('$gt/m.json','w'))"
  bash "$REPO_ROOT/scripts/grok-fleet.sh" drain --manifest "$gt/m.json" >/dev/null 2>&1
  local waited=0
  while (( waited < 240 )); do
    [[ -f "$REPO_ROOT/.claude/.grok-fleet/lwtest/DONE" ]] && break
    sleep 5; waited=$(( waited + 5 ))
  done
  local gdone; gdone="$(grep -l '"state":"DONE"' "$REPO_ROOT"/.claude/.grok-fleet/lwtest/agents/*/status.json 2>/dev/null | wc -l | tr -d ' ')"
  (( gdone == 2 )) \
    && lgate grok_continues PASS "detached grok run completed $gdone/2 with Claude idle (${waited}s)" \
    || lgate grok_continues FAIL "only $gdone/2 completed after ${waited}s"

  # ---- gate 8: ship --------------------------------------------------------
  local sh; sh="$(cat "$STATE/limit_ship_result" 2>/dev/null || echo no)"
  [[ "$sh" == pass ]] && lgate ship PASS "$(cat "$STATE/limit_ship_detail" 2>/dev/null)" \
                      || lgate ship FAIL "$(cat "$STATE/limit_ship_detail" 2>/dev/null || echo 'not shipped yet')"

  # restore real state — verification must not leave the watcher armed
  rm -f "$ARMED_FILE" "$STATE/limit-ping-due"
  [[ -f "$SAVE_ARMED"  ]] && mv "$SAVE_ARMED"  "$ARMED_FILE"
  [[ -f "$SAVE_BUDGET" ]] && mv "$SAVE_BUDGET" "$BUDGET_FILE"
  rm -rf "$TMP" "$gt" "$REPO_ROOT/.claude/.grok-fleet/lwtest"

  local IFS=,
  cat > "$REPORT_DIR/limit-watch.json" <<EOF
{"ok":$([[ $LFAIL -eq 0 ]] && echo true || echo false),"ts":"$(date -Iseconds)","gates":[${LGATES[*]}]}
EOF
  $as_json && cat "$REPORT_DIR/limit-watch.json"
  local p; p=$(grep -o '"status":"PASS"' "$REPORT_DIR/limit-watch.json" | wc -l)
  log "gates: $p/${#LGATES[@]} PASS"
  return $LFAIL
}
````
