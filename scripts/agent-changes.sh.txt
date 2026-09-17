#!/bin/bash
# Which files did an agent ACTUALLY change?
#
# Every agent mirrors the whole source tree it was given, so "differs from the repo" is not the same
# as "the agent edited it" — the repo has moved on since the agent branched. Comparing against the
# repo alone once nearly reverted a landed fix. Compare against the agent's own BASELINE instead.
#
#   agent-changes.sh <agent-output-dir> [baseline-dir]
#
# REAL  = differs from baseline -> the agent edited it
# STALE = identical to baseline -> untouched; taking it would revert whatever landed since
# NEW   = absent from baseline  -> the agent created it
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
R="$1"
BASE="${2:-$ROOT/.claude/.grok-fleet/fix/agents/r06/output}"
[ -d "$R" ] || { echo "no such agent output: $R" >&2; exit 2; }

cd "$R" || exit 2
find reladynamo-*/src -name '*.java' 2>/dev/null | sort | while IFS= read -r f; do
  if [ ! -f "$BASE/$f" ]; then
    [ -f "$ROOT/$f" ] && echo "NEW-ISH $f" || echo "NEW     $f"
  elif diff -q --strip-trailing-cr "$BASE/$f" "$R/$f" >/dev/null 2>&1; then
    diff -q --strip-trailing-cr "$ROOT/$f" "$R/$f" >/dev/null 2>&1 || echo "STALE   $f"
  else
    echo "REAL    $f"
  fi
done
