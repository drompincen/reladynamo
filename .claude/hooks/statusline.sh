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
