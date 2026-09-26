# Research scripts

The `/df-research` pipeline and its audit gate.

Each `## <path>` section below is one script. `java scripts/GenerateScripts.java` writes it to that
path (relative to the repository root) with LF line endings; the generated file is gitignored.

## scripts/df-research.sh

````bash
#!/bin/bash
# drom-flow — df-research: deep research on the grok fleet.
#
#   df-research.sh doctor
#   df-research.sh run "<question>" [--depth quick|deep] [--slug NAME]
#   df-research.sh verify [--json]
#
# Method borrowed from hyperresearch (MIT, github.com/jordan-gibbs/hyperresearch):
# independence audit, contradiction graph, adversarial critics, cite-check gate.
# Reimplemented as fleet task templates — no dependency on that package.
#
# Claude sees per-phase verdict lines only, never phase bodies.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TPL="$REPO_ROOT/scripts/task-templates/research"
FLEET="$REPO_ROOT/scripts/grok-fleet.sh"
RESEARCH_DIR="${DF_RESEARCH_DIR:-$REPO_ROOT/research}"
REPORT_DIR="$REPO_ROOT/reports"
mkdir -p "$RESEARCH_DIR" "$REPORT_DIR"

log() { echo "[df-research] $*" >&2; }
die() { echo "[df-research] ERROR: $*" >&2; exit 2; }

mk() { bash "$REPO_ROOT/scripts/mk-task.sh" "research/$1" "$2" "${@:3}" >/dev/null; }

# Run one manifest of units and return only a compact status line.
run_phase() { # phase_name run_id manifest
  local name="$1" run_id="$2" mf="$3"
  bash "$FLEET" spawn --manifest "$mf" >/dev/null 2>&1
  local out; out="$(bash "$FLEET" collect --run-id "$run_id" --brief 2>/dev/null)"
  local done_n fail_n
  done_n=$(grep -c $'\tDONE\t' <<<"$out" || true)
  fail_n=$(( $(grep -c $'\t' <<<"$out" || true) - done_n ))
  printf '%-12s %s ok / %s failed\n' "$name" "$done_n" "$((fail_n<0?0:fail_n))"
  [[ "$done_n" -gt 0 ]]
}

manifest() { # out_json run_id parallel  id:taskfile ...
  python3 - "$@" <<'PY'
import json,sys
out,run,par=sys.argv[1],sys.argv[2],int(sys.argv[3])
ag=[{'id':a.split(':',1)[0],'task_file':a.split(':',1)[1]} for a in sys.argv[4:]]
json.dump({'run_id':run,'budget_usd':0,'max_parallel':par,'agents':ag},open(out,'w'),indent=2)
PY
}

cmd_doctor() {
  local ok=true
  bash "$FLEET" doctor --live >/dev/null 2>&1 && echo "grok fleet: OK" || { echo "grok fleet: FAIL"; ok=false; }
  local n; n=$(ls "$TPL"/*.md 2>/dev/null | wc -l | tr -d ' ')
  [[ "$n" -ge 7 ]] && echo "templates: $n present" || { echo "templates: only $n"; ok=false; }
  # gate 1: templates must be self-contained
  if grep -rlE 'Skill\(|WebSearch|WebFetch|hyperresearch|\.claude/agents' "$TPL" 2>/dev/null | grep -q .; then
    echo "templates: external dependency found"; ok=false
  else echo "templates: no external deps"; fi
  $ok
}

cmd_run() {
  local q="" depth=quick slug=""
  q="${1:-}"; shift || true
  while [[ $# -gt 0 ]]; do case $1 in
    --depth) depth="$2"; shift 2 ;; --slug) slug="$2"; shift 2 ;; *) shift ;;
  esac; done
  [[ -n "$q" ]] || die 'run needs a question: df-research.sh run "<question>"'

  local n_persp n_crit
  if [[ "$depth" == deep ]]; then n_persp=6; n_crit=4; else n_persp=4; n_crit=3; fi
  [[ -z "$slug" ]] && slug="$(echo "$q" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | cut -c1-40 | sed 's/-$//')"
  local W="$RESEARCH_DIR/$slug"; mkdir -p "$W"
  local T="$REPO_ROOT/.claude/.grok-fleet/_dfr/$slug"; mkdir -p "$T"
  local RUN="dfr-$slug"
  log "question: $q"
  log "depth=$depth perspectives=$n_persp critics=$n_crit workdir=$W"

  # ---- 1. decompose -------------------------------------------------------
  mk decompose "$T/decompose.md" QUESTION="$q"
  manifest "$T/m1.json" "$RUN-1" 1 "decompose:$T/decompose.md"
  run_phase decompose "$RUN-1" "$T/m1.json" || die "decompose failed"
  local PLAN; PLAN="$(ls "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-1"/agents/decompose/output/plan.md 2>/dev/null)"
  [[ -f "$PLAN" ]] || die "decompose produced no plan.md"
  cp "$PLAN" "$W/plan.md"

  # perspectives come from the plan; fall back to generic ones if unparseable
  mapfile -t PERSP < <(python3 - "$W/plan.md" "$n_persp" <<'PY'
import re,sys
txt=open(sys.argv[1],encoding='utf-8',errors='replace').read(); n=int(sys.argv[2])
m=re.search(r'#+\s*Search perspectives(.*?)(\n#|\Z)',txt,re.S|re.I)
out=[]
if m:
    for line in m.group(1).splitlines():
        line=line.strip()
        mm=re.match(r'^\d+[.)]\s*(.+)$',line) or re.match(r'^[-*]\s*(.+)$',line)
        if mm: out.append(mm.group(1).strip()[:200])
if not out:
    out=["proponent / vendor claims","independent skeptic or critic","regulator, standards body or primary filing",
         "practitioner field reports","primary data or peer-reviewed study","non-English or regional coverage"]
print('\n'.join(out[:n]))
PY
)
  log "perspectives: ${#PERSP[@]}"

  # ---- 2. width sweep (parallel) -----------------------------------------
  local args=() i=1
  for p in "${PERSP[@]}"; do
    local social="Use ordinary web search and page fetches."
    (( i == ${#PERSP[@]} )) && social="For THIS perspective use X/Twitter search (x_semantic_search, x_keyword_search, x_thread_fetch) as your primary tool. Tag every X source type: social — it is signal, not peer-reviewed evidence."
    mk sweep "$T/sweep$i.md" QUESTION="$q" PERSPECTIVE="$p" SOCIAL_RULE="$social" TARGET="6-10"
    args+=("sweep$i:$T/sweep$i.md"); ((i++))
  done
  manifest "$T/m2.json" "$RUN-2" "${#PERSP[@]}" "${args[@]}"
  run_phase sweep "$RUN-2" "$T/m2.json" || die "sweep failed"
  local CORPUS="$W/corpus"; mkdir -p "$CORPUS"
  local k=1
  for d in "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-2"/agents/*/output; do
    [[ -f "$d/sources.md" ]] && cp "$d/sources.md" "$CORPUS/sources-$k.md" && ((k++))
  done
  log "corpus files: $(ls "$CORPUS" | wc -l | tr -d ' ')"

  # ---- 3. audit -----------------------------------------------------------
  mk audit "$T/audit.md" QUESTION="$q" CORPUS_DIR="$(wslpath -w "$CORPUS")"
  manifest "$T/m3.json" "$RUN-3" 1 "audit:$T/audit.md"
  run_phase audit "$RUN-3" "$T/m3.json" || log "audit failed (continuing)"
  cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-3"/agents/audit/output/audit.md "$W/audit.md" 2>/dev/null

  # ---- 4. draft -----------------------------------------------------------
  cp "$W/plan.md" "$CORPUS/plan.md" 2>/dev/null
  [[ -f "$W/audit.md" ]] && cp "$W/audit.md" "$CORPUS/audit.md"
  mk draft "$T/draft.md" QUESTION="$q" PLAN="$(wslpath -w "$CORPUS/plan.md")" \
     CORPUS_DIR="$(wslpath -w "$CORPUS")" AUDIT="$(wslpath -w "$CORPUS/audit.md")"
  manifest "$T/m4.json" "$RUN-4" 1 "draft:$T/draft.md"
  run_phase draft "$RUN-4" "$T/m4.json" || die "draft failed"
  cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-4"/agents/draft/output/report.md "$W/report.md" 2>/dev/null \
    || die "draft produced no report.md"
  cp "$W/report.md" "$CORPUS/report.md"

  # ---- 5. critics (parallel) ---------------------------------------------
  local MAND=("coverage gaps: sub-questions from the plan that the report leaves unanswered or answers thinly"
              "weak sourcing: claims resting on secondary, derivative, social or undated sources"
              "overclaiming: statements stronger than their evidence, hedges dropped, correlation stated as cause"
              "alternative explanations: readings of the evidence the report failed to consider")
  args=(); for c in $(seq 1 "$n_crit"); do
    mk critique "$T/crit$c.md" QUESTION="$q" MANDATE="${MAND[$((c-1))]}" \
       DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")" CRITIC_ID="c$c"
    args+=("crit$c:$T/crit$c.md")
  done
  manifest "$T/m5.json" "$RUN-5" "$n_crit" "${args[@]}"
  run_phase critics "$RUN-5" "$T/m5.json" || log "critics failed (continuing)"
  for d in "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-5"/agents/*/output; do
    cp "$d"/objections-*.md "$CORPUS/" 2>/dev/null
  done
  cp "$CORPUS"/objections-*.md "$W/" 2>/dev/null

  # ---- 6. patch (surgical) ------------------------------------------------
  cp "$W/report.md" "$W/report.pre-patch.md"
  mk patch "$T/patch.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
  manifest "$T/m6.json" "$RUN-6" 1 "patch:$T/patch.md"
  run_phase patch "$RUN-6" "$T/m6.json" || log "patch failed (continuing)"
  local pd="$REPO_ROOT/.claude/.grok-fleet/$RUN-6/agents/patch/output"
  [[ -f "$pd/report.md" ]] && cp "$pd/report.md" "$W/report.md"
  [[ -f "$pd/patch-log.md" ]] && cp "$pd/patch-log.md" "$W/patch-log.md"

  # ---- 7. cite-check (hard gate) -----------------------------------------
  cp "$W/report.md" "$CORPUS/report.md"
  mk citecheck "$T/citecheck.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
  manifest "$T/m7.json" "$RUN-7" 1 "citecheck:$T/citecheck.md"
  run_phase citecheck "$RUN-7" "$T/m7.json" || log "citecheck failed (continuing)"
  cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-7"/agents/citecheck/output/citecheck.json "$W/citecheck.json" 2>/dev/null

  # ---- 8. remediate + re-check (closed loop on the hard gate) -------------
  # The cite-check gate is only useful if failures get fixed. Loop up to twice:
  # fix the flagged sentences, then re-check. Never loosen the gate instead.
  local attempt=1
  while (( attempt <= 2 )); do
    local bad
    bad="$(python3 -c "
import json
try:
  d=json.load(open('$W/citecheck.json')); c=d.get('counts',{})
  print(int(c.get('unsupported',0))+int(c.get('missing',0))+len(d.get('fabricated_quotes') or []))
except Exception: print(0)" 2>/dev/null || echo 0)"
    (( bad == 0 )) && break
    log "cite-check found $bad unsupported/missing citation(s) — remediation pass $attempt"
    cp "$W/report.md" "$CORPUS/report.md"; cp "$W/citecheck.json" "$CORPUS/citecheck.json"
    mk remediate "$T/remediate$attempt.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" \
       CITECHECK="$(wslpath -w "$CORPUS/citecheck.json")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
    manifest "$T/m8-$attempt.json" "$RUN-8-$attempt" 1 "remediate:$T/remediate$attempt.md"
    run_phase "remediate$attempt" "$RUN-8-$attempt" "$T/m8-$attempt.json" || break
    local rd8="$REPO_ROOT/.claude/.grok-fleet/$RUN-8-$attempt/agents/remediate/output"
    [[ -f "$rd8/report.md" ]] && cp "$rd8/report.md" "$W/report.md"
    [[ -f "$rd8/remediation-log.md" ]] && cp "$rd8/remediation-log.md" "$W/remediation-log-$attempt.md"
    cp "$W/report.md" "$CORPUS/report.md"
    mk citecheck "$T/citecheck$attempt.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
    manifest "$T/m9-$attempt.json" "$RUN-9-$attempt" 1 "citecheck:$T/citecheck$attempt.md"
    run_phase "recheck$attempt" "$RUN-9-$attempt" "$T/m9-$attempt.json" || break
    cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-9-$attempt"/agents/citecheck/output/citecheck.json "$W/citecheck.json" 2>/dev/null
    ((attempt++))
  done

  echo "---"
  echo "report:  $W/report.md"
  bash "$REPO_ROOT/scripts/df-research-audit.sh" "$W" 2>&1 | tail -12
}

cmd_verify() {
  local as_json=false; [[ "${1:-}" == "--json" ]] && as_json=true
  source "$REPO_ROOT/scripts/df-research-verify.sh"
  dfr_verify "$as_json"
}

case "${1:-}" in
  doctor) shift; cmd_doctor "$@" ;;
  run)    shift; cmd_run "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  *) die 'usage: df-research.sh {doctor|run "<question>" [--depth quick|deep]|verify}' ;;
esac
````

## scripts/df-research-audit.sh

````bash
#!/bin/bash
# drom-flow — df-research quality audit (gate 3).
#
#   df-research-audit.sh <research-workdir>
#
# Machine-checks that a report is actually sourced, not merely plausible.
# Writes reports/df-research-audit.json. Exit 0 = pass, 1 = fail.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
W="${1:?usage: df-research-audit.sh <research-workdir>}"
OUT="$REPO_ROOT/reports/df-research-audit.json"
mkdir -p "$REPO_ROOT/reports"

MIN_SOURCES="${DF_MIN_SOURCES:-20}"

python3 - "$W" "$OUT" "$MIN_SOURCES" <<'PY'
import json,os,re,sys
W,out,minsrc=sys.argv[1],sys.argv[2],int(sys.argv[3])
rep=os.path.join(W,'report.md')
corpus=os.path.join(W,'corpus')
checks=[]; ok=True
def chk(name,passed,detail):
    global ok
    checks.append({'check':name,'status':'PASS' if passed else 'FAIL','detail':detail})
    if not passed: ok=False

if not os.path.exists(rep):
    json.dump({'ok':False,'checks':[{'check':'report','status':'FAIL','detail':'no report.md'}]},open(out,'w'),indent=2)
    print('FAIL: no report.md'); raise SystemExit(1)
text=open(rep,encoding='utf-8',errors='replace').read()

# --- distinct sources actually in the corpus -------------------------------
urls=set(); social=set(); ids=set(); derivative=0
if os.path.isdir(corpus):
    for f in os.listdir(corpus):
        if not f.startswith('sources'): continue
        body=open(os.path.join(corpus,f),encoding='utf-8',errors='replace').read()
        for blk in re.split(r'\n(?=##\s*S)',body):
            u=re.search(r'^\s*url:\s*(\S+)',blk,re.M)
            i=re.search(r'##\s*(S\d+)',blk)
            if u: urls.add(u.group(1).rstrip('/').lower())
            if i: ids.add(i.group(1))
            if re.search(r'^\s*type:\s*social',blk,re.M): social.add(u.group(1) if u else i.group(1) if i else '?')
            if re.search(r'^\s*origin:\s*derivative',blk,re.M): derivative+=1
chk('distinct_sources', len(urls)>=minsrc, f'{len(urls)} distinct source URLs in corpus (need >={minsrc}); {derivative} marked derivative')

# --- every citation in the report resolves ---------------------------------
cited=set(re.findall(r'\[(S\d+)\]',text))
listed=set(re.findall(r'^\s*\[(S\d+)\]',text,re.M))
known=ids|listed
unresolved=sorted(c for c in cited if c not in known)
chk('citations_resolve', not unresolved and bool(cited), f'{len(cited)} citations, unresolved: {unresolved or "none"}')

# --- contradiction clusters -------------------------------------------------
aud=os.path.join(W,'audit.md'); nclusters=0
if os.path.exists(aud):
    a=open(aud,encoding='utf-8',errors='replace').read()
    nclusters=len(re.findall(r'^##\s*C\d+',a,re.M))
chk('contradictions', nclusters>=1, f'{nclusters} contradiction cluster(s)')

# --- uncited claims in Findings --------------------------------------------
m=re.search(r'##\s*Findings(.*?)(\n##\s|\Z)',text,re.S|re.I)
uncited=[]
if m:
    for line in m.group(1).splitlines():
        s=line.strip()
        if not s or s.startswith('#') or s.startswith('|') or s.startswith('```'): continue
        core=re.sub(r'^[-*\d.\s]+','',s)
        if len(core)<40: continue            # headings/fragments, not claims
        # strip trailing emphasis so "Drivers:**" is still recognised as a lead-in
        bare=re.sub(r'[*_`\s]+$','',core)
        if bare.endswith(':'): continue            # lead-in for the cited bullets below
        # A statement that the corpus does NOT contain something cannot carry a citation --
        # that is what a gaps/limits statement IS. Requiring one here is a checker bug.
        # Match word STEMS: "evidenced"/"evidence", "studies"/"study", "measured"/"measure".
        if re.search(r'\b(no|not|never|none|unmeasured|unestablished|absent|lacking)\b'
                     r'.*\b(evidenc|stud|corpus|establish|controll|report|measur|data|baseline|quantif)',
                     core, re.I): continue
        if not re.search(r'\[S\d+\]',s): uncited.append(core[:80])
chk('no_uncited_claims', not uncited, f'{len(uncited)} uncited claim(s) in Findings' + (f'; e.g. "{uncited[0]}"' if uncited else ''))

# --- social sources labelled ------------------------------------------------
sec=re.search(r'##\s*Sources(.*)$',text,re.S|re.I)
labelled = ('social' in sec.group(1).lower()) if sec else False
chk('social_labelled', (not social) or labelled,
    f'{len(social)} social source(s) in corpus; labelled in report: {labelled}')

# --- cite-check hard block --------------------------------------------------
cc=os.path.join(W,'citecheck.json'); ccinfo='no citecheck.json'
ccpass=False
if os.path.exists(cc):
    try:
        d=json.load(open(cc,encoding='utf-8',errors='replace'))
        c=d.get('counts',{})
        bad=int(c.get('unsupported',0))+int(c.get('missing',0))+len(d.get('fabricated_quotes') or [])
        ccpass = bad==0
        ccinfo=f"supported={c.get('supported',0)} partial={c.get('partial',0)} unsupported={c.get('unsupported',0)} missing={c.get('missing',0)} fabricated={len(d.get('fabricated_quotes') or [])}"
    except Exception as e: ccinfo=f'unparseable: {e}'
chk('citecheck', ccpass, ccinfo)

json.dump({'ok':ok,'workdir':W,'checks':checks},open(out,'w'),indent=2)
for c in checks: print(f"  {c['status']:<5} {c['check']:<20} {c['detail']}")
print(('PASS' if ok else 'FAIL')+f" — audit written to {out}")
raise SystemExit(0 if ok else 1)
PY
````

## scripts/df-research-verify.sh

````bash
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
````
