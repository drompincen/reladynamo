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
