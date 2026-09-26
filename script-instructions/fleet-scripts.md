# Fleet support scripts

Shared fleet runner, resume, task templating and retry helpers.

Each `## <path>` section below is one script. `java scripts/GenerateScripts.java` writes it to that
path (relative to the repository root) with LF line endings; the generated file is gitignored.

## scripts/fleet.sh

````bash
#!/bin/bash
# drom-flow — one front door to the sub-agent runners.
#
#   fleet.sh doctor
#   fleet.sh route <kind>                       # which runner, and why
#   fleet.sh spawn --manifest M [--backend B]   # B = auto (default) | grok | codex
#   fleet.sh status|collect|stop|resume --run-id R [...]
#
# The caller is Claude, and two entry points meant the choice of runner leaked into every plan.
# This decides it once, from measured capability rather than preference, and says why.
#
# Exit: 0 ok, 1 gate/agent failure, 2 usage, 3 no runner available here.

set -uo pipefail
R="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$R/.." && pwd)"
GROK="$R/grok-fleet.sh"
CODEX="$R/codex-fleet.sh"

log() { echo "[fleet] $*" >&2; }
die() { echo "[fleet] ERROR: $*" >&2; exit 2; }

have_grok()  { [ -x "$GROK" ]  && bash "$GROK"  doctor >/dev/null 2>&1 \
               && python3 -c "import json;import sys;sys.exit(0 if json.load(open('$REPO_ROOT/reports/grok-doctor.json')).get('available') else 1)" 2>/dev/null; }
have_codex() { [ -x "$CODEX" ] && bash "$CODEX" doctor >/dev/null 2>&1 \
               && python3 -c "import json;import sys;sys.exit(0 if json.load(open('$REPO_ROOT/reports/codex-doctor.json')).get('available') else 1)" 2>/dev/null; }

unavailable() {
  printf '{"ok":false,"available":false,"reason":"no sub-agent runner available (install grok or codex, or unset GROK_DISABLE/CODEX_DISABLE)","results":[]}\n'
  exit 3
}

# Preference by work kind. Capability first, taste second — the reason string says which.
#
# The single hard capability difference measured on this machine: codex's sandbox has NO network
# (DNS resolution fails), so anything needing the web or X can only be grok.
route_for() {
  local kind="${1:-default}" g=false c=false
  have_grok && g=true
  have_codex && c=true

  if ! $g && ! $c; then echo "none|no runner installed or both disabled"; return 3; fi

  case "$kind" in
    research|web|social|search)
      if $g; then echo "grok|needs the web; codex's sandbox has no network access"
      else echo "codex|only codex is available, but it cannot reach the network — expect no web sources"; fi ;;
    bulk|breadth|sweep)
      if $g; then echo "grok|breadth fan-out is what grok is for"
      else echo "codex|grok unavailable"; fi ;;
    audit|review|analysis|code|author|implement|refactor|test)
      if $c; then echo "codex|repository-grounded work: reliable repo reads and a sandbox that enforces write containment"
      else echo "grok|codex unavailable"; fi ;;
    *)
      if $c; then echo "codex|default: native binary, no path translation, real progress events"
      else echo "grok|codex unavailable"; fi ;;
  esac
}

cmd_route() {
  local kind="${1:-default}" out backend why
  out="$(route_for "$kind")" || { unavailable; }
  backend="${out%%|*}"; why="${out#*|}"
  [ "$backend" = none ] && unavailable
  printf '{"kind":%s,"backend":"%s","reason":%s}\n' \
    "$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$kind")" "$backend" \
    "$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$why")"
}

cmd_doctor() {
  local g=false c=false
  have_grok && g=true
  have_codex && c=true
  local any=false
  { $g || $c; } && any=true
  printf '{"ok":true,"grok":%s,"codex":%s,"any":%s}\n' "$g" "$c" "$any"
  # Reporting "neither" is a valid answer, not a failure: callers ask in order to decide.
  return 0
}

# Which control plane holds this run? Saves every caller from remembering.
backend_of_run() {
  local run="$1"
  [ -d "${GROK_FLEET_ROOT:-$REPO_ROOT/.claude/.grok-fleet}/$run" ] && { echo grok; return 0; }
  [ -d "${CODEX_FLEET_ROOT:-$REPO_ROOT/.claude/.fleet}/$run" ] && { echo codex; return 0; }
  return 1
}

delegate() { # backend subcommand args...
  local b="$1"; shift
  case "$b" in
    grok)  bash "$GROK"  "$@" ;;
    codex) bash "$CODEX" "$@" ;;
    *) die "unknown backend: $b" ;;
  esac
}

cmd_spawn() {
  local backend="auto" kind="default" args=()
  while [[ $# -gt 0 ]]; do case $1 in
    --backend) backend="$2"; shift 2 ;;
    --kind)    kind="$2"; shift 2 ;;
    *) args+=("$1"); shift ;;
  esac; done
  if [ "$backend" = auto ]; then
    local out; out="$(route_for "$kind")" || unavailable
    backend="${out%%|*}"
    [ "$backend" = none ] && unavailable
    log "backend=$backend (${out#*|})"
  else
    case "$backend" in
      grok)  have_grok  || unavailable ;;
      codex) have_codex || unavailable ;;
      *) die "unknown backend: $backend" ;;
    esac
  fi
  delegate "$backend" spawn "${args[@]}"
}

cmd_passthrough() { # subcommand args...
  local sub="$1"; shift
  local run="" args=()
  local i=0 argv=("$@")
  while [[ $i -lt ${#argv[@]} ]]; do
    if [[ "${argv[$i]}" == "--run-id" ]]; then run="${argv[$((i+1))]}"; fi
    args+=("${argv[$i]}"); ((i++))
  done
  [ -n "$run" ] || die "$sub needs --run-id"
  local b
  b="$(backend_of_run "$run")" || die "no such run in either control plane: $run"
  delegate "$b" "$sub" "${args[@]}"
}

[[ $# -eq 0 ]] && die "usage: fleet.sh {doctor|route|spawn|status|collect|stop|resume}"
SUB="$1"; shift
case "$SUB" in
  doctor)  cmd_doctor "$@" ;;
  route)   cmd_route "$@" ;;
  spawn)   cmd_spawn "$@" ;;
  status|collect|stop|resume) cmd_passthrough "$SUB" "$@" ;;
  *) die "unknown subcommand: $SUB" ;;
esac
````

## scripts/grok-resume.sh

````bash
#!/bin/bash
# drom-flow — resume support for the grok fleet.
# Sourced by grok-fleet.sh. Provides: drain (detached runner), checkpoint (compact
# resume record), resume (reconcile + re-dispatch).
#
# Premise: Claude tokens are finite, grok's are not. Claude is therefore the
# interruptible component — in-flight grok work must be able to finish without it,
# and a cold Claude session must resume from a tiny amount of state.

RESUME_MAX_BYTES="${RESUME_MAX_BYTES:-2048}"

# --- drain: run a manifest to completion detached from this shell ---------------
# Survives the Claude session ending, so a fan-out is never stranded.
cmd_drain() {
  local mf=""
  while [[ $# -gt 0 ]]; do case $1 in --manifest) mf="$2"; shift 2 ;; *) shift ;; esac; done
  [[ -f "$mf" ]] || die "drain needs --manifest <file>"
  local run_id; run_id="$(python3 -c "import json;print(json.load(open('$mf'))['run_id'])")"
  mkdir -p "$FLEET_ROOT/$run_id"
  local logf="$FLEET_ROOT/$run_id/drain.log"
  nohup setsid bash "$REPO_ROOT/scripts/grok-fleet.sh" spawn --manifest "$mf" \
        > "$logf" 2>&1 < /dev/null &
  disown 2>/dev/null
  echo "{\"run_id\":\"$run_id\",\"detached\":true,\"log\":\"$logf\"}"
  log "drain: run $run_id detached — it will finish without Claude"
}

# --- checkpoint: the entire cost of resuming --------------------------------------
cmd_checkpoint() {
  local run_id="" goal="" plan="" chapter=""
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --goal) goal="$2"; shift 2 ;;
    --plan) plan="$2"; shift 2 ;;
    --chapter) chapter="$2"; shift 2 ;;
    *) shift ;;
  esac; done
  [[ -n "$run_id" ]] || die "checkpoint needs --run-id"
  local rd="$FLEET_ROOT/$run_id"; mkdir -p "$rd"
  [[ -n "$goal"    ]] && echo "$goal"    > "$rd/.goal"
  [[ -n "$plan"    ]] && echo "$plan"    > "$rd/.plan"
  [[ -n "$chapter" ]] && echo "$chapter" > "$rd/.chapter"

  python3 - "$rd" "$RESUME_MAX_BYTES" <<'PY'
import json,os,sys
rd,cap=sys.argv[1],int(sys.argv[2])
def rd_file(n,d=''):
    p=os.path.join(rd,n)
    return open(p).read().strip() if os.path.exists(p) else d
base=os.path.join(rd,'agents')
done=[];pend=[];run=[]
if os.path.isdir(base):
    for a in sorted(os.listdir(base)):
        try: st=json.load(open(os.path.join(base,a,'status.json')))['state']
        except Exception: st='UNKNOWN'
        (done if st=='DONE' else run if st=='RUNNING' else pend).append(f"{a}:{st}")
L=[]
L.append(f"# RESUME — {os.path.basename(rd)}")
g=rd_file('.goal');  L.append(f"goal: {g}") if g else None
p=rd_file('.plan');  L.append(f"plan: {p}") if p else None
c=rd_file('.chapter');L.append(f"chapter: {c}") if c else None
L.append(f"done({len(done)}): {', '.join(done) or '-'}")
L.append(f"running({len(run)}): {', '.join(run) or '-'}")
L.append(f"pending({len(pend)}): {', '.join(pend) or '-'}")
L.append(f"next: bash scripts/grok-fleet.sh resume --run-id {os.path.basename(rd)}")
L.append("note: DONE units are never re-run. Read outputs only for FAILED units.")
out='\n'.join(L)+'\n'
if len(out.encode())>cap:                      # hard cap — resuming must stay cheap
    out=out.encode()[:cap-20].decode('utf-8','ignore')+"\n...(truncated)\n"
tmp=os.path.join(rd,'.RESUME.tmp')
open(tmp,'w').write(out); os.replace(tmp,os.path.join(rd,'RESUME.md'))
print(out,end='')
PY
}

# --- resume: reconcile reality, re-dispatch only what is genuinely incomplete -----
cmd_resume() {
  local run_id=""
  while [[ $# -gt 0 ]]; do case $1 in --run-id) run_id="$2"; shift 2 ;; *) shift ;; esac; done
  [[ -n "$run_id" ]] || die "resume needs --run-id"
  local rd="$FLEET_ROOT/$run_id" base="$FLEET_ROOT/$run_id/agents"
  [[ -d "$base" ]] || die "no such run: $run_id"

  # Reconcile: trust on-disk results over the recorded state. An agent whose process
  # is gone but whose result is complete really did finish; one with neither is
  # INTERRUPTED and must be redone.
  local recovered=0 interrupted=0
  for d in "$base"/*; do [[ -d "$d" ]] || continue
    local s; s="$(get_state "$d")"
    [[ "$s" == RUNNING ]] || continue
    local pid alive=0
    pid="$(python3 -c "import json;print(json.load(open('$d/pid'))['wsl_pid'])" 2>/dev/null)"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && alive=1
    (( alive )) && continue
    if [[ -s "$d/result.json" ]] && [[ -n "$(ls -A "$d/output" 2>/dev/null)" ]]; then
      local c; c="$(python3 -c "import json;print(json.load(open('$d/result.json')).get('total_cost_usd',0))" 2>/dev/null || echo 0)"
      set_status "$d" DONE "\"recovered\":true,\"cost_usd\":${c:-0}"; recovered=$(( recovered + 1 ))
    else
      set_status "$d" INTERRUPTED "\"reason\":\"process gone, no result\""; interrupted=$(( interrupted + 1 ))
    fi
  done
  log "resume: recovered=$recovered interrupted=$interrupted"

  # Re-dispatch. spawn is idempotent — DONE agents are skipped, never re-billed.
  local mf="$rd/run.json"
  if [[ -f "$mf" ]]; then
    cmd_spawn_manifest "$mf"
  else
    log "resume: no stored manifest; nothing to re-dispatch automatically"
  fi
  cmd_checkpoint --run-id "$run_id" >/dev/null
  cmd_collect --run-id "$run_id" --brief
}
````

## scripts/agent-changes.sh

````bash
#!/bin/bash
# Which files did an agent ACTUALLY change?
#
# Every agent mirrors the whole source tree it was given, so "differs from the repo" is not the same
# as "the agent edited it" — the repo has moved on since the agent branched. Comparing against the
# repo alone once nearly reverted a landed fix. Compare against the agent's own BASELINE instead.
#
#   agent-changes.sh <agent-output-dir> [baseline-dir]
#
# REAL  = differs from baseline -> the agent edited it
# STALE = identical to baseline -> untouched; taking it would revert whatever landed since
# NEW   = absent from baseline  -> the agent created it
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
R="$1"
BASE="${2:-$ROOT/.claude/.grok-fleet/fix/agents/r06/output}"
[ -d "$R" ] || { echo "no such agent output: $R" >&2; exit 2; }

cd "$R" || exit 2
find reladynamo-*/src -name '*.java' 2>/dev/null | sort | while IFS= read -r f; do
  if [ ! -f "$BASE/$f" ]; then
    [ -f "$ROOT/$f" ] && echo "NEW-ISH $f" || echo "NEW     $f"
  elif diff -q --strip-trailing-cr "$BASE/$f" "$R/$f" >/dev/null 2>&1; then
    diff -q --strip-trailing-cr "$ROOT/$f" "$R/$f" >/dev/null 2>&1 || echo "STALE   $f"
  else
    echo "REAL    $f"
  fi
done
````

## scripts/astra-retry.sh

````bash
#!/bin/bash
# Re-dispatch an astra (codex gpt-6-astra) run once the usage limit resets.
#
# codex reports its reset time in the error text ("try again at 2:53 PM") and nothing exposes a
# live quota meter, so this polls rather than predicts: try, and if the failure is a usage limit,
# wait and try again. Any other failure is a real failure and stops the loop — retrying a broken
# task forever is how a fleet burns a night producing nothing.
#
#   astra-retry.sh <manifest> <run_id> <agent_id> [interval_secs] [max_tries]
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MF="$1"; RUN="$2"; AGENT="$3"; INT="${4:-900}"; MAX="${5:-24}"
ST="$REPO_ROOT/.claude/.fleet/$RUN/agents/$AGENT/status.json"
EV="$REPO_ROOT/.claude/.fleet/$RUN/agents/$AGENT/events.jsonl"

for i in $(seq 1 "$MAX"); do
  echo "[astra-retry] attempt $i/$MAX $(date -Is)"
  CODEX_MODEL=gpt-6-astra CODEX_AGENT_TIMEOUT=7200 \
    bash "$REPO_ROOT/scripts/codex-fleet.sh" spawn --manifest "$MF" >/dev/null 2>&1

  state="$(python3 -c "import json;print(json.load(open('$ST'))['state'])" 2>/dev/null || echo UNKNOWN)"
  if [[ "$state" != "FAILED" ]]; then
    echo "[astra-retry] state=$state — done"; exit 0
  fi
  if ! grep -q "usage limit" "$EV" 2>/dev/null; then
    echo "[astra-retry] FAILED for a reason other than the usage limit — not retrying"; exit 1
  fi
  echo "[astra-retry] usage limit still in force; sleeping ${INT}s"
  sleep "$INT"
done
echo "[astra-retry] gave up after $MAX attempts"; exit 1
````

## scripts/mk-task.sh

````bash
#!/bin/bash
# drom-flow — generate a grok task.md from a template, so dispatching N units costs
# Claude one command instead of N hand-written prompts.
#
#   mk-task.sh <template> <out-file> KEY=VALUE ...
#
# Templates live in scripts/task-templates/*.md and use {{KEY}} placeholders.
# On an unmetered grok account, prompts are free — templates are verbose on purpose.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TPL_DIR="$REPO_ROOT/scripts/task-templates"

[[ $# -ge 2 ]] || { echo "usage: mk-task.sh <template> <out-file> KEY=VAL ..." >&2; exit 2; }
tpl="$1"; out="$2"; shift 2
src="$TPL_DIR/$tpl.md"
[[ -f "$src" ]] || { echo "no such template: $tpl (have: $(ls "$TPL_DIR" 2>/dev/null | sed 's/\.md//' | tr '\n' ' '))" >&2; exit 2; }

mkdir -p "$(dirname "$out")"
cp "$src" "$out"
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  python3 - "$out" "$k" "$v" <<'PY'
import sys
p,k,v=sys.argv[1],sys.argv[2],sys.argv[3]
s=open(p,encoding='utf-8').read().replace('{{'+k+'}}',v)
open(p,'w',encoding='utf-8').write(s)
PY
done
# Leftover placeholders mean a caller forgot a key — fail loudly rather than
# shipping a prompt with literal {{FOO}} in it.
if grep -q '{{[A-Z_]*}}' "$out"; then
  echo "ERROR: unfilled placeholders in $out: $(grep -o '{{[A-Z_]*}}' "$out" | sort -u | tr '\n' ' ')" >&2
  exit 2
fi
echo "$out"
````
