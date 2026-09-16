#!/bin/bash
# drom-flow — PostToolUse dirty marker for repository intelligence.
#
# This is the hot path: it runs after every Write/Edit/MultiEdit. It must never start a JVM,
# never parse anything, and never block the edit. It appends one line and returns.
# Everything expensive happens later, at the first structural query that actually needs it.

DIR="${CLAUDE_PROJECT_DIR:-.}"
STATE="${DROMFLOW_REPO_INTEL_STATE:-}"
if [ -z "$STATE" ] && [ -f "$DIR/.claude/hooks/repo-intel-path.sh" ]; then
  . "$DIR/.claude/hooks/repo-intel-path.sh"
  STATE="$(repo_intel_state_dir "$DIR")"
fi
STATE="${STATE:-$DIR/.claude/.state/repo-intel}"
[ -d "$STATE" ] || exit 0

payload="${CLAUDE_TOOL_USE_INPUT:-}"
if [ -z "$payload" ] && [ ! -t 0 ]; then
  IFS= read -r -t 0.2 -d '' payload 2>/dev/null
fi
[ -n "$payload" ] || exit 0

# Bash regex, not grep/cut: no forks on the hot path.
[[ "$payload" =~ \"file_path\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]] || exit 0
fp="${BASH_REMATCH[1]}"
[ -n "$fp" ] || exit 0

case "$fp" in
  "$DIR"/*) fp="${fp#"$DIR"/}" ;;
  ./*)      fp="${fp#./}" ;;
esac
case "$fp" in
  /*|//*|\\\\*)          exit 0 ;;   # outside the project: not ours to track
  [A-Za-z]:[/\\]*)        exit 0 ;;   # Windows absolute path, likewise outside
  .claude/.state/*)        exit 0 ;;   # never make our own state look like a source change
esac

printf '%s\t%s\n' "$fp" "${EPOCHSECONDS:-0}" >> "$STATE/dirty" 2>/dev/null
exit 0
