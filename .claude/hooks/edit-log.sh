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
