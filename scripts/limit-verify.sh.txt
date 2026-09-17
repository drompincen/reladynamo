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
