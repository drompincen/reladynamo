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
