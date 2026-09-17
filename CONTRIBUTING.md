# Contributing to Reladynamo

## The one rule that matters

**H2 is the reference implementation. When the adapter and H2 disagree, the adapter is wrong until
proven otherwise — and the way to prove otherwise is evidence from Reladomo's generated code or
`javap`, never an adjusted assertion.**

Fifteen findings are recorded in [`docs/CONFORMANCE-FINDINGS.md`](docs/CONFORMANCE-FINDINGS.md).
Twelve turned out to be wrong premises about Reladomo; three were genuine adapter defects. That ratio
is the trap: a failing differential test is *usually* wrong about Reladomo, so "the test is probably
wrong" is a reasonable first guess and a terrible policy. Never weaken a comparison to reach green.

## Before you start

```bash
java scripts/GenerateScripts.java   # scripts are tracked as *.sh.txt — see start-here.md
mvn clean install                   # all modules
bash scripts/check.sh               # the full gate — this is what CI runs
```

**Never commit a `.sh` file.** Edit the `.sh.txt` source; if you changed a generated script in place,
run `java scripts/GenerateScripts.java --adopt` before committing.

`scripts/check.sh` is the contract. Seven gates, and `exit_condition_met` in
`reports/check-latest.json` is true only when every one passes with nothing pending.

## Testing expectations

**Write the test first and watch it fail.** A test that has never failed proves nothing. The
`/tdd` skill in `.claude/skills/` states this at length; it is not decoration.

**Differential tests are the real proof.** Run the operation against H2, push the rows through the
adapter, read them back, compare with `TemporalRowSetDiffer` — which compares temporal values exactly,
with no tolerance, and distinguishes MISSING from EXTRA rows.

**Know what your test does not cover.** Every real defect found in this project was found by writing
the first test of an untested path — and each time, dozens of green tests were sitting beside it:

- 48 differential tests read with `pk = :pk` only, so none exercised as-of translation (finding 12)
- the write path was validated by `insert`, which is the one operation that avoids the transactional
  wrapper (finding 14)
- relationships were declared in fixtures and navigated by nothing (finding 15)

[`docs/COVERAGE-GAPS.md`](docs/COVERAGE-GAPS.md) is the current list of what nobody has tested. Add to
it when you notice something; check it before claiming a feature works.

**Fixtures must be built the way production builds things.** `PlanFixtures` once set infinity from
Reladomo's own sentinel while production reconstructed it from XML. The fixture was *more correct*
than production, so it hid the bug production had (finding 13). A fixture tidier than reality tests a
system you do not ship.

## Code expectations

- **Java 11 is the floor.** `maven.compiler.release=11`; no records, sealed types, text blocks or
  `Stream.toList()`. The gate checks every emitted class is class-file major ≤ 55.
- **Verify every Reladomo API before using it.** Three invented APIs were caught in review this
  project. `javap -cp <reladomo jar> <fqcn>` costs seconds.
- **Refuse loudly rather than returning something plausible.** An unimplemented read that returns an
  empty list is indistinguishable from a working query over an empty table. Throw, and name the method.
- **Never let a non-permissively licensed dependency reach compile or runtime scope.** DynamoDB Local
  is Amazon Software Licence; H2 is MPL/EPL. A `maven-enforcer` rule fails the build, and it has
  already caught a real transitive leak.

## Adding a finding

If you discover behaviour that contradicts what the code assumed, add a numbered section to
`docs/CONFORMANCE-FINDINGS.md` with: the symptom, the evidence (a dumped plan, a `javap` output, a row
set), and whether it was a wrong premise or a real defect. Update the index table at the top.

Leaving a test red with a recorded finding is a **better** outcome than a green suite that proves less.
