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
