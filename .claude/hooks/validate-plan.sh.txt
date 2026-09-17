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
