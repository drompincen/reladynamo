#!/bin/bash
# drom-flow — codex sub-agent fleet: filesystem-controlled fan-out to the codex CLI.
#
# Subcommands: doctor | spawn | status | stop | collect | resume | clean
# Exit: 0 = ok, 1 = gate/agent failure, 2 = usage error, 3 = codex not available here
#
# Sibling of scripts/grok-fleet.sh and deliberately the same on-disk protocol, so `collect
# --brief` reads identically whichever runner produced the work. Two things differ, both in
# codex's favour:
#
#   * codex is a native binary, so there is none of grok.exe's Windows path translation and the
#     control plane can live anywhere.
#   * `--json` emits real activity events and `-o` writes the final message straight to a file,
#     so nothing here has to reconstruct a verdict by parsing a token stream.
#
# Optionality is a hard rule: with no codex installed, `doctor` succeeds and reports
# available=false, and nothing else in drom-flow says a word about it.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLEET_ROOT="${CODEX_FLEET_ROOT:-$REPO_ROOT/.claude/.fleet}"
REPORT_DIR="$REPO_ROOT/reports"
CODEX_MODEL="${CODEX_MODEL:-}"
MAX_PARALLEL="${CODEX_MAX_PARALLEL:-4}"
AGENT_TIMEOUT="${CODEX_AGENT_TIMEOUT:-900}"
GRACE_SECS="${CODEX_GRACE_SECS:-10}"
STALL_SECS="${CODEX_STALL_SECS:-240}"
# Codex reports tokens, not dollars. 0 disables the cap; spend is always recorded either way.
TOKEN_CAP="${CODEX_TOKEN_CAP:-0}"

log() { echo "[codex-fleet] $*" >&2; }
die() { echo "[codex-fleet] ERROR: $*" >&2; exit 2; }
jstr() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

# --- binary resolution -------------------------------------------------------
resolve_codex() {
  # An explicit opt-out for people who have codex installed but do not want drom-flow using it.
  # Treated exactly like "not installed": supported state, no complaint.
  [[ "${CODEX_DISABLE:-0}" == "1" ]] && return 1
  if [[ -n "${CODEX_BIN:-}" && -x "$CODEX_BIN" ]]; then echo "$CODEX_BIN"; return 0; fi
  local c; c="$(command -v codex 2>/dev/null)"; [[ -n "$c" ]] && { echo "$c"; return 0; }
  for p in "$HOME/.local/bin/codex" "/usr/local/bin/codex" "/mnt/c/Users/$USER/codex/codex"; do
    [[ -x "$p" ]] && { echo "$p"; return 0; }
  done
  return 1
}

# Absence is a fact, not a failure.
#
# NB: this must be called in the CURRENT shell, never as `bin="$(require_codex)"` -- an `exit`
# inside a command substitution only leaves the subshell, and the caller sails on with the error
# JSON as the binary path. That bug shipped for exactly one test run.
unavailable() {
  printf '{"ok":false,"available":false,"reason":"codex CLI not available here (not installed, or CODEX_DISABLE=1)","results":[]}\n'
  exit 3
}

# --- doctor ------------------------------------------------------------------
cmd_doctor() {
  local live=false; [[ "${1:-}" == "--live" ]] && live=true
  mkdir -p "$REPORT_DIR"
  local bin ver auth checks=() ok=true

  if ! bin="$(resolve_codex)"; then
    # Exit 0 on purpose: "codex is not installed" is a supported state of the world, and a
    # non-zero exit here would make every optional caller look like a broken one.
    cat > "$REPORT_DIR/codex-doctor.json" <<EOF
{"ok":true,"available":false,"reason":"codex CLI not found on PATH, \$CODEX_BIN or known locations"}
EOF
    log "doctor: codex not installed (this is fine)"
    return 0
  fi

  add() { checks+=("{\"name\":\"$1\",\"ok\":$2,\"detail\":$(jstr "$3")}"); [[ "$2" == "true" ]] || ok=false; }
  add binary true "$bin"
  ver="$(timeout 60 "$bin" --version 2>/dev/null | head -1)"
  [[ -n "$ver" ]] && add version true "$ver" || add version false "--version produced no output"
  auth="${CODEX_HOME:-$HOME/.codex}/auth.json"
  [[ -f "$auth" ]] && add auth true "auth.json present" || add auth false "not authenticated: run 'codex login'"

  local live_json='{"ran":false,"ok":false,"latency_ms":0}'
  if $live && $ok; then
    local t0 t1 out rc tmp; tmp="$(mktemp -d)"
    t0=$(date +%s%3N)
    out="$(cd "$tmp" && timeout 120 "$bin" exec --skip-git-repo-check -s read-only \
           "Reply with exactly the single word: PONG" </dev/null 2>/dev/null | tail -1)"; rc=$?
    t1=$(date +%s%3N); rm -rf "$tmp"
    if [[ $rc -eq 0 && "$out" == *PONG* ]]; then live_json="{\"ran\":true,\"ok\":true,\"latency_ms\":$((t1-t0))}"
    else live_json="{\"ran\":true,\"ok\":false,\"latency_ms\":$((t1-t0))}"; ok=false; fi
  fi

  local IFS=,
  cat > "$REPORT_DIR/codex-doctor.json" <<EOF
{"ok":$ok,"available":true,"binary":$(jstr "$bin"),"version":$(jstr "$ver"),
 "fleet_root":$(jstr "$FLEET_ROOT"),"checks":[${checks[*]}],"live_test":$live_json}
EOF
  $ok && { log "doctor: OK"; return 0; } || { log "doctor: FAILED (see $REPORT_DIR/codex-doctor.json)"; return 1; }
}

# --- agent plumbing ----------------------------------------------------------
agent_dir()  { echo "$FLEET_ROOT/$1/agents/$2"; }
set_status() {
  local d="$1" s="$2" extra="${3:-}"
  printf '{"state":"%s","backend":"codex","ts":"%s"%s}\n' "$s" "$(date -Iseconds)" "${extra:+,$extra}" > "$d/.status.tmp"
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

# Distil the event stream into result.json: verdict text, usage, thread id for resume.
harvest() {
  python3 - "$1" <<'PY'
import json, os, sys
d = sys.argv[1]
out = {"backend": "codex"}
ev = os.path.join(d, "events.jsonl")
try:
    for line in open(ev, errors="replace"):
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        t = o.get("type")
        if t == "thread.started":
            out["thread_id"] = o.get("thread_id")
        elif t == "turn.completed":
            out["usage"] = o.get("usage", {})
        elif t == "item.completed":
            out.setdefault("items", []).append(o.get("item", {}).get("type"))
except FileNotFoundError:
    pass
last = os.path.join(d, "last-message.txt")
if os.path.exists(last):
    out["text"] = open(last, errors="replace").read().strip()
u = out.get("usage") or {}
out["total_tokens"] = int(u.get("input_tokens", 0)) + int(u.get("output_tokens", 0))
json.dump(out, open(os.path.join(d, "result.json"), "w"), indent=2)
PY
}

run_agent() {
  local d="$1" bin="$2" schema="${3:-}" sandbox="${4:-workspace-write}" cwd="${5:-}"
  local -a args=( exec --json --skip-git-repo-check -s "$sandbox"
                  -C "${cwd:-$d/output}" -o "$d/last-message.txt" )
  [[ -n "$CODEX_MODEL" ]] && args+=( -m "$CODEX_MODEL" )
  # Generic passthrough so reasoning effort (and any other -c setting) can be pinned per run
  # without another flag here. e.g. CODEX_EXTRA_ARGS='-c model_reasoning_effort="ultra"'
  if [[ -n "${CODEX_EXTRA_ARGS:-}" ]]; then
    # shellcheck disable=SC2206
    local _extra=( ${CODEX_EXTRA_ARGS} )
    args+=( "${_extra[@]}" )
  fi
  [[ -n "$schema" ]] && args+=( --output-schema "$schema" )
  args+=( - )   # prompt on stdin: no argv length limit, no quoting hazards
  printf '%q ' "$bin" "${args[@]}" > "$d/cmd.txt"

  timeout "$AGENT_TIMEOUT" "$bin" "${args[@]}" < "$d/task.md" > "$d/events.jsonl" 2> "$d/stream.err" &
  local pid=$!
  printf '{"pid":%d}\n' "$pid" > "$d/pid"
  set_status "$d" RUNNING "\"pid\":$pid"
  wait $pid; local rc=$?

  harvest "$d"
  local state
  case $rc in
    0)   state=DONE ;;
    124) state=TIMEOUT ;;
    143|137) state=STOPPED ;;
    *)   state=FAILED ;;
  esac
  # An agent that produced nothing did not do the work, whatever the exit code says.
  if [[ "$state" == DONE && -z "$(ls -A "$d/output" 2>/dev/null)" && ! -s "$d/last-message.txt" ]]; then
    state=FAILED
  fi
  local tok; tok="$(python3 -c "import json;print(json.load(open('$d/result.json')).get('total_tokens',0))" 2>/dev/null || echo 0)"
  set_status "$d" "$state" "\"exit\":$rc,\"tokens\":$tok"
}

run_total_tokens() {
  python3 - "$FLEET_ROOT/$1/agents" <<'PY'
import json, os, sys
b = sys.argv[1]; t = 0
if os.path.isdir(b):
    for a in os.listdir(b):
        try:
            t += int(json.load(open(os.path.join(b, a, "status.json"))).get("tokens") or 0)
        except Exception:
            pass
print(t)
PY
}

cmd_spawn() {
  local run_id="" agent_id="" task_file="" schema="" sandbox="workspace-write" write_repo=false bin
  [[ "${1:-}" == "--manifest" ]] && { shift; cmd_spawn_manifest "$@"; return $?; }
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --agent-id) agent_id="$2"; shift 2 ;;
    --task-file) task_file="$2"; shift 2 ;;
    --schema) schema="$2"; shift 2 ;;
    --sandbox) sandbox="$2"; shift 2 ;;
    --write-repo) write_repo=true; shift ;;
    *) die "spawn: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" && -n "$agent_id" && -n "$task_file" ]] || die "spawn needs --run-id --agent-id --task-file"
  [[ -f "$task_file" ]] || die "task file not found: $task_file"
  bin="$(resolve_codex)" || unavailable

  local d cwd=""
  d="$(agent_dir "$run_id" "$agent_id")"
  if [[ -f "$d/status.json" && "$(get_state "$d")" == DONE ]]; then
    log "spawn: $agent_id already DONE, skipping"; return 0
  fi
  mkdir -p "$d/output"
  $write_repo && { cwd="$REPO_ROOT"; sandbox="workspace-write"; }
  { cat "$task_file"; printf '%s' "$FLEET_PREAMBLE"; } > "$d/task.md"
  : > "$d/PROGRESS.md"
  set_status "$d" QUEUED

  local attempts="${CODEX_MAX_ATTEMPTS:-2}" n=1
  while :; do
    run_agent "$d" "$bin" "$schema" "$sandbox" "$cwd"
    [[ "$(get_state "$d")" == DONE ]] && break
    # A deliberate stop is not a failure. Retrying past one silently defeats `stop`.
    [[ "$(get_state "$d")" == STOPPED || -f "$d/STOP" ]] && { log "agent $agent_id stopped by request"; break; }
    (( n >= attempts )) && break
    log "agent $agent_id attempt $n/$attempts -> $(get_state "$d"), retrying"
    { echo; echo "--- PREVIOUS ATTEMPT FAILED (attempt $n) ---";
      tail -c 600 "$d/stream.err" 2>/dev/null;
      echo "Fix the cause and complete the task. Write output into your working directory."; } >> "$d/task.md"
    n=$(( n + 1 ))
  done
  printf '{"attempts":%d}\n' "$n" > "$d/attempts.json"
  [[ "$(get_state "$d")" == DONE ]]
}

cmd_spawn_manifest() {
  local mf="${1:-}" write_repo=false
  shift || true
  while [[ $# -gt 0 ]]; do case $1 in --write-repo) write_repo=true; shift ;; *) shift ;; esac; done
  [[ -f "$mf" ]] || die "manifest not found: $mf"
  resolve_codex >/dev/null || unavailable

  local run_id par
  run_id="$(python3 -c "import json;print(json.load(open('$mf'))['run_id'])")"
  par="$(python3 -c "import json;print(json.load(open('$mf')).get('max_parallel',$MAX_PARALLEL))")"
  # Parallel agents writing into one working tree corrupt each other. Fan-out is the point of
  # the fleet, so repository writes are serialised rather than silently racing.
  if $write_repo; then
    [[ "$par" -gt 1 ]] && log "--write-repo: forcing max_parallel 1 (was $par)"
    par=1
  fi
  mkdir -p "$FLEET_ROOT/$run_id"; rm -f "$FLEET_ROOT/$run_id/DONE" "$FLEET_ROOT/$run_id/HALT"
  [[ "$(readlink -f "$mf")" == "$(readlink -f "$FLEET_ROOT/$run_id/run.json")" ]] || cp -f "$mf" "$FLEET_ROOT/$run_id/run.json"
  log "manifest run=$run_id parallel=$par sandbox=$($write_repo && echo repo-write || echo agent-dir)"

  local -a pids=()
  alive() { local n=0 p; for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && ((n++)); done; echo $n; }

  while IFS=$'\t' read -r aid tf schema sandbox; do
    [[ -z "$aid" ]] && continue
    [[ -f "$FLEET_ROOT/$run_id/HALT" ]] && { log "halted, not launching $aid"; break; }
    if [[ ! -f "$tf" ]]; then
      log "manifest: agent '$aid' task_file missing: $tf — skipping"
      mkdir -p "$FLEET_ROOT/$run_id/agents/$aid"
      set_status "$FLEET_ROOT/$run_id/agents/$aid" FAILED "\"reason\":\"task_file missing\""
      continue
    fi
    while (( $(alive) >= par )); do sleep 1; done
    if [[ "$TOKEN_CAP" -gt 0 ]]; then
      local spent; spent="$(run_total_tokens "$run_id")"
      if (( spent > TOKEN_CAP )); then
        log "TOKEN CAP EXCEEDED: $spent > $TOKEN_CAP — not launching $aid or any remaining agent"
        echo "{\"state\":\"TOKEN_CAP\",\"spent\":$spent,\"cap\":$TOKEN_CAP}" > "$FLEET_ROOT/$run_id/HALT"
        break
      fi
    fi
    ( cmd_spawn --run-id "$run_id" --agent-id "$aid" --task-file "$tf" \
        ${schema:+--schema "$schema"} ${sandbox:+--sandbox "$sandbox"} \
        $($write_repo && echo --write-repo) >/dev/null 2>&1 ) &
    pids+=($!)
  done < <(python3 -c "
import json
for a in json.load(open('$mf'))['agents']:
    print('\t'.join([a['id'], a['task_file'], a.get('schema',''), a.get('sandbox','')]))")

  (( ${#pids[@]} > 0 )) && wait "${pids[@]}" 2>/dev/null
  touch "$FLEET_ROOT/$run_id/DONE"
  log "run $run_id complete, $(run_total_tokens "$run_id") tokens"
  cmd_status --run-id "$run_id" >/dev/null 2>&1
  [[ -f "$FLEET_ROOT/$run_id/HALT" ]] && return 1 || return 0
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

  python3 - "$base" "$STALL_SECS" "$REPORT_DIR/codex-fleet-$run_id.json" "$as_json" <<'PY'
import json, os, sys, time
base, stall, out, as_json = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4] == 'true'
agents = []; total = 0
for a in sorted(os.listdir(base)):
    d = os.path.join(base, a)
    if not os.path.isdir(d): continue
    st = {}
    try: st = json.load(open(os.path.join(d, 'status.json')))
    except Exception: pass
    state = st.get('state', 'UNKNOWN'); tok = int(st.get('tokens') or 0); total += tok
    ev = os.path.join(d, 'events.jsonl')
    age = int(time.time() - os.path.getmtime(ev)) if os.path.exists(ev) else -1
    if state == 'RUNNING' and age > stall: state = 'STALLED'
    # codex reports real activity, so steps are counted from the event stream and only fall
    # back to the agent-written checkpoints when there are none
    steps = 0
    try:
        for line in open(ev, errors='replace'):
            if '"item.completed"' in line: steps += 1
    except Exception: pass
    prog = []
    p = os.path.join(d, 'PROGRESS.md')
    if os.path.exists(p):
        prog = [l.strip() for l in open(p, errors='replace') if l.strip()]
    agents.append({'agent': a, 'state': state, 'tokens': tok, 'stream_age_s': age,
                   'steps': steps or len(prog), 'last_progress': prog[-1] if prog else ''})
roll = {'running': sum(1 for x in agents if x['state'] in ('RUNNING', 'STALLED')),
        'done': sum(1 for x in agents if x['state'] == 'DONE'),
        'failed': sum(1 for x in agents if x['state'] in ('FAILED', 'TIMEOUT')),
        'stopped': sum(1 for x in agents if x['state'] == 'STOPPED'),
        'total_tokens': total}
json.dump({'backend': 'codex', 'agents': agents, 'rollup': roll}, open(out, 'w'), indent=2)
if as_json:
    print(json.dumps({'backend': 'codex', 'agents': agents, 'rollup': roll}))
else:
    print(f"{'AGENT':<22}{'STATE':<10}{'STEPS':>6}{'AGE':>6}{'TOKENS':>9}  LAST")
    for x in agents:
        print(f"{x['agent']:<22}{x['state']:<10}{x['steps']:>6}{x['stream_age_s']:>6}{x['tokens']:>9}  {x['last_progress'][:44]}")
    print(f"-- {roll['done']} done / {roll['running']} running / {roll['failed']} failed / "
          f"{roll['stopped']} stopped, {roll['total_tokens']} tokens")
PY
}

# --- stop --------------------------------------------------------------------
kill_agent() {
  local d="$1" pid
  pid="$(python3 -c "import json;print(json.load(open('$d/pid'))['pid'])" 2>/dev/null)" || return 0
  [[ -z "$pid" ]] && return 0
  touch "$d/STOP"
  local waited=0
  while kill -0 "$pid" 2>/dev/null && (( waited < GRACE_SECS )); do sleep 1; ((waited++)); done
  kill -0 "$pid" 2>/dev/null && { kill "$pid" 2>/dev/null; sleep 2; }
  kill -0 "$pid" 2>/dev/null && { kill -9 "$pid" 2>/dev/null; sleep 1; }
  local prior; prior="$(python3 -c "
import json
try: print(json.load(open('$d/status.json')).get('tokens') or 0)
except Exception: print(0)" 2>/dev/null || echo 0)"
  set_status "$d" STOPPED "\"stopped_by\":\"fleet\",\"tokens\":${prior:-0}"
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
    --run-id) run_id="$2"; shift 2 ;; --brief) brief=true; shift ;; *) die "collect: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" ]] || die "collect needs --run-id"
  local base="$FLEET_ROOT/$run_id/agents" failed=0

  # Brief mode is what Claude reads: verdicts only, never agent output bodies.
  if $brief; then
    for d in "$base"/*; do [[ -d "$d" ]] || continue
      local a s line; a="$(basename "$d")"; s="$(get_state "$d")"
      [[ "$s" == DONE ]] || failed=1
      line="$(grep -h '^RESULT:' "$d/output"/* "$d/last-message.txt" 2>/dev/null | head -1)"
      [[ -z "$line" ]] && line="$(tail -c 200 "$d/last-message.txt" 2>/dev/null | tr '\n' ' ')"
      [[ -z "$line" ]] && line="$(tail -n1 "$d/PROGRESS.md" 2>/dev/null)"
      printf '%s\t%s\t%s\n' "$a" "$s" "${line:0:90}"
    done
    echo "-- run=$run_id backend=codex tokens=$(run_total_tokens "$run_id") outputs=$base/<id>/output/"
    return $failed
  fi

  local out="$REPORT_DIR/codex-fleet-$run_id.md"
  mkdir -p "$REPORT_DIR"
  { echo "# Codex fleet run: $run_id"; echo; echo "_$(date -Iseconds)_"; echo; } > "$out"
  for d in "$base"/*; do [[ -d "$d" ]] || continue
    local a s; a="$(basename "$d")"; s="$(get_state "$d")"
    [[ "$s" == DONE ]] || failed=1
    { echo "## $a — **$s**"; echo '```'; head -c 1200 "$d/last-message.txt" 2>/dev/null || echo '(no output)';
      echo '```'; echo; } >> "$out"
  done
  echo "**Total tokens: $(run_total_tokens "$run_id")**" >> "$out"
  log "collect -> $out"
  return $failed
}

# --- resume ------------------------------------------------------------------
cmd_resume() {
  local run_id=""
  while [[ $# -gt 0 ]]; do case $1 in --run-id) run_id="$2"; shift 2 ;; *) die "resume: unknown arg $1" ;; esac; done
  [[ -n "$run_id" ]] || die "resume needs --run-id"
  local mf="$FLEET_ROOT/$run_id/run.json"
  [[ -f "$mf" ]] || die "no manifest recorded for run $run_id"
  resolve_codex >/dev/null || unavailable
  log "resuming $run_id — finished agents are never re-run"
  cmd_spawn_manifest "$mf"
}

cmd_clean() { rm -rf "${FLEET_ROOT:?}"/*; log "fleet root cleared"; }

[[ $# -eq 0 ]] && die "usage: codex-fleet.sh {doctor|spawn|status|stop|collect|resume|clean}"
SUB="$1"; shift
case "$SUB" in
  doctor)  cmd_doctor "$@" ;;
  spawn)   cmd_spawn "$@" ;;
  status)  cmd_status "$@" ;;
  stop)    cmd_stop "$@" ;;
  collect) cmd_collect "$@" ;;
  resume)  cmd_resume "$@" ;;
  clean)   cmd_clean "$@" ;;
  *) die "unknown subcommand: $SUB" ;;
esac
