#!/bin/bash
# Reladynamo closed-loop check. Idempotent; safe to re-run from any iteration.
#
#   bash scripts/check.sh [--iteration N]
#
# Exit 0 = every gate passed (the loop's exit condition). Non-zero = at least one gate failed.
# Writes reports/check-<N>.json and reports/check-latest.json.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ITER=0
while [[ $# -gt 0 ]]; do case $1 in --iteration) ITER="$2"; shift 2 ;; *) shift ;; esac; done
mkdir -p "$ROOT/reports"
OUT="$ROOT/reports/check-$ITER.json"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

declare -a NAMES=() STATES=() DETAILS=()
gate() { NAMES+=("$1"); STATES+=("$2"); DETAILS+=("$3"); }

# Each gate records its real result. A gate that cannot run is FAIL, never silently skipped —
# a skipped gate reported as green is how a closed loop deceives itself.
run_mvn() {
  local dir="$1"
  local label="$2"
  local log="$TMP/$label.log"
  if [ ! -f "$dir/pom.xml" ]; then gate "$label" FAIL "no pom.xml at $dir"; return 1; fi
  if (cd "$dir" && timeout "${CHECK_MVN_TIMEOUT:-1800}" mvn -B clean test > "$log" 2>&1); then
    # Count from surefire XML, not stdout: `-q` hides the summary line and a gate that reports
    # PASS with tests=0 is worse than no gate at all.
    #
    # KNOWN CAVEAT: this undercounts. The XML `tests` attribute and Maven's reactor "Tests run:"
    # line disagree for jqwik property classes — at iteration 123, XML summed to 496 where the
    # reactor reported 504 (core 183 vs 188, ddb 295 vs 298). The gate's job is "did tests actually
    # run, and did any fail", which is unaffected: a failure still surfaces either way. But the
    # number on the board is a floor, not the test count. Do not quote it as the total.
    local t
    t="$(python3 - "$dir" <<'PYC'
import sys,glob,xml.etree.ElementTree as ET
n=0
d=sys.argv[1]
pats=[d+"/**/target/surefire-reports/TEST-*.xml"]
for f in glob.glob(pats[0], recursive=True):
    if "/demos/" in f.replace("\\","/") and "/demos/" not in d.replace("\\","/"): continue
    try: n+=int(ET.parse(f).getroot().get("tests","0"))
    except Exception: pass
print(n)
PYC
)"
    if [ "${t:-0}" -eq 0 ]; then gate "$label" FAIL "built but ran 0 tests"; return 1; fi
    gate "$label" PASS "tests=$t"; return 0
  else
    # `Tests run:.*Fail` used to match here, which matches EVERY surefire line — they all carry
    # "Failures: 0" — so a FAIL routinely quoted a passing [INFO] line and said nothing about what
    # broke. Ask for the failure by name first, then a genuinely non-zero count, then anything.
    local why
    why="$(grep -m1 -E '^\[ERROR\]   [A-Za-z].*[:.]' "$log" \
        || grep -m1 -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$log" \
        || grep -m1 -E '^\[ERROR\].*(error:|cannot find symbol|BUILD FAILURE)' "$log" \
        || grep -m1 -E 'BUILD FAILURE' "$log" \
        || grep -m1 -iE 'timed out|Killed' "$log" \
        || echo "no diagnostic in $(wc -l < "$log") log lines — likely the build timeout under load")"
    gate "$label" FAIL "$(printf '%s' "$why" | head -c 200 | tr -d '\n')"
    return 1
  fi
}

run_mvn "$ROOT"                                  adapter-build
run_mvn "$ROOT/demos/01-crm-bitemporal/project"  demo-crm
run_mvn "$ROOT/demos/02-petstore-unitemporal/project" demo-petstore
run_mvn "$ROOT/demos/03-car-classifier/project"  demo-classifier

# Java 11 floor: every adapter class must be class-file 55 or lower. Verified, not assumed.
bad=0
while IFS= read -r f; do
  v="$(od -An -tu1 -j7 -N1 "$f" 2>/dev/null | tr -d ' ')"
  [ -n "$v" ] && [ "$v" -gt 55 ] && bad=$((bad+1))
done < <(find "$ROOT"/reladynamo-*/target/classes -name '*.class' 2>/dev/null)
if [ "$bad" -eq 0 ]; then gate java11-floor PASS "all adapter classes <= major 55"
else gate java11-floor FAIL "$bad class(es) above major 55"; fi

# Findings 16, 17 and 18 were all one shape: a design document described behaviour that did not
# exist and was read as though it did — a DoS mitigation, three tuning knobs, and a multi-tenancy
# data-isolation guard. Each was found by hand. This gate finds them for free.
if (cd "$ROOT" && timeout 120 bash scripts/spec-drift.sh > "$TMP/drift.log" 2>&1); then
  gate spec-drift PASS "$(tail -1 "$TMP/drift.log" | sed 's/^spec-drift: //' | head -c 90)"
else
  gate spec-drift FAIL "$(grep -m1 '  - ' "$TMP/drift.log" | sed 's/^  - //' | head -c 140)"
fi

# The MIT claim is a licence claim, and it is only true while DynamoDB Local (Amazon Software
# Licence) and H2 (MPL/EPL) stay out of the distributed classpath. The enforcer runs in `validate`,
# so a compile-scope leak already fails adapter-build — this gate states the result explicitly
# rather than leaving it buried in build output nobody reads.
if (cd "$ROOT" && timeout 300 mvn -B -q validate > "$TMP/lic.log" 2>&1); then
  gate licence-scope PASS "no ASL/MPL dependency in compile or runtime scope"
else
  gate licence-scope FAIL "$(grep -m1 -A1 'BannedDependencies failed' "$TMP/lic.log" | tail -1 | head -c 160 | tr -d '\n')"
fi

# The differential gate is the real exit criterion. It is honestly reported as PENDING until the
# DynamoDB persister exists — never as PASS, because "not yet implemented" is not "passing".
# Gate on the DIFFERENTIAL SUITE existing, not on the persister class. The persister can exist
# while the suite that proves H2/DynamoDB equivalence does not, and gating on the class would flip
# this to PASS on the strength of code nobody has compared against anything.
DIFF_SUITE="$ROOT/reladynamo-ddb/src/test/java/io/reladynamo/ddb/differential/BitemporalDifferentialTest.java"
if [ -f "$DIFF_SUITE" ]; then
  if (cd "$ROOT" && timeout 900 mvn -B -pl reladynamo-ddb -am test -Dtest='io.reladynamo.ddb.differential.*Test' -Dsurefire.failIfNoSpecifiedTests=false > "$TMP/diff.log" 2>&1); then
    # Require the suite to have actually RUN. A green build that executed zero differential tests
    # is exactly the vacuous pass this gate exists to prevent.
    # Sum every differential class, not just the first — the suite is now several classes and
    # reading only the first would under-report the gate's real coverage.
    # Report the STORAGE and QUERY paths separately. Finding 12 exists because every differential
    # test read back with `pk = :pk` and no sort-key condition — so 48 green tests said nothing about
    # as-of translation, and a real bug lived behind that number. A single total invites exactly the
    # misreading that let it hide.
    dt="$(python3 - "$ROOT/reladynamo-ddb/target/surefire-reports" <<'PYC'
import sys,glob,xml.etree.ElementTree as ET
storage=query=0
for f in glob.glob(sys.argv[1]+"/TEST-io.reladynamo.ddb.differential.*.xml"):
    try:
        r=ET.parse(f).getroot(); n=int(r.get("tests","0"))
    except Exception:
        continue
    # Query-path = the operation is EXECUTED against DynamoDB through a real generated finder and
    # compared to H2. Storage-path = H2 computes the history, the rows are mirrored in, and the copy
    # is compared. The distinction is the point of this gate: storage fidelity proves the codec and
    # key layout; it says nothing about whether a query is executed the way H2 executes it.
    #
    # This matched only "FinderDriven" until 2026-09-15, by which time ~112 finder-matrix cases and
    # several acceptance cases were executing real finders and being counted as storage-path. The
    # gate was under-reporting the half I had repeatedly called the one that matters.
    if any(k in f for k in ("FinderDriven", "FinderMatrix", "Acceptance", "BoundWritePath",
                            "NullPredicateDynamoDb", "GetItemFilterFinder", "PaginationSafeguardFinder",
                            "RelationshipDifferential", "RefreshTest")):
        query+=n
    else:
        storage+=n
print("%d storage-path, %d query-path" % (storage, query))
PYC
)"
    qp="${dt##*, }"; qp="${qp%% *}"
    if [ -z "$dt" ]; then
      gate differential-h2-vs-ddb FAIL "suite present but ran 0 tests"
    else
      if [ "${qp:-0}" -eq 0 ]; then
        # Storage fidelity without query fidelity is half the claim. Say so in the gate rather than
        # only in a document nobody reads while looking at a green line.
        gate differential-h2-vs-ddb PARTIAL "$dt — QUERY PATH UNPROVEN (see finding 12)"
      else
        gate differential-h2-vs-ddb PASS "H2 and DynamoDB identical: $dt"
      fi
    fi
  else
    why="$(grep -m1 -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$TMP/diff.log" \
          || grep -m1 -E '^\[ERROR\].*(BUILD FAILURE|\.java|Could not|Failed to execute)' "$TMP/diff.log" \
          || grep -m1 -iE 'timed out|killed' "$TMP/diff.log" \
          || echo 'build failed with no test-level diagnostic (possible timeout or concurrent build)')"
    gate differential-h2-vs-ddb FAIL "$(printf '%s' "$why" | head -c 200 | tr -d '\n\t')"
  fi
else
  gate differential-h2-vs-ddb PENDING "differential suite not written yet (persister write path: DONE, read path: pending executor)"
fi

pass=0; fail=0; pend=0
for s in "${STATES[@]}"; do
  case $s in PASS) pass=$((pass+1)) ;; FAIL) fail=$((fail+1)) ;; PENDING|PARTIAL) pend=$((pend+1)) ;; esac
done

# Gates are handed over as one JSON document in a file. An earlier version piped it to
# `python3 - "$OUT" <<PYJSON`, which cannot work: the heredoc IS stdin, so the script was read from
# it and the piped payload was discarded. json.loads("") then threw, no report was written, and the
# script still printed "-> reports/check-N.json" because the failure was swallowed. A reporting step
# that lies about having reported is the worst kind of bug in a gate board.
GATES_JSON="$TMP/gates.json"
{
  printf '{"iteration":%d,"pass":%d,"fail":%d,"pending":%d,"gates":[' "$ITER" "$pass" "$fail" "$pend"
  for i in "${!NAMES[@]}"; do
    [ "$i" -gt 0 ] && printf ','
    python3 -c 'import json,sys; sys.stdout.write(json.dumps({"name":sys.argv[1],"state":sys.argv[2],"detail":sys.argv[3]}))' \
      "${NAMES[$i]}" "${STATES[$i]}" "${DETAILS[$i]}"
  done
  printf ']}'
} > "$GATES_JSON"

if ! python3 - "$OUT" "$GATES_JSON" <<'PYJSON'
import json, sys, datetime
out, src = sys.argv[1], sys.argv[2]
doc = json.load(open(src, encoding="utf-8"))
gates = doc["gates"]
# A FAIL with no detail is a gate that told you nothing. That happened twice under machine
# contention and cost a false-regression investigation each time, so it is no longer expressible.
for g in gates:
    if not (g.get("detail") or "").strip() and g["state"] != "PASS":
        g["detail"] = "(no diagnostic captured - check the gate's log; usually a build timeout)"
json.dump({
    "iteration": doc["iteration"],
    "timestamp": datetime.datetime.now().astimezone().isoformat(),
    "summary": {"pass": doc["pass"], "fail": doc["fail"], "pending": doc["pending"]},
    "exit_condition_met": doc["fail"] == 0 and doc["pending"] == 0,
    "gates": gates,
}, open(out, "w"), indent=2)
PYJSON
then
  echo "FATAL: could not write $OUT (gate payload kept at $GATES_JSON)" >&2
  exit 3
fi

cp "$OUT" "$ROOT/reports/check-latest.json"

printf '%-26s %-8s %s\n' GATE STATE DETAIL
for i in "${!NAMES[@]}"; do printf '%-26s %-8s %s\n' "${NAMES[$i]}" "${STATES[$i]}" "${DETAILS[$i]}"; done
printf -- '-- iteration %d: %d pass / %d fail / %d pending -> %s\n' \
  "$ITER" "$pass" "$fail" "$pend" "$OUT"

[ "$fail" -eq 0 ] && [ "$pend" -eq 0 ]
