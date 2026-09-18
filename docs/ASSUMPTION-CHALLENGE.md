# Load-bearing assumptions that still need evidence

**The largest risk is treating a declared capability as a working contract.** Table topology is not the main correctness blocker. A version stamp, a GSI type, a transaction flag and a passing test each describe less than the surrounding prose suggests.

Review: 2026-09-14 America/Denver. This is a source review with existing test-report evidence, not a fresh test run. The required `javap` run is blocked by WSL interop; see [TOPOLOGY §6](TOPOLOGY.md). No production/test code was written. Findings distinguish current source defects, demonstrated documentation errors and unproven behavior. Implementers must reproduce each defect with behavioral RED before claiming remediation.

## Evidence paths

Paths below are relative to `/mnt/c/Users/drom/IdeaProjects/reladynamo`:

- **Core:** `reladynamo-core/src/main/java/io/reladynamo/core/`.
- **DDB:** `reladynamo-ddb/src/main/java/io/reladynamo/ddb/`.
- **Tests:** `reladynamo-ddb/src/test/java/io/reladynamo/ddb/`.
- **Models:** CRM `demos/01-crm-bitemporal/project/src/main/resources/reladomo/models/`; petstore `demos/02-petstore-unitemporal/project/src/main/resources/reladomo/`; classifier `demos/03-car-classifier/project/src/main/resources/reladomo/` and its model subdirectory.

Read alongside [INDEXING.md](INDEXING.md); its complete key contracts and acceptance gates are prescriptions, not APIs presumed to exist.

## C-01 — “Fixing the writer's sparse branch completes sparse GSIs” is false

**Current source defect; blocker for enabling sparse indexes.** DDB `persist/DynamoDbWriter.stampGsiKeys` skips sparse specs. Core `plan/QueryPlanner.baseTablePlan` can nevertheless select them, but builds the condition using base `pk/sk` instead of the GSI attributes. The same branch requires a business-as-of, so it does not deliver the proposed audit-only optimization. It tests business-to against infinity with the ordinary upper-bound path, which is wrong for an infinity business query. It can also choose the sparse path before honoring exact rectangle predicates.

There is a deployment defect beyond R-10: DDB `exec/TableCreator.anyItemLacksAttribute` classifies every absent index key as unfinished backfill. A legitimately closed processing row or null-FK row can prevent adding the index forever. Presence-only verification also misses incorrect present values.

**Prescription:** one immutable, validated index specification shared by stamping, planning, creation and migration; eligibility-aware verification; separate history/current profiles; full boundary and exact-from semantics. Acceptance is a populated old table with open/closed rows, null FKs, wrong keys and two concurrent index definitions, followed by correct reads. An empty-table schema test is insufficient.

## C-02 — “A public GsiSpec describes a usable index” is false

**Current source defect.** Core `plan/GsiSpec` accepts compound partition attributes, narrow projections and current business keys. All compound specs share `gsi_pk`; all current sort keys share `gsi_sk`. Independent specs can overwrite each other's derived attributes. `QueryPlanner.encodeGsiPk` delegates most non-single-FK cases to the *entity* PK encoder, not the declared compound tuple. A `(storeId,productId)` StockLevel index does not supply `stockId` to that encoder.

DDB `exec/TableCreator.toProjection` sends Java names as physical INCLUDE names. DDB `exec/QueryPlanExecutor.accept` decodes the result as a complete item without base hydration. `GsiSpec.uniqueAttribute` calls a KEYS_ONLY lookup “unique”; no GSI guarantees uniqueness.

**Prescription:** reject configurations not proven through complete write/read lifecycle. Use explicit physical names per index, a shared tuple encoder, mapped projection names and explicit hydration. Distinguish lookup indexes from transactional uniqueness reservations. Test two compound GSIs simultaneously, custom column names, absent nullable projected fields and duplicate indexed values.

## C-03 — “TableCreator VALIDATE means the requested schema is installed” is false

**Current source defect.** `TableCreator.reconcileExisting` checks base key schema/types and existing GSI key names, but does not compare the GSI projection in its compatibility decision. A table with a same-named KEYS_ONLY index can be reported valid when the reader expects ALL. This is precisely a declaration read as proof of implementation.

**Prescription:** validate base/index key names, scalar types, projections and mapped non-key projection sets; record intentional extra indexes separately. Report immutable incompatibilities rather than success. Behavioral acceptance: provision a same-key/same-name but wrong-projection index, request ALL and verify rejection before any query can decode it. Add eligibility-aware key backfill verification from C-01.

## C-04 — “The planner forbids GSI access in transactions” is unproven at the seam

**Source-confirmed wiring gap.** Core `plan/PlannerConfig` stores `inTransaction`, default false. DDB `persist/DynamoDbPersister.find` passes its constructor-supplied config into every PlanningRequest without deriving actual transaction context. `count` does likewise and uses FIND purpose. A correct guard inside QueryPlanner does not prove that callers supply truthful context.

This bites a real application: classifier `Classifier.java:30–48` loads `active=true` rules and deep-fetches criteria inside its transaction. The Tier 4 prose cannot simultaneously promise transactional GSI exclusion and let the static default make that decision.

**Prescription:** derive per-call context at the persister boundary, leave the planner pure, make the strong/eventual contract explicit, and test using a real active transaction with default public configuration. Assert no GSI request reaches AWS. For non-key transactional membership with no authoritative path, fail by name; do not permit the GSI or silently scan. The exact Reladomo context API used in a fix must receive its own working javap verification.

## C-05 — “Below 100 rows, temporal mutations are atomic” remains false

**Current writer path still performs immediate per-item I/O.** DDB `DynamoDbWriter.insert/update/delete` call PutItem/DeleteItem directly; ORM batch methods iterate those calls. `DynamoDbPersister` delegates straight to the writer, and its transaction-participation method is a no-op. Adding conditional writes, which current source now has, does not change that durability boundary.

The ordinary successful correction can still close an old version durably and fail before replacements are all stored. Changing table topology does not fix it. DynamoDB can atomically write across entity tables, subject to the service limits. [AWS transaction API](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html).

**Prescription:** keep the public transaction-equivalence claim blocked until one logical transaction buffers/coalesces its actual base actions, validates total bytes/actions before the first durable write, commits atomically and reconciles ambiguous outcomes. Count guard/reservation/projection items as base actions; GSI-maintenance entries are not separate submitted transaction actions. Over-limit must fail without partial durable state. Test flush-then-throw, second-entity failure, close-before-insert failure, duplicate action on one item, retry and rollback across two clients. Do not chunk an atomic unit into “successful batches.”

## C-06 — “Conditional pk+sk writes enforce temporal uniqueness” is unproven

**Specific unsupported invariant, not a claim that R-02 is unchanged.** Current insert condition is `attribute_not_exists(pk)` evaluated at the submitted `pk+sk`; update/delete check expected row content. Those are useful. They do not serialize competing insertions into different sort keys of the same logical PK. Two cold processes can propose different processing-from values for overlapping open business intervals, each with an absent physical key.

**Prescription:** prove logical-key serialization/optimistic revision handling across processes and during director expansion. Any guard must protect the same logical identity and be committed with the resulting rows. Keep interval splitting above the persister; a guard protects concurrency, it must not implement a second temporal director. Acceptance: two synchronized writers creating/correcting the same logical identity with different physical keys; exactly one valid outcome or explicit conflict, never overlapping current facts. A duplicate test using the exact same `pk+sk` does not establish this.

## C-07 — “Current processing means one current row” is false

**Model-level counterexample.** The CRM's bounded price changes and retroactive corrections allow several business intervals to remain open in processing time. A processing-current GSI contains all of them. A terminated object's earlier valid interval may still be current knowledge. Wall-clock passage does not update an item or remove an index key.

**Prescription:** index by processing-open membership only, retain all current business segments, apply the actual business containment/infinity semantics, and never promise a general GetItem or unqualified Limit=1. Test several current segments, gaps and finite termination. See INDEXING §4 for the algorithm.

## C-08 — “A single table / fewer requests implies less billed work” is unsupported

**Evidence is narrower than the claim.** Existing relationship report records one call, 24 children, eight parents and zero scans. The test resets counters after resolving the parents; `executeStatement` is counted under “query.” Thus it measures one homogeneous child-fetch phase. Its assertions allow any read count below eight and do not require zero scans. The saved measurement is useful; the assertion does not protect its full reported outcome.

Further, `QueryPlanExecutor.executeFanOutSelect` increments `stats.examined` using returned items. It has no service-provided examined-item count for that API. Filtered-out history disappears from the reported ratio, creating a falsely efficient explain result.

**Prescription:** assert total graph calls and per-phase calls separately; require exact one child request/zero scans for the small fixture; test >50 IDs and large/filtered pages. Report consumed capacity and unavailable examined-count honestly. A request counter is neither a query plan nor a billing meter. [AWS ExecuteStatement response](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_ExecuteStatement.html).

## C-09 — “R-08 is now fixed, so numerical ordering is exact” is false

**New source-confirmed defect in the changed implementation.** Core `plan/RowOrderComparator.compareNumbers` converts any BigDecimal, Float or Double pair to `double` before comparison. Distinct BigDecimal values can compare equal; for example `9007199254740992` and `9007199254740993`. An incorrect tie can select the wrong ordered top-one, or let a secondary key override the true primary numeric order. CRM amount/revenue/price fields and petstore prices are BigDecimal.

The comparator also falls back to `String.valueOf` for unsupported types. For independent byte arrays that is object identity text, not content order. Java String ordering and DynamoDB UTF-8 key order must not be assumed identical for all Unicode strings or SQL collations.

**Prescription:** exact type-specific comparators, BigDecimal numeric comparison without binary floating conversion, and named rejection for unsupported comparisons. Match actual generated/framework order semantics with bytecode-verified APIs and behavioral comparisons; don't infer them from a comment. Acceptance: numerically adjacent high-precision values returned in adversarial order across partitions, descending top-one, nulls, binary content and non-ASCII strings. Existing ordering/dedup additions are real progress, not blanket closure.

## C-10 — “Canonical key encoding agrees with finder equality” is unproven for decimals

**Source mechanism confirmed; ORM equality parity needs a behavioral test.** Core `key/KeyComponentEncoder` encodes BigDecimal with `toPlainString`, retaining scale (`1.0` versus `1.00`). These produce different HASH strings. If the generated finder/SQL predicate treats those as numerically equal, routing one spelling cannot find the other. Wire-fidelity tests that preserve scale do not answer key equality semantics.

**Prescription:** specify equality semantics separately from payload fidelity. Prove supported key types against actual generated equality operations and independent JVM values. Until a decimal-key canonicalization/migration contract is established, reject decimal key/index components whose equivalent spellings cannot be guaranteed identical. Do not silently normalize existing persisted keys: that is a key migration. The new shared scalar encoder fixes the old byte-array identity issue; do not repeat that old defect as current.

## C-11 — “Versioned items can be upgraded or relocated by a version bump” is false

**Current source limitation plus a concrete constructor defect.** DDB `codec/ItemCodec` has a version-dispatch hook but no transformations; supported decoder range is only 1. Its `(mapping, schemaVersion)` constructor accepts every integer ≥1, and encode stamps that integer. Thus a codec can write `_rd_v=2` which its own decoder refuses. Key encoders still emit only v1.

**Prescription:** reject unsupported writer versions at construction; test encode/decode for every supported version and refusal before a write for unsupported versions. Keep payload version, key grammar and routing epoch distinct. A topology move preserving keys can preserve `_rd_v`; a re-key needs explicit translation, copy and cutover. See TOPOLOGY §3.

## C-12 — “The entity prefix is globally unique” is false

**Concrete model collision.** `DefaultKeyStrategy` uppercases the simple class name. CRM and petstore both have audit-only Payment with long paymentId. The same ID/from timestamp can alias exactly if tables are consolidated. Existing FK index keys have no entity discriminator either.

**Prescription:** stable fully qualified entity-to-storage-token manifest; refuse collisions and accidental shared tables; use explicit entity/access tokens in new indexes. Test both Payment types with identical IDs/times in a proposed merged schema before calling consolidation a no-rekey move. Java class rename and storage identity must be independent.

## C-13 — “Metadata is resolved once and then consistent everywhere” is false

**Current source mismatch.** Core `mapping/MithraObjectXmlParser` invents one conventional infinity, retains only simplified temporal flavor, and creates `<axis>From/To` names. Core `config/TemporalMapping` stores one sentinel, not one per axis. Core `bridge/InfinityResolver` rejects differing axis sentinels; `PhysicalDesign.infinityFrom` changes design metadata but not the writer's EntityMapping. Writer and Backfill still require literal `businessDateFrom/To` and `processingDateFrom/To` names. `PhysicalDesign.Builder` still defaults physical names to FROM_Z/THRU_Z/IN_Z/OUT_Z.

Parser metadata handling is also narrower than “unchanged arbitrary XML”: maxLength is explicitly ignored; precision/scale, generator/read-only and other flags are not carried in AttributeMapping. SourceAttribute is now explicitly refused, which is safer than silent routing. The demos exercise conventional axes and scalar keys, so they do not prove the general mapping contract.

**Prescription:** bind one resolved storage contract for all consumers or reject unsupported models before I/O. For 0.1.0 explicitly reject unequal axis infinities until per-axis metadata is carried end to end; do not guess one. For the future full sparse contract, carry each axis independently. Test custom names/columns, inclusive-to, timezone-sensitive sentinels, read-only models and source-scoped identities. A getter invocation that discards a value is not metadata support.

## C-14 — “A 32-MB setting bounds query memory” is false

**Current source-confirmed limit gap.** PlannerConfig defaults `inMemoryByteCeiling` to 32 MiB but rejects only non-default changes and documents it as unenforced. QueryPlanExecutor bounds row count, not decoded bytes; its memory error even reports zero bytes. At 50,000 rows, wide items can exceed the advertised ceiling by orders of magnitude. `count` materializes rows. Uncollapsed fan-out enters each child without a global pre-call page-cap check; existing checks concentrate on continuation, while collapse chunks have a separate check.

**Prescription:** one root execution budget counts every request/page, all branches, raw response buffers, decoded bytes and retained dedup/sort state. Check before issuing a new child request and before retaining another item. Do not turn exhaustion into a partial count/list. Test many one-page uncollapsed children with a tiny maxPages and a few large rows with a tiny byte limit, configured through the public persister path. For large counts/exports, implement streaming semantics or refuse; increasing a default is not a fix.

## C-15 — “Keyed temporal history is small enough to filter” is unmeasured

**Load-bearing performance hypothesis.** Design 01 §2.4 assumes bounded collections and a few pages. QueryPlanner often uses PK equality plus temporal filters and defaults to 16 estimated versions. Neither the estimate nor the demos establish production distributions. Filters do not reduce examined data, and a sparse current view does not accelerate every historical as-of.

The supposed universal 10-GB logical-key cap is also wrong: that item-collection cap is tied to LSIs. Avoiding it does not make large histories cheap. [AWS LSI limits](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/LSI.html).

**Prescription:** publish distributions for physical versions/key, current business segments/key, children/FK, item bytes and correction fan-out. Test tail keys, not just averages. State a supported history/latency budget; require an explicit archive-aware model if history exceeds it. No invisible TTL deletion of audit history.

## C-16 — “A child FK included in the logical PK needs no GSI” is false here

**Decided-design error.** Design 01 §2.5 says a one-to-many relationship whose child FK is a subset of the child PK can use PK/begins_with. But this project's entire logical PK tuple is encoded into the DynamoDB **partition key**. For a hypothetical compound `(orderId,lineId)`, knowing orderId alone does not supply HASH equality. Prefix conditions apply to sort keys, not a partial HASH key. The real demos' scalar PKs cannot catch this.

**Prescription:** correct that derivation rule; require a complete HASH equality or a declared relationship index. Test an additional compound-key child fixture, including two line IDs under the same parent. Do not redesign the base keys silently to fit the erroneous sentence. [AWS Query contract](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_Query.html).

## C-17 — “Verified Backfill means the target is a complete mirror” is false

**Scope defect if used as a cutover certificate.** DDB `migrate/Backfill.verify` now groups by every PK component and compares all mapped attributes using MappedRowSetDiffer. Those are meaningful fixes. However, it reads only logical partitions present in the supplied source list. A destination-only logical key is never inspected. Passing the complete source list still cannot discover keys absent from that source. The comment “a full-table migration therefore passes the full source” overstates what the verification certifies.

Source lists and per-key histories remain fully materialized. `hasIdenticalCounterpart` linearly searches each destination history for each source row, leaving quadratic per-key matching. Unconditional upsert can overwrite newer destination state on a stale retry. BackfillResult deliberately refuses to certify an empty source, which prevents one false green but also requires a separate manifest procedure to prove legitimately empty entities are empty at both ends.

**Prescription:** distinguish batch verification from full migration certification. Full certification enumerates destination scope independently, validates exact key/value/version sets both ways and records intentional empty tables. Use stable source snapshots, bounded extraction, checkpoints and safe conflict handling. For later topology reversal, also verify derived index keys; decoded mapped-row equality alone ignores those metadata fields.

## C-18 — “A green differential suite proves unchanged application semantics” is false

**Source-confirmed evidence mismatch.** Tests `differential/BitemporalDifferentialTest.assertStoresAgree` perform the mutation on H2, read the resulting rows and upsert them into DynamoDB. That proves a storage transfer, not the mutation's DDB transaction behavior. CRM and classifier Dynamo differential tests explicitly say they mirror H2 and do not bind application finders to DDB.

The current BoundWritePathTest is stronger than the inspection describes: it now checks both old/new quantities on success and compares complete temporal boundaries for a bound update. Do not repeat the old “at least one row” defect as if unchanged. It still has two tests whose accepted outcome is a named unsupported-method failure, and its shape projection includes quantity/boundaries rather than every payload attribute. Named refusal is useful compatibility evidence, not supported mutation behavior.

The root Maven reactor lists core, ddb, test-kit and spike, with a benchmark profile; the three demos are separate projects. Petstore's POM specifies release 21. Thus `mvn clean test` at root cannot prove all 73 entities run through the adapter on Java 11.

**Prescription:** separate storage-fidelity, refusal, bound-read, bound-mutation, transaction/concurrency and application-run gates. Run each original business script independently on both backends, cold caches, JDBC disconnected on the DDB run, with full result/payload/history/exception/rollback comparison. Record exact executed test names and runtime version. Refusal tests must never count toward supported-behavior totals.

## C-19 — “A strong base read or transaction flag preserves the whole graph's snapshot” is false

**Service boundary and unproven cache semantics.** Independent Query pages and target-portal calls do not create a snapshot across time or entities. GSI propagation can expose only part of a correction, while a cached empty relationship can outlive propagation. The current materialization through portal caches proves basic identity reuse but does not establish cross-process invalidation, transaction-local pending-write visibility or bounded stale-query caching.

**Prescription:** state separately: base-item consistency, relationship membership consistency, transaction isolation and cache freshness. Test two processes, partial correction propagation, read-after-write, cached empty/nonempty relationships and concurrent pagination. If exact snapshot semantics are required, reject an ordinary multi-Query plan without a supported snapshot/revision protocol. Do not describe eventual consistency as merely a transient latency cost. [AWS consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html).

## C-20 — “The support contract's Tier 1 table is a precise impossibility theorem” is too broad

**Documentation error with implementation consequences.** Some boundaries are hard service limits; some are consequences of this chosen layout. “Any operation that cannot supply [a partition key] is a Scan” overlooks finite key enumeration, a GSI whose key is supplied, or an explicitly maintained membership/rollup view. A strongly consistent alternative to a GSI can exist as transactionally maintained base items. Those alternatives cost writes and change the application/storage contract; they are not already implemented.

Likewise, “GSIs are not unique constraints” is correct; “a unique index has no equivalent” must distinguish native GSI enforcement from conditional reservation items. Table topology does not decide whether those items can participate atomically.

**Prescription:** preserve hard limits and honest refusals, but distinguish (a) service impossibility, (b) unsupported adapter capability and (c) an application change/read model that can provide an alternative. This prevents both false promises and premature rejection of feasible workloads. None of these alternatives should be added automatically during the indexing fix.

## Triage outcomes — 2026-09-17

Each challenge above is graded against the current source and the current tests. The sections above are
left as written on 2026-09-14; this section is the verdict. **Held** means the challenge still stands.
**Superseded** means the code moved and the challenge no longer describes it. **Disproven** means the
challenge was wrong, or is now contradicted by evidence. **Not determined** means this repository does
not settle it — and that is recorded as "not determined" rather than guessed.

C-09 and C-11 already have outcomes: they became conformance findings 22 and 23, both fixed (see
`docs/CONFORMANCE-FINDINGS.md` and `docs/RELEASE-READINESS.md`).

| # | Outcome | Evidence |
|---|---|---|
| C-01 | **Held, and acted on.** The narrow fix it warned against was not the fix taken: R-10 closed across stamping, planning *and* creation, not the writer alone. Its second half — presence-only index backfill verification — is **still held**. | `DynamoDbWriter.stampSparseCurrent`; `SparseCurrentGsiStampTest`, `SparseCurrentGsiQueryTest` (the as-of-now saving is measured); R-10 CLOSED in `docs/INSPECTION-TRACKING.md`. Still held: `TableCreator.anyItemLacksAttribute` is unchanged and still treats every absent index key as unfinished backfill. |
| C-02 | **Partly superseded, partly held.** Compound GSI partition keys are stamped through the shared encoder, and `KEYS_ONLY`/`INCLUDE` are refused before `CreateTable`, so the untranslated-INCLUDE-names and unhydrated-decode paths are unreachable. Held: `GsiSpec.uniqueAttribute` still calls a non-unique lookup "unique", and two compound GSIs on one entity simultaneously are not tested. | `DynamoDbWriter.encodeLookupGsiPk` → `PartitionKeyEncoder.gsiPartitionKey`; `RELADYNAMO-CFG-014` in `MappingValidator` and `TableCreator.refuseUnsupportedProjections`; `MappingValidatorTest`, `TableCreatorTest`, `AcceptanceMappingContractTest` (tables verified absent after the throw). Held: `GsiSpec.java:53`. |
| C-03 | **Held.** `TableCreator.reconcileExisting` compares base key schema and GSI *key schema* only; it never compares the projection of an existing index. `refuseUnsupportedProjections` screens the *requested* spec, which cannot see a same-named `KEYS_ONLY` index already installed. | `TableCreator.reconcileExisting` and `gsiKeySchemaMatches`. |
| C-04 | **Held.** `PlannerConfig.inTransaction` is still a static, constructor-supplied flag defaulting to `false`; `DynamoDbPersister` never reads it and derives no transaction context. A different hole was closed instead: `find`, `count`, `refresh` and `enrollDatedObject` refuse with `RELADYNAMO-TXN-006` while the current transaction has staged writes. | `PlannerConfig.java:127`; `QueryPlanner.java:205,365,380` consume the flag; no reference in `DynamoDbPersister.java`. |
| C-05 | **Superseded.** R-01 landed. One Reladomo transaction buffers its base actions, validates action count and bytes before the first durable write, commits as one `TransactWriteItems`, refuses over-limit with `RELADYNAMO-TXN-001`/`TXN-002` without partial durable state, and surfaces an ambiguous outcome as its own exception. | `DynamoDbTransactionCoordinator`, `PhysicalWrite`, `DynamoDbCommitOutcomeUnknownException`; `DurableTransactionTest`, `DurableTransactionLocalTest`, `BoundDurableTransactionTest`; acceptance case 3, `AcceptanceFailedTransactionTest`. The cross-client retry/rollback part of its prescription is still single-JVM. |
| C-06 | **Held.** R-02 added conditions at the submitted `pk+sk`, which is exactly what this challenge said does not establish logical-key serialization. No test creates the same logical identity from two processes with different physical keys. | `DynamoDbWriter` insert `attribute_not_exists`, expected-prior-state update/delete; acceptance case 4 `AcceptanceConflictContractTest` uses two clients in **one JVM**. `docs/RELEASE-READINESS.md`: no test in `reladynamo-ddb` starts a second thread or process. |
| C-07 | **Held as a constraint, and honoured in the stamping.** The sparse-current index stamps one entry per open processing row, keyed by that row's business-from, so several current business segments each keep an entry instead of collapsing to one; nothing promises a `Limit=1` or unqualified `GetItem`. The several-segments-plus-gaps-plus-finite-termination fixture the prescription asks for is **not determined**. | `DynamoDbWriter.stampSparseCurrent` with `PartitionKeyEncoder.currentBusinessSk`; `SparseCurrentGsiStampTest` covers open, closed-by-correction and composite cases, not a multi-segment fixture. |
| C-08 | **Partly disproven on the assertion, held on the billing claim.** The weak assertion is fixed: the two-level deep fetch now requires `scan=0`, `getItem=0`, at most 2 reads, *and* a grandchild count, so a low request count with nothing returned fails. Held: no consumed-capacity reporting, no >50-ID or large-filtered-page case, and `examined` is still incremented from returned item counts where the service gives none. | `RelationshipDifferentialGraphTest.two_level_deep_fetch_costs_one_request_per_level_not_one_per_row`; `QueryPlanExecutor.java:185` still does `stats.examined += items.size()`. |
| C-10 | **Held.** `KeyComponentEncoder` still encodes `BigDecimal` with `toPlainString`, so `1.0` and `1.00` are different HASH strings. Nothing rejects a decimal key component, and no test compares generated-finder equality across two spellings of the same number. | `KeyComponentEncoder.java:83-84`. |
| C-12 | **Held, unexercised.** `DefaultKeyStrategy` still derives the entity token from the uppercased simple class name, with no manifest and no collision refusal. The collision needs table consolidation, which nothing in this repository does — so it is a latent hazard, not a current defect. | `DefaultKeyStrategy.java:33`. |
| C-13 | **Partly superseded, partly held.** Superseded: a unitemporal custom axis name is derived end to end and a bitemporal one is refused with `RELADYNAMO-CFG-015` before `CreateTable`; the writer and `Backfill` read axis names through `TemporalAttributeNames` instead of literal `businessDateFrom`. Held: `TemporalMapping` still stores one sentinel, not one per axis, and `maxLength`, precision/scale and generator flags are still not carried in `AttributeMapping`. | `PhysicalDesign.Builder.build()` → `TemporalAttributeNames`; `DynamoDbWriter.java:489,517,523`; `Backfill.java:438-443`; `MappingValidator.unsupportedTemporalAxis`; acceptance case 9 `AcceptanceMappingContractTest`. |
| C-14 | **Held, and now stated honestly in the code.** `PlannerConfig.inMemoryByteCeiling` rejects any non-default value with the message that it "is declared but not yet enforced"; `QueryPlanExecutor` bounds row count and page count, not decoded bytes. Refusing to accept the setting prevents a false promise; it does not create a byte budget. | `PlannerConfig.java:191-198`; `RELADYNAMO-PLAN-006`/`PLAN-007` in `QueryPlanExecutor`, `PaginationSafeguardFinderTest`. |
| C-15 | **Not determined, and unchanged.** No load test exists; pagination has been exercised only with deliberately tiny `pageSize`/`maxPages` on small fixtures, so no distribution of versions per key, children per FK or item bytes has been published. Nothing in the repository settles the hypothesis either way. | `docs/RELEASE-READINESS.md` "No load"; the load test is still an open checklist item. |
| C-16 | **Held.** The entire logical PK tuple is still encoded into the DynamoDB partition key, so knowing one component of a compound child key supplies no HASH equality and `begins_with` cannot substitute. Whether a compound-key *child* fixture exists is **not determined**: there is a composite-PK fixture (`DiffFinderValue`), but the relationship fixtures use scalar-PK children. | `DefaultKeyStrategy`, `PartitionKeyEncoder`; `docs/FINDER-MATRIX.md` §2; `RelationshipFixture`. |
| C-17 | **Partly superseded, held on scope.** Superseded: M-04 closed — streaming intake, one destination-partition read per key rather than O(V) per row, and durable checkpoints that survive kill and resume. Held: `verify` still re-reads only the destination partitions of the supplied source rows, so a destination-only logical key is never inspected and the result is not a cutover certificate. | `Backfill` class javadoc and `verify`; `BackfillRestartableTest`; M-05/M-06 keep cutover and reversal out of scope (`docs/SUPPORT-CONTRACT.md` Tier 2). |
| C-18 | **Held as a method, and the project now reports that way.** The gate reports storage-path and query-path differential counts separately (78 / 189, iteration 128), and the bound-portal tests run the demos' original business operations against DynamoDB with H2 disconnected and cold caches. Held: application coverage is still uneven, and the petstore demo's release-21 POM still means a root `mvn clean test` cannot prove every demo entity on Java 11. | `reports/check-latest.json`; `ClassifierBoundPortalTest`, `PetstoreBoundPortalTest`; acceptance cases 10 and 12; `docs/RELEASE-READINESS.md` "Application coverage". |
| C-19 | **Held.** No test starts a second thread or process, so cross-process invalidation, partial GSI propagation, cached-empty-relationship staleness and concurrent pagination are all untested. Transaction-local pending-write visibility is handled by refusing (`RELADYNAMO-TXN-006`), not by providing a snapshot — honest, but not what the prescription asks for. | `docs/RELEASE-READINESS.md` "Concurrency"; Tier 1 of `docs/SUPPORT-CONTRACT.md` states GSI eventual consistency as permanent. |
| C-20 | **Partly superseded, partly held.** Superseded: the uniqueness row of Tier 1 now names the conditional-write alternative rather than calling it impossible, and R-02 implements that alternative. Held: the "any operation that cannot supply a partition key is a Scan" row is still stated absolutely, and Tier 1 has not been split into (a) service impossibility, (b) unsupported adapter capability and (c) an application read model that could serve the query at a write cost. | `docs/SUPPORT-CONTRACT.md` Tier 1; `DynamoDbWriter` conditional ORM writes. |

**Tally of the eighteen: 11 held, 4 partly superseded with a held remainder, 1 partly disproven with a
held remainder, 1 superseded outright, 1 not determined.** Held: C-01, C-03, C-04, C-06, C-07, C-10,
C-12, C-14, C-16, C-18, C-19. Partly superseded: C-02, C-13, C-17, C-20. Partly disproven: C-08.
Superseded: C-05. Not determined: C-15. Two held rows (C-07, C-16) additionally carry a sub-item this
repository does not settle, marked "not determined" in place.

Nothing here has been graded against a real AWS endpoint or under load, because neither exists. Item 5
of the priority list below — the `javap` verification in TOPOLOGY §6 — is likewise not determined here.

## Priority and required evidence

1. **Before enabling any new index:** close C-01–04 and the sparse/index lifecycle gates; prove transaction context is real. Existing unsupported specs must fail startup immediately.
2. **Before a temporal correctness claim:** close C-05–06 and C-19; run independent backends through the original scripts, not a post-H2 snapshot copier.
3. **Before query-order or migration correctness claims:** close C-09–13 and C-17. Protect exact numeric semantics, key identity and the full destination scope.
4. **Before a production performance claim:** close C-08, C-14–16; publish billed work and workload tails; no substitution of request count or assumed history size.
5. **Before calling this review fully verified:** execute the javap command in TOPOLOGY §6, compare bytecode to the source trace and retain its output. This is the one requested verification this environment could not perform.

## Inspection deltas and audit fingerprints

This repository is being changed by other work. The inspection dated 2026-09-14 is a starting point, not an immutable current defect list. At review time the source already contained conditional ORM writes, final-item size validation, shared scalar key encoding, full-key/full-value backfill comparisons, sorting and physical-key deduplication. They must be acknowledged; their tests were not rerun here.

To identify the reviewed implementations without relying on a moving line number, these SHA-256 hashes were captured after reading them (paths abbreviated with the prefixes above):

| File | SHA-256 |
|---|---|
| Core `key/DefaultKeyStrategy.java` | `f0c4e29e30e412a56cc1537020d6122b9d18bad923700730accef106cd477cfb` |
| Core `plan/GsiSpec.java` | `1f6d12a42c0664a093c0c074a7bca397c1df9f058eeaae8b43b1a449df21db5f` |
| Core `plan/QueryPlanner.java` | `df5da4a5f3e07889c83d8912668fda6b39845cc651d7303101c18046ff24a142` |
| Core `plan/RowOrderComparator.java` | `6c17caa3ac1b9516c321b5402280d520385b7c0c34b6dcb385632031c3eda364` |
| DDB `persist/DynamoDbWriter.java` | `3dad59f2f7b7dc130691b406d31bb48b35982765a28289cd508a4a9cace6782e` |
| DDB `persist/DynamoDbPersister.java` | `e5b8ac9f9c4b9977644207772e7acc0ee13a03ece7491243a833fb6a5526e7ac` |
| DDB `exec/QueryPlanExecutor.java` | `6392fe42dd2adf6606a73964d6c26834b8388d33096a611f19688a819ba43303` |
| DDB `exec/TableCreator.java` | `235494f8f2d1697a224dc3fce71c462be10a60414870149ec8ab764b568ee5f5` |
| DDB `codec/ItemCodec.java` | `acee0fc5a580dd4c8729b03a72bd6948447b84fe0e2f1120bfd4776c3531b6eb` |
| DDB `migrate/Backfill.java` | `623908d3aded3203a321cfe676c0850c498d03543959e81660bcfb3b14c3fc1b` |

Workspace limitation: the environment grants writes to the working output directory, not its parent. Fleet checkpoints were therefore recorded in local `PROGRESS.md`; the requested parent `../PROGRESS.md` was not modified.
