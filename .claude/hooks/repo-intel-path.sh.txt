#!/bin/bash
# drom-flow — where repository-intelligence state lives. Sourced, never run directly.
#
# Default is inside the host project, so the graph travels with the repository it describes and
# is removed cleanly by uninstall. It is configurable because some hosts keep generated state off
# the project tree entirely: a slow or synced filesystem, a read-only checkout, or a policy that
# forbids machine-generated files in the working copy.
#
# Resolution order (first wins):
#   1. DROMFLOW_REPO_INTEL_STATE           environment, absolute or relative to the project
#   2. REPO_INTEL_STATE in .claude/.state/drom-flow.conf
#   3. <project>/.claude/.state/repo-intel  (default)

repo_intel_state_dir() {
  local root="${1:-${CLAUDE_PROJECT_DIR:-.}}"
  local configured=""

  if [ -n "${DROMFLOW_REPO_INTEL_STATE:-}" ]; then
    configured="$DROMFLOW_REPO_INTEL_STATE"
  elif [ -f "$root/.claude/.state/drom-flow.conf" ]; then
    configured="$(sed -n 's/^REPO_INTEL_STATE=//p' "$root/.claude/.state/drom-flow.conf" | tail -1)"
    configured="${configured%\"}"; configured="${configured#\"}"
  fi

  if [ -z "$configured" ]; then
    printf '%s\n' "$root/.claude/.state/repo-intel"
    return 0
  fi
  # `~` and relative paths are resolved against the project, never against the caller's cwd.
  case "$configured" in
    "~"/*) configured="$HOME/${configured#~/}" ;;
  esac
  # Absolute means POSIX, a Windows drive letter, or a UNC share -- all three reach here on
  # WSL, and treating `C:/graphs` as relative would silently create a directory called `C:`.
  case "$configured" in
    /*|//*|\\\\*) printf '%s\n' "$configured" ;;
    [A-Za-z]:[/\\]*) printf '%s\n' "$configured" ;;
    # A relative value is joined to the project and is NOT constrained to stay inside it:
    # `../shared/repo-intel` is a legitimate way to share one graph directory between sibling
    # checkouts. That is the same capability an absolute path already grants, and this value
    # comes from the project's own config, never from indexed content.
    *)  printf '%s\n' "$root/$configured" ;;
  esac
}
