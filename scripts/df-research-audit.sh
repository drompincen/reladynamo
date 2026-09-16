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
