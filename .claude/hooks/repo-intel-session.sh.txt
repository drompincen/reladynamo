#!/bin/bash
# drom-flow — SessionStart check for repository intelligence.
#
# Metadata inspection only: this reads two small files and, when intake is genuinely needed,
# starts it detached so session startup is never blocked. Silent by design — the capability is
# supposed to be invisible, so a healthy graph prints nothing at all.

DIR="${CLAUDE_PROJECT_DIR:-.}"
RUN="$DIR/.claude/df/repo-intel/run"
if [ -f "$DIR/.claude/hooks/repo-intel-path.sh" ]; then
  . "$DIR/.claude/hooks/repo-intel-path.sh"
  STATE="$(repo_intel_state_dir "$DIR")"
else
  STATE="${DROMFLOW_REPO_INTEL_STATE:-$DIR/.claude/.state/repo-intel}"
fi

[ -f "$RUN" ] || exit 0
mkdir -p "$STATE" 2>/dev/null || true

# A machine already known to be unable to run the engine is left alone.
[ -f "$STATE/unavailable.json" ] && exit 0

needs_intake=1
if [ -f "$STATE/graph.json" ] && [ -f "$STATE/metadata.json" ]; then
  if grep -q '"status": *"ready"' "$STATE/metadata.json" 2>/dev/null; then
    installed="$(tr -d '[:space:]' < "$DIR/VERSION" 2>/dev/null)"
    recorded="$(sed -n 's/.*"drom_flow_version": *"\([^"]*\)".*/\1/p' "$STATE/metadata.json" 2>/dev/null)"
    if [ -z "$installed" ] || [ -z "$recorded" ] || [ "$installed" = "$recorded" ]; then
      needs_intake=0
    fi
  fi
fi

if [ "$needs_intake" = 1 ]; then
  # Detached and quiet. The engine also self-heals at query time, so a failure here is harmless.
  ( setsid nohup bash "$RUN" ensure >/dev/null 2>&1 & ) >/dev/null 2>&1
fi
exit 0
