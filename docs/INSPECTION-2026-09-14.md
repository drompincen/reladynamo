**Reladynamo inspection — migration readiness and Reladomo compatibility**

Date: 2026-09-14. Role: inspector. No implementation fixes are included in this review.

**Assessment**

The repository contains a useful prototype of a Reladomo persistence adapter and a small backfill library. It does not yet implement the stated product goal: take an arbitrary existing H2/Sybase ASE Reladomo project through a reliable migration and continue running with equivalent behavior on DynamoDB.

The strongest work is the integration seam, typed serialization, temporal storage fixtures, explicit refusal of some unsupported operations, and the beginnings of real finder and relationship execution. The most consequential gaps are transaction durability, query correctness, trustworthy migration verification, and project-level migration orchestration. These are correctness and product-scope issues, not just production tuning.

The work should be reviewed as two connected tracks: **migration tooling** and **runtime ORM compatibility**. Neither track is complete, and a successful backfill would not establish that an application can safely switch its runtime.

**Scope and evidence**

- Inspected production code in `reladynamo-core`, `reladynamo-ddb`, and `reladynamo-test-kit`; representative tests, demos, design documents, project plan, readiness documents, and CI configuration.
- Ran `mvn -o test -Dstyle.color=never`. The run finished successfully at **2026-09-14 10:12:33 America/Denver** on the installed Windows JDK **23.0.2**. Maven reported core **124**, test-kit **8**, DynamoDB **159**, spike **3**: **294 tests, zero failures/errors/skips**. This was not a clean build or a new Java 11/17/21 matrix run. Standalone demos and live AWS were not run during this inspection.
- Most defects below are **source-confirmed implementation mismatches**, with concrete proposed reproductions. They are not claimed as newly executed failing tests. Additional diagnostic probes were prepared under `reladynamo-ddb/target/audit`, but their execution was interrupted; that directory was subsequently absent. No result is attributed to those probes.
- Claude is concurrently changing the repository. This is a dated inspection, not an immutable commit review. The local `.git` directory did not provide usable Git history. Source references and line numbers describe the inspected code and may move; recheck each finding against subsequent changes.
- Production code, plans, and existing status documents were not edited by this inspection. This report is the requested review artifact.

**What works, with the limits of the evidence**

| Area | Evidence | What the evidence supports |
|---|---|---|
| Portal binding | `PortalBindingSpikeTest`, `WritePathSpikeTest` | A public reader replacement can route both reader and persister calls into the adapter without regenerating domain classes. |
| Mapping for the demonstrated XML subset | Parser, mapping tests, demo corpus tests | Ordinary declared attributes, logical primary keys, and supported temporal flavors can be mapped. This does not cover the full Reladomo model language. |
| Value storage | Codec tests and jqwik properties | Strong attention to decimal scale, floating-point bit patterns, explicit nulls, temporal precision, and rejection of invalid values. Query translation does not yet consistently honor these encodings. |
| History storage | H2 snapshot differential tests | Supported H2-generated historical rows can be encoded, stored, read back, and compared. Most of these tests do not execute the temporal operation on a DynamoDB-bound portal. |
| Actual read path | Finder-driven and relationship tests | `find()` does materialize Reladomo objects through the cache; specific as-of queries work. The README statement that materialization is missing is stale. |
| Actual writes | Bound portal tests | Inserts and a limited update scenario reach DynamoDB through Reladomo. They do not establish rollback, concurrent update safety, or complete temporal parity. |
| Relationships | Three `RelationshipDifferentialTest` tests | A configured foreign-key GSI supports the demonstrated deep fetch. The run logged one read request for 24 children across eight parents, with no scan. |
| Batch writes | `BatchWriter`, tests | Chunks of 25, retry of unprocessed writes, jitter, and explicit exhaustion failure exist. Partial persistence is still possible. |
| Build discipline | Maven run, compiler release setting, CI workflow | The inspected suite passes. CI defines Java 11/17/21 and standalone demo jobs; this review did not verify their remote execution. |

The portal seam is a sound foundation to continue evaluating. Keeping Reladomo's temporal directors is preferable to duplicating their algorithms, but preserving a director's calculations does not by itself preserve database transaction semantics.

**Track A — migration tooling**

**M-01 — Blocker: backfill can certify a payload-corrupted copy as identical. Source-confirmed.**

[`Backfill.java`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/migrate/Backfill.java), `run()` lines 65–83 and `findMatching()` lines 114–130, checks only temporal boundaries. It never compares quantity, money, labels, foreign keys, binary data, or other payload attributes. For non-dated entities, the boundary list is empty, so any candidate in the partition satisfies the comparison. `BackfillResult.summary()` nevertheless reports that the copy was verified identical.

It also does not establish that the destination contains no extra rows. `rowsWritten` increments when a partition is nonempty, before a matching version is found; it is not an independently measured successful-write count.

Impact: a corrupt or incomplete migration can receive the success signal intended to authorize cutover. This is more serious than missing convenience tooling.

Review acceptance: modify a payload value while retaining all temporal boundaries; remove one version; introduce an extra version; repeat for a non-dated entity. Verification must distinguish each divergence and compare every mapped attribute with type-aware equality. Define the destination scope before deciding which preexisting rows are legitimate.

**M-02 — Blocker for composite models: backfill verification uses only the first PK attribute. Source-confirmed.**

[`Backfill.java`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/migrate/Backfill.java), lines 69 and 87–90, extracts the first primary-key component and builds a singleton key map. [`DefaultKeyStrategy.partitionKey()`](../reladynamo-core/src/main/java/io/reladynamo/core/key/DefaultKeyStrategy.java) requires every component.

For `(tenantId, accountId)`, the writer can persist the row, then verification fails because `accountId` is missing. The failure happens after writes, leaving an altered destination and an unsuccessful migration result.

Review acceptance: complete round trips and restart tests for composite PKs, including two tenants with the same account ID. Pass the full logical key throughout extraction, checkpointing, reading, and verification.

**M-03 — Major product gap: no project-level migration process or relational source adapter exists. Source-confirmed absence in the inspected production tree.**

The production migration package contains `Backfill` and `BackfillResult`. The entry point accepts `List<Map<String,Object>>`, already prepared by somebody else. There is no production H2/ASE extractor, project discovery command, class-list/runtime configuration reader, schema inventory, access-pattern compatibility report, or migration job runner.

The demos and test fixtures contain H2 bootstrapping and extraction, but they are not a reusable migration product. No Sybase ASE execution or extraction evidence was found.

Required scope: discover model XML plus generated classes and runtime settings; enumerate data sources and schemas; read all temporal versions; preserve source precision and null semantics; inspect the application's actual queries, cache modes, key generation, and transaction sizes; emit an explicit supported/unsupported report before copying data. H2 success cannot establish ASE compatibility for temporal types, source routing, collation, identity/sequence usage, or source snapshot behavior.

**M-04 — Major: backfill is not restartable or scalable as an operational job. Source-confirmed.**

`Backfill.run()` takes the entire source in memory. [`DynamoDbWriter.batchInsert()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/persist/DynamoDbWriter.java), lines 104–111, constructs another collection containing every encoded write before `BatchWriter` chunks it. Verification queries the whole partition once per source row. A key with V historical versions can therefore cause approximately V² versions to be reread for that key.

There is no durable checkpoint, stable source snapshot identifier, source watermark, per-chunk manifest, rate limit, progress persistence, or resumable error record. The current nonempty-source guard is useful against a bad query, but needs an explicit valid-empty-table outcome in a project-wide migration.

Review acceptance: bounded memory and read amplification on a large history; kill and resume after a partial chunk; source snapshot changes detected; intentionally empty tables represented accurately.

**M-05 — Blocker for online migration: idempotent keys do not make replay safe against newer writes. Source-confirmed mechanism; failure scenario requires a concurrency test.**

Backfill uses unconditional puts. A stale source row replayed after a newer destination update can overwrite the newer payload or restore old temporal end boundaries. The same issue applies if a delayed backfill races change capture. Stable keys avoid duplicate items; they do not resolve ordering or ownership of writes.

There is no implemented CDC, ordered event application, cutover barrier, dual-write coordinator, or conflict/version policy. The instructions in [`MIGRATION.md`](MIGRATION.md), stage 3, are a suggested workflow rather than an implemented guarantee.

Review acceptance: explicitly choose an offline immutable-source migration or an online protocol. For online migration, prove snapshot-plus-log convergence, ordered/idempotent event application, delete and purge handling, and stale-event rejection. A retry must not resurrect a closed or deleted version.

**M-06 — Major: reverse migration is claimed but not implemented. Source-confirmed.**

[`MIGRATION.md`](MIGRATION.md), stage 5, says `Backfill` runs in either direction depending on the supplied rows. Its destination is always `DynamoDbWriter`, and its verification client is always DynamoDB. Supplying different rows cannot turn it into a relational importer.

Review acceptance: either provide an actual DDB-to-H2/ASE sink preserving temporal history and transaction boundaries, or label reverse migration as unimplemented. Rehearse rollback after destination-only writes, not just before cutover.

**M-07 — Major: physical-design application is not reconciliation. Source-confirmed.**

[`TableCreator.create()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/exec/TableCreator.java), lines 70–85, returns when the existing table and its existing indexes are active. It does not check that the requested key schema or requested GSIs exist and match. An active table lacking the requested FK GSI can be accepted as ready.

There is also no versioned schema/index migration executor. `_rd_v` is written and parsed by the codec, but decode does not enforce a supported-version range or dispatch version transformations.

Review acceptance: detect incompatible existing tables before use; distinguish create, validate, add-index, backfill-index-key, and incompatible key-format migration. Treat requested-versus-actual design as a checked invariant.

**M-08 — Major: the shared differ is not a generic identity/value oracle. Source-confirmed.**

[`TemporalRowSetDiffer.java`](../reladynamo-test-kit/src/main/java/io/reladynamo/testkit/diff/TemporalRowSetDiffer.java), lines 104–137, infers identity from names ending in `Id` and hard-coded temporal names, in map iteration order. It does not use `EntityMapping.primaryKeyAttributes()`. A foreign key can become identity; a real PK called `code` can be omitted. If only temporal fields qualify, distinct records with equal boundaries can collide. Differently ordered maps can produce different identity strings.

`valuesEqual()` falls back to `Object.equals()`, so separate `byte[]` instances with identical contents are unequal. This compromises its use for arbitrary models even after Backfill starts using it.

Review acceptance: mapping-driven deterministic identity, explicit duplicate/multiset policy, content-based binary equality, and tests for custom PK names and map insertion order. Retain its useful exact temporal and numeric-type comparisons.

**Track B — DynamoDB ORM behavior and Reladomo parity**

**R-01 — Blocker: no durable Reladomo transaction integration, even below 100 items. Source-confirmed.**

[`DynamoDbWriter.java`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/persist/DynamoDbWriter.java), lines 89–121, immediately issues `PutItem`, `DeleteItem`, or `BatchWriteItem`. No production `TransactWriteItems` call or transaction coordinator was found. [`DynamoDbPersister.setTxParticipationMode()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/persist/DynamoDbPersister.java), lines 313–317, does nothing.

Once buffered Reladomo operations reach this persister, their effects are durable independently of the final logical transaction outcome. A temporal correction requiring several physical changes can partially persist if a later operation fails. Successful-path temporal tests do not establish rollback or atomic visibility.

The documentation frames transactions primarily as a 100-item/4-MB limit. That understates the current implementation gap. DynamoDB supports atomic writes across tables within those bounds, while `BatchWriteItem` is not atomic as a whole. See [AWS transaction API](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html) and [AWS batch-write API](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html).

Review acceptance: flush then throw; failure between closing an old rectangle and inserting its replacement; cross-entity commit; timeout with ambiguous commit outcome; over-limit transaction rejected before any durable mutation. A coordinator also needs a defined read/isolation policy and coalescing of multiple actions on the same physical item. Chunking a large logical transaction does not preserve its atomicity.

**R-02 — Blocker: inserts, updates, and deletes lack concurrency conditions. Source-confirmed.**

The same writer methods issue unconditional requests. [`DynamoDbPersister.update()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/persist/DynamoDbPersister.java), lines 138–145, rewrites the complete current data row rather than applying checked attribute changes. No existence, expected-version, or optimistic-lock condition is attached.


Impact: a duplicate insert can overwrite an existing item; a stale whole-row update can erase another writer's changes; a stale temporal close can conflict with another correction without detection. Retaining Reladomo's in-process cache/transaction machinery does not coordinate independent JVMs.

Review acceptance: two independent clients/processes update the same row, duplicate PK insert with a cold cache, stale delete, overlapping temporal corrections, and failure/retry. Distinguish migration upsert semantics from ORM insert/update semantics and translate conflicts into the appropriate Reladomo exception behavior.

**R-03 — Blocker: point reads discard planned filters. Source-confirmed.**

[`QueryPlanner.baseTablePlan()`](../reladynamo-core/src/main/java/io/reladynamo/core/plan/QueryPlanner.java), around lines 220–290, creates `GET_ITEM` plans for non-dated complete keys and exact dated rectangles while attaching payload/as-of filters. [`QueryPlanExecutor.getItem()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/exec/QueryPlanExecutor.java), lines 168–183, fetches the item and calls `accept()`. `accept()` checks only the residual predicate, not the plan's filter.

Example: for a non-dated object, `id.eq(7).and(status.eq("ACTIVE"))` can return row 7 even when its status is `CLOSED`. Exact-rectangle as-of containment can similarly be dropped. This also affects `count()`.

`GetItem` has no `FilterExpression` parameter; the adapter must evaluate remaining predicates locally or choose an appropriate alternative request. See [AWS GetItem API](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_GetItem.html).

Review acceptance: a complete PK plus a failing payload condition returns no object and count zero; repeat for exact dated keys and failing as-of containment.

**R-04 — Blocker: numeric predicates disagree with the codec's wire representation. Source-confirmed.**

[`QueryPlanner.toValue()`](../reladynamo-core/src/main/java/io/reladynamo/core/plan/QueryPlanner.java), lines 860–879, encodes all Java numbers as DynamoDB `N`. [`AttributeValueCodec`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/codec/AttributeValueCodec.java), lines 37–40 and 60–61, stores float/double as binary IEEE-754 and BigDecimal as a string.

Consequently, a correctly stored double or decimal can fail an equality/IN/range filter generated for the same Java value. Decimal string ordering is not numeric ordering; raw IEEE bits also cannot simply be substituted into ordered comparisons. Using the correct type for equality alone will not fix range semantics.

Review acceptance: codec-written float, double, and BigDecimal rows queried through generated finders; equalities, ranges, IN, negatives, zero, decimal scale differences, and special float values. Define a shared storage/query contract: queryable auxiliary encodings or safe typed residual evaluation where needed.

**R-05 — Blocker: null predicates are wrong, and the planner test oracle repeats the error. Source-confirmed.**

[`ItemCodec.encode()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/codec/ItemCodec.java), line 100, stores an explicit `NULL` attribute. [`QueryPlanner.payloadFragment()`](../reladynamo-core/src/main/java/io/reladynamo/core/plan/QueryPlanner.java), lines 773–777, translates `isNull()` to `attribute_not_exists` and `isNotNull()` to `attribute_exists`.

An explicitly null attribute exists. Thus ordinary Query/Scan filtering excludes stored nulls from `isNull` and includes them in `isNotNull`. Missing attributes from schema evolution require a deliberate additional policy. See [AWS expression function semantics](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.OperatorsAndFunctions.html).

[`QueryPlanInterpreter`](../reladynamo-core/src/main/java/io/reladynamo/core/plan/eval/QueryPlanInterpreter.java), lines 152–159, incorrectly treats explicit NULL as nonexistence. Planner-versus-interpreter property tests can therefore be green while the actual DynamoDB expression is wrong.

Review acceptance: execute both predicates against DynamoDB Local with explicit NULL, missing attribute, empty string, and a populated value. Fix the independent oracle as well as the translator. Audit negation/SQL three-valued semantics too.

**R-06 — Blocker: fan-out collapsing can change OR branch semantics. Source-confirmed.**

[`FanOutSelect.tryCollapse()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/exec/FanOutSelect.java), lines 46–136, checks expression text but does not compare every child's expression-value bindings, attribute-name mappings, or residual semantics. It then builds a shared filter and sort-key condition from the first child, and selects a residual from one child.

Example: `(id=1 AND label="A") OR (id=2 AND label="B")`. Both child filters can be textually `#LABEL = :v0`; the values differ. Collapsing to `pk IN (...) AND LABEL="A"` loses valid row 2 and can admit the wrong row 2. Equal-looking temporal range expressions with different bound dates present the same concern.

Review acceptance: OR branches with different literal values, time ranges, attribute mappings, and residuals. Collapse only if semantic equivalence is established, or preserve the branch predicates explicitly.

**R-07 — Major: residual evaluation receives the wrong object representation. Source-confirmed call mismatch; exact failure needs a generated-operation regression.**

[`QueryPlanExecutor.passesResidual()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/exec/QueryPlanExecutor.java), lines 321–326, calls `Operation.matches(decodedMap)`. Generated Reladomo attributes operate on their domain/data types, not an arbitrary `Map`. Object materialization happens later in `DynamoDbPersister.find()`.

Ends-with, complex LIKE, large IN, mapped predicates, and other residual paths therefore cannot be assumed to work. A generated cast failure is a likely outcome. Relationship predicates additionally require relationship resolution, not merely replacing a map with a data object. The pure interpreter accepts a separate typed candidate, so it does not prove this executor path.

Review acceptance: actual generated finder `endsWith`, complex LIKE and large-IN predicates against codec-written rows; relationship predicates with cold caches; preserve nullable `matches()` semantics without silently treating an unevaluable condition as false.

**R-08 — Major: order, top-N, and OR-union semantics are incomplete. Source-confirmed executor gaps; verify final Finder behavior too.**

[`QueryPlanner.attachOrderBy()`](../reladynamo-core/src/main/java/io/reladynamo/core/plan/QueryPlanner.java), lines 574–613, assigns an order mode using `OrderBy.toString()` substring checks. The plan does not carry a usable comparator. The executor never consumes `orderMode()` or sorts rows, and it can stop at `rowcount` before all matching rows are available. A later Reladomo sort cannot recover rows excluded by early limiting.

Fan-out children append results without deduplication by physical identity. Overlapping OR branches can return the same item twice; `count()` directly counts that list. Collapsed PartiQL does not specify a global order either.

Review acceptance: descending top-one across multiple partitions where the highest value is in the last partition; multiple sort columns; overlapping OR predicates returning one logical row and count one; limits applied after deduplication and required ordering.

**R-09 — Major: pagination and memory safeguards are not wired end to end. Source-confirmed.**

`PlannerConfig.maxPages` defaults to 64, but [`QueryPlanner`](../reladynamo-core/src/main/java/io/reladynamo/core/plan/QueryPlanner.java) never copies it into `QueryPlan.maxPages`, whose default is zero. Query/Scan loops enforce a positive plan value, so manually constructed-plan tests can pass while normal finder plans remain unbounded. The PartiQL pagination loop has no equivalent page-limit check.

`inMemoryRowCeiling` is consulted only to refuse in-memory ordering when set to zero; a positive ceiling is not enforced during accumulation. `count()` materializes all result maps. `inMemoryByteCeiling`, join limits, and page-size settings are partly declared/refused rather than implemented. Cartesian PK expansion is built before its result-size check, so a limit does not necessarily prevent allocation growth.

Review acceptance: set limits through public configuration, drive actual finder/planner/executor paths, and exceed them through Query, Scan, and PartiQL. Bound intermediate allocations and count memory. Failure must be explicit rather than a partial result labeled complete.

**R-10 — Major: the supported GSI surface is narrower than the public design types. Source-confirmed.**

[`DynamoDbWriter.stampGsiKeys()`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/persist/DynamoDbWriter.java), lines 138–157, skips sparse-current GSIs and composite GSI keys. The planner can nevertheless select a sparse-current path. It also constructs that path using base key attribute names, whereas `GsiSpec` defines distinct sparse-current attributes. A configured optimization can therefore be empty or invalid.

`GsiSpec` exposes `KEYS_ONLY` and `INCLUDE`. `TableCreator.toProjection()` uses the declared Java names directly; the codec stores mapped item/column names. The executor decodes GSI results as full rows without fetching missing attributes from the base table. A keys-only result lacks `_rd_v` and required mapped attributes; omitted nullable attributes can also be reconstructed incorrectly as null.

The demonstrated ALL-projected, single-FK GSI is meaningful working coverage. It does not establish these other configurations. GSIs do not automatically provide unique constraints and offer eventual consistency. See [AWS GSI behavior](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html).

Review acceptance: reject unsupported designs at startup or implement and exercise their complete write/read lifecycle. Test projected-name translation, base-table hydration, current-version removal on correction, GSI lag, and transactional access restrictions.

**R-11 — Major: “unchanged arbitrary XML” is not the implemented mapping contract. Source-confirmed.**

[`MithraObjectXmlParser`](../reladynamo-core/src/main/java/io/reladynamo/core/mapping/MithraObjectXmlParser.java) only collects direct `Attribute` and `AsOfAttribute` children for the core mapping. Source attributes, inherited metadata, relationships/indexes for design derivation, and other model features do not receive full handling. Identity columns and infinity-as-null are explicitly rejected. Rejection is preferable to corruption, but it contradicts an unrestricted migration promise.

Custom as-of names become `<name>From/To` attributes in the parser, while the writer and Backfill hard-code `businessDateFrom/To` and `processingDateFrom/To`. [`PhysicalDesign.Builder`](../reladynamo-core/src/main/java/io/reladynamo/core/plan/PhysicalDesign.java) defaults temporal item names to `FROM_Z/THRU_Z/IN_Z/OUT_Z`, rather than deriving all of them from the parsed mapping. A model with `validDate` or different boundary column names needs more than the documented generic setup.

The codec explicitly rejects sub-millisecond timestamps. This protects against silent rounding, but disqualifies source values outside that precision unless the migration contract resolves them. A custom writer `KeyStrategy` is also not automatically shared by the planner, which uses its own `PartitionKeyEncoder`; changing write keys alone makes reads incompatible.

Review acceptance: preflight arbitrary axis/column names, compound and non-`Id` PKs, source attributes, inheritance, generated IDs, supported timestamp precision, and custom key strategies. Every rejected feature should be reported before any migration write. Source routing must prevent different databases with equal logical keys from colliding.

**R-12 — Major: missing SPI functions remain application compatibility blockers. Source-confirmed.**

The inspected [`DynamoDbPersister`](../reladynamo-ddb/src/main/java/io/reladynamo/ddb/persist/DynamoDbPersister.java) has **32 overrides: 11 with implemented behavior, 1 transaction-participation no-op, and 20 explicit `throw notYet(...)` methods**. Existing documents saying 21 named refusals do not match that count.

Missing behavior includes cursors, aggregates/functions, refresh and dated refresh, full-cache loading/reloading/renewal, database identifier extraction, operation-based mass delete/purge preparation, batch/multi-update, dated enrollment, and date-range access. `find()` also requires as-of equalities to materialize dated objects; all-history edge-point operations cannot be presumed interchangeable with point-as-of queries.

Replacing the reader leaves the original tuple persister installed, as recorded in the spike and design. Temp-tuple and more complex relationship/query paths need explicit closure. There is no production bootstrap implementation matching the proposed `PortalBinder`/runtime handle in design 02. The README and migration guide instantiate the three-argument, explicitly write-only persister constructor; following those snippets does not enable reads.

Review acceptance: define a feature-level compatibility matrix from actual target applications, then close every required SPI path. Cold-cache and source-disconnected tests are essential so JDBC or existing cached objects cannot mask gaps.

**R-13 — Major: additional storage contracts need startup validation. Source-confirmed.**

`ItemCodec` checks the 400-KB limit before `DynamoDbWriter` appends primary keys and GSI keys, so an accepted payload can become an oversized final item. Mapping construction does not establish that every item name is unique and disjoint from reserved storage names such as `pk`, `sk`, `_rd_v`, and GSI attributes. A mapped collision can overwrite storage metadata or payload. The default key strategy rejects `#` in key components and stringifies some other types; arbitrary binary/decimal key semantics require explicit review.

Review acceptance: validate the final stored item and full mapping namespace, including near-limit payloads with long keys/GSIs. Pin supported key types and canonical encodings; test reconstruction across JVMs and independent byte-array instances.

**Why the current green tests do not close these findings**

[`BitemporalDifferentialTest`](../reladynamo-ddb/src/test/java/io/reladynamo/ddb/differential/BitemporalDifferentialTest.java), especially `assertStoresAgree()`, performs the temporal operation on H2, reads the resulting history, writes those rows into DynamoDB, and compares the copy. [`DifferentialSupport.Store.push()`](../reladynamo-ddb/src/test/java/io/reladynamo/ddb/differential/DifferentialSupport.java) supports the same pattern. This is good storage fidelity coverage; it does not prove that the DDB persister executes the original mutation with equivalent failure, concurrency, and transaction semantics.

[`BoundWritePathTest`](../reladynamo-ddb/src/test/java/io/reladynamo/ddb/differential/BoundWritePathTest.java) improves coverage, but two tests explicitly accept named unsupported-operation failures. Its successful-update branch only requires at least one stored row, and the stronger H2 comparison compares sorted quantities rather than all temporal boundaries. Several paths conditionally skip the mutation when `findOne()` returns null. Green therefore does not uniformly mean the requested operation executed successfully.

The CRM and classifier DynamoDB demo tests explicitly say they mirror H2 snapshots and do not rebind the application finders. They provide useful domain examples but do not prove unchanged business services running on a disconnected relational source. This is inconsistent with the migration guide's broader demo claim.

Planner property tests are valuable, but use a custom interpreter and manually constructed wire values. R-05 demonstrates a shared wrong assumption between implementation and oracle. They need complementary tests using codec-written rows, generated operations, the real executor, and actual DynamoDB expression evaluation.

**Review of the existing plan**

[`reladomo-dynamodb-adapter.md`](../drom-plans/reladomo-dynamodb-adapter.md) primarily plans an adapter: spike, mapping, query translation, writes, conformance, demos, and release. A migration product appears mainly in the hardening narrative. It lacks a first-class workstream for source inventory/extraction, snapshot/CDC protocol, job state, validation manifests, cutover, and reverse migration.

The design documents contain a substantially broader architecture than the implementation, particularly [`02-java-config-and-bootstrap.md`](design/02-java-config-and-bootstrap.md). Proposed classes and workflows should remain visibly marked as designs until actual public entry points and tests exist. Error-message factory methods likewise do not establish that a validation rule is invoked.

Progress checkboxes and status documents are not a dependable current feature inventory: some implemented work remains unchecked, some descriptions are obsolete, `find()` is understated, while transactional equivalence, reversible Backfill, demo runtime migration, and configuration safeguards are overstated. The right review unit is an observable application behavior with an executable acceptance case, not a checked chapter or a raw test count.

**Recommended review order — no changes performed**

| Priority | Track | Review gate |
|---|---|---|
| 1 | ORM correctness | Close R-01/R-02 before trusting transactional writes; close R-03–R-06 before trusting query results. |
| 2 | Migration correctness | Close M-01/M-02/M-08; full-history verification must be trustworthy before any successful cutover signal. |
| 3 | Compatibility contract | Inventory target-project features; make unsupported XML, query, cache, ID-generation, and transaction behavior fail preflight. |
| 4 | Controlled migration | Implement an offline migration first if acceptable: stable source, bounded extraction/write/verify, checkpoints, source-disconnected runtime replay. |
| 5 | Runtime breadth | Close required residual, ordering, GSI, cache, bulk, aggregate, cursor, and dated SPI paths with cold-cache application tests. |
| 6 | Online migration | Add CDC/order/conflict handling, cutover fencing, rollback, and reverse import if online operation is required. |
| 7 | Production validation | Exercise ASE, live AWS permissions/GSI lag/throttling, representative history sizes, multiple application instances, and operational recovery. |

An unrestricted “any Reladomo project, same functionality, no call-site changes” promise needs a constrained support contract. DynamoDB transactions, access paths, GSI consistency, and item sizes impose real boundaries even after implementation defects are fixed. The migration tool should turn those boundaries into an explicit eligibility report and required adaptations.

**Acceptance cases for later review**

- [ ] Every source PK component and every mapped value participates in verification; binary values compare by content; extra/missing versions are detected.
- [ ] A stale migration retry cannot overwrite newer destination state or resurrect a purge.
- [ ] A failed, already-flushed Reladomo transaction leaves no partial durable result.
- [ ] Duplicate insert and concurrent/stale updates follow an explicit conflict contract across JVMs.
- [ ] Complete-key queries with failing payload/as-of filters return no row and count zero.
- [ ] Numeric/null/string predicates execute correctly against items written by the actual codec.
- [ ] OR branches retain distinct bindings, deduplicate results, and preserve ordered top-N behavior.
- [ ] Limits set through public configuration bound Query, Scan, PartiQL, and intermediate memory.
- [ ] Custom temporal names, PK types, source routing, and GSI projections either work end to end or fail preflight.
- [ ] Every required application SPI feature runs with cold caches and the relational source disconnected.
- [ ] H2 and ASE extraction copy all temporal versions from a reproducible source snapshot.
- [ ] One original business operation script runs independently against each backend; complete histories, results, exceptions, and rollback outcomes agree.
- [ ] Crash/restart, partial writes, cutover, post-cutover rollback, and reverse import are rehearsed.

**Status of this review:** findings recorded for later review. Implementation and remediation remain with Claude; no finding is marked fixed merely because its surrounding tests pass.
