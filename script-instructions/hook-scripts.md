# Claude Code hook scripts

Session lifecycle hooks registered in `.claude/settings.json`.

Each `## <path>` section below is one script. `java scripts/GenerateScripts.java` writes it to that
path (relative to the repository root) with LF line endings; the generated file is gitignored.

## .claude/hooks/memory-sync.sh

````bash
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
````

## .claude/hooks/session-end.sh

````bash
#!/bin/bash
# drom-flow session end — remind to persist progress and update plans

DIR="${CLAUDE_PROJECT_DIR:-.}"
PLANS_DIR="$DIR/drom-plans"

echo "[Session ending. Update context/MEMORY.md with progress, findings, and next steps.]"

# Remind about in-progress plans
if [ -d "$PLANS_DIR" ]; then
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null; then
      title=$(grep "^title:" "$plan" 2>/dev/null | sed 's/^title: *//')
      echo "[Plan in progress: \"${title}\" — update chapter status and step checkboxes before ending.]"
    fi
  done
fi

# JavaDucker session-end hygiene
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available && javaducker_healthy; then
  edits=0
  [ -f "$DIR/.claude/edit-log.jsonl" ] && edits=$(wc -l < "$DIR/.claude/edit-log.jsonl" | tr -d ' ')
  if [ "$edits" -gt 10 ]; then
    echo "[JavaDucker: $edits files edited — run javaducker_index_health to check freshness.]"
  fi
  # Check for un-enriched artifacts
  queue=$(curl -sf "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/enrich-queue?limit=1" 2>/dev/null)
  if [ -n "$queue" ] && echo "$queue" | grep -q '"artifact_id"'; then
    echo "[JavaDucker: un-enriched artifacts detected — run workflows/javaducker-hygiene.md Phase 2 to classify, tag, and extract points.]"
  fi
fi
````

## .claude/hooks/edit-log.sh

````bash
#!/bin/bash
# drom-flow edit logger — appends edit events to JSONL

DIR="${CLAUDE_PROJECT_DIR:-.}"
LOG="$DIR/.claude/edit-log.jsonl"

# Extract file_path from tool input (passed via stdin)
file_path="unknown"
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi

timestamp=$(date +%s)
echo "{\"type\":\"edit\",\"file\":\"$file_path\",\"timestamp\":$timestamp}" >> "$LOG"
````

## .claude/hooks/validate-plan.sh

````bash
#!/bin/bash
# drom-flow — validate plan files written to drom-plans/

DIR="${CLAUDE_PROJECT_DIR:-.}"
PLANS_DIR="$DIR/drom-plans"

# Extract file_path from tool input
file_path=""
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi

# Only validate files in drom-plans/
case "$file_path" in
  */drom-plans/*.md|drom-plans/*.md) ;;
  *) exit 0 ;;
esac

[ ! -f "$file_path" ] && exit 0

errors=""

# Check frontmatter exists
if ! head -1 "$file_path" | grep -q "^---"; then
  errors="${errors}\n  - Missing YAML frontmatter (must start with ---)"
fi

# Check required frontmatter fields
for field in title status created updated current_chapter; do
  if ! grep -q "^${field}:" "$file_path"; then
    errors="${errors}\n  - Missing frontmatter field: ${field}"
  fi
done

# Check status value
status=$(grep "^status:" "$file_path" | head -1 | sed 's/^status: *//')
case "$status" in
  in-progress|completed|pending|abandoned) ;;
  *) errors="${errors}\n  - Invalid status: '${status}' (must be: in-progress, completed, pending, or abandoned)" ;;
esac

# Check for at least one chapter
chapter_count=$(grep -c "^## Chapter " "$file_path" 2>/dev/null | tr -d '[:space:]')
chapter_count=${chapter_count:-0}
if [ "$chapter_count" -eq 0 ]; then
  errors="${errors}\n  - No chapters found (need at least one '## Chapter N: Title')"
fi

# Check chapters have Status lines
chapters_without_status=0
while IFS= read -r line; do
  chapter_num=$(echo "$line" | grep -o "Chapter [0-9]*" | grep -o "[0-9]*")
  if ! grep -A2 "^## Chapter ${chapter_num}:" "$file_path" | grep -q '^\*\*Status:\*\*'; then
    chapters_without_status=$((chapters_without_status + 1))
    errors="${errors}\n  - Chapter ${chapter_num} missing **Status:** line"
  fi
done < <(grep "^## Chapter " "$file_path")

# Check chapters have at least one step (checkbox)
while IFS= read -r line; do
  chapter_num=$(echo "$line" | grep -o "Chapter [0-9]*" | grep -o "[0-9]*")
  # Get content between this chapter and the next (or end of file)
  next_section=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^## /{print NR; exit}" "$file_path")
  if [ -n "$next_section" ]; then
    step_count=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^## /{exit} found && /^- \[/" "$file_path" | wc -l)
  else
    step_count=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^- \[/" "$file_path" | wc -l)
  fi
  if [ "$step_count" -eq 0 ]; then
    errors="${errors}\n  - Chapter ${chapter_num} has no steps (need at least one '- [ ] ...')"
  fi
done < <(grep "^## Chapter " "$file_path")

# Check current_chapter points to a valid chapter
current=$(grep "^current_chapter:" "$file_path" | head -1 | sed 's/^current_chapter: *//')
if [ -n "$current" ] && [ "$chapter_count" -gt 0 ]; then
  if ! grep -q "^## Chapter ${current}:" "$file_path"; then
    errors="${errors}\n  - current_chapter: ${current} does not match any chapter heading"
  fi
fi

if [ -n "$errors" ]; then
  echo "PLAN VALIDATION FAILED: $(basename "$file_path")"
  echo -e "Issues:${errors}"
  echo ""
  echo "Expected format: see /planner skill or drom-plans/ docs in CLAUDE.md"
  exit 1
fi
````

## .claude/hooks/track-agents.sh

````bash
#!/bin/bash
# drom-flow — track background agent count

STATE_DIR="${CLAUDE_PROJECT_DIR:-.}/.claude/.state"
mkdir -p "$STATE_DIR"

count=0
[ -f "$STATE_DIR/agent-count" ] && count=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')
echo $((count + 1)) > "$STATE_DIR/agent-count"
````

## .claude/hooks/statusline.sh

````bash
#!/bin/bash
# drom-flow statusline — git-aware status for Claude Code

DIR="${CLAUDE_PROJECT_DIR:-.}"
STATE_DIR="$DIR/.claude/.state"

# --- Version ---
DROMFLOW_VERSION=""
for vfile in "$DIR/VERSION" "$(dirname "${BASH_SOURCE[0]}")/../../../VERSION"; do
  if [ -f "$vfile" ]; then
    DROMFLOW_VERSION=$(tr -d '[:space:]' < "$vfile")
    break
  fi
done
DROMFLOW_VERSION="${DROMFLOW_VERSION:-dev}"

# --- Project root (bright cyan to pop) ---
PROJECT_ROOT="\033[1;36m$(basename "$(cd "$DIR" && pwd)")\033[0m"

# --- Session elapsed time ---
elapsed=""
if [ -f "$STATE_DIR/session-start" ]; then
  start=$(cat "$STATE_DIR/session-start")
  now=$(date +%s)
  diff=$((now - start))
  mins=$((diff / 60))
  secs=$((diff % 60))
  if [ $mins -ge 60 ]; then
    hrs=$((mins / 60))
    mins=$((mins % 60))
    elapsed="${hrs}h${mins}m"
  else
    elapsed="${mins}m${secs}s"
  fi
fi

# --- Plan progress (computed early so both git and no-git paths can use it) ---
plan_info=""
PLANS_DIR="$DIR/drom-plans"
if [ -d "$PLANS_DIR" ]; then
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null || grep -q '^\*\*Status:\*\* in-progress' "$plan" 2>/dev/null; then
      cur=$(grep "^current_chapter:" "$plan" 2>/dev/null | sed 's/^current_chapter: *//')
      total=$(grep -c "^## Chapter " "$plan" 2>/dev/null)
done_count=$(grep -c '^\*\*Status:\*\* completed' "$plan" 2>/dev/null)
      plan_info="plan:ch${cur:-?}/${total:-?}(${done_count:-0}✓)"
      break
    fi
  done
fi

# --- Git info ---
branch=$(git branch --show-current 2>/dev/null || echo "no-git")
if [ "$branch" = "no-git" ]; then
  nogit_status="drom-flow v$DROMFLOW_VERSION • $PROJECT_ROOT • [no-git] • ${elapsed:-0m0s}"
  [ -n "$plan_info" ] && nogit_status="$nogit_status • $plan_info"
  echo -e "$nogit_status"
  exit 0
fi

staged=$(git diff --cached --numstat 2>/dev/null | wc -l | tr -d ' ')
unstaged=$(git diff --numstat 2>/dev/null | wc -l | tr -d ' ')
untracked=$(git ls-files --others --exclude-standard 2>/dev/null | wc -l | tr -d ' ')

ahead=0
behind=0
upstream=$(git rev-list --left-right --count HEAD...@{upstream} 2>/dev/null)
if [ $? -eq 0 ]; then
  ahead=$(echo "$upstream" | awk '{print $1}')
  behind=$(echo "$upstream" | awk '{print $2}')
fi

# Compact git: +staged/-unstaged/?untracked
git_info="$branch +${staged}/-${unstaged}/?${untracked}"
[ "$ahead" -gt 0 ] || [ "$behind" -gt 0 ] && git_info="$git_info ↑${ahead}↓${behind}"

# --- Edit count (from edit-log) ---
edits=0
[ -f "$DIR/.claude/edit-log.jsonl" ] && edits=$(wc -l < "$DIR/.claude/edit-log.jsonl" | tr -d ' ')

# --- Background agents: Claude, grok and codex counted separately ---
# Claude agents come from the track-agents hook; the sub-agent runners are counted live from
# their fleet control planes, so the delegation split is visible while work is in flight.
# A runner that is not installed simply has no control plane and contributes nothing — no
# probing of binaries here, and nothing shown for a runner that is not in use.
agents=0
[ -f "$STATE_DIR/agent-count" ] && agents=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')
running_in() {
  [ -d "$1" ] || { echo 0; return; }
  grep -l '"state":"RUNNING"' "$1"/*/agents/*/status.json 2>/dev/null | wc -l | tr -d ' '
}
grok_agents=$(running_in "$DIR/.claude/.grok-fleet")
gpt_agents=$(running_in "$DIR/.claude/.fleet")

# --- Usage-limit watcher ---
limit_flag=""
if [ -f "$DIR/.claude/.state/limit-armed.json" ]; then
  limit_flag=" • ⏳armed"
elif [ -f "$DIR/.claude/.state/limit-ping-due" ]; then
  limit_flag=" • ⏳ping-due"
fi

# --- Memory status ---
mem="off"
[ -s "$DIR/context/MEMORY.md" ] && mem="on"

# --- JavaDucker status ---
jd_icon=""
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available; then
  if javaducker_healthy; then
    javaducker_is_shared && jd_icon="JD(shared)" || jd_icon="JD"
  else
    jd_icon="JD(off)"
  fi
fi

# G = grok, X = codex/GPT. Both are shown only when a run exists, so a project using neither
# sees the same statusline it always had.
runners="C:$agents"
[ "${grok_agents:-0}" -gt 0 ] && runners="$runners G:$grok_agents"
[ "${gpt_agents:-0}" -gt 0 ] && runners="$runners X:$gpt_agents"
status="drom-flow v$DROMFLOW_VERSION • $PROJECT_ROOT • $git_info • ${elapsed:-0m0s} • edits:$edits • $runners • mem:$mem"
[ -n "$jd_icon" ] && status="$status • $jd_icon"
[ -n "$limit_flag" ] && status="$status$limit_flag"
[ -n "$plan_info" ] && status="$status • $plan_info"
echo -e "$status"
````
