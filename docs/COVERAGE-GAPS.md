# What the test suite does not cover

The gate reports what passes. This document lists what was never tested. Before claiming the adapter
handles something, look for it here. If it is listed as uncovered, then either the claim is wrong or
this document is out of date, and both are worth knowing.

**Where this comes from.** Worked out again on 2026-09-17 from:
- `reports/check-126.json`
- `DynamoDbPersister.java`
- the test sources under `reladynamo-ddb/src/test/java/io/reladynamo/ddb/differential/` and the demos
- `scripts/check.sh.txt`

A number in parentheses after a test class is the count of `@Test` / `@ParameterizedTest` methods
declared in its source. It is **not** a run count: parameterized classes run more cases than they
declare. Run counts come only from the gate report.

## 1. The persister SPI: 17 of 32 methods refuse

`DynamoDbPersister` overrides all 32 methods that Reladomo 18.1.0 declares on `MithraObjectReader`,
`MithraObjectPersister` and `MithraDatedObjectPersister`. 14 are implemented, 17 always refuse, and 1
is conditional. The full classification is in `docs/RELEASE-READINESS.md` § Persister SPI.

Every refusal is an `UnsupportedOperationException` that names the method and the entity class.

| Refused method(s) | Feature family (from the method name; not traced through Reladomo's call sites) |
|---|---|
| `findCursor` | Iterating a result through a cursor |
| `computeFunction` | Computed SQL-expression functions |
| `findAggregatedData` | Aggregate lists (group-by, having) |
| `loadFullCache`, `reloadFullCache`, `renewCacheForOperation` | Full-cache portals and cache renewal |
| `extractDatabaseIdentifiers(Operation)`, `extractDatabaseIdentifiers(Set)` | Resolving database identifiers. Source routing itself is refused at parse (`RELADYNAMO-CFG-012`). |
| `findForMassDelete`, `deleteUsingOperation`, `deleteBatchUsingOperation`, `prepareForMassDelete`, `prepareForMassPurge` (×2) | Delete and purge driven by an operation rather than by objects |
| `batchUpdate`, `multiUpdate` | Batched and multi-row updates |
| `getForDateRange` | Date-range reads of dated objects. The comment on `enrollDatedObject` names it as the path for objects that are not in cache. |

`setTxParticipationMode` also refuses any non-null mode, with `RELADYNAMO-TXN-007`. There is no
pessimistic lock and no alternative participation mode.

**What is known.**
- `AcceptanceRequiredSpiTest` binds `DiffBalance` with cold caches and H2 disconnected. It asserts that
  the methods reached include `insert`, `find`, `update`, `enrollDatedObject` and `count`, and that
  none of the reached methods is a refusal.
- `ClassifierBoundPortalTest` (demo 03) and `PetstoreBoundPortalTest` (demo 02) run real demo
  operations through bound portals.

**What is not known.** Which application calls reach the 17 refusals. No test drives a cursor, an
aggregate, a full cache, an operation-based delete or a batch update through a bound portal, so an
application's first contact with any of them will be in production code. A refusal stops the
operation; it does not degrade gracefully.

**Full cache in particular.** The only runtime configurations in the repository that declare
`cacheType="full"` belong to demo 01 (CRM). That demo never binds the adapter, so a full-cache portal
has never started against it.

## 2. Coverage by area

| Area | Covered by | Not covered |
|---|---|---|
| Storage fidelity: H2 computes the history, the rows are copied into DynamoDB, and the copy is compared | `BitemporalDifferentialTest` (3), `BitemporalOperationMatrixTest` (14), `BitemporalEdgeCaseDifferentialTest` (16), `AuditOnlyDifferentialTest` (13) | These say nothing about whether the adapter itself performs the mutation the way H2 does |
| Generated-finder queries compared with H2 | `FinderMatrixTypeOperatorCasesTest` (9, parameterized), `FinderMatrixShapeCasesTest` (22), `FinderMatrixTemporalCasesTest` (16, parameterized), `FinderMatrixMinimumCasesTest` (7), `FinderDrivenDifferentialTest` (6), `AcceptanceCompleteKeyFilterTest` (2), `AcceptanceOrFanOutFinderTest` (3) | Only the fixture axes chosen in `docs/FINDER-MATRIX.md` §7. The matrix is deliberately not a full cross-product of every axis. |
| Query-path regressions without an H2 run | `GetItemFilterFinderTest` (3), `NullPredicateDynamoDbTest` (6), `NumericPredicateFinderTest` (10), `ResidualEvaluationTest` (5), `FindPathTest` (2) | |
| Writes through a bound portal | `BoundWritePathTest` (5), `BoundDurableTransactionTest` (3), `AcceptanceFailedTransactionTest` (2) | |
| Conflict contract | `AcceptanceConflictContractTest` (2), with two `DynamoDbClient`s in one JVM sharing one Local table. `WriterConcurrencyTest` covers the non-transactional writer. | **No test in `reladynamo-ddb` or the demos starts a second thread or process.** Real contention is untested. |
| `refresh`, `refreshDatedObject` | `RefreshTest` (7), including the refusal while writes are staged (`TXN-006`) | Pessimistic locking, which does not exist |
| Relationships and deep fetch | `RelationshipDifferentialTest` (3): 8 parents × 3 children through a foreign-key GSI with `ALL` projection. It asserts fewer reads than parents. | Other relationship shapes. `KEYS_ONLY` and `INCLUDE` projections are refused (`CFG-014`). GSI propagation delay cannot be tested on DynamoDB Local. |
| Limits and pagination | `PaginationSafeguardFinderTest` (8): `maxPages`, `pageSize` and `inMemoryRowCeiling` on Query, Scan, PartiQL, `count` and in-memory ordering | Only deliberately tiny page sizes on small fixtures. **No test is sized for volume.** |
| Cold-cache harness | `FinderMatrixColdCacheTest` (2) shows the harness catches a query answered from the cache | Cache eviction and renewal. Full cache refuses (§1). |
| Mapping preflight | `AcceptanceMappingContractTest` (7): custom temporal names, string and composite keys, float keys, source routing, GSI projections | Inherited metadata, and XML-declared relationships or indexes driving the physical design |
| Required SPI from a real run | `AcceptanceRequiredSpiTest` (1) | The 17 refusals (§1) |
| Real application scripts | `ClassifierBoundPortalTest` (demo 03, all five portals bound); `PetstoreBoundPortalTest` (demo 02, `Product` only: insert, as-of find, same-segment correction) | Demo 01 (CRM, about 46 entities) copies rows through `DynamoDbWriter` and never binds the adapter. The other pet-store entities are not bound. |
| Aggregation, cursors, operation-based delete, batch and multi update, date-range reads | Nothing | They refuse (§1) |
| Real AWS behaviour | Nothing | Throttling, adaptive capacity, GSI lag, IAM, real network failure |
| Load | Nothing yet | [ ] Load test large enough to page, against DynamoDB Local |
| Java 11 execution | `reladynamo-core`: 110 tests on Temurin 11, 2026-09-13 (`docs/JAVA11-VERIFICATION.md`) | Core has grown to 202 tests since then. The DynamoDB modules cannot run their tests on a Java 11 VM, because `DynamoDBLocal-2.5.3.jar` is Java 17 bytecode (class-file major 61, sampled). No CI result is recorded in the repository. |
| Migration | `BackfillRestartableTest` and the other `migrate` tests; `MappedRowSetDifferTest` | Online migration and CDC, cutover, reverse migration, Sybase ASE. All are declared out of scope for 0.1.0. |

## 3. How to read the differential numbers

The gate's last run reports **78 storage-path and 176 query-path** tests (254 in all) in
`io.reladynamo.ddb.differential`.

**How the gate splits them.** `scripts/check.sh.txt` sums surefire XML per class. A class counts as
query-path if its file name contains one of `FinderDriven`, `FinderMatrix`, `Acceptance`,
`BoundWritePath`, `NullPredicateDynamoDb`, `GetItemFilterFinder`, `PaginationSafeguardFinder`,
`RelationshipDifferential` or `RefreshTest`. Every other class counts as storage-path.

**Why neither number is exact.** Matching on file names puts some classes in the wrong bucket:

- **Storage-path (78) means "no name matched."** The declared methods add up to exactly 78:
  - the four storage-fidelity suites (46)
  - `BoundDurableTransactionTest` (3), which drives bound-portal transactions
  - `NumericPredicateFinderTest` (10), `ResidualEvaluationTest` (5) and `FindPathTest` (2)
  - harness and fixture self-tests in the `findermatrix` package, whose lowercase package name does
    not match `FinderMatrix`: `RequestAssertionsTest` (4), `DiffFinderValueGeneratedApiTest` (3) and
    `FinderValueStorageVerificationTest` (2)
  - `ByteArrayOrderByConformanceTest` (3), which tests Reladomo alone with no DynamoDB involved
- **Query-path (176) includes `AcceptanceMappingContractTest` (7)**, which tests mapping preflight
  rather than finder execution. Its classes declare 111 methods; the parameterized
  `FinderMatrixTemporalCasesTest` and `FinderMatrixTypeOperatorCasesTest` expand to the remaining
  runs.

The split is a useful signal, not a measurement. Do not quote either number as "N tests that execute
a query."

The distinction behind it still matters:
- **Storage-path** means H2 computed the history and the copy was compared. That proves the codec and
  the key layout.
- **Query-path** means DynamoDB answered through a generated finder and the answer was compared with
  H2. Only this kind of test shows the adapter executes a query the way H2 does.

## How to use this document

Look here before assuming an operation is supported. `docs/RELEASE-READINESS.md` holds the publish
checklist, which includes bringing the documents that still disagree with the source up to date.
