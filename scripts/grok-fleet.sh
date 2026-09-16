#!/bin/bash
# drom-flow — grok sub-agent fleet: filesystem-controlled fan-out from WSL to grok CLI (Windows).
#
# Subcommands: doctor | spawn | status | stop | collect | verify | clean
# Exit: 0 = ok, 1 = gate/agent failure, 2 = usage/env error
#
# Control plane lives on the Windows-visible disk because grok.exe is a Windows
# process and cannot see WSL-native paths (/tmp is invisible to it).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLEET_ROOT="${GROK_FLEET_ROOT:-$REPO_ROOT/.claude/.grok-fleet}"
REPORT_DIR="$REPO_ROOT/reports"
# NB: `grok models` lists the *selectable* ids. The name that appears in the `end`
# event's modelUsage (e.g. grok-4.5-build) is an internal resolved name and is NOT
# a valid -m value. Verify with `grok models` before changing this.
GROK_MODEL="${GROK_MODEL:-grok-4.5}"
GROK_MAX_PARALLEL="${GROK_MAX_PARALLEL:-4}"
GROK_BUDGET_USD="${GROK_BUDGET_USD:-5}"
# 30 was the hardcoded ceiling and it is the binding constraint on this account, not cost: several
# agents reached "max turns reached" having finished their analysis and captured a RED build, then
# died before implementing. Turns are free here; wall-clock is the only price.
GROK_MAX_TURNS="${GROK_MAX_TURNS:-30}"
STALL_SECS="${GROK_STALL_SECS:-180}"
AGENT_TIMEOUT="${GROK_AGENT_TIMEOUT:-600}"
GRACE_SECS="${GROK_GRACE_SECS:-10}"

log() { echo "[grok-fleet] $*" >&2; }
die() { echo "[grok-fleet] ERROR: $*" >&2; exit 2; }

# --- binary resolution -------------------------------------------------------
resolve_grok() {
  if [[ -n "${GROK_BIN:-}" && -x "$GROK_BIN" ]]; then echo "$GROK_BIN"; return 0; fi
  local c; c="$(command -v grok 2>/dev/null)"; [[ -n "$c" ]] && { echo "$c"; return 0; }
  local p="/mnt/c/Users/$USER/.grok/bin/grok.exe"; [[ -x "$p" ]] && { echo "$p"; return 0; }
  local up; up="$(cmd.exe /c echo %USERPROFILE% 2>/dev/null | tr -d '\r')"
  if [[ -n "$up" ]]; then
    p="$(wslpath -u "$up" 2>/dev/null)/.grok/bin/grok.exe"
    [[ -x "$p" ]] && { echo "$p"; return 0; }
  fi
  return 1
}

winpath() { wslpath -w "$1" 2>/dev/null; }

json_escape() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

# Guard: everything grok touches must live on the Windows-visible mount.
assert_win_visible() {
  case "$1" in /mnt/[a-z]/*) return 0 ;; *) return 1 ;; esac
}

# --- doctor ------------------------------------------------------------------
cmd_doctor() {
  local live=false; [[ "${1:-}" == "--live" ]] && live=true
  mkdir -p "$REPORT_DIR"
  local checks=() ok=true bin="" ver=""

  add() { checks+=("{\"name\":\"$1\",\"ok\":$2,\"detail\":$(json_escape "$3")}"); [[ "$2" == "true" ]] || ok=false; }

  if bin="$(resolve_grok)"; then add binary true "$bin"; else add binary false "grok.exe not found (set GROK_BIN)"; fi

  if [[ -n "$bin" ]]; then
    ver="$(timeout 60 "$bin" --version 2>/dev/null | head -1)"
    [[ -n "$ver" ]] && add version true "$ver" || add version false "--version produced no output"
  else add version false "skipped, no binary"; fi

  local auth="/mnt/c/Users/$USER/.grok/auth.json"
  [[ -f "$auth" ]] && add auth true "auth.json present" || add auth false "not authenticated: run 'grok login' on Windows"

  if timeout 30 tasklist.exe /NH >/dev/null 2>&1; then add interop true "tasklist.exe responds"
  else add interop false "WSL->Windows interop unavailable"; fi

  local w; w="$(winpath "$REPO_ROOT")"
  [[ -n "$w" ]] && add wslpath true "$REPO_ROOT -> $w" || add wslpath false "wslpath -w failed"

  if assert_win_visible "$REPO_ROOT"; then add win_visible true "repo on Windows-visible mount"
  else add win_visible false "repo at $REPO_ROOT is WSL-native; grok.exe cannot see it. Move the repo under /mnt/c."; fi

  local live_json='{"ran":false,"ok":false,"latency_ms":0}'
  if $live && [[ -n "$bin" ]] && $ok; then
    local t0 t1 out rc
    t0=$(date +%s%3N)
    out="$(timeout 60 "$bin" --cwd "$(winpath "$REPO_ROOT")" --permission-mode dontAsk --max-turns 2 \
           -p 'Reply with exactly the single word: PONG' 2>/dev/null)"; rc=$?
    t1=$(date +%s%3N)
    if [[ $rc -eq 0 && -n "${out// /}" ]]; then live_json="{\"ran\":true,\"ok\":true,\"latency_ms\":$((t1-t0))}"
    else live_json="{\"ran\":true,\"ok\":false,\"latency_ms\":$((t1-t0))}"; ok=false; fi
  fi

  local IFS=,
  cat > "$REPORT_DIR/grok-doctor.json" <<EOF
{"ok":$ok,"binary":$(json_escape "$bin"),"version":$(json_escape "$ver"),
 "fleet_root":$(json_escape "$FLEET_ROOT"),"model":$(json_escape "$GROK_MODEL"),
 "checks":[${checks[*]}],"live_test":$live_json}
EOF
  python3 -m json.tool "$REPORT_DIR/grok-doctor.json" >/dev/null 2>&1 || log "warn: doctor json malformed"
  $ok && { log "doctor: OK"; return 0; } || { log "doctor: FAILED (see $REPORT_DIR/grok-doctor.json)"; return 1; }
}

# --- agent helpers -----------------------------------------------------------
agent_dir()  { echo "$FLEET_ROOT/$1/agents/$2"; }
set_status() { # dir state [extra-json]
  # Atomic: write to a temp file then rename, so a kill mid-write (Claude running out
  # of tokens, machine sleep) can never leave a truncated, unreadable status.
  local d="$1" s="$2" extra="${3:-}"
  printf '{"state":"%s","ts":"%s"%s}\n' "$s" "$(date -Iseconds)" "${extra:+,$extra}" > "$d/.status.tmp"
  mv -f "$d/.status.tmp" "$d/status.json"
}
get_state() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['state'])" "$1/status.json" 2>/dev/null || echo UNKNOWN; }

FLEET_PREAMBLE='
--- FLEET PROTOCOL (mandatory) ---
1. You are a fleet sub-agent. Your working directory is yours alone.
2. After each meaningful step, APPEND one line to PROGRESS.md in the PARENT of your
   working directory (../PROGRESS.md), formatted: [HH:MM:SS] <what you just did>.
   Write at least two such checkpoints before finishing.
3. Write all work product INTO your current working directory.
4. NEVER write outside your working directory or its parent PROGRESS.md.
5. Finish with a one-line summary starting with RESULT:
--- END FLEET PROTOCOL ---
'

# spawn one agent (blocking wrapper, meant to be backgrounded)
run_agent() {
  local d="$1" bin="$2" schema="${3:-}"
  local rc; local -a args
  args=( --cwd "$(winpath "$d/output")" -m "$GROK_MODEL"
         --prompt-file "$(winpath "$d/task.md")"
         --output-format streaming-json --permission-mode bypassPermissions
         --no-memory --max-turns "$GROK_MAX_TURNS" )
  [[ -n "$schema" ]] && args+=( --json-schema "$(cat "$schema")" )
  # Grok self-verifies before returning. On an unmetered account this is free and
  # replaces a Claude review turn, which is not.
  # NOT with --json-schema: the appended verification turn displaces the structured
  # output and `structuredOutput` comes back empty.
  [[ "${GROK_SELF_CHECK:-1}" == 1 && -z "$schema" ]] && args+=( --check )
  # Run the task N ways in parallel and keep the best — free quality, no Claude fixes.
  [[ -n "${GROK_BEST_OF_N:-}" ]] && args+=( --best-of-n "$GROK_BEST_OF_N" )
  printf '%q ' "$bin" "${args[@]}" > "$d/cmd.txt"

  timeout "$AGENT_TIMEOUT" "$bin" "${args[@]}" > "$d/stream.jsonl" 2> "$d/stream.err" &
  local pid=$!
  printf '{"wsl_pid":%d}\n' "$pid" > "$d/pid"
  set_status "$d" RUNNING "\"wsl_pid\":$pid"
  wait $pid; rc=$?

  # distil the terminal `end` event
  python3 - "$d" <<'PY' 2>/dev/null
import json,sys,os
d=sys.argv[1]; end=None
try:
    for line in open(os.path.join(d,'stream.jsonl'),errors='replace'):
        line=line.strip()
        if not line: continue
        try: o=json.loads(line)
        except Exception: continue
        if o.get('type')=='end': end=o
except FileNotFoundError: pass
json.dump(end or {}, open(os.path.join(d,'result.json'),'w'), indent=2)
PY

  local state
  case $rc in
    0)   state=DONE ;;
    124) state=TIMEOUT ;;
    143|137) state=STOPPED ;;
    *)   state=FAILED ;;
  esac
  # An agent that produced nothing did not do the work, whatever the exit code says.
  if [[ "$state" == DONE ]] && [[ -z "$(ls -A "$d/output" 2>/dev/null)" ]]; then state=FAILED; fi
  local cost; cost="$(python3 -c "import json;print(json.load(open('$d/result.json')).get('total_cost_usd',0))" 2>/dev/null || echo 0)"
  set_status "$d" "$state" "\"exit\":$rc,\"cost_usd\":$cost"
}

run_total_cost() { # run_id -> total USD across all agents
  python3 - "$FLEET_ROOT/$1/agents" <<'PY'
import json,os,sys
b=sys.argv[1]; t=0.0
if os.path.isdir(b):
    for a in os.listdir(b):
        try: t+=float(json.load(open(os.path.join(b,a,'status.json'))).get('cost_usd') or 0)
        except Exception: pass
print(round(t,6))
PY
}

# A cap of 0 (or negative) means unlimited — for accounts where grok spend is not
# metered. Cost is still tracked and reported; it just never halts a run.
over_budget() { python3 -c "
import sys
cap=float('$2')
sys.exit(1 if cap<=0 else (0 if float('$1')>cap else 1))"; }

# Polls spend while a fan-out runs and halts the whole run if it breaches the cap.
budget_watchdog() {
  local run="$1" cap="$2" t
  source "$REPO_ROOT/scripts/grok-resume.sh" 2>/dev/null
  while [[ ! -f "$FLEET_ROOT/$run/DONE" ]]; do
    sleep 8
    # Refresh the resume record continuously so an abrupt Claude exit never leaves
    # a stale picture of what finished.
    cmd_checkpoint --run-id "$run" >/dev/null 2>&1
    t="$(run_total_cost "$run")"
    if over_budget "$t" "$cap"; then
      log "BUDGET EXCEEDED: \$$t > \$$cap — halting run $run"
      echo "{\"state\":\"BUDGET_EXCEEDED\",\"spent\":$t,\"cap\":$cap}" > "$FLEET_ROOT/$run/HALT"
      cmd_stop --run-id "$run" >/dev/null 2>&1
      return 0
    fi
  done
}

# spawn --manifest: fan out N agents with a concurrency gate + budget guard
cmd_spawn_manifest() {
  local mf="$1"
  [[ -f "$mf" ]] || die "manifest not found: $mf"
  local run_id cap par
  run_id="$(python3 -c "import json;print(json.load(open('$mf'))['run_id'])")"
  cap="$(python3 -c "import json;print(json.load(open('$mf')).get('budget_usd',$GROK_BUDGET_USD))")"
  par="$(python3 -c "import json;print(json.load(open('$mf')).get('max_parallel',$GROK_MAX_PARALLEL))")"
  mkdir -p "$FLEET_ROOT/$run_id"; rm -f "$FLEET_ROOT/$run_id/HALT" "$FLEET_ROOT/$run_id/DONE"
  # Keep the manifest with the run so `resume` can re-dispatch without Claude.
  [[ "$(readlink -f "$mf")" == "$(readlink -f "$FLEET_ROOT/$run_id/run.json")" ]] || cp -f "$mf" "$FLEET_ROOT/$run_id/run.json"
  log "manifest run=$run_id parallel=$par budget=\$$cap"

  budget_watchdog "$run_id" "$cap" &
  local wd=$!

  local -a pids=()
  # Count only agent jobs — `jobs -rp` would also count the budget watchdog and
  # silently shrink the gate by one.
  alive_agents() { local n=0 p; for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && ((n++)); done; echo $n; }

  while IFS=$'\t' read -r aid tf schema; do
    [[ -z "$aid" ]] && continue
    [[ -f "$FLEET_ROOT/$run_id/HALT" ]] && { log "halted, not launching $aid"; break; }
    if [[ ! -f "$tf" ]]; then
      log "manifest: agent '$aid' task_file missing: $tf — skipping"
      mkdir -p "$FLEET_ROOT/$run_id/agents/$aid"
      set_status "$FLEET_ROOT/$run_id/agents/$aid" FAILED "\"reason\":\"task_file missing\""
      continue
    fi
    while (( $(alive_agents) >= par )); do sleep 1; done
    # Synchronous budget check at the launch point — the watchdog alone races with
    # the gate and can let another agent start after the cap is already breached.
    local spent; spent="$(run_total_cost "$run_id")"
    if over_budget "$spent" "$cap"; then
      log "BUDGET EXCEEDED: \$$spent > \$$cap — not launching $aid or any remaining agent"
      echo "{\"state\":\"BUDGET_EXCEEDED\",\"spent\":$spent,\"cap\":$cap}" > "$FLEET_ROOT/$run_id/HALT"
      break
    fi
    ( cmd_spawn --run-id "$run_id" --agent-id "$aid" --task-file "$tf" ${schema:+--schema "$schema"} >/dev/null 2>&1 ) &
    pids+=($!)
  done < <(python3 -c "
import json
for a in json.load(open('$mf'))['agents']:
    print('\t'.join([a['id'],a['task_file'],a.get('schema','')]))")

  (( ${#pids[@]} > 0 )) && wait "${pids[@]}" 2>/dev/null
  touch "$FLEET_ROOT/$run_id/DONE"; kill $wd 2>/dev/null; wait $wd 2>/dev/null

  local total; total="$(run_total_cost "$run_id")"
  if [[ -f "$FLEET_ROOT/$run_id/HALT" ]]; then
    log "run $run_id BUDGET_EXCEEDED (\$$total > \$$cap)"; return 1
  fi
  log "run $run_id complete, \$$total"
  cmd_status --run-id "$run_id" >/dev/null 2>&1
  return 0
}

cmd_spawn() {
  local run_id="" agent_id="" task_file="" schema="" bin
  [[ "${1:-}" == "--manifest" ]] && { cmd_spawn_manifest "$2"; return $?; }
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --agent-id) agent_id="$2"; shift 2 ;;
    --task-file) task_file="$2"; shift 2 ;;
    --schema) schema="$2"; shift 2 ;;
    --wait) shift ;;
    *) die "spawn: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" && -n "$agent_id" && -n "$task_file" ]] || die "spawn needs --run-id --agent-id --task-file"
  [[ -f "$task_file" ]] || die "task file not found: $task_file"
  bin="$(resolve_grok)" || die "grok binary not found"
  assert_win_visible "$FLEET_ROOT" || die "fleet root $FLEET_ROOT is not Windows-visible"

  local d; d="$(agent_dir "$run_id" "$agent_id")"
  # Idempotent resume: an agent that already finished is never re-run (and never re-billed).
  if [[ -f "$d/status.json" && "$(get_state "$d")" == DONE ]]; then
    log "spawn: $agent_id already DONE, skipping"; return 0
  fi
  mkdir -p "$d/output"
  { cat "$task_file"; printf '%s' "$FLEET_PREAMBLE"; } > "$d/task.md"
  : > "$d/PROGRESS.md"
  set_status "$d" QUEUED
  # Retry on the grok side. A failure re-runs with the failure text appended, so
  # Claude only ever sees terminal states — never intermediate attempts.
  local attempts="${GROK_MAX_ATTEMPTS:-3}" n=1
  while :; do
    run_agent "$d" "$bin" "$schema"
    [[ "$(get_state "$d")" == DONE ]] && break
    # A deliberate stop is NOT a failure — never retry past it, or `stop` would be
    # defeated by the retry immediately relaunching the agent.
    [[ "$(get_state "$d")" == STOPPED || -f "$d/STOP" ]] && { log "agent $agent_id stopped by request; not retrying"; break; }
    (( n >= attempts )) && break
    log "agent $agent_id attempt $n/$attempts -> $(get_state "$d"), retrying on grok"
    { echo; echo "--- PREVIOUS ATTEMPT FAILED (attempt $n) ---";
      echo "Error output:"; tail -c 600 "$d/stream.err" 2>/dev/null;
      echo "Fix the cause and complete the task. Write your output into the working directory."; } >> "$d/task.md"
    n=$(( n + 1 ))
  done
  printf '{"attempts":%d}\n' "$n" > "$d/attempts.json"
  [[ "$(get_state "$d")" == DONE ]]
}

# --- status ------------------------------------------------------------------
cmd_status() {
  local run_id="" as_json=false
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;; --json) as_json=true; shift ;; *) die "status: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" ]] || die "status needs --run-id"
  local base="$FLEET_ROOT/$run_id/agents"
  [[ -d "$base" ]] || die "no such run: $run_id"
  mkdir -p "$REPORT_DIR"

  python3 - "$base" "$STALL_SECS" "$REPORT_DIR/grok-fleet-$run_id.json" "$as_json" <<'PY'
import json,os,sys,time
base,stall,out,as_json=sys.argv[1],int(sys.argv[2]),sys.argv[3],sys.argv[4]=='true'
agents=[];total=0.0
for a in sorted(os.listdir(base)):
    d=os.path.join(base,a)
    if not os.path.isdir(d): continue
    st={}
    try: st=json.load(open(os.path.join(d,'status.json')))
    except Exception: pass
    state=st.get('state','UNKNOWN'); cost=float(st.get('cost_usd') or 0); total+=cost
    sp=os.path.join(d,'stream.jsonl')
    age=int(time.time()-os.path.getmtime(sp)) if os.path.exists(sp) else -1
    if state=='RUNNING' and age>stall: state='STALLED'
    prog=[l.strip() for l in open(os.path.join(d,'PROGRESS.md'),errors='replace')] if os.path.exists(os.path.join(d,'PROGRESS.md')) else []
    prog=[p for p in prog if p]
    agents.append({'agent':a,'state':state,'cost_usd':cost,'stream_age_s':age,
                   'checkpoints':len(prog),'last_progress':prog[-1] if prog else ''})
roll={'running':sum(1 for x in agents if x['state']in('RUNNING','STALLED')),
      'done':sum(1 for x in agents if x['state']=='DONE'),
      'failed':sum(1 for x in agents if x['state']in('FAILED','TIMEOUT')),
      'stopped':sum(1 for x in agents if x['state']=='STOPPED'),
      'total_cost_usd':round(total,4)}
json.dump({'agents':agents,'rollup':roll},open(out,'w'),indent=2)
if as_json: print(json.dumps({'agents':agents,'rollup':roll}))
else:
    print(f"{'AGENT':<22}{'STATE':<10}{'CKPT':>5}{'AGE':>6}{'COST':>9}  LAST")
    for x in agents:
        print(f"{x['agent']:<22}{x['state']:<10}{x['checkpoints']:>5}{x['stream_age_s']:>6}{x['cost_usd']:>9.4f}  {x['last_progress'][:48]}")
    print(f"-- {roll['done']} done / {roll['running']} running / {roll['failed']} failed / {roll['stopped']} stopped, ${roll['total_cost_usd']}")
PY
}

# --- stop --------------------------------------------------------------------
kill_agent() {
  local d="$1"
  local pid; pid="$(python3 -c "import json;print(json.load(open('$d/pid'))['wsl_pid'])" 2>/dev/null)" || return 0
  [[ -z "$pid" ]] && return 0
  touch "$d/STOP"
  local waited=0
  while kill -0 "$pid" 2>/dev/null && (( waited < GRACE_SECS )); do sleep 1; ((waited++)); done
  kill -0 "$pid" 2>/dev/null && { kill "$pid" 2>/dev/null; sleep 2; }
  kill -0 "$pid" 2>/dev/null && { kill -9 "$pid" 2>/dev/null; sleep 1; }
  # Preserve spend already incurred — a stopped agent still cost money, and dropping
  # it here would under-report the run total that the budget guard depends on.
  local prior; prior="$(python3 -c "
import json
try: print(json.load(open('$d/result.json')).get('total_cost_usd') or json.load(open('$d/status.json')).get('cost_usd') or 0)
except Exception: print(0)" 2>/dev/null || echo 0)"
  set_status "$d" STOPPED "\"stopped_by\":\"fleet\",\"cost_usd\":${prior:-0}"
}

cmd_stop() {
  local run_id="" agent_id="" all=false
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;; --agent-id) agent_id="$2"; shift 2 ;;
    --all) all=true; shift ;; *) die "stop: unknown arg $1" ;;
  esac; done

  if $all; then
    for d in "$FLEET_ROOT"/*/agents/*; do [[ -d "$d" ]] || continue
      [[ "$(get_state "$d")" == RUNNING ]] && kill_agent "$d"; done
    # NB: never `pkill -f grok.exe` — it matches any process whose command line merely
    # mentions the string (including the caller's own shell) and kills bystanders.
    # Tracked PIDs above, then the Windows side for orphans, is sufficient.
    sleep 2
    taskkill.exe /IM grok.exe /F >/dev/null 2>&1
    log "stop --all complete"; return 0
  fi
  [[ -n "$run_id" ]] || die "stop needs --run-id or --all"
  if [[ -n "$agent_id" ]]; then kill_agent "$(agent_dir "$run_id" "$agent_id")"
  else for d in "$FLEET_ROOT/$run_id/agents"/*; do [[ -d "$d" ]] && kill_agent "$d"; done; fi
  log "stop complete"
}

# --- collect -----------------------------------------------------------------
cmd_collect() {
  local run_id="" brief=false
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --brief) brief=true; shift ;;
    *) die "collect: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" ]] || die "collect needs --run-id"

  # Brief mode is what Claude reads: verdicts only, never agent output bodies.
  # Full artifacts stay on disk and are opened only to diagnose a FAIL.
  if $brief; then
    local base="$FLEET_ROOT/$run_id/agents" failed=0
    for d in "$base"/*; do [[ -d "$d" ]] || continue
      local a s line; a="$(basename "$d")"; s="$(get_state "$d")"
      [[ "$s" == DONE ]] || failed=1
      line="$(grep -h '^RESULT:' "$d/output"/* 2>/dev/null | head -1)"
      [[ -z "$line" ]] && line="$(tail -n1 "$d/PROGRESS.md" 2>/dev/null)"
      printf '%s\t%s\t%s\n' "$a" "$s" "${line:0:90}"
    done
    echo "-- run=$run_id spend=\$$(run_total_cost "$run_id") outputs=$FLEET_ROOT/$run_id/agents/<id>/output/"
    return $failed
  fi
  local base="$FLEET_ROOT/$run_id/agents" out="$REPORT_DIR/grok-fleet-$run_id.md" failed=0
  mkdir -p "$REPORT_DIR"
  { echo "# Grok fleet run: $run_id"; echo; echo "_$(date -Iseconds)_"; echo; } > "$out"
  local total=0
  for d in "$base"/*; do [[ -d "$d" ]] || continue
    local a s c; a="$(basename "$d")"; s="$(get_state "$d")"
    c="$(python3 -c "import json;print(json.load(open('$d/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)"
    total="$(python3 -c "print(round($total+${c:-0},4))")"
    [[ "$s" == DONE ]] || failed=1
    { echo "## $a — **$s** (\$$c)"; echo '```'; head -c 1200 "$d/output"/* 2>/dev/null || echo '(no output)'; echo '```'; echo; } >> "$out"
  done
  echo "**Total cost: \$$total**" >> "$out"
  log "collect -> $out (total \$$total)"
  return $failed
}

cmd_clean() { rm -rf "${FLEET_ROOT:?}"/*; log "fleet root cleared"; }

[[ $# -eq 0 ]] && die "usage: grok-fleet.sh {doctor|spawn|status|stop|collect|verify|clean}"
SUB="$1"; shift
case "$SUB" in
  doctor)  cmd_doctor "$@" ;;
  spawn)   cmd_spawn "$@" ;;
  status)  cmd_status "$@" ;;
  stop)    cmd_stop "$@" ;;
  collect) cmd_collect "$@" ;;
  clean)   cmd_clean "$@" ;;
  verify)  source "$REPO_ROOT/scripts/grok-verify.sh"; cmd_verify "$@" ;;
  drain)      source "$REPO_ROOT/scripts/grok-resume.sh"; cmd_drain "$@" ;;
  checkpoint) source "$REPO_ROOT/scripts/grok-resume.sh"; cmd_checkpoint "$@" ;;
  resume)     source "$REPO_ROOT/scripts/grok-resume.sh"; cmd_resume "$@" ;;
  *) die "unknown subcommand: $SUB" ;;
esac
