#!/bin/bash
# drom-flow — install a third-party skill into a HOST PROJECT, reproducibly.
#
#   add-skill.sh <owner/repo@skill> [target-dir]   # from GitHub, nested paths included
#   add-skill.sh /path/to/skill-dir  [target-dir]  # from a local directory
#   add-skill.sh --list              [target-dir]  # what this project has, and where it came from
#   add-skill.sh --restore           [target-dir]  # reinstall everything in the manifest
#
# Why this exists, twice over:
#
#   1. A skill installed into ~/.claude/skills is on ONE machine. It does not travel with the
#      project, it is not in code review, and a teammate cloning the repo does not get it. Skills
#      a project depends on belong in the project.
#   2. `npx skills add` cannot install nested skills. aws/agent-toolkit-for-aws keeps its real
#      DynamoDB skill at skills/specialized-skills/database-skills/amazon-dynamodb, the CLI's
#      discovery only sees the top level, and the install fails with "No matching skills found"
#      for a skill the registry itself lists. This searches the whole tree.
#
# LICENCES ARE ENFORCED. drom-flow is MIT. A skill carries its upstream licence with it — this
# copies the upstream LICENSE and NOTICE into the installed skill (Apache-2.0 requires both) and
# records the SPDX id. Copyleft sources are refused by default, because pulling one into an MIT
# project is a licensing decision nobody should make by accident.
#
# Every install records its source and the exact commit in
# `.claude/.state/drom-flow-extra-skills.json`, so `--restore` rebuilds the set on a fresh clone
# and `init.sh --update` does the same. Host edits to an installed skill are preserved by the
# same rule the shipped skills use: a modified file is kept and the new one lands alongside.

set -uo pipefail

SRC=""; TARGET="."; MODE="add"
case "${1:-}" in
  --list)    MODE="list"; TARGET="${2:-.}" ;;
  --restore) MODE="restore"; TARGET="${2:-.}" ;;
  -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
  "")        echo "usage: add-skill.sh <owner/repo@skill | path> [target-dir]" >&2; exit 2 ;;
  *)         SRC="$1"; TARGET="${2:-.}" ;;
esac

TARGET="$(cd "$TARGET" 2>/dev/null && pwd)" || { echo "add-skill: no such target: $TARGET" >&2; exit 2; }
SKILLS_DIR="$TARGET/.claude/skills"
MANIFEST="$TARGET/.claude/.state/drom-flow-extra-skills.json"
log() { echo "[add-skill] $*" >&2; }

# Permissive licences that can sit inside an MIT-licensed project. Included files keep THEIR
# licence — they are not relicensed — so the upstream text ships alongside them and the project
# stays MIT overall with a clearly marked third-party directory.
# Policy resolution: environment first, then the project's own setting, then a permissive
# default. A project with a strict requirement sets it ONCE in .claude/.state/drom-flow.conf:
#
#     SKILL_LICENSES=MIT
#
# and every later add-skill in that project obeys it without anyone having to remember a flag.
if [ -z "${DROMFLOW_SKILL_LICENSES:-}" ] && [ -f "$TARGET/.claude/.state/drom-flow.conf" ]; then
  DROMFLOW_SKILL_LICENSES="$(sed -n 's/^SKILL_LICENSES=//p' "$TARGET/.claude/.state/drom-flow.conf" | tail -1)"
  DROMFLOW_SKILL_LICENSES="${DROMFLOW_SKILL_LICENSES%\"}"; DROMFLOW_SKILL_LICENSES="${DROMFLOW_SKILL_LICENSES#\"}"
fi
: "${DROMFLOW_SKILL_LICENSES:=MIT,Apache-2.0,BSD-2-Clause,BSD-3-Clause,ISC,0BSD,Unlicense,CC0-1.0}"

license_ok() {
  local want="$1"
  [ -z "$want" ] && return 1
  case ",${DROMFLOW_SKILL_LICENSES}," in *",$want,"*) return 0 ;; esac
  return 1
}

detect_license() {
  local d="$1" f head
  for f in LICENSE LICENSE.txt LICENSE.md COPYING; do
    [ -f "$d/$f" ] || continue
    head="$(head -25 "$d/$f")"
    case "$head" in
      *"Apache License"*) echo "Apache-2.0"; return 0 ;;
      *"GNU AFFERO"*) echo "AGPL-3.0"; return 0 ;;
      *"GNU LESSER"*) echo "LGPL-3.0"; return 0 ;;
      *"GNU GENERAL PUBLIC"*) echo "GPL-3.0"; return 0 ;;
      *"Mozilla Public License"*) echo "MPL-2.0"; return 0 ;;
      *"ISC License"*) echo "ISC"; return 0 ;;
      *"BSD 3-Clause"*) echo "BSD-3-Clause"; return 0 ;;
      *"MIT License"*|*"Permission is hereby granted, free of charge"*) echo "MIT"; return 0 ;;
    esac
  done
  echo ""
}

# Apache-2.0 section 4 requires the licence and any NOTICE to travel with the work. Even under a
# permissive licence this is the difference between attribution and quiet appropriation.
copy_license() {
  local src="$1" dest="$2" got="" f
  for f in LICENSE LICENSE.txt LICENSE.md COPYING NOTICE NOTICE.txt; do
    [ -f "$src/$f" ] || continue
    cp "$src/$f" "$dest/$f"
    got="$got $f"
  done
  [ -n "$got" ] && log "carried upstream licence text:$got"
  return 0
}

record() { # name source commit
  mkdir -p "$(dirname "$MANIFEST")"
  python3 - "$MANIFEST" "$1" "$2" "$3" "${4:-}" <<'PY'
import json, sys, datetime
path, name, source, commit = sys.argv[1:5]
try: d = json.load(open(path))
except Exception: d = {}
skills = d.get("skills", {})
skills[name] = {"source": source, "commit": commit, "license": (sys.argv[5] if len(sys.argv) > 5 else "") or "unknown",
                "installed": datetime.datetime.now().isoformat(timespec="seconds")}
json.dump({"skills": skills}, open(path, "w"), indent=2)
PY
}

# The same merge rule the shipped skills use: never silently replace a file the host edited.
install_dir() { # srcdir name
  local src="$1" name="$2" dest="$SKILLS_DIR/$2" preserved=0
  mkdir -p "$dest"
  while IFS= read -r -d '' f; do
    # NB: two statements. `local a=... b="$a"` does not see `a` — the names are declared before
    # the values are assigned, so b would take the outer/unset value.
    local rel="${f#$src/}"
    local out="$dest/$rel"
    mkdir -p "$(dirname "$out")"
    if [ -f "$out" ] && ! cmp -s "$f" "$out"; then
      cp "$f" "$out.dromflow-new"; preserved=$((preserved + 1))
      echo "  preserve: $name/$rel (you modified it — new version at $rel.dromflow-new)"
      continue
    fi
    cp "$f" "$out"
    # Shell assets are text at rest everywhere in drom-flow; keep third-party skills consistent
    # so `*.sh` stays gitignored and init materialises them like everything else.
    case "$rel" in
      *.sh) mv "$out" "$out.txt"; cp "$out.txt" "$out"; chmod +x "$out" 2>/dev/null ;;
      *.py) chmod +x "$out" 2>/dev/null ;;
    esac
  done < <(find "$src" -type f -not -path '*/.git/*' -print0)
  [ "$preserved" -gt 0 ] && log "kept $preserved locally modified file(s)"
  return 0
}

if [ "$MODE" = "list" ]; then
  if [ -f "$MANIFEST" ]; then python3 -m json.tool "$MANIFEST"; else echo '{"skills":{}}'; fi
  exit 0
fi

materialise() { # every *.sh.txt in an installed skill gets a runnable sibling
  local n=0
  while IFS= read -r -d '' t; do
    local d="${t%.txt}"
    [ -f "$d" ] && [ "$d" -nt "$t" ] && continue
    cp "$t" "$d" 2>/dev/null && chmod +x "$d" 2>/dev/null && n=$((n + 1))
  done < <(find "$SKILLS_DIR" -name '*.sh.txt' -type f -print0 2>/dev/null)
  [ "$n" -gt 0 ] && log "materialised $n shell asset(s) in installed skills"
  return 0
}

if [ "$MODE" = "restore" ]; then
  # A fresh clone has the committed .sh.txt but no runnable .sh — the shell assets are
  # gitignored by design. Materialise first, then fetch anything missing entirely.
  materialise
  [ -f "$MANIFEST" ] || { log "no third-party skills recorded"; exit 0; }
  mapfile -t entries < <(python3 -c "
import json,sys
d=json.load(open('$MANIFEST'))
for n,v in (d.get('skills') or {}).items(): print(n+'\t'+v.get('source',''))" 2>/dev/null)
  rc=0
  for e in "${entries[@]}"; do
    n="${e%%$'\t'*}"; s="${e##*$'\t'}"
    [ -d "$SKILLS_DIR/$n" ] && { log "already present: $n"; continue; }
    [ -n "$s" ] || continue
    log "restoring $n from $s"
    bash "$0" "$s" "$TARGET" || rc=1
  done
  exit $rc
fi

# ---- local directory ----
if [ -d "$SRC" ]; then
  name="$(basename "$SRC")"
  [ -f "$SRC/SKILL.md" ] || { log "not a skill directory (no SKILL.md): $SRC"; exit 2; }
  spdx="$(detect_license "$SRC")"
  install_dir "$SRC" "$name"
  copy_license "$SRC" "$SKILLS_DIR/$name"
  record "$name" "$SRC" "local" "$spdx"
  log "installed $name into $SKILLS_DIR/$name"
  exit 0
fi

# ---- owner/repo@skill ----
case "$SRC" in
  */*@*) : ;;
  *) log "unrecognised source: $SRC (want owner/repo@skill or a local path)"; exit 2 ;;
esac
repo="${SRC%@*}"; skill="${SRC##*@}"
command -v git >/dev/null 2>&1 || { log "git is required"; exit 3; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
log "cloning $repo"
git clone --depth 1 -q "https://github.com/$repo.git" "$TMP/repo" 2>/dev/null || {
  log "cannot clone https://github.com/$repo.git"; exit 3; }
commit="$(git -C "$TMP/repo" rev-parse --short HEAD 2>/dev/null || echo unknown)"

# Search the WHOLE tree — this is exactly what `npx skills add` fails to do.
found=""
while IFS= read -r f; do
  d="$(dirname "$f")"
  if [ "$(basename "$d")" = "$skill" ]; then found="$d"; break; fi
  if grep -qE "^name:[[:space:]]*$skill[[:space:]]*$" "$f" 2>/dev/null; then found="$d"; break; fi
done < <(find "$TMP/repo" -name SKILL.md -type f 2>/dev/null)

if [ -z "$found" ]; then
  log "no skill '$skill' in $repo. Skills found:"
  find "$TMP/repo" -name SKILL.md -type f 2>/dev/null | sed "s|$TMP/repo/||; s|/SKILL.md||" | head -40 >&2
  exit 1
fi

spdx="$(detect_license "$TMP/repo")"
if [ -z "$spdx" ]; then
  log "REFUSED: cannot determine the licence of $repo."
  log "  A skill with no identifiable licence must not be added to an MIT project."
  exit 4
fi
if ! license_ok "$spdx"; then
  log "REFUSED: $repo is $spdx, which is not in the allowed set."
  log "  Allowed: $DROMFLOW_SKILL_LICENSES"
  log "  This project is MIT. Adding $spdx code is a licensing decision, not a packaging one."
  exit 4
fi
log "licence: $spdx (allowed)"

log "found at ${found#$TMP/repo/} @ $commit"
install_dir "$found" "$skill"
copy_license "$TMP/repo" "$SKILLS_DIR/$skill"
record "$skill" "$SRC" "$commit" "$spdx"
log "installed $skill into $SKILLS_DIR/$skill"
if [ "$spdx" = "MIT" ]; then
  log "MIT, same as this project — commit .claude/skills/$skill and the manifest."
else
  log "These files stay $spdx and are NOT relicensed. Your project remains MIT with a clearly"
  log "marked third-party directory, and the upstream LICENSE/NOTICE sits beside them."
  log "To keep NO $spdx files in your repo at all: gitignore .claude/skills/$skill/ and commit"
  log "only the manifest — 'add-skill.sh --restore' refetches it at the recorded commit."
fi
