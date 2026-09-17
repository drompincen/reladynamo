#!/bin/bash
# drom-flow — generate a grok task.md from a template, so dispatching N units costs
# Claude one command instead of N hand-written prompts.
#
#   mk-task.sh <template> <out-file> KEY=VALUE ...
#
# Templates live in scripts/task-templates/*.md and use {{KEY}} placeholders.
# On an unmetered grok account, prompts are free — templates are verbose on purpose.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TPL_DIR="$REPO_ROOT/scripts/task-templates"

[[ $# -ge 2 ]] || { echo "usage: mk-task.sh <template> <out-file> KEY=VAL ..." >&2; exit 2; }
tpl="$1"; out="$2"; shift 2
src="$TPL_DIR/$tpl.md"
[[ -f "$src" ]] || { echo "no such template: $tpl (have: $(ls "$TPL_DIR" 2>/dev/null | sed 's/\.md//' | tr '\n' ' '))" >&2; exit 2; }

mkdir -p "$(dirname "$out")"
cp "$src" "$out"
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  python3 - "$out" "$k" "$v" <<'PY'
import sys
p,k,v=sys.argv[1],sys.argv[2],sys.argv[3]
s=open(p,encoding='utf-8').read().replace('{{'+k+'}}',v)
open(p,'w',encoding='utf-8').write(s)
PY
done
# Leftover placeholders mean a caller forgot a key — fail loudly rather than
# shipping a prompt with literal {{FOO}} in it.
if grep -q '{{[A-Z_]*}}' "$out"; then
  echo "ERROR: unfilled placeholders in $out: $(grep -o '{{[A-Z_]*}}' "$out" | sort -u | tr '\n' ' ')" >&2
  exit 2
fi
echo "$out"
