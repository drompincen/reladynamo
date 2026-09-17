#!/bin/bash
# Compares the design documents against the code they describe.
#
# Findings 16, 17 and 18 were all the same shape: a design document described behaviour that did not
# exist, and was read as though it did. One was a documented DoS mitigation, three were tuning knobs,
# and one was a multi-tenancy data-isolation guard. Each was found by hand. This finds them for free.
#
# Exit 0 = every documented error code exists in code and every config getter has a consumer.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

python3 - <<'PY'
import glob, re, sys

problems = []

# 1. Every RELADYNAMO-xxx-nnn code named in a design doc must exist in main sources — or be listed
#    in the waiver below with a reason. A code in the spec and not in the code is a promise nobody
#    kept.
WAIVED = {
    "RELADYNAMO-PLAN-003": "join fan-out limit not implemented; joinFanOutLimit refuses non-defaults (finding 17)",
    "RELADYNAMO-PLAN-006": "page-exhaustion diagnostic not implemented; maxPages throws instead (finding 16)",
    "RELADYNAMO-PLAN-007": "in-memory ceilings partially implemented; byte ceiling refuses non-defaults (finding 17)",
    "RELADYNAMO-PLAN-004": "sourceAttribute is refused outright at parse time instead (finding 18, CFG-012)",
}
in_code = set()
for f in glob.glob('reladynamo-*/src/main/java/**/*.java', recursive=True):
    in_code |= set(re.findall(r'RELADYNAMO-[A-Z]+-\d+', open(f, encoding='utf-8').read()))
in_docs = set()
for f in glob.glob('docs/design/*.md'):
    in_docs |= set(re.findall(r'RELADYNAMO-[A-Z]+-\d+', open(f, encoding='utf-8').read()))
missing = sorted(c for c in in_docs - in_code if c not in WAIVED)
for c in missing:
    problems.append(f"documented but not implemented, and not waived: {c}")

# 2. Every public getter on a config object must have a consumer somewhere in main sources, or
#    refuse non-default values. A knob nothing reads is worse than a missing one: it reads as a
#    guarantee.
for cfg in ['reladynamo-core/src/main/java/io/reladynamo/core/plan/PlannerConfig.java']:
    src = open(cfg, encoding='utf-8').read()
    getters = set(re.findall(r'public (?:int|boolean|long|String|Integer) (\w+)\(\) \{', src))
    others = {f: open(f, encoding='utf-8').read()
              for f in glob.glob('reladynamo-*/src/main/java/**/*.java', recursive=True)
              if not f.endswith('PlannerConfig.java')}
    # A same-named getter declared on a *different* class used to satisfy this check by its own
    # declaration alone. That is how R-09 hid in plain sight: PlannerConfig.maxPages() looked
    # consumed because QueryPlan.java declares its own maxPages(), while QueryPlanner never copied
    # the configured value into the plan. Every real finder plan was therefore unbounded, and the
    # gate said the knob was wired. So: strip declaration sites, and require an actual receiver.
    DECL = re.compile(r'public\s+(?:static\s+)?[\w<>\[\], .]+\s+\w+\s*\([^)]*\)\s*\{')
    calls = {f: DECL.sub(' ', b) for f, b in others.items()}
    for g in sorted(getters):
        # `something.maxPages()` — a call through a receiver, not a declaration of the same name.
        consumed = any(re.search(r'\.\s*' + g + r'\s*\(\)', b) for b in calls.values())
        guarded = re.search(r'public Builder ' + g + r'\(int v\) \{\s*if \(v != ', src) is not None
        if not consumed and not guarded:
            problems.append(f"config option with no consumer and no guard: {g}")
            continue
        # Second-order check: a config value that exists as a same-named field on QueryPlan must
        # actually be copied there by the planner. Being read somewhere is not being wired.
        plan_f = 'reladynamo-core/src/main/java/io/reladynamo/core/plan/QueryPlan.java'
        planner_f = 'reladynamo-core/src/main/java/io/reladynamo/core/plan/QueryPlanner.java'
        if plan_f in others and planner_f in others:
            if re.search(r'\bprivate final [\w<>\[\]]+ ' + g + r'\s*;', others[plan_f]):
                if not re.search(r'\b' + g + r'\b', others[planner_f]):
                    problems.append(
                        f"config option reaches QueryPlan.{g} but QueryPlanner never populates it: {g}")

if problems:
    print("spec-drift: %d problem(s)" % len(problems))
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("spec-drift: documented codes implemented or waived; every config option consumed or guarded")
PY
