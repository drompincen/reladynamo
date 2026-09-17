#!/bin/bash
# drom-flow memory sync — inject session memory and check for in-progress plans on start

DIR="${CLAUDE_PROJECT_DIR:-.}"
MEMORY="$DIR/context/MEMORY.md"
STATE_DIR="$DIR/.claude/.state"
PLANS_DIR="$DIR/drom-plans"

# Initialize session state
mkdir -p "$STATE_DIR"
date +%s > "$STATE_DIR/session-start"
echo "0" > "$STATE_DIR/agent-count"
echo "0" > "$STATE_DIR/edit-count"

# Load session memory.
# Cap it: this is injected into EVERY session and then re-read on every turn, so a
# large MEMORY.md is paid for continuously. Show the top (current focus) and the
# tail (most recent entries); the full file stays one Read away.
MEMORY_MAX_BYTES="${DROMFLOW_MEMORY_MAX_BYTES:-4000}"
if [ -s "$MEMORY" ]; then
  echo "[Session Memory Loaded]"
  echo "---"
  msize=$(wc -c < "$MEMORY" | tr -d ' ')
  if [ "$msize" -gt "$MEMORY_MAX_BYTES" ]; then
    # Bound by BYTES, not lines: a memory file can be 70 long lines, where a
    # head/tail line count trims nothing at all.
    _head=$(( MEMORY_MAX_BYTES * 2 / 5 ))
    _tail=$(( MEMORY_MAX_BYTES - _head ))
    head -c "$_head" "$MEMORY"
    echo ""
    echo "... [truncated: ${msize} bytes total, showing first ${_head}B + last ${_tail}B. Full file: context/MEMORY.md] ..."
    echo ""
    tail -c "$_tail" "$MEMORY"
  else
    cat "$MEMORY"
  fi
  echo "---"
else
  echo "[No session memory found. Create context/MEMORY.md to persist context across sessions.]"
fi

# Check for in-progress plans
if [ -d "$PLANS_DIR" ]; then
  in_progress=""
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null; then
      title=$(grep "^title:" "$plan" 2>/dev/null | sed 's/^title: *//')
      chapter=$(grep "^current_chapter:" "$plan" 2>/dev/null | sed 's/^current_chapter: *//')
      basename=$(basename "$plan")
      in_progress="${in_progress}\n  - ${basename} — \"${title}\" (Chapter ${chapter:-?})"
    fi
  done
  if [ -n "$in_progress" ]; then
    echo ""
    echo "[In-Progress Plans Found]"
    echo -e "The following plans were stopped midway and can be resumed:${in_progress}"
    echo "Read the plan file to review progress and resume from the current chapter."
  fi
fi

# --- JavaDucker: auto-start and health check ---
# Sourcing with 2>/dev/null hides a missing file but NOT the "command not found" that follows,
# so a host without the guard script printed an error on every session start. An optional
# companion that is absent must be silent, so define the guard as "no" when it is missing.
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
command -v javaducker_available >/dev/null 2>&1 || javaducker_available() { return 1; }
if javaducker_available; then
  if javaducker_healthy; then
    if javaducker_is_shared; then
      echo "[JavaDucker: connected to shared instance (port ${JAVADUCKER_HTTP_PORT:-8080}, from ${JAVADUCKER_SHARED})]"
    else
      echo "[JavaDucker: connected (port ${JAVADUCKER_HTTP_PORT:-8080})]"
    fi
    # The 48-tool catalog lives in a doc, surfaced only when JavaDucker is really
    # present — projects without it should not carry it in every session.
    [ -f "$DIR/.claude/docs/javaducker.md" ] && \
      echo "[JavaDucker tool catalog: .claude/docs/javaducker.md]"
  else
    if javaducker_is_shared; then
      echo "[JavaDucker: shared instance not running (from ${JAVADUCKER_SHARED}) — start it from the owning project]"
    else
      echo "[JavaDucker: starting server...]"
      if javaducker_start; then
        echo "[JavaDucker: connected (port ${JAVADUCKER_HTTP_PORT:-8080})]"
      else
        echo "[JavaDucker: server starting in background — will be available shortly]"
      fi
    fi
  fi
fi
