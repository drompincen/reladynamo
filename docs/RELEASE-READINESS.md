# Release readiness — 0.1.0

What this is, what the evidence proves, and what has to be true before anyone depends on it for
production data.

**Where the numbers come from.** Every figure below was worked out again on 2026-09-17 from the
source and the reports, not copied from other documents. The build was not re-run for this document.

- Gate figures: `reports/check-126.json`, iteration 126, 2026-09-16 08:17 MT. This is the last
  complete gate run.
- SPI classification: read from `reladynamo-ddb/src/main/java/io/reladynamo/ddb/persist/DynamoDbPersister.java`
  and checked against the interface declarations in `reladomo-18.1.0-sources.jar`.
- Acceptance cases and findings: `docs/INSPECTION-TRACKING.md` and `docs/CONFORMANCE-FINDINGS.md`.
  Where a fix is called present, it was checked in the current source.

## Evidence at a glance

| | Value | Source |
|---|---|---|
| Closed-loop gate | **8 of 8 pass**, 0 fail, 0 pending | `check-126.json` |
| Adapter tests: gate figure | **681**. This is a **floor**, not the total. | `adapter-build` gate. It adds up the surefire XML `tests` attributes, which undercount jqwik property classes. The caveat is recorded in `scripts/check.sh.txt`. |
| Adapter tests: reactor total | **689**: core 202, test-kit 15, ddb 469, spike 3 | Maven reactor summary recorded 2026-09-15 15:40 MT in `docs/INSPECTION-TRACKING.md` and quoted in `README.md`. Not re-run for this document. |
| Differential tests (`io.reladynamo.ddb.differential.*Test`) | **254**: 78 storage-path, 176 query-path | `check-126.json`. The split goes by class name, and neither half is exact. See `docs/COVERAGE-GAPS.md` §3. |
| Demo tests | **48**: CRM 18, pet store 17, classifier 13 | `check-126.json`, using the same surefire-XML counting |
| Persister SPI | **32 methods: 14 implemented, 17 refuse by name, 1 conditional** | [§ Persister SPI](#persister-spi) |
| Inspection acceptance cases | **10 of 13 pass**. The other 3 are blocked by declared scope. | [§ Acceptance cases](#acceptance-cases) |
| Conformance findings | **33 recorded**. None is an open adapter defect. | [§ Conformance findings](#conformance-findings) |
| Java 11 | Bytecode floor enforced. Execution proven for `reladynamo-core` only. | `java11-floor` gate; [§ Not proven](#what-is-not-proven--read-this-before-adopting) |
| Licence scope | Pass | `licence-scope` gate; `bannedDependencies` enforcer rule in `pom.xml` |
| Main source | 81 top-level source files: core 41, ddb 37 (29 public), test-kit 3 | `*/src/main/java` |
| Real AWS endpoint | **None** | |
| Load test | **None** | |

## Persister SPI

`DynamoDbPersister` implements `MithraObjectReader` and `MithraDatedObjectPersister`. In Reladomo
18.1.0 those interfaces declare **32 methods** with no default methods: 12 on `MithraObjectReader`,
18 on `MithraObjectPersister` (which extends it) and 2 on `MithraDatedObjectPersister`. The class
overrides all 32. Overloads are counted separately below.

### Implemented (14)

| Method | What it does, and what it can still refuse |
|---|---|
| `insert` | Conditional put (`attribute_not_exists(pk)`). A duplicate `pk+sk` raises `MithraUniqueIndexViolationException`. |
| `delete`, `purge` | Deletes only if the stored item still matches the row. If the item is missing or its payload has changed, it raises `MithraOptimisticLockException`. `purge` is the same delete. |
| `batchInsert`, `batchDelete`, `batchPurge` | Sends one conditional insert or delete per row, because `BatchWriteItem` cannot carry conditions. `batchPurge` issues the same deletes as `batchDelete`. |
| `batchDeleteQuietly` | Deletes one row at a time and ignores a `MithraOptimisticLockException` on that row |
| `update(object, wrapper)`, `update(object, List)` | Applies the `AttributeUpdateWrapper`s to the current data, then replaces the item only if the stored row still matches the committed prior. With no prior to lock against it raises `MithraOptimisticLockException`. |
| `find` | Plans the query, executes it, and returns objects through the portal cache. It refuses in four cases. (1) The instance was built write-only: `UnsupportedOperationException`. (2) A dated object's operation has no as-of on every axis: `UnsupportedOperationException`. (3) The current transaction has staged writes: `RELADYNAMO-TXN-006`. (4) The planner refuses, for example a Scan when `PlannerConfig.allowTableScan` is off. |
| `count` | Executes the plan and counts the rows. It does not use DynamoDB's `Select.COUNT`, which would ignore residual predicates. It refuses in the same cases as `find`. |
| `refresh` | Strongly consistent `GetItem` on the derived `pk+sk`. Returns `null` when the item is gone. Ignores `lockInDatabase`. Refuses with `TXN-006` while writes are staged. |
| `refreshDatedObject` | Addresses the rectangle already on the object's current data, then delegates to `refresh` |
| `enrollDatedObject` | Returns the object's current data from cache without a database round trip. Refuses with `TXN-006` while writes are staged. |

### Refuse by name (17)

Each of these always throws `UnsupportedOperationException("DynamoDbPersister.<method> is not implemented yet for <class>…")`:

`findCursor` · `computeFunction` · `findAggregatedData` · `loadFullCache` · `reloadFullCache` ·
`renewCacheForOperation` · `extractDatabaseIdentifiers(Operation)` · `extractDatabaseIdentifiers(Set)` ·
`findForMassDelete` · `deleteUsingOperation` · `deleteBatchUsingOperation` · `batchUpdate` ·
`multiUpdate` · `prepareForMassDelete` · `prepareForMassPurge(Operation, boolean)` ·
`prepareForMassPurge(List)` · `getForDateRange`

### Conditional (1)

`setTxParticipationMode` does nothing for a `null` mode. Any non-null mode throws
`DynamoDbTransactionException` with `RELADYNAMO-TXN-007`, because no pessimistic lock or optimistic
version-check mode is implemented.

`AcceptanceRequiredSpiTest` classifies by method *name* rather than by overload. It counts
`setTxParticipationMode` as implemented, which gives 14 implemented names and 15 refusal names. That
is the same 32 methods counted a different way.

## Acceptance cases

These are the 13 cases from `docs/INSPECTION-2026-09-14.md`. Their state comes from
`docs/INSPECTION-TRACKING.md` ("Final state of the thirteen"), and each test class named below exists
in the tree.

| # | Case (abridged) | State | Evidence |
|---|---|---|---|
| 1 | Verification covers every PK component and mapped value, and compares binary by content | **Pass** | `MappedRowSetDifferTest` (core) |
| 2 | A stale migration retry cannot overwrite newer state or resurrect a purge | Out of scope | M-05: only offline, immutable-source migration is supported |
| 3 | A failed, already-flushed transaction leaves no partial durable result | **Pass** | `AcceptanceFailedTransactionTest` |
| 4 | Duplicate insert and stale update follow an explicit conflict contract | **Pass** | `AcceptanceConflictContractTest`: two `DynamoDbClient`s in one JVM, sharing one Local table |
| 5 | Complete-key queries with failing filters return no row and count zero | **Pass** | `AcceptanceCompleteKeyFilterTest` |
| 6 | Numeric, null and string predicates work against codec-written items | **Pass** | `FinderMatrixTypeOperatorCasesTest` |
| 7 | OR branches keep their bindings, deduplicate, and preserve ordered top-N | **Pass** | `AcceptanceOrFanOutFinderTest` |
| 8 | Limits bound Query, Scan, PartiQL and intermediate memory | **Pass** | `PaginationSafeguardFinderTest` |
| 9 | Custom temporal names, PK types, source routing and GSI projections work end to end or fail preflight | **Pass** | `AcceptanceMappingContractTest` |
| 10 | Required SPI methods work with cold caches and the source disconnected | **Pass** | `AcceptanceRequiredSpiTest`, `ClassifierBoundPortalTest`, `PetstoreBoundPortalTest` |
| 11 | H2 and ASE extraction copy every version | Out of scope | M-03: there is no ASE access |
| 12 | One original business script runs independently against each backend | **Pass** | `ClassifierBoundPortalTest` (demo 03) |
| 13 | Crash/restart, partial writes, cutover, rollback, reverse import | Partly out of scope | Crash/restart (M-04, `BackfillRestartableTest`) and partial writes (R-01) are covered. Cutover, rollback and reverse import (M-05, M-06) are out of scope. |

"10 of 13" is only an honest figure together with this breakdown.

## Conformance findings

`docs/CONFORMANCE-FINDINGS.md` records **33** findings. Its index table covers only 1–19; the state of
20–33 is in each finding's own section.

- **1–11**: harness bugs or wrong premises about Reladomo, all resolved.
- **Adapter defects, fixed**: 12, 14, 15, 16, 18, 20, 21, 22, 23, 25, 26, 27, 29, 32 and 33. The fixes
  for 22, 23, 25, 26, 27, 29 and 33 were checked in the current source:
  - `RowOrderComparator` compares `BigDecimal` with `compareTo`.
  - `ItemCodec` rejects a schema version above `MAX_SUPPORTED_SCHEMA_VERSION`.
  - `dataOf` reads `zGetTxDataForRead()`.
  - `find` caches under `op.getOriginalOperation()`.
  - `notEq` excludes stored NULL through `attribute_type`.
  - `axisOperationsOf` accepts any `AsOfOperation`, so `equalsEdgePoint` materialises.
  - PartiQL requests go through `requestLimit`.
- **13**: fixed at the root. Infinity is resolved from the generated `AsOfAttribute`.
- **17**: guarded. The options are refused rather than implemented.
- **19**: became the `spec-drift` gate.
- **24**: `refresh` was missing. Closed by implementing `refresh` and `refreshDatedObject`.
- **Reladomo 18.1.0, not the adapter**: 28 (`ByteArrayAttribute.notEq` throws; the matrix case expects
  that refusal) and 30 (`ByteArrayOrderBy` crashes on an empty `byte[]`).
- **31, a permanent divergence**: Reladomo sorts binary values as signed bytes and DynamoDB as
  unsigned. The adapter follows DynamoDB, and `UnsignedByteArrayOrderBy` makes the H2 side of the
  comparison sort the same way.

## What is proven

- **The seam.** A single `setMithraObjectReader` call binds both read and write
  (`WritePathSpikeTest`). Bound-portal tests drive real Reladomo transactions through it.
- **Bitemporal semantics are inherited, not reimplemented.**
  - The storage-fidelity suites compare H2-computed histories with the DynamoDB copy on all four
    temporal boundaries.
  - `BoundWritePathTest` compares the version set left by a bound update with H2's.
  - `ClassifierBoundPortalTest` binds all five demo-03 portals, runs the demo's unchanged business
    operations, and compares the histories.
- **The query path.** The finder matrix, the finder-driven tests and the acceptance tests run
  generated finders against DynamoDB Local and compare the results with H2. They cover type ×
  operator, shapes, temporal cases including `equalsEdgePoint`, and a cold-cache check that fails if
  the cache answers instead of the adapter.
- **Transaction boundaries are enforced, not hidden.**
  - One Reladomo transaction commits as one `TransactWriteItems`.
  - More than 100 actions is refused with `RELADYNAMO-TXN-001`, and more than 4 MB with `TXN-002`.
    Neither is split into chunks (`DynamoDbTransactionCoordinator`).
  - A failed flushed transaction leaves no partial result, and conflicts surface as the Mithra
    exception types (cases 3 and 4).
- **Refusal instead of plausible wrong answers.**
  - 17 SPI methods refuse by name.
  - Scan is opt-in.
  - `maxPages` and the in-memory row ceiling raise `RELADYNAMO-PLAN-006` and `PLAN-007` rather than
    returning a partial result (`PaginationSafeguardFinderTest`).
- **Deep fetch without N+1.** `RelationshipDifferentialTest` fetches 3 children for each of 8 parents
  through a foreign-key GSI with an `ALL` projection. It asserts fewer reads than parents; a comment in
  the test records one query measured.
- **The MIT claim.** An enforcer rule stops DynamoDB Local, sqlite4java and H2 from reaching compile
  or runtime scope.
- **Java 11 bytecode.** Every adapter class is class-file major 55 or lower (gate).

## What is NOT proven — read this before adopting

- **No live AWS.** Every test uses DynamoDB Local. Throttling, adaptive capacity, GSI propagation
  delay, IAM and real network failures are untested. This is the largest gap.
- **No load.** No test is sized to exercise volume. Pagination has been exercised only with
  deliberately tiny `pageSize`/`maxPages` values on small fixtures.
- **Java 11 execution of the DynamoDB modules.**
  - `reladynamo-core` ran 110 tests on Temurin 11 on 2026-09-13 (`docs/JAVA11-VERIFICATION.md`).
    The core module has grown to 202 tests since, and that run has not been repeated.
  - `DynamoDBLocal-2.5.3.jar` is class-file major 61 (Java 17); `DynamoDBEmbedded` and `ServerRunner`
    were sampled. The `reladynamo-ddb` and test-kit tests start it inside the test JVM, so they cannot
    run on a Java 11 VM as the build is set up.
  - `.github/workflows/build.yml` runs `mvn -B clean test` on JDK 11 across the whole reactor. No CI
    result is recorded in this repository.
  - "Java 11+" for the DynamoDB modules therefore rests on bytecode level and API surface only.
- **Concurrency.** No test in `reladynamo-ddb` or the demos starts a second thread or process. The
  conflict contract is tested with two clients in one JVM.
- **17 of the 32 SPI methods refuse.** Applications that use cursors, aggregates, full cache,
  operation-based mass delete or purge, batch or multi update, or date-range reads will stop with a
  named refusal. See `docs/COVERAGE-GAPS.md` §1.
- **Application coverage.**
  - Demo 03 (classifier) binds all 5 entities.
  - Demo 02 (pet store, about 22 entities) binds only `Product`.
  - Demo 01 (CRM, about 46 entities) copies rows through `DynamoDbWriter` and never binds the adapter.
- **Transactions are not equivalent, and cannot be.** DynamoDB caps a transaction at 100 actions and
  4 MB, has no interactive transaction, and keeps GSIs only eventually consistent. These limits are
  permanent.
- **Reviews predate later code.**
  - `docs/SECURITY-REVIEW.md` is dated 2026-09-13, before the transaction coordinator and the
    PartiQL limit fix landed (2026-09-15).
  - `docs/ASSUMPTION-CHALLENGE.md` raises C-01 to C-20. C-09 and C-11 became findings 22 and 23, which
    are fixed. No triage outcome is recorded for the other eighteen.
- **Out of scope for 0.1.0**: online migration, reverse migration, a relational source extractor, and
  Sybase ASE.

## API stability review

### The surface a user touches

This is derived from the `README.md` "Usage" and "Migrating" sections and from how every
read-capable test and demo wires the adapter (`ClassifierBoundPortalTest`, `PetstoreBoundPortalTest`).

**Intended public API: wiring**

| Type | Module | Role |
|---|---|---|
| `MithraObjectXmlParser` | core | `parse(String)` / `parse(Path)` → `EntityMapping` |
| `EntityMapping` | core | The parsed mapping, passed to every component below |
| `PhysicalDesign` (+ `builder(mapping)`, `infinityFrom(finder)`, `addGsi`, `build`) | core | Table, key and GSI design |
| `GsiSpec` | core | Declared GSIs. Only `ALL` projection works; `KEYS_ONLY` and `INCLUDE` are refused with `CFG-014`. |
| `PlannerConfig` (+ builder) | core | Safeguards: scan opt-in, page and memory limits |
| `QueryPlanner` | core | Constructed and passed to the persister |
| `DefaultKeyStrategy` | core | Write-key derivation; see finding 3 below |
| `ItemCodec`, `DynamoDbWriter`, `QueryPlanExecutor` | ddb | Collaborators for the persister |
| `DynamoDbPersister` (7-argument constructor) | ddb | Bound with `setMithraObjectReader` |
| `TableCreator` (+ `Options`, `SchemaReconcileResult`, `SchemaReconcileOutcome`) | ddb | Table creation and reconciliation |
| `Backfill`, `BackfillConfig`, `BackfillResult`, `BackfillCheckpointStore`, `FileBackfillCheckpointStore`, `WriteRateLimiter`, `MappedRowSetDiffer` | ddb / core | Offline migration and verification |
| `LocalDynamoDb` | test-kit | Test scope only |

**Intended public API: exceptions a caller may catch.** `ReladynamoConfigException`;
`ReladynamoUnplannableOperationException` and its subclasses `ReladynamoScanRequiredException` and
`ReladynamoResidualEvaluationException`; `DynamoDbTransactionException` and
`DynamoDbCommitOutcomeUnknownException`; `PageLimitExceededException`; `CodecException`,
`ItemTooLargeException` and `UnsupportedSchemaVersionException`; `UnprocessedWritesException`;
`SchemaReconcileException`; `TableCreateTimeoutException`.

**Contracts that are not Java types, and bind just as hard.**
- **The stored item format.** `pk`/`sk` use the `v1#…` layout, and `_rd_v` is 1
  (`ItemCodec.CURRENT_SCHEMA_VERSION`, with decode range-checked to `[1, 1]`). Breaking this strands
  stored data, where a signature change only breaks a compile. No second version exists, so upgrading
  from one version to the next is untested.
- **The `RELADYNAMO-<AREA>-nnn` error codes** (`CFG`, `PLAN`, `RESIDUAL`, `TXN`). The `spec-drift`
  gate ties the documented codes to the implementation.

**Public but internal.** These are public only because another package or module calls them:
- core `bridge.*`
- core `config.AttributeMapping` and `TemporalMapping`
- core `key.KeyComponentEncoder`
- core `mapping.MappingValidator` and `TemporalAttributeNames`
- core `temporal.TemporalEncoder`
- core `plan.*` apart from the types listed above, including `plan.eval.QueryPlanInterpreter` (a test
  oracle) and `plan.reladomo.ReladomoOperationAccess`
- ddb `persist.DynamoDbTransactionCoordinator` and `PhysicalWrite`
- ddb `write.BatchWriter` and `BatchWriteClient`
- ddb `codec.ExpressionNames`

`ExplainPlan` and `ExecutionExplain` are diagnostic output: public, with no stability promise.

### Findings of the review

1. **Nothing in the code marks the boundary.** There is no `module-info.java`, no `package-info.java`
   and no `internal` package. All 41 core classes and 29 of the 37 ddb classes are `public`, so a
   consumer cannot tell from the jar what is supported.
2. **The README "Usage" snippet builds a write-only persister.** The 3-argument `DynamoDbPersister`
   constructor leaves out the planner, executor, design and config, so `find` and `count` throw
   `UnsupportedOperationException`. Every read-capable wiring in the repository uses the 7-argument
   constructor, and `src/main` has no bootstrap or factory type that assembles it.
3. **`KeyStrategy` is a public interface, but the read path never calls it.** `QueryPlanner` derives
   partition keys through `PartitionKeyEncoder`, and the executor uses
   `DefaultKeyStrategy.NON_DATED_SORT_KEY`. A custom `KeyStrategy` writes keys that reads cannot find.
   For 0.1.0, `DefaultKeyStrategy` is the only supported implementation.
4. **The refusal message is out of date.** `notYet(...)` still says "The read path lands with
   QueryPlanExecutor". The read path has landed; the refusal is right, but the explanation is not.

### What 0.x versioning promises

Semantic Versioning 2.0.0, item 4: *major version zero is for initial development; anything may
change at any time; the public API should not be considered stable.* **0.1.0 by itself promises
nothing.**

Proposed project convention. **This is not in force until the repository owner adopts it.**
- `0.1.z` patch releases do not break the intended public API above, change the stored item format,
  or change what an existing error code means.
- A `0.y` minor release may break the Java API, and lists every break in its release notes.
- Any change to the stored format bumps `_rd_v`.

### `japicmp`: deliberately deferred

`japicmp` compares a build against a published baseline, and there is no published baseline until
0.1.0 exists. Adding it now would give a plugin that always passes, which is worse than no check
because it looks like one. It becomes meaningful for the first release after 0.1.0.

## Publish checklist for 0.1.0

**Already true**

- [x] Gate green, 8 of 8: `reports/check-126.json`
- [x] Licence: MIT `LICENSE`, `<licenses>` in `pom.xml`, `THIRD-PARTY-NOTICES.md`, and the
      `licence-scope` enforcer rule with its gate passing
- [x] Java 11 bytecode floor: `maven.compiler.release=11` in `pom.xml`, and the `java11-floor` gate
      passes
- [x] Every persister SPI method works or refuses by name: `DynamoDbPersister.java`,
      `AcceptanceRequiredSpiTest`
- [x] Acceptance cases: 10 of 13 pass, and the remaining 3 are declared scope ([table](#acceptance-cases))
- [x] Query path compared with H2 through generated finders: `FinderMatrix*CasesTest`,
      `FinderDrivenDifferentialTest`, and 176 query-path tests in `check-126.json`
- [x] Relationships and deep fetch compared with H2: `RelationshipDifferentialTest`
- [x] No open adapter defect among findings 1–33: `docs/CONFORMANCE-FINDINGS.md`, with source checks
      listed [above](#conformance-findings)
- [x] Security review exists: `docs/SECURITY-REVIEW.md` (see the re-run item below)
- [x] Per-row hot path measured: `docs/PERFORMANCE.md` (indicative only; the error bars are larger than
      the scores)
- [x] CI workflow defined for JDK 11/17/21, the gate and the demos: `.github/workflows/build.yml`. This
      is the definition only; see the CI item below.

**Needs the repository owner's decision or credentials**

- [ ] **Real-AWS differential run in a throwaway account.** Needs an AWS account and credentials.
      Nothing in this repository has touched a real endpoint.
- [ ] **Artifact coordinates and publishing target.**
  - `pom.xml` has `io.reladynamo` at `0.1.0-SNAPSHOT`.
  - Which modules to publish is open; `README.md` marks spike and bench as internal.
  - `pom.xml` has no `<url>`, `<scm>`, `<developers>` or `<distributionManagement>`, and no
    source-jar, javadoc-jar or signing plugin. Maven Central requires these if it is the target.
  - Needs the owner's decision and publishing credentials.
- [ ] **Tag `0.1.0`.** No git tags exist. Set the version to `0.1.0` on the tagged commit. This is an
      owner action.
- [ ] **Adopt the 0.x versioning convention** proposed above. Owner decision.

**Remaining work before tagging**

- [ ] Load test large enough to page, against DynamoDB Local
- [ ] Resolve the Java 11 claim for the DynamoDB modules against the DynamoDB Local 2.5.3 finding above,
      then get a green CI run on the commit to be tagged. No CI result is recorded in this repository.
- [ ] Make `README.md` "Usage" show the read-capable 7-argument wiring
- [ ] Bring `docs/SUPPORT-CONTRACT.md` up to date with the source:
  - Tier 1 still says the 400 KB check runs before keys are appended (R-13).
  - Tier 2 still lists R-01, R-02 and M-04 as open and says "20 of 32 persister SPI methods refuse".
  - Tier 4 still describes R-10's unstamped GSI keys.
  - "What verified means" quotes "56 storage-path, 6 query-path".
  - `docs/INSPECTION-TRACKING.md` says Tier 2 was rewritten to quote the case-10 split. The file does
    not contain it.
- [ ] Mark the internal API boundary (review finding 1), or at least document it in `README.md`
- [ ] Record a triage outcome for `docs/ASSUMPTION-CHALLENGE.md` C-01 to C-20, other than C-09 and C-11
- [ ] Re-run the security review against the tree being tagged

**After 0.1.0**

- [ ] Set the `japicmp` baseline against the published 0.1.0

## The honest summary

The architecture is proven. The semantics are checked against a reference implementation, and every
SPI method either works or refuses by name. But no test has yet touched a real AWS endpoint or run
under load, and 17 of the 32 persister SPI methods refuse rather than work.

**Suitable for evaluation, prototyping and design review. Not for production data.**

Check `docs/COVERAGE-GAPS.md` before assuming any given operation is supported.
