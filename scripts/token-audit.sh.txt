#!/bin/bash
# drom-flow — Claude token audit + delegation gates.
#
#   token-audit.sh mark <label>          record the current transcript position
#   token-audit.sh measure <label>       report Claude cost since that mark
#   token-audit.sh gates [--json]        evaluate the eight exit-criteria gates
#
# Measures the real thing: per-turn usage from the live Claude Code session
# transcript (~/.claude/projects/<slug>/<session>.jsonl), which records
# input_tokens / output_tokens / cache_read_input_tokens for every turn.
#
# Exit: 0 = all evaluated gates PASS, 1 = a gate failed, 2 = usage/env error

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$REPO_ROOT/reports"
STATE_DIR="${TOKEN_AUDIT_STATE:-$REPO_ROOT/.claude/.token-audit}"
mkdir -p "$STATE_DIR" "$REPORT_DIR"

log() { echo "[token-audit] $*" >&2; }
die() { echo "[token-audit] ERROR: $*" >&2; exit 2; }

# Resolve the transcript for THIS project (most recently modified session).
transcript() {
  [[ -n "${CLAUDE_TRANSCRIPT:-}" && -f "${CLAUDE_TRANSCRIPT:-}" ]] && { echo "$CLAUDE_TRANSCRIPT"; return; }
  local slug d
  slug="$(echo "$REPO_ROOT" | sed 's|/|-|g')"
  d="$HOME/.claude/projects/$slug"
  [[ -d "$d" ]] || return 1
  ls -t "$d"/*.jsonl 2>/dev/null | head -1
}

usage_between() { # file start_line end_line -> JSON of the window
  python3 - "$1" "$2" "$3" <<'PY'
import json,sys
f,a,b=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
turns=out=cr=cc=fresh=0; tr_bytes=0
for i,line in enumerate(open(f,errors='replace'),1):
    if i<=a or i>b: continue
    try: o=json.loads(line)
    except Exception: continue
    m=o.get('message') or {}
    if not isinstance(m,dict): continue
    u=m.get('usage')
    if u:
        turns+=1
        out+=u.get('output_tokens',0); fresh+=u.get('input_tokens',0)
        cr+=u.get('cache_read_input_tokens',0); cc+=u.get('cache_creation_input_tokens',0)
    c=m.get('content')
    if isinstance(c,list):
        for blk in c:
            if isinstance(blk,dict) and blk.get('type')=='tool_result':
                t=blk.get('content'); tr_bytes+=len(t if isinstance(t,str) else json.dumps(t))
print(json.dumps({'turns':turns,'output_tokens':out,'fresh_input_tokens':fresh,
                  'cache_read':cr,'cache_creation':cc,'tool_result_bytes':tr_bytes,
                  'billable_tokens':out+fresh+cc}))
PY
}

cmd_mark() {
  local label="${1:-}"; [[ -n "$label" ]] || die "mark needs a label"
  local f; f="$(transcript)" || die "no transcript found for $REPO_ROOT"
  wc -l < "$f" | tr -d ' ' > "$STATE_DIR/$label.mark"
  log "mark '$label' @ line $(cat "$STATE_DIR/$label.mark")"
}

cmd_measure() {
  local label="${1:-}"; [[ -n "$label" ]] || die "measure needs a label"
  local mk="$STATE_DIR/$label.mark"; [[ -f "$mk" ]] || die "no such mark: $label"
  local f; f="$(transcript)" || die "no transcript found"
  local a b; a="$(cat "$mk")"; b="$(wc -l < "$f" | tr -d ' ')"
  local j; j="$(usage_between "$f" "$a" "$b")"
  echo "$j" > "$STATE_DIR/$label.usage.json"
  echo "$j"
}

# --- gates ---------------------------------------------------------------------
gate() { # id status detail
  GATES+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$3")}")
  [[ "$2" == PASS ]] || FAIL=1
  log "gate $1: $2 — $3"
}

num() { python3 -c "
import json,sys
try: print(json.load(open(sys.argv[1])).get(sys.argv[2],0))
except Exception: print(0)" "$1" "$2"; }

pct_cut() { python3 -c "
b,d=float('$1'),float('$2')
print(round((b-d)/b*100,1) if b>0 else 0.0)"; }

cmd_gates() {
  local as_json=false; [[ "${1:-}" == "--json" ]] && as_json=true
  GATES=(); FAIL=0
  local B="$STATE_DIR/baseline.usage.json" D="$STATE_DIR/delegated.usage.json"

  # 1 measurable
  local f; if f="$(transcript)" && [[ -s "$f" ]]; then
    gate measurable PASS "transcript readable: $(basename "$f"), $(wc -l < "$f" | tr -d ' ') records"
  else gate measurable FAIL "no readable transcript"; fi

  # 2 delegation ratio (from the fleet ledger)
  local led="$STATE_DIR/ledger.tsv" tot=0 grok=0
  if [[ -f "$led" ]]; then
    tot=$(wc -l < "$led" | tr -d ' '); grok=$(grep -c $'\tgrok$' "$led" || echo 0)
  fi
  local ratio; ratio="$(python3 -c "print(round($grok/$tot*100,1) if $tot else 0.0)")"
  python3 -c "import sys;sys.exit(0 if $ratio>=95 else 1)" \
    && gate delegation PASS "$grok/$tot units on grok = ${ratio}%" \
    || gate delegation FAIL "$grok/$tot units on grok = ${ratio}% (need >=95%)"

  # 3 turns / 4 authoring — delegated vs measured Claude-only baseline
  if [[ -f "$B" && -f "$D" ]]; then
    local bt dt bo do_ ct co
    bt="$(num "$B" turns)"; dt="$(num "$D" turns)"
    bo="$(num "$B" output_tokens)"; do_="$(num "$D" output_tokens)"
    ct="$(pct_cut "$bt" "$dt")"; co="$(pct_cut "$bo" "$do_")"
    python3 -c "import sys;sys.exit(0 if $ct>=50 else 1)" \
      && gate turns PASS "turns $bt -> $dt (-${ct}%)" || gate turns FAIL "turns $bt -> $dt (-${ct}%, need -50%)"
    python3 -c "import sys;sys.exit(0 if $co>=50 else 1)" \
      && gate authoring PASS "output_tokens $bo -> $do_ (-${co}%)" || gate authoring FAIL "output_tokens $bo -> $do_ (-${co}%, need -50%)"
  else
    gate turns FAIL "missing baseline/delegated measurement"
    gate authoring FAIL "missing baseline/delegated measurement"
  fi

  # 5 context bytes into Claude for the fan-out
  local cb; cb="$(cat "$STATE_DIR/brief_bytes" 2>/dev/null || echo 999999)"
  (( cb <= 4096 )) && gate context PASS "collect --brief = ${cb}B (<=4096)" \
                   || gate context FAIL "collect --brief = ${cb}B (>4096)"

  # 6 parity
  local vg; vg="$(python3 -c "
import json
try:
  d=json.load(open('$REPORT_DIR/grok-verify.json'))
  print('ok' if d.get('ok') else 'fail')
except Exception: print('missing')")"
  local bench_ok; bench_ok="$(cat "$STATE_DIR/benchmark_ok" 2>/dev/null || echo no)"
  [[ "$vg" == ok && "$bench_ok" == yes ]] \
    && gate parity PASS "fleet verify 6/6 and benchmark output correct" \
    || gate parity FAIL "fleet verify=$vg benchmark_correct=$bench_ok"

  # 7 resume
  local rs; rs="$(cat "$STATE_DIR/resume_result" 2>/dev/null || echo no)"
  [[ "$rs" == pass ]] && gate resume PASS "$(cat "$STATE_DIR/resume_detail" 2>/dev/null)" \
                      || gate resume FAIL "$(cat "$STATE_DIR/resume_detail" 2>/dev/null || echo 'not run')"

  # 8 ship
  local sh; sh="$(cat "$STATE_DIR/ship_result" 2>/dev/null || echo no)"
  [[ "$sh" == pass ]] && gate ship PASS "$(cat "$STATE_DIR/ship_detail" 2>/dev/null)" \
                      || gate ship FAIL "$(cat "$STATE_DIR/ship_detail" 2>/dev/null || echo 'not shipped')"

  local IFS=,
  cat > "$REPORT_DIR/token-audit.json" <<EOF
{"ok":$([[ $FAIL -eq 0 ]] && echo true || echo false),"ts":"$(date -Iseconds)","gates":[${GATES[*]}]}
EOF
  $as_json && cat "$REPORT_DIR/token-audit.json"
  local p; p=$(grep -o '"status":"PASS"' "$REPORT_DIR/token-audit.json" | wc -l)
  log "gates: $p/${#GATES[@]} PASS"
  return $FAIL
}

# record a work unit and which engine executed it
cmd_ledger() { printf '%s\t%s\n' "${1:-unit}" "${2:-grok}" >> "$STATE_DIR/ledger.tsv"; }

case "${1:-}" in
  mark)    shift; cmd_mark "$@" ;;
  measure) shift; cmd_measure "$@" ;;
  gates)   shift; cmd_gates "$@" ;;
  ledger)  shift; cmd_ledger "$@" ;;
  *) die "usage: token-audit.sh {mark <label>|measure <label>|gates [--json]|ledger <unit> <engine>}" ;;
esac
