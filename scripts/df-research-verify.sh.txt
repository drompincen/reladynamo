#!/bin/bash
# drom-flow — df-research exit gates. Sourced by df-research.sh.

DG=(); DFAIL=0
dg() {
  DG+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$3")}")
  [[ "$2" == PASS ]] || DFAIL=1
  echo "[df-research] gate $1: $2 — $3" >&2
}

dfr_verify() {
  local as_json="${1:-false}"
  DG=(); DFAIL=0
  local ST="$REPO_ROOT/.claude/.state"; mkdir -p "$ST"
  local W="${DF_VERIFY_WORKDIR:-$(ls -dt "$RESEARCH_DIR"/*/ 2>/dev/null | head -1)}"
  W="${W%/}"

  # 1 templates — present and self-contained
  local n; n=$(ls "$TPL"/*.md 2>/dev/null | wc -l | tr -d ' ')
  local dep; dep="$(grep -rlE 'Skill\(|WebSearch|WebFetch|hyperresearch|\.claude/agents' "$TPL" 2>/dev/null | tr '\n' ' ')"
  if [[ "$n" -ge 7 && -z "$dep" ]]; then dg templates PASS "$n templates, no external/Skill-tool deps"
  else dg templates FAIL "count=$n deps=[${dep:-none}]"; fi

  # 2 pipeline — a report exists with citations and a sources section
  if [[ -n "$W" && -f "$W/report.md" ]]; then
    local cites secs
    cites=$(grep -o '\[S[0-9]\+\]' "$W/report.md" | wc -l | tr -d ' ')
    secs=$(grep -ci '^##\s*Sources' "$W/report.md" || echo 0)
    if (( cites > 0 && secs > 0 )); then dg pipeline PASS "report at $W with $cites citations and a Sources section"
    else dg pipeline FAIL "report present but citations=$cites sources_section=$secs"; fi
  else dg pipeline FAIL "no report produced (workdir=${W:-none})"; fi

  # 3 quality — the machine audit
  if [[ -n "$W" ]] && bash "$REPO_ROOT/scripts/df-research-audit.sh" "$W" >/dev/null 2>&1; then
    dg quality PASS "$(python3 -c "
import json;d=json.load(open('$REPO_ROOT/reports/df-research-audit.json'))
print('; '.join(c['check']+'='+c['status'] for c in d['checks']))" 2>/dev/null)"
  else
    dg quality FAIL "$(python3 -c "
import json
try:
  d=json.load(open('$REPO_ROOT/reports/df-research-audit.json'))
  print('; '.join(c['check']+':'+c['detail'][:60] for c in d['checks'] if c['status']=='FAIL'))
except Exception: print('audit did not run')" 2>/dev/null)"
  fi

  # 4 adversarial — critics ran concurrently AND changed the report
  local nobj=0 changed=no
  [[ -n "$W" ]] && nobj=$(ls "$W"/objections-*.md 2>/dev/null | wc -l | tr -d ' ')
  if [[ -f "$W/report.pre-patch.md" && -f "$W/report.md" ]]; then
    cmp -s "$W/report.pre-patch.md" "$W/report.md" || changed=yes
  fi
  local conc; conc="$(cat "$ST/dfr_concurrent" 2>/dev/null || echo 0)"
  if (( nobj >= 2 )) && [[ "$changed" == yes ]]; then
    dg adversarial PASS "$nobj critics ran (max concurrent observed: $conc); objections changed the report"
  else dg adversarial FAIL "critics=$nobj report_changed=$changed concurrent=$conc"; fi

  # 5 cheap — Claude cost of the run
  local turns out
  turns="$(python3 -c "
import json;print(json.load(open('$ST/../.token-audit/dfrun.usage.json')).get('turns',999))" 2>/dev/null || echo 999)"
  out="$(python3 -c "
import json;print(json.load(open('$ST/../.token-audit/dfrun.usage.json')).get('tool_result_bytes',99999))" 2>/dev/null || echo 99999)"
  if (( turns <= 6 && out <= 8192 )); then dg cheap PASS "run cost $turns Claude turns, ${out}B context"
  else dg cheap FAIL "turns=$turns (<=6) context=${out}B (<=8192)"; fi

  # 6 host — docs, template mirror, merge list
  local h=true
  [[ -f "$REPO_ROOT/docs/df-research.md" ]] || h=false
  [[ -f "$REPO_ROOT/template/.claude/skills/df-research/df-research.md" ]] || h=false
  [[ -f "$REPO_ROOT/template/scripts/df-research.sh" ]] || h=false
  grep -q '"## Deep Research"' "$REPO_ROOT/init.sh" || h=false
  $h && dg host PASS "docs + template skill/scripts + CLAUDE.md merge entry present" \
     || dg host FAIL "missing docs, template mirror, or '## Deep Research' merge entry"

  # 7 dotfiles — docs ship to .claude/docs/ and are gitignored
  local d=true
  [[ -d "$REPO_ROOT/template/.claude/docs" ]] || d=false
  [[ -d "$REPO_ROOT/template/docs" ]] && d=false          # old location must be gone
  grep -q '.claude/docs/' "$REPO_ROOT/init.sh" || d=false
  $d && dg dotfiles PASS "docs ship at template/.claude/docs and init.sh gitignores .claude/docs/" \
     || dg dotfiles FAIL "template/.claude/docs missing, template/docs still present, or gitignore entry absent"

  # 8 catsandbears — verified there
  local cb; cb="$(cat "$ST/dfr_catsandbears" 2>/dev/null || echo no)"
  [[ "$cb" == pass ]] && dg catsandbears PASS "$(cat "$ST/dfr_catsandbears_detail" 2>/dev/null)" \
                      || dg catsandbears FAIL "$(cat "$ST/dfr_catsandbears_detail" 2>/dev/null || echo 'not run there yet')"

  # 9 ship
  local sh; sh="$(cat "$ST/dfr_ship" 2>/dev/null || echo no)"
  [[ "$sh" == pass ]] && dg ship PASS "$(cat "$ST/dfr_ship_detail" 2>/dev/null)" \
                      || dg ship FAIL "$(cat "$ST/dfr_ship_detail" 2>/dev/null || echo 'not shipped')"

  local IFS=,
  cat > "$REPORT_DIR/df-research.json" <<EOF
{"ok":$([[ $DFAIL -eq 0 ]] && echo true || echo false),"ts":"$(date -Iseconds)","gates":[${DG[*]}]}
EOF
  [[ "$as_json" == true ]] && cat "$REPORT_DIR/df-research.json"
  local p; p=$(grep -o '"status":"PASS"' "$REPORT_DIR/df-research.json" | wc -l)
  echo "[df-research] gates: $p/${#DG[@]} PASS" >&2
  return $DFAIL
}
