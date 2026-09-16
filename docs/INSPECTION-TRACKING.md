# Inspection 2026-09-14 — disposition

Every finding in `docs/INSPECTION-2026-09-14.md`, its owner, and its current state. The inspector
closed with *"no finding is marked fixed merely because its surrounding tests pass"* — so a row moves
to **fixed** only when there is a test that **was seen to fail first** and now passes.

I re-verified every finding below against the source as it stood on 2026-09-14 before dispatching any
work. All 21 were still present. Line references in the inspection were accurate.

## Track A — migration tooling

| Code | Severity | Finding | Owner | State |
|---|---|---|---|---|
| M-01 | Blocker | Backfill certifies a payload-corrupted copy as identical; non-dated entities compare nothing at all | grok `insp/backfill` | **integrated** — every mapped attribute compared type-aware, both directions; `rowsWritten` now counts identical counterparts |
| M-02 | Blocker | Backfill verification uses only the first PK attribute; composite keys fail *after* writing | grok `insp/backfill` | **integrated** — full logical key throughout; `readBack(Object)` refuses composite mappings |
| M-03 | Major | No relational source extractor, project discovery, or migration job runner; no ASE evidence | scope | **declared out of scope** — `docs/SUPPORT-CONTRACT.md` Tier 2 |
| M-04 | Major | Backfill not restartable or bounded; whole source in memory; O(V²) verification reads | grok `work/m04` → `integ/wave` | **CLOSED** — streaming intake, grouped verification, durable checkpoints surviving kill/resume; `BackfillRestartableTest` 11/11 |
| M-05 | Blocker (online) | Unconditional puts make replay unsafe against newer writes; no CDC or cutover barrier | scope | **offline-only declared** — Tier 2; `MIGRATION.md` corrected |
| M-06 | Major | Reverse migration claimed in `MIGRATION.md` but not implemented | doc | **fixed** — claim retracted in `MIGRATION.md` stage 5 |
| M-07 | Major | `TableCreator` checks "is it ACTIVE", not "does it match"; no `_rd_v` range enforcement | grok `fix/m07` | **integrated** — `SchemaReconcileOutcome`/`Result`/`Exception` distinguish create / validate / add-index / incompatible; `_rd_v` range enforced at decode with a version-dispatch hook |
| M-08 | Major | `TemporalRowSetDiffer` infers identity from name heuristics and map order; `byte[]` compared by reference | grok `insp/backfill` | **integrated** — new `MappedRowSetDiffer` keyed on `EntityMapping.primaryKeyAttributes()`; `Arrays.equals` recursively; duplicate identities throw |

## Track B — ORM behaviour and Reladomo parity

| Code | Severity | Finding | Owner | State |
|---|---|---|---|---|
| R-01 | Blocker | No transaction integration at all; `setTxParticipationMode` is a no-op; writes durable on arrival | astra design + grok `work/tx` → `integ/wave` | **CLOSED** — coordinator composed with R-02's conditions via `PhysicalWrite.Condition`; `DurableTransactionTest`, `DurableTransactionLocalTest`, `BoundDurableTransactionTest` all green; `BoundWritePathTest` still 5/5 |
| R-02 | Blocker | Inserts/updates/deletes carry no conditions; `update()` rewrites the whole row | grok `fix/r02` + `merge/r02` | **integrated** — two write families: ORM (`attribute_not_exists` on insert, expected-prior-state on update/delete → `MithraUniqueIndexViolationException` / `MithraOptimisticLockException`) and migration (`upsert`/`batchUpsert`, unconditional). **Closed finding 21 with it.** |
| R-03 | Blocker | `getItem()` evaluates only the residual, discarding the plan's filter — wrong rows returned | grok `fix/r03` | **integrated** — plan filter evaluated locally against the decoded item, so the single-RCU point read is kept; verified independently by RED-by-disabling-the-fix, GREEN 17/17 |
| R-04 | Blocker | Planner emits every number as `N`; codec stores double/float as `B` and BigDecimal as `S` | grok `fix5` → `merge2` → `integ/wave` | **CLOSED** — numeric predicates evaluated as typed residuals; `BigDecimal.compareTo()==0` pinned |
| R-05 | Blocker | `isNull`/`isNotNull` inverted for stored explicit NULL — **and the interpreter oracle repeats the bug** | grok `fix3/r05` | **integrated** — `attribute_type(#n, "NULL")` rather than comparison-with-NULL coercion; interpreter oracle fixed too, so the property tests stop agreeing on a shared wrong premise |
| R-06 | Blocker | Fan-out collapse compares expression *text*, not value bindings — OR branches lose and gain rows | grok `fix/r06` | **integrated** — equivalence now requires same text *and* name map *and* value bindings *and* SK bounds *and* residual; the safe `pk IN (...)` collapse is kept, asserted at one request |
| R-07 | Major | Residual evaluated against a raw `Map`; generated attributes need domain types | grok `fix4/r07` | **integrated** — nullable `matches()` contract preserved; `ResidualEvaluationTest` 5/5 |
| R-08 | Major | Order mode from `OrderBy.toString()` substrings; executor never sorts; fan-out never dedupes | grok `fix/r08` | **integrated** — real `OrderByTranslator`/`RowOrderComparator`/`SortTerm` off the actual `OrderBy` API, executor sorts, fan-out dedupes by physical identity, limits applied after ordering, Dynamo `limit` suppressed under IN_MEMORY |
| R-09 | Major | `PlannerConfig.maxPages` never reaches `QueryPlan` — every real finder plan is unbounded | grok `fix4/r09` | **integrated** — re-implemented against the current tree after its first solution proved unmergeable; `QueryPlannerLimitWiringTest` 5/5, `PaginationSafeguardFinderTest` 6/6, and R-03/R-06/R-08 suites verified still green |
| R-10 | Major | Sparse-current and composite GSI keys are never stamped; KEYS_ONLY/INCLUDE unhydrated | grok `work/gsi` → `integ/wave` | **CLOSED** — sparse-processing-current stamping and removal, composite GSI keys, `RELADYNAMO-CFG-014` for unsupported projections. `SparseCurrentGsiStampTest` + `SparseCurrentGsiQueryTest`; the as-of-now win is **measured**, not asserted |
| R-11 | Major | "Unchanged arbitrary XML" is not the implemented contract; custom axis names break the writer | scope | **narrowed** — Tier 3 states exactly what parses, refuses, and half-works |
| R-12 | Major | 20 of 32 SPI methods refuse; docs said 21; no production bootstrap matching design 02 | grok `refresh` → `merge3` → `land/demos25` | **partially closed** — `refresh` and `refreshDatedObject` implemented and integrated (`RefreshTest`); the coordinator refuses a read while writes are staged (`RELADYNAMO-TXN-006`) rather than returning a stale value. The classifier's next wall is finding 25, not an SPI gap. The remaining refusals are now **ordered by what a real application hits**, not counted. |
| R-13 | Major | 400 KB checked before keys are appended; no reserved-name validation; key types unpinned | grok `fix/r13` | **integrated** — final stored item measured after keys and GSI attributes; mapping namespace validated against `pk`/`sk`/`_rd_v`/GSI names; new `KeyComponentEncoder` pins key types |

## Findings about the documents themselves

The inspection's closing section is not a defect list but it is the most actionable part of the
report, and it was right:

- *"Progress checkboxes and status documents are not a dependable current feature inventory."* Acted
  on: `MIGRATION.md` stage 5 retracted, the transaction row rewritten, the idempotency claim narrowed,
  the SPI refusal count corrected from 21 to 20, and `docs/SUPPORT-CONTRACT.md` written to state scope
  separately from progress.
- *"The design documents contain a substantially broader architecture than the implementation."* The
  `spec-drift` gate (`scripts/spec-drift.sh`, added at iteration 108) now fails the build when a
  documented `RELADYNAMO-xxx-nnn` code has no implementation and no waiver, and when a `PlannerConfig`
  getter has neither a consumer nor a refusal. R-09 is precisely the class of bug it was built for and
  it did **not** catch it — `maxPages` *is* consumed, in `QueryPlanExecutor`, just never populated.
  The gate checks that a setting is read somewhere; it does not check that the value read is the value
  configured. That is a real hole in the gate and is itself worth fixing.
- *"The right review unit is an observable application behavior with an executable acceptance case,
  not a checked chapter or a raw test count."* Recorded in `docs/SUPPORT-CONTRACT.md`.

## Acceptance cases

The inspection's own checklist is the exit criterion for this work. It is reproduced at the end of
`docs/INSPECTION-2026-09-14.md` and none of its 13 boxes is ticked yet.

## Integration order — read this before merging any agent output

The first attempt bundled three or four findings per agent. All three of those agents died on **"max
turns reached"** having done real work but finished nothing. They were re-cut mechanically — each
finding's section carved out of the existing brief into its own task file — into **ten single-finding
agents** under run `fix`, five at a time, each with an explicit instruction to record adjacent
problems rather than fix them.

That changed the merge problem. The collisions are now between *individual* agents, and only where
two findings genuinely share a file:

| File | Claimed by |
|---|---|
| `QueryPlanner.java` | `r03`, `r04`, `r05`, `r08`, `r09` |
| `QueryPlanExecutor.java` | `r03`, `r07`, `r08`, `r09` |
| `QueryPlan.java` | `r08`, `r09` |
| `QueryPlanInterpreter.java` | `r05` |
| `FanOutSelect.java` | `r06` — **integrated** |
| `DynamoDbWriter.java`, `DynamoDbPersister.java` | `r02`, `r13`, and astra's transaction coordinator |
| `TableCreator.java` | `m07` |
| `ItemCodec.java` | `r13` |

**Integrate one agent at a time, with `mvn clean test` between each**, and diff the agent's tree
against the repo first — they mirror the whole source tree, so a blind copy would revert work already
landed by an earlier agent. `r06` mirrored 52 files and had changed exactly two; taking only those two
is the difference between an integration and a regression.

Order: whatever finished, least-contended file first. `r06` (FanOutSelect, uncontended) went in
cleanly. `QueryPlanner` is the contended one — expect to resolve `r03`/`r04`/`r05`/`r08`/`r09` against
each other by hand, in that order, since each later one builds on the planner state the previous left.
The transaction coordinator goes **last**: it must compose with the conditional writes `r02` adds, and
its brief was told to leave a named seam for exactly that.

Verify each agent's output against the real jars (`javap`) before integrating — two invented Reladomo
APIs and one nullable-`Boolean` unboxing NPE have already been caught this way. Anything that fails
gets **parked, not deleted**, and the gate stays red until it is genuinely fixed.

## Found while closing the inspection — finding 21, and it is worse than R-02 says

R-02 describes `DynamoDbPersister.update(object, wrapper)` as a *concurrency* hazard: it "rewrites the
complete current data row rather than applying checked attribute changes." Strengthening
`BoundWritePathTest` — the very test the inspection called out for asserting `>= 1` row where the
insert alone already satisfied it — showed the change is not merely unguarded but **lost**.

A bound insert-then-update leaves DynamoDB with the right row count, the right quantities, the right
business boundaries, and one superseded version whose **processing rectangle is never closed**. H2
closes it. `terminate()` fails the same way: every version stays at `processingDateTo = infinity`.
An as-of-processing query then sees two rows claiming to be current.

The mechanism: the director delivers the change in the `AttributeUpdateWrapper`, the persister ignores
the wrapper and re-puts `zGetCurrentData()`, and closing a rectangle only touches `processingDateTo` —
which is not in the sort key. So the put lands on the correct item carrying the old value, and reports
success.

**Routed to `insp/writes` at integration.** Its brief already owns this method for R-02; the fix must
also make the close land, and the acceptance test is the now-failing
`BoundWritePathTest.a_bound_update_produces_the_same_bitemporal_shape_as_h2`. Full write-up:
finding 21 in `docs/CONFORMANCE-FINDINGS.md`.


## Demo claim corrected (inspection closing section)

The inspection noted that the CRM and classifier DynamoDB demos "mirror H2 snapshots and do not
rebind the application finders," and that this is inconsistent with the migration guide's broader
claim. **Verified: `setMithraObjectReader` appears nowhere under `demos/`.** All three mirror rows
through `DynamoDbWriter` and compare the copy.

`MIGRATION.md` said "All three demo projects do exactly this" of the portal binding. Corrected: the
byte-identical object model claim is true and kept; the binding claim is retracted and the gap named
as acceptance case 12, blocked on R-02/R-03/R-07 and finding 21.

## R-01 — astra's transaction coordinator, verified against the jar and parked

The astra (`gpt-6-astra`) agent on R-01 was killed by the codex usage limit on its wrap-up turn, but
it had already finished the work: **16 tests green**, TDD red-then-green captured in its BUILD-LOG,
and a `verification/JAVAP.txt` recording the Reladomo API surface it built against. It never wrote
`NOTES.md`.

Nine files: `DynamoDbTransactionCoordinator`, `PhysicalWrite`, `DynamoDbTransactionException`,
`DynamoDbCommitOutcomeUnknownException`, edits to `DynamoDbPersister` and `DynamoDbWriter`, and three
test classes including a bound-portal durability test.

**Independently verified against `reladomo-18.1.0.jar`** rather than taken on its word — this project
has already caught two invented Reladomo APIs and a nullable-`Boolean` unboxing NPE that way. Every
API it uses is real:

| API | Result |
|---|---|
| `MithraTransaction.registerSynchronization` | exists |
| `MithraTransaction.registerLifeCycleListener` | exists |
| `MithraTransaction.expectRollbackWithCause` | exists |
| `MithraTransaction.getParent` | exists |
| `MithraTransaction.enlistResource` | exists |
| `com.gs.fw.common.mithra.transaction.LocalTx` | exists (`implements javax.transaction.Transaction`) |
| `com.gs.fw.common.mithra.transaction.TransactionLocal` | exists |
| `com.gs.fw.common.mithra.util.InternalList` | exists |

**Parked, not integrated.** It edits `DynamoDbPersister` and `DynamoDbWriter`, which `r02` is
concurrently rewriting to add conditional expressions, and `TransactWriteItems` carries a
`ConditionExpression` per action — the two features are meant to compose. Integrating the coordinator
first would force `r02` to be merged into a file it never saw. It goes **last**, as the integration
order says, and its brief was told to leave a named seam for exactly this.

Because it never wrote `NOTES.md`, the isolation policy, the ambiguous-commit policy and the location
of that seam have to be read out of the source at integration time. Budget for that.


## Astra's topology and indexing review (2026-09-14 21:20)

Delivered as `docs/TOPOLOGY-DECISION.md`, `docs/INDEXING-PRESCRIPTION.md` and
`docs/ASSUMPTION-CHALLENGE.md`.

**Decision: retain table-per-object.** Not from preference — from a fact about the seam.
`MithraObjectReader.find(...)` returns a single `CachedQuery` and `Operation.getResultObjectPortal()`
returns **one** portal, so a query reaching the adapter has exactly one root type. Single-table's
headline benefit is retrieving several entity types in one request; that request cannot be expressed
at this seam, so consolidating tables would buy the costs and none of the benefit.

Two supporting points worth keeping:

- The current keys **do not colocate** related entities anyway. A customer is `v1#CUSTOMER#42`, its
  contact `v1#CONTACT#87`; putting both in one table does not make one Query return both. The class
  prefix is an identity discriminator, not a grouping key.
- Design 01 §2.2 cited cross-entity transactions as a single-table advantage. **That is wrong** —
  DynamoDB transactions already span tables in the same account and Region, and neither topology
  changes the 100-action/4-MB boundary.

Astra **declared its own javap gate BLOCKED** (WSL interop failure) rather than reporting it passed,
and labelled its Reladomo findings source-supported instead of bytecode-verified. I ran the gate here:
`find(...)`, `getResultObjectPortal()` and `MithraDatedObjectPersister`'s two additions all match its
description exactly. **Gate now passed.**

`CHALLENGE.md` raises thirteen assumptions. Two are confirmed defects **in code landed the same day**
and are dispatched as `chal/c09c11` — recorded as findings 22 and 23 in `docs/CONFORMANCE-FINDINGS.md`.
The remaining eleven need triage.


## Status after the overnight integration (2026-09-15 05:58 MT)

`mvn -B clean test` green: core **188**, test-kit **15**, ddb **298**, spike 3 — **504 tests**.

**Eighteen of twenty-one inspection findings are closed**: M-01, M-02, M-04, M-07, M-08, R-01, R-02,
R-03, R-04, R-05, R-06, R-07, R-08, R-09, R-10, R-13 — plus findings 20, 21, 22 and 23 found along the
way. Remaining: **R-11** and **R-12** (both narrowed in `docs/SUPPORT-CONTRACT.md`, and R-12 now has a
concrete ordering from finding 24), and **M-03/M-05/M-06**, declared out of scope for 0.1.0.

The five-step integration was done by a single grok agent working to a sequenced brief, with a build
between each step and named invariants that had to hold throughout. Its `NOTES.md` records, per step,
which files were copied new, which were copied because the agent owned them outright, which were
merged as three-way hunks, every conflict and how it was resolved, and the test counts after that
step. That audit trail is why the result could be trusted enough to land.


## Consolidation, 2026-09-15 07:07 MT

`mvn -B clean test` green: core **188**, test-kit **15**, ddb **305**, spike 3 — **511 tests**
(287 when the inspection landed). Demos: CRM 18, petstore 16, classifier 11.

**Nineteen of twenty-one findings closed.** R-01, R-02, R-03, R-04, R-05, R-06, R-07, R-08, R-09,
R-10, R-13, M-01, M-02, M-04, M-07, M-08, plus findings 20-23 discovered while closing them, plus
R-12 partially (`refresh` landed; the rest ordered rather than counted).

**Not closed:** R-11 (mapping contract narrowed in the support contract, Tier 3) and M-03/M-05/M-06
(out of scope for 0.1.0, stated). Findings 24, 25 and 26 are open — 25 and 26 have fixes written but
never landed, dispatched as `land/demos25`.

**The ninth exit criterion is 0 of 13.** No acceptance case has an executable test. Case 12 is the
first attempted: `ClassifierBoundPortalTest` exists and fails for a real, named reason. That is
progress toward the count but does not change it — the count moves when a case **passes**.


## Acceptance case 12 — CLOSED (2026-09-15 07:45 MT)

> *One original business operation script runs independently against each backend; complete
> histories, results, exceptions and rollback outcomes agree.*

`demos/03-car-classifier` now carries `ClassifierBoundPortalTest`: it binds each portal with
`MithraAbstractObjectPortal.setMithraObjectReader`, runs the demo's **unchanged** business operations
against DynamoDB, pins the processing clock with `MithraTransaction.setProcessingStartTime` so IN_Z
and OUT_Z are comparable, and asserts the two histories are identical across all four temporal
boundaries. Where an operation cannot be served it asserts a **named** refusal and then fails — there
is no skip and no `@Disabled`, which is how the previous version of this claim stayed green while
proving nothing.

Classifier demo: 11 → **12 tests**, BUILD SUCCESS.

**The ninth exit criterion moves from 0/13 to 1/13.**

### Correction to the previous checkpoint

I reported that findings 25 and 26 had "never been landed". **That was wrong.** Both were already in
the live tree:

- `DynamoDbPersister:466-470` — `zGetTxDataForRead()` with its explanatory comment (finding 25)
- `DynamoDbPersister:220` — `new CachedQuery(op.getOriginalOperation(), orderBy)` (finding 26)

The five-step integration wave picked them up, because the `tx` agent branched **after** the `demos`
agent and inherited its persister changes. The `merge3` agent's report that the classifier still
failed on `ResultLabel cannot be null` was accurate **for its own merge tree**, which did not include
the wave's result — not for the integrated tree.

The dispatched agent found the persister already correct (a **zero-line** diff against live) and
contributed only the bound test. No harm done, but the diagnosis was wrong and the correction belongs
on the record next to it.


## Finder-matrix harness landed (2026-09-15 08:40 MT)

`docs/FINDER-MATRIX.md` §2/§4/§5 implemented: the `DiffFinderValue` fixture (composite PK, one
attribute per supported type) and the `findermatrix` harness with a counting DynamoDB client and
request assertions. ddb 305 → **323**; total **529**.

The harness's own cold-cache tests prove it detects a cache-served query rather than assuming it —
one asserts that with the cache serving, data reads do not increase and the guard throws. Without
that, every case built on the harness would pass whether or not the adapter was involved, which is
the exact failure mode that let R-05 be wrong in both the translator and its oracle.

`fm2/cases` and `fm2/shapes` now implement §6.


## Finder matrix landed (2026-09-15 10:40 MT)

`docs/FINDER-MATRIX.md` §6 implemented across two agents and merged by a third. **ddb 323 → 435**
(+112 query-path cases). The differential suite's weak half — 89 storage-path against 6 query-path —
is no longer the shape of the evidence.

Seven cases are red, and each is named:

| Count | Finding | Status |
|---|---|---|
| 5 | **29** — `equalsEdgePoint` / from-range cannot materialise; `asOfDatesOf` requires `AsOfEqOperation` | dispatched `fm4/edgepoint` |
| 1 | **28** — `ByteArrayAttribute.notEq` throws in Reladomo 18.1.0 itself (javap-confirmed) | not ours; the case asserts the refusal |
| 1 | **30** — binary ordering on a GSI route returns nothing | dispatched `fm4/binorder` |

**Finding 27 is closed**: `notEq` now excludes stored explicit NULLs on all five native-filter types,
matching what double/float/BigDecimal already did through residual evaluation, with the interpreter
oracle fixed alongside the planner.

The refusal message finding 29 trips over is worth preserving verbatim, because it is the right
instinct: *"Picking a default date would return rows that look right and are silently the wrong
version."* The fix must add the capability, not soften that.


## Acceptance cases — 3 of 13 (2026-09-15 12:30 MT)

| # | Case | State |
|---|---|---|
| 3 | A failed, already-flushed Reladomo transaction leaves no partial durable result | **PASS** — `AcceptanceFailedTransactionTest`, including failure between closing a rectangle and inserting its replacement |
| 4 | Duplicate insert and concurrent/stale updates follow an explicit conflict contract across JVMs | **FAILING** — finding 32; conditions fire but the coordinator reports TXN-004 instead of the Mithra conflict type. Dispatched `txn/conflict` |
| 5 | Complete-key queries with failing payload/as-of filters return no row and count zero | **PASS** — `AcceptanceCompleteKeyFilterTest`, asserting `getItem>=1, query==0` so a point read is not silently downgraded |
| 12 | One original business operation script runs independently against each backend | **PASS** — `ClassifierBoundPortalTest` |

Remaining red across the whole build: those two case-4 failures plus finding 28
(`ByteArrayAttribute.notEq` throws inside Reladomo 18.1.0 — not ours).

core **193**, test-kit 15, ddb **444**, spike 3.


## Acceptance cases — 8 of 13 (2026-09-15 14:40 MT)

Build is **green with zero errors**: core 198, test-kit 15, ddb 461, spike 3 — **677 tests**.

| # | Case | State |
|---|---|---|
| 1 | Every source PK component and mapped value participates in verification; binary by content; extra/missing detected | **PASS** — `MappedRowSetDifferTest` pins the wording, including a composite key and two independently constructed `byte[]` |
| 2 | A stale migration retry cannot overwrite newer state or resurrect a purge | **BLOCKED BY SCOPE** — M-05; offline immutable-source migration is what the support contract declares |
| 3 | A failed, already-flushed transaction leaves no partial durable result | **PASS** |
| 4 | Duplicate insert and concurrent/stale updates follow an explicit conflict contract across JVMs | **PASS** — finding 32 closed |
| 5 | Complete-key queries with failing payload/as-of filters return no row and count zero | **PASS** |
| 6 | Numeric/null/string predicates execute correctly against codec-written items | **PASS** — already covered by `FinderMatrixTypeOperatorCasesTest`; the agent proved coverage rather than padding it |
| 7 | OR branches retain distinct bindings, deduplicate, and preserve ordered top-N | **PASS** — `AcceptanceOrFanOutFinderTest`, including descending top-one where the maximum is in the last partition |
| 8 | Limits bound Query, Scan, PartiQL and intermediate memory | **PASS** — and it found finding 33: PartiQL was unbounded |
| 9 | Custom temporal names, PK types, source routing, GSI projections work end to end or fail preflight | open — R-11 territory |
| 10 | Every required SPI feature runs with cold caches and the source disconnected | open — R-12 territory |
| 11 | H2 **and ASE** extraction copy all versions from a reproducible snapshot | **BLOCKED BY SCOPE** — M-03; no ASE access, stated in the support contract |
| 12 | One original business script runs independently against each backend | **PASS** — `ClassifierBoundPortalTest` |
| 13 | Crash/restart, partial writes, cutover, rollback, reverse import rehearsed | **PARTIAL** — crash/restart and partial writes covered (M-04, R-01); cutover, rollback and reverse import are M-05/M-06, out of scope |

**8 pass, 2 open and actionable (9, 10), 3 blocked by declared scope (2, 11, 13).** Saying "8 of 13"
without that breakdown would overstate it: three of the five remaining are not work in progress, they
are things `docs/SUPPORT-CONTRACT.md` says 0.1.0 does not do.


## The differential gate's storage/query split was mis-attributing (2026-09-15 14:50)

The gate classified a differential test as query-path only if its filename contained `FinderDriven`.
By the time the finder matrix landed, ~112 matrix cases and every acceptance test were executing real
generated finders against DynamoDB and comparing to H2 — and all were being counted as storage-path.

```
reported:  240 storage-path,   6 query-path
actual:     78 storage-path, 168 query-path
```

The "6 query-path" figure has been quoted in these documents for days as evidence that the query path
was the weak half. It was the **classifier** that was stale, not the coverage. Fixed to match on the
test classes that genuinely execute a finder end to end.

The distinction still matters and is worth restating: **storage-path** means H2 computed the history,
the rows were mirrored in, and the copy was compared — that proves the codec and key layout.
**Query-path** means the operation was executed against DynamoDB through a real generated finder and
the result compared to H2. Only the second says the adapter executes a query the way H2 does.


## Acceptance cases — 10 of 13, and the other three are scope (2026-09-15 15:40 MT)

Adapter **BUILD SUCCESS, zero errors**: core 202, test-kit 15, ddb 469, spike 3 — **689 tests**.
Demos: CRM 18, petstore **17**, classifier **13**.

**Case 9 — PASS.** Each of the four either works end to end or refuses at **preflight**, by name:

| | Outcome |
|---|---|
| Custom temporal names | Unitemporal `validDate` **works end to end** — `PhysicalDesign` derives `validDateFrom`/`VALID_FROM`, the sort key is `v1#B#<encoded validDateFrom>`, and a write/consistent-read round-trips. Bitemporal custom names **refuse** with `RELADYNAMO-CFG-015` before `CreateTable`. |
| PK types | Float/Double primary keys **refuse at parse**. They were already rejected by `KeyComponentEncoder` — but at *write* time, which is not preflight. Moved. |
| Source routing | Refused at parse (`RELADYNAMO-CFG-012`), as before. |
| GSI projections | `ALL` works; `KEYS_ONLY`/`INCLUDE` **refuse** at `TableCreator` (`RELADYNAMO-CFG-014`) — verified by the tables being **absent** after the throw, not merely by the exception type. |

The custom-temporal-name path was the one I expected to be silently half-working, and it was: the
parser emitted `validDateFrom`/`validDateTo` while the writer looked up `businessDateFrom`. It is now
derived from the parsed mapping. RED was captured for all three new behaviours first.

**Case 10 — PASS.** The "required" SPI set was derived by **running applications** — the classifier
and petstore bound-portal tests with H2 disconnected — not by reading the refusal list, which is the
method finding 24 established. Every method actually reached either works or refuses by name; the
methods never reached remain `notYet`. `docs/SUPPORT-CONTRACT.md` Tier 2 now quotes that split instead
of the undifferentiated "20 of 32 refuse".

`demos/02-petstore-unitemporal` **rebinds cleanly** — 22 entities, a different (unitemporal) director,
16 → 17 tests. A previous agent had predicted it would hit the same `refresh` wall; `refresh` landed
in between, and the prediction was checked rather than assumed.

### Final state of the thirteen

**10 pass**: 1, 3, 4, 5, 6, 7, 8, 9, 10, 12.
**3 blocked by declared scope**, not by effort:

- **2** — stale-replay safety. M-05; `docs/SUPPORT-CONTRACT.md` declares offline immutable-source
  migration for 0.1.0, so there is no online replay to make safe.
- **11** — H2 **and ASE** extraction. M-03; there is no ASE access in this environment, and inventing
  evidence for it is exactly what this project has spent two days removing.
- **13** — crash/restart, partial writes, cutover, rollback, reverse import. **Half done**:
  crash/restart (M-04) and partial writes (R-01) are covered and tested. Cutover, post-cutover
  rollback and reverse import are M-05/M-06, out of scope.

Reporting "10 of 13" without that breakdown would overstate it in exactly the way the inspection was
written to prevent.
