#!/bin/bash
# drom-flow — encapsulated audit fan-out.
#
#   bench-audit.sh <run-id> <file> [<file> ...]
#
# Exists so dispatching a fan-out costs Claude a one-line command instead of a
# hand-written block of bash + python. Claude authoring is a top-two token cost;
# this moves it into a script that is written once.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${1:?usage: bench-audit.sh <run-id> <file>...}"; shift
[[ $# -ge 1 ]] || { echo "no target files" >&2; exit 2; }

T="$REPO_ROOT/.claude/.grok-fleet/_tasks/$RUN"; mkdir -p "$T"
rm -rf "$REPO_ROOT/.claude/.grok-fleet/$RUN"

CHECKS='1) YAML frontmatter present and valid with name, description, user-invocable. 2) Ordered list numbering is strictly sequential with no duplicated or skipped numbers — report the exact duplicated number, its section heading, and the line range.'

agents=()
for f in "$@"; do
  id="$(basename "$(dirname "$f")")"; [[ "$id" == "." ]] && id="$(basename "$f" .md)"
  bash "$REPO_ROOT/scripts/mk-task.sh" audit "$T/$id.md" \
    TARGET="$f" CHECKS="$CHECKS" OUTFILE="findings.md" TITLE="$id" >/dev/null || exit 2
  bash "$REPO_ROOT/scripts/token-audit.sh" ledger "audit-$id" grok
  agents+=("$id:$T/$id.md")
done

python3 - "$T/m.json" "$RUN" "${agents[@]}" <<'PY'
import json,sys
out,run=sys.argv[1],sys.argv[2]
ag=[{'id':a.split(':',1)[0],'task_file':a.split(':',1)[1]} for a in sys.argv[3:]]
json.dump({'run_id':run,'budget_usd':0,'max_parallel':len(ag),'agents':ag},open(out,'w'),indent=2)
PY

bash "$REPO_ROOT/scripts/grok-fleet.sh" spawn --manifest "$T/m.json" >/dev/null 2>&1
bash "$REPO_ROOT/scripts/grok-fleet.sh" collect --run-id "$RUN" --brief
