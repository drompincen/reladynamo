# Indexing hook scripts

JavaDucker and repository-intelligence hooks registered in `.claude/settings.json`.

Each `## <path>` section below is one script. `java scripts/GenerateScripts.java` writes it to that
path (relative to the repository root) with LF line endings; the generated file is gitignored.

## .claude/hooks/javaducker-check.sh

````bash
#!/bin/bash
# drom-flow — JavaDucker guard and lifecycle functions (sourced by other hooks)
# When .claude/.state/javaducker.conf does not exist, all functions return false.

JAVADUCKER_CONF="${CLAUDE_PROJECT_DIR:-.}/.claude/.state/javaducker.conf"
JAVADUCKER_SHARED=""

# Discover a shared JavaDucker instance from ancestor projects or running servers
javaducker_discover() {
  local dir
  dir="$(cd "${CLAUDE_PROJECT_DIR:-.}" && pwd)"

  # Phase 1: Walk up looking for an ancestor's javaducker.conf
  local parent
  parent="$(dirname "$dir")"
  while [ "$parent" != "/" ]; do
    if [ -f "$parent/.claude/.state/javaducker.conf" ]; then
      JAVADUCKER_CONF="$parent/.claude/.state/javaducker.conf"
      JAVADUCKER_SHARED="$parent"
      return 0
    fi
    parent="$(dirname "$parent")"
  done

  # Phase 2: Scan ports for a running JavaDucker (fast /dev/tcp pre-filter)
  # Use /api/info (returns app name) or /api/stats (returns artifact_count)
  # to positively identify JavaDucker and avoid false positives from other apps.
  local port resp
  for port in $(seq 8080 8180); do
    if (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
      resp=$(curl -sf "http://localhost:$port/api/info" 2>/dev/null)
      if echo "$resp" | grep -qi '"javaducker"'; then
        JAVADUCKER_HTTP_PORT="$port"
        JAVADUCKER_SHARED="localhost:$port"
        return 0
      fi
      # Fallback: /api/stats is JavaDucker-specific (has artifact_count)
      if curl -sf "http://localhost:$port/api/stats" 2>/dev/null | grep -q '"artifact_count"'; then
        JAVADUCKER_HTTP_PORT="$port"
        JAVADUCKER_SHARED="localhost:$port"
        return 0
      fi
    fi
  done

  return 1
}

# Check if using a shared (non-local) JavaDucker instance
javaducker_is_shared() {
  [ -n "$JAVADUCKER_SHARED" ]
}

javaducker_available() {
  # Check local config first
  if [ -f "$JAVADUCKER_CONF" ]; then
    . "$JAVADUCKER_CONF"
    [ -n "$JAVADUCKER_ROOT" ] && return 0
  fi
  # Try discovering a shared instance
  if javaducker_discover; then
    [ -f "$JAVADUCKER_CONF" ] && . "$JAVADUCKER_CONF"
    return 0
  fi
  return 1
}

javaducker_healthy() {
  javaducker_available || return 1
  curl -sf "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/health" >/dev/null 2>&1
}

# Find a free TCP port in the 8080-8180 range
javaducker_find_free_port() {
  for port in $(seq 8080 8180); do
    if ! (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
      echo "$port"
      return 0
    fi
  done
  echo "8080"
}

# Start the server with project-local data paths
javaducker_start() {
  javaducker_available || return 1
  javaducker_healthy && return 0

  # If using a shared instance, don't start — let the owning project handle it
  if javaducker_is_shared; then
    return 1
  fi

  local db="${JAVADUCKER_DB:-${CLAUDE_PROJECT_DIR:-.}/.claude/.javaducker/javaducker.duckdb}"
  local intake="${JAVADUCKER_INTAKE:-${CLAUDE_PROJECT_DIR:-.}/.claude/.javaducker/intake}"
  local port="${JAVADUCKER_HTTP_PORT:-8080}"

  mkdir -p "$(dirname "$db")" "$intake"

  # Check if the configured port is taken; if so, find a free one
  if (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
    # Port in use — check if it's our server
    if curl -sf "http://localhost:$port/api/health" >/dev/null 2>&1; then
      return 0  # Already running
    fi
    # Port taken by something else — find a free one
    port=$(javaducker_find_free_port)
    # Update config with new port
    sed -i "s/^JAVADUCKER_HTTP_PORT=.*/JAVADUCKER_HTTP_PORT=$port/" "$JAVADUCKER_CONF"
    export JAVADUCKER_HTTP_PORT="$port"
  fi

  DB="$db" HTTP_PORT="$port" INTAKE_DIR="$intake" \
    nohup bash "${JAVADUCKER_ROOT}/run-server.sh" >/dev/null 2>&1 &

  # Wait for startup
  for i in 1 2 3 4 5 6 7 8; do
    sleep 1
    if curl -sf "http://localhost:$port/api/health" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}
````

## .claude/hooks/javaducker-index.sh

````bash
#!/bin/bash
# drom-flow — index modified files in JavaDucker after edits
# Triggered by PostToolUse on Write|Edit|MultiEdit
# Fire-and-forget: does not block the edit. Silently no-ops if JavaDucker is not configured.

DIR="${CLAUDE_PROJECT_DIR:-.}"
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
javaducker_healthy || exit 0

# Extract file_path from tool input
file_path=""
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi
[ -z "$file_path" ] && exit 0
[ -f "$file_path" ] || exit 0

# Index via REST API (background, fire-and-forget)
abs_path=$(realpath "$file_path" 2>/dev/null || echo "$file_path")
curl -sf -X POST "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/upload-file" \
  -H "Content-Type: application/json" \
  -d "{\"file_path\":\"$abs_path\"}" \
  >/dev/null 2>&1 &
````

## .claude/hooks/repo-intel-session.sh

````bash
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
````

## .claude/hooks/repo-intel-mark.sh

````bash
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
````

## .claude/hooks/repo-intel-path.sh

````bash
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
````
