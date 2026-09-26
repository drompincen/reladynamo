# Audit scripts

Verification of fleet output and token/benchmark accounting.

Each `## <path>` section below is one script. `java scripts/GenerateScripts.java` writes it to that
path (relative to the repository root) with LF line endings; the generated file is gitignored.

## scripts/grok-verify.sh

````bash
#!/bin/bash
# drom-flow — closed-loop verifier for the grok sub-agent fleet.
# Sourced by grok-fleet.sh; implements the six exit-criteria gates.
# Writes reports/grok-verify.json. Exit 0 only when every gate PASSes.

GATES_JSON=()
GATE_FAIL=0

gate() { # id status detail evidence
  local id="$1" st="$2" detail="$3" ev="${4:-}"
  GATES_JSON+=("{\"id\":\"$id\",\"status\":\"$st\",\"detail\":$(json_escape "$detail"),\"evidence\":$(json_escape "$ev")}")
  [[ "$st" == PASS ]] || GATE_FAIL=1
  log "gate $id: $st — $detail"
}

mk_task() { mkdir -p "$(dirname "$1")"; cat > "$1"; }

cmd_verify() {
  local as_json=false iteration="${GROK_ITERATION:-0}"
  while [[ $# -gt 0 ]]; do case $1 in
    --json) as_json=true; shift ;;
    --iteration) iteration="$2"; shift 2 ;;
    *) shift ;;
  esac; done

  mkdir -p "$REPORT_DIR" "$FLEET_ROOT"
  local t_start; t_start=$(date +%s)
  local RUN="verify-$(date +%H%M%S)"
  local TASKS="$FLEET_ROOT/_tasks"; mkdir -p "$TASKS"

  # ---------- Gate 1: feasibility ----------
  if cmd_doctor --live >/dev/null 2>&1; then
    gate feasibility PASS "doctor --live ok" "$REPORT_DIR/grok-doctor.json"
  else
    gate feasibility FAIL "doctor --live failed" "$REPORT_DIR/grok-doctor.json"
    finish_verify "$RUN" "$t_start" "$iteration" "$as_json"; return $?
  fi

  # ---------- Gates 2+3: work_done + monitor (one fan-out) ----------
  local -a AGENTS=(alpha bravo charlie)
  local i=1
  for a in "${AGENTS[@]}"; do
    mk_task "$TASKS/$a.md" <<EOF
You are fleet agent "$a" (unit $i of 3).

TASK: Write a file named findings.md in your working directory. It must contain:
  - line 1 exactly: MARKER_${a^^}
  - then 3 bullet points describing what a "closed-loop QA pipeline" is.

Do the work in at least two steps, appending a PROGRESS.md checkpoint after each.
EOF
    ((i++))
  done

  local -a pids=()
  for a in "${AGENTS[@]}"; do
    ( cmd_spawn --run-id "$RUN" --agent-id "$a" --task-file "$TASKS/$a.md" >/dev/null 2>&1 ) &
    pids+=($!)
  done

  # monitor while they run: sample progress checkpoints
  local max_ckpt=0 samples=0 saw_running=0
  while :; do
    local alive=0
    for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && alive=1; done
    local s; s="$(cmd_status --run-id "$RUN" --json 2>/dev/null | tail -1)"
    if [[ -n "$s" ]]; then
      local c r
      c="$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(max([a['checkpoints'] for a in d['agents']]+[0]))" "$s" 2>/dev/null || echo 0)"
      r="$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(d['rollup']['running'])" "$s" 2>/dev/null || echo 0)"
      (( c > max_ckpt )) && max_ckpt=$c
      (( r > 0 )) && saw_running=1
      ((samples++))
    fi
    [[ $alive -eq 0 ]] && break
    sleep 3
  done
  wait "${pids[@]}" 2>/dev/null

  # work_done: every agent DONE and output carries its marker
  local wd_ok=true wd_detail=""
  for a in "${AGENTS[@]}"; do
    local d; d="$(agent_dir "$RUN" "$a")"
    local st; st="$(get_state "$d")"
    local marker="MARKER_${a^^}"
    if [[ "$st" != DONE ]]; then wd_ok=false; wd_detail+="$a=$st "; continue; fi
    if ! grep -rqs "$marker" "$d/output" 2>/dev/null; then wd_ok=false; wd_detail+="$a=no-marker "; fi
  done
  if $wd_ok; then gate work_done PASS "3/3 agents DONE with correct markers" "$FLEET_ROOT/$RUN"
  else gate work_done FAIL "agent problems: $wd_detail" "$FLEET_ROOT/$RUN"; fi

  # monitor: live status sampled + >=2 checkpoints seen on some agent
  if (( samples > 0 )) && (( max_ckpt >= 2 )) && (( saw_running == 1 )); then
    gate monitor PASS "sampled $samples times, max $max_ckpt checkpoints, live RUNNING observed" "$REPORT_DIR/grok-fleet-$RUN.json"
  else
    gate monitor FAIL "samples=$samples max_checkpoints=$max_ckpt saw_running=$saw_running" "$REPORT_DIR/grok-fleet-$RUN.json"
  fi

  # ---------- Gate 4: stop ----------
  mk_task "$TASKS/longrun.md" <<'EOF'
Count from 1 to 400. For EVERY number write a full sentence reflecting on it into
notes.md in your working directory, appending as you go. Work slowly and thoroughly.
Append a PROGRESS.md checkpoint every 10 numbers.
EOF
  ( cmd_spawn --run-id "$RUN" --agent-id longrun --task-file "$TASKS/longrun.md" >/dev/null 2>&1 ) &
  local lp=$!
  local ld; ld="$(agent_dir "$RUN" longrun)"
  local waited=0
  while (( waited < 60 )); do
    [[ -s "$ld/stream.jsonl" ]] && break
    sleep 2; ((waited+=2))
  done

  local stop_ok=false stop_detail="agent never started streaming"
  if [[ -s "$ld/stream.jsonl" ]]; then
    local before after tl
    before=$(stat -c%s "$ld/stream.jsonl")
    cmd_stop --run-id "$RUN" --agent-id longrun >/dev/null 2>&1
    sleep 5
    after=$(stat -c%s "$ld/stream.jsonl"); sleep 4
    local after2; after2=$(stat -c%s "$ld/stream.jsonl")
    tl="$(tasklist.exe /FI "IMAGENAME eq grok.exe" 2>/dev/null | grep -c grok.exe)"
    local st; st="$(get_state "$ld")"
    if [[ "$st" == STOPPED ]] && (( after == after2 )); then
      stop_ok=true; stop_detail="state=STOPPED, stream frozen at $after2 bytes (was $before growing), grok.exe procs=$tl"
    else
      stop_detail="state=$st stream $after->$after2 procs=$tl"
    fi
  fi
  kill $lp 2>/dev/null; wait $lp 2>/dev/null
  $stop_ok && gate stop PASS "$stop_detail" "$ld" || gate stop FAIL "$stop_detail" "$ld"

  # ---------- Gate 5: combined claude + grok ----------
  # (a) cross-model schema verdict from grok
  local schema="$TASKS/verdict.schema.json"
  cat > "$schema" <<'EOF'
{"type":"object","properties":{"verdict":{"type":"string","enum":["pass","fail"]},"reason":{"type":"string"}},"required":["verdict","reason"]}
EOF
  mk_task "$TASKS/review.md" <<EOF
Review this statement for correctness and answer with the required schema:
"A closed-loop pipeline re-runs its check after each fix round and stops on regression."
EOF
  cmd_spawn --run-id "$RUN" --agent-id reviewer --task-file "$TASKS/review.md" --schema "$schema" >/dev/null 2>&1
  local rd; rd="$(agent_dir "$RUN" reviewer)"
  local verdict=""
  # --json-schema results land in `structuredOutput`; fall back to parsing `text`.
  verdict="$(python3 -c "
import json,re
d=json.load(open('$rd/result.json'))
so=d.get('structuredOutput')
if isinstance(so,dict) and so.get('verdict'):
    print(so['verdict'])
else:
    m=re.search(r'\{.*\}',d.get('text','') or '',re.S)
    print(json.loads(m.group(0)).get('verdict','') if m else '')" 2>/dev/null)"

  # (b) Claude-side merged artifact consuming grok outputs
  local merged="$REPORT_DIR/grok-claude-merge.md"
  local merge_ok=false
  if [[ -f "$merged" ]]; then
    merge_ok=true
    for a in "${AGENTS[@]}"; do grep -qs "MARKER_${a^^}" "$merged" || merge_ok=false; done
  fi
  if [[ "$verdict" =~ ^(pass|fail)$ ]] && $merge_ok; then
    gate combined PASS "grok schema verdict='$verdict'; Claude merged all 3 grok outputs" "$merged"
  else
    gate combined FAIL "schema verdict='$verdict' merged_artifact=$merge_ok (needs $merged citing every MARKER_*)" "$merged"
  fi

  # ---------- Gate 6: control (failure honesty, budget guard, idempotent resume) ----------
  local ctl_ok=true ctl=""

  # failure honesty: an agent that cannot produce output must NOT be DONE
  mk_task "$TASKS/impossible.md" <<'EOF'
Do not create any file. Do not write anything to disk. Simply reply with the word SKIP and stop immediately.
EOF
  cmd_spawn --run-id "$RUN" --agent-id impossible --task-file "$TASKS/impossible.md" >/dev/null 2>&1
  local ist; ist="$(get_state "$(agent_dir "$RUN" impossible)")"
  [[ "$ist" == DONE ]] && { ctl_ok=false; ctl+="no-output-agent reported DONE; "; } || ctl+="failure-honesty=$ist ok; "
  if cmd_collect --run-id "$RUN" >/dev/null 2>&1; then ctl_ok=false; ctl+="collect exited 0 despite failure; "
  else ctl+="collect non-zero ok; "; fi

  # budget guard
  local spent; spent="$(python3 -c "import json;print(json.load(open('$REPORT_DIR/grok-fleet-$RUN.json'))['rollup']['total_cost_usd'])" 2>/dev/null || echo 0)"
  local over; over="$(python3 -c "print('yes' if float('$spent')>0.0001 else 'no')")"
  [[ "$over" == yes ]] && ctl+="budget-accounting ok (\$$spent tracked); " || { ctl_ok=false; ctl+="no cost tracked; "; }

  # idempotent resume: re-spawning a DONE agent must skip, not re-bill
  local before_cost; before_cost="$(python3 -c "import json;print(json.load(open('$(agent_dir "$RUN" alpha)/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)"
  cmd_spawn --run-id "$RUN" --agent-id alpha --task-file "$TASKS/alpha.md" >/dev/null 2>&1
  local after_cost; after_cost="$(python3 -c "import json;print(json.load(open('$(agent_dir "$RUN" alpha)/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)"
  if [[ "$before_cost" == "$after_cost" ]]; then ctl+="idempotent-resume ok (no re-bill); "
  else ctl_ok=false; ctl+="resume re-ran agent ($before_cost -> $after_cost); "; fi

  $ctl_ok && gate control PASS "$ctl" "$FLEET_ROOT/$RUN" || gate control FAIL "$ctl" "$FLEET_ROOT/$RUN"

  cmd_stop --all >/dev/null 2>&1
  finish_verify "$RUN" "$t_start" "$iteration" "$as_json"
}

finish_verify() {
  local run="$1" t0="$2" iter="$3" as_json="$4"
  local spent; spent="$(python3 -c "import json;print(json.load(open('$REPORT_DIR/grok-fleet-$run.json'))['rollup']['total_cost_usd'])" 2>/dev/null || echo 0)"
  local ok=true; [[ $GATE_FAIL -eq 0 ]] || ok=false
  local IFS=,
  cat > "$REPORT_DIR/grok-verify.json" <<EOF
{"ok":$ok,"iteration":$iter,"run_id":"$run","gates":[${GATES_JSON[*]}],
 "cost_usd":$spent,"wall_clock_s":$(( $(date +%s) - t0 )),"ts":"$(date -Iseconds)"}
EOF
  $as_json && cat "$REPORT_DIR/grok-verify.json"
  local passed=$(( ${#GATES_JSON[@]} - $(grep -o '"status":"FAIL"' "$REPORT_DIR/grok-verify.json" | wc -l) ))
  log "verify: $passed/${#GATES_JSON[@]} gates PASS, \$$spent, $(( $(date +%s) - t0 ))s"
  return $GATE_FAIL
}
````

## scripts/token-audit.sh

````bash
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
````

## scripts/bench-audit.sh

````bash
#!/bin/bash
# drom-flow — encapsulated audit fan-out.
#
#   bench-audit.sh <run-id> <file> [<file> ...]
#
# Exists so dispatching a fan-out costs Claude a one-line command instead of a
# hand-written block of bash + python. Claude authoring is a top-two token cost;
# this moves it into a script that is written once.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${1:?usage: bench-audit.sh <run-id> <file>...}"; shift
[[ $# -ge 1 ]] || { echo "no target files" >&2; exit 2; }

T="$REPO_ROOT/.claude/.grok-fleet/_tasks/$RUN"; mkdir -p "$T"
rm -rf "$REPO_ROOT/.claude/.grok-fleet/$RUN"

CHECKS='1) YAML frontmatter present and valid with name, description, user-invocable. 2) Ordered list numbering is strictly sequential with no duplicated or skipped numbers — report the exact duplicated number, its section heading, and the line range.'

agents=()
for f in "$@"; do
  id="$(basename "$(dirname "$f")")"; [[ "$id" == "." ]] && id="$(basename "$f" .md)"
  bash "$REPO_ROOT/scripts/mk-task.sh" audit "$T/$id.md" \
    TARGET="$f" CHECKS="$CHECKS" OUTFILE="findings.md" TITLE="$id" >/dev/null || exit 2
  bash "$REPO_ROOT/scripts/token-audit.sh" ledger "audit-$id" grok
  agents+=("$id:$T/$id.md")
done

python3 - "$T/m.json" "$RUN" "${agents[@]}" <<'PY'
import json,sys
out,run=sys.argv[1],sys.argv[2]
ag=[{'id':a.split(':',1)[0],'task_file':a.split(':',1)[1]} for a in sys.argv[3:]]
json.dump({'run_id':run,'budget_usd':0,'max_parallel':len(ag),'agents':ag},open(out,'w'),indent=2)
PY

bash "$REPO_ROOT/scripts/grok-fleet.sh" spawn --manifest "$T/m.json" >/dev/null 2>&1
bash "$REPO_ROOT/scripts/grok-fleet.sh" collect --run-id "$RUN" --brief
````
