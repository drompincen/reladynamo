# Reladynamo support contract

**What this document is.** The inspection of 2026-09-14 ended on a point worth taking seriously:

> An unrestricted "any Reladomo project, same functionality, no call-site changes" promise needs a
> constrained support contract. DynamoDB transactions, access paths, GSI consistency, and item sizes
> impose real boundaries even after implementation defects are fixed.

This is that contract. It separates three things that had been running together: what DynamoDB
**cannot** do regardless of how good the adapter gets, what the adapter **does not yet** do, and what
it **does** do and has tests for. Only the third is a promise.

Read it before you plan a migration. `docs/RELEASE-READINESS.md` tracks *progress*; this page states
*scope*, and scope is the thing that decides whether your project is a candidate at all.

---

## Tier 1 — permanent boundaries

These do not move. They are properties of DynamoDB, and an adapter that appeared to remove them would
be lying about durability or about cost.

| Boundary | Consequence for a Reladomo application |
|---|---|
| A transaction is capped at **100 actions and 4 MB** | A logical transaction larger than that cannot be atomic. Chunking it across several `TransactWriteItems` calls does not preserve atomicity — it only hides the seam. Applications that commit thousands of rows in one unit of work need their transaction boundaries reconsidered, not a bigger batch. |
| An item is capped at **400 KB** | A single wide row, or one with a large binary attribute, has no home. The adapter measures the **fully assembled stored item** — payload, `pk`, `sk`, `_rd_v` and any stamped GSI key attributes — and throws `ItemTooLargeException` before the write leaves the JVM: `DynamoDbWriter.toItem` appends the keys, calls `stampGsiKeys`, and only then calls `ItemCodec.rejectIfTooLarge`. Mapped item names are separately validated as disjoint from `pk`, `sk`, `_rd_v` and every GSI key attribute, with `RELADYNAMO-CFG-013` (`MappingValidator.validateItemNamespace`), and `KeyComponentEncoder` pins the supported key types. R-13 is closed. |
| **GSIs are eventually consistent** | A read through a secondary index can miss a write that has already committed. A query the planner routes to a GSI cannot offer read-your-writes. There is no `consistentRead` for a GSI — not as a limitation of this adapter, but as a property of the service. |
| **GSIs are not unique constraints** | A relational unique index that the application relies on for correctness has no equivalent. Uniqueness must be enforced by a conditional write on the base table's key, or not at all. |
| **No server-side joins, no aggregates over unindexed data** | Reladomo deep fetches become additional round trips. Aggregations either read everything or need a maintained rollup. |
| **Binary sort order is unsigned** | DynamoDB orders binary values by unsigned lexicographic comparison; Reladomo's in-memory `ByteArrayOrderBy` uses *signed* byte subtraction, so `0x80` sorts before `0x01` there and after it here. The adapter matches DynamoDB, and supplies `UnsignedByteArrayOrderBy` so an H2 comparison sorts the same way. An application that depends on Reladomo's signed order will see a different sequence. (Reladomo's comparator additionally crashes on an empty `byte[]` — finding 30.) |
| **Query needs a partition key** | Any operation that cannot supply one is a Scan. The planner refuses rather than silently degrading, which is the right behaviour and also means some working H2 queries will simply not plan. |

A project that depends on any Tier 1 row is not a migration candidate without changing the
application. That is a legitimate answer, and finding it out during preflight is much cheaper than
finding it out after cutover.

## Tier 2 — not implemented yet

These are gaps, not boundaries. Each names the finding it comes from; M-03, M-05 and M-06 are
**declared out of scope for 0.1.0** rather than merely unfinished. None of them should be read as
"coming soon" — read them as "if your project needs this, it does not work today."

| Gap | Finding | Status |
|---|---|---|
| Reverse migration (DDB → relational) | M-06 | Does not exist. Only the differ is symmetric. |
| A relational source extractor, project discovery, job runner | M-03 | Does not exist. `Backfill` takes rows somebody else prepared. |
| Online migration (CDC, ordering, cutover fencing) | M-05 | Not implemented. Offline, immutable-source migration is the only supported shape. |
| Sybase ASE anything | M-03 | No ASE execution or extraction evidence exists. H2 success does not establish ASE compatibility for temporal types, source routing, collation, or identity/sequence usage. |
| Cursors, aggregates, full-cache load, operation-based mass delete and purge, batch and multi update, date-range access | R-12 | Of the **32** persister SPI methods, **14 are implemented, 17 refuse by name, and 1 is conditional**. See below. |

**The SPI split, counted from `DynamoDbPersister.java`.** 32 methods, no defaults, all overridden;
overloads counted separately.

- **14 implemented**: `insert`, `delete`, `purge`, `batchInsert`, `batchDelete`, `batchDeleteQuietly`,
  `batchPurge`, `update(object, wrapper)`, `update(object, List)`, `find`, `count`, `refresh`,
  `refreshDatedObject`, `enrollDatedObject`. `refresh`/`refreshDatedObject` and `enrollDatedObject`
  were gaps when this page was first written; they are not any more.
- **17 refuse by name** with `UnsupportedOperationException`: `findCursor`, `computeFunction`,
  `findAggregatedData`, `loadFullCache`, `reloadFullCache`, `renewCacheForOperation`,
  `extractDatabaseIdentifiers(Operation)`, `extractDatabaseIdentifiers(Set)`, `findForMassDelete`,
  `deleteUsingOperation`, `deleteBatchUsingOperation`, `batchUpdate`, `multiUpdate`,
  `prepareForMassDelete`, `prepareForMassPurge(Operation, boolean)`, `prepareForMassPurge(List)`,
  `getForDateRange`.
- **1 conditional**: `setTxParticipationMode` does nothing for a `null` mode, which is what existing
  callers pass. Any non-null mode throws `DynamoDbTransactionException` with `RELADYNAMO-TXN-007`,
  because neither a database pessimistic lock nor an optimistic version-check mode is implemented.

This split was derived by **running applications** — the classifier and pet-store bound-portal tests
with H2 disconnected — not by reading the refusal list. That is acceptance case 10
(`AcceptanceRequiredSpiTest`, `ClassifierBoundPortalTest`, `PetstoreBoundPortalTest`). Every method
those applications actually reach either works or refuses by name; the methods never reached remain
refusals. `AcceptanceRequiredSpiTest` classifies by method *name* rather than by overload, so it
reports 14 implemented names and 15 refusal names — the same 32 methods counted a different way.

**Closed since this page was first written.** Three rows that used to sit in this table are gone
because the code changed, not because the wording did:

- **R-01, durable transaction integration.** One Reladomo transaction now commits as one
  `TransactWriteItems` through `DynamoDbTransactionCoordinator`. Over 100 actions is refused with
  `RELADYNAMO-TXN-001` and over 4 MB with `TXN-002`, neither chunked
  (`DurableTransactionTest`, `DurableTransactionLocalTest`, `BoundDurableTransactionTest`).
- **R-02, conditional writes.** ORM writes carry conditions: `attribute_not_exists` on insert,
  expected-prior-state on update and delete, surfacing as `MithraUniqueIndexViolationException` and
  `MithraOptimisticLockException`. Migration writes (`upsert`, `batchUpsert`) stay unconditional by
  design. Concurrency is still only tested with two clients in one JVM — see Tier 1 and
  `docs/RELEASE-READINESS.md`.
- **M-04, restartable backfill.** Streaming intake, grouped verification, and durable checkpoints
  that survive kill and resume (`BackfillRestartableTest`).

## Tier 3 — the mapping contract

The promise has been stated as "unchanged arbitrary Reladomo XML." It is not that yet, and R-11 is
explicit about it. What the parser actually handles:

**Supported.** Direct `Attribute` and `AsOfAttribute` children. Logical primary keys, including
composite ones. The three temporal flavours (bitemporal, audit-only, non-dated). Millisecond
timestamp precision.

**Refused, by name, with a `RELADYNAMO-CFG-nnn` code** — which is the correct behaviour, and better
than silent corruption:

- `SourceAttribute` (`RELADYNAMO-CFG-012`) — source routing would let two databases with equal
  logical keys collide in one table
- identity / generated columns
- infinity-as-null temporal representation
- sub-millisecond timestamps — this protects against silent rounding, but it does disqualify source
  values outside that precision until the migration contract resolves them

**Custom temporal axis names — half of this is now resolved.** A *unitemporal* axis with a custom name
works end to end: `PhysicalDesign.Builder.build()` derives the Java and item names from the parsed
mapping through `TemporalAttributeNames` rather than defaulting to `FROM_Z`/`THRU_Z`/`IN_Z`/`OUT_Z`,
and `DynamoDbWriter` and `Backfill` follow those derived names rather than a literal
`businessDateFrom`. A write plus a
consistent read round-trips (acceptance case 9, `AcceptanceMappingContractTest`). A *bitemporal* object
with non-conventional axis names is **refused** with `RELADYNAMO-CFG-015` before `CreateTable`, because
`TemporalMapping` carries one sentinel and no per-axis names; refusing beats writing rows under column
names the mapping does not contain.

**Still a sharp edge.** A custom `KeyStrategy` given to the writer is **not** automatically used by the
planner, which has its own `PartitionKeyEncoder` — changing write keys alone makes reads incompatible.
`DefaultKeyStrategy` is the only supported implementation for 0.1.0.

**Not handled for design derivation.** Inherited metadata, relationships and indexes declared in XML
do not yet drive physical design; GSIs are configured explicitly.

## Tier 4 — the GSI surface

**R-10 is closed.** What `GsiSpec` declares and what the writer stamps now agree, and the declarations
that could not work are refused rather than half-implemented:

- **Sparse processing-current GSIs are stamped and unstamped.** `DynamoDbWriter.stampSparseCurrent`
  writes the index keys only while `processingDateTo` is infinity; a later Put of a closed rectangle
  omits them, and DynamoDB drops the index entry (`SparseCurrentGsiStampTest`). The as-of-now saving
  is *measured*, not asserted (`SparseCurrentGsiQueryTest`).
- **Composite GSI partition keys are stamped**, through the shared `PartitionKeyEncoder` /
  `KeyComponentEncoder`; a composite key that happens to be the entity primary key encodes identically
  to the base partition key (`SparseCurrentGsiStampTest`, last two cases).
- **`KEYS_ONLY` and `INCLUDE` are refused by name** with `RELADYNAMO-CFG-014`, at mapping validation
  and again in `TableCreator.refuseUnsupportedProjections` before any `CreateTable`. Acceptance case 9
  verifies the tables are *absent* after the throw, not merely that an exception was raised.
  `GsiSpec` still exposes them as declarable values, and still has a `uniqueAttribute` factory whose
  name overstates what any GSI can enforce — see Tier 1.

**What is actually proven:** `ALL`-projected GSIs only — a foreign-key lookup GSI, and a sparse
processing-current GSI. Relationships and deep fetch are now compared **differentially**, not merely
counted: `RelationshipDifferentialGraphTest` is 13 full-result-set comparisons against H2, including
as-of navigation at a past *business* date and at a past *processing* date, many-to-one, an empty
relationship, 12 children across 12 base-table partitions, a two-level chain that costs one request
per level rather than one per row, and a refusal by name when the relationship has no GSI
(`PLAN-001`). A lazy `parent.getChildren()` navigation on a single object — which used to be refused
with `PLAN-001`, because `RelationshipMultiEqualityOperation` implements `EqualityOperation` but not
`MultiEqualityOperation` and so stayed one opaque atom in the planner — is now decomposed through the
public `getOrCreateMultiEqualityOperation()` and planned through the foreign-key GSI, with no Scan.

Reads through any of these indexes are still eventually consistent. That is Tier 1, and it does not
move.

## What "verified" means here

The inspection's sharpest methodological point deserves repeating, because it changes how to read
every other status document in this repository:

> The right review unit is an observable application behavior with an executable acceptance case, not
> a checked chapter or a raw test count.

Several existing differential tests perform the temporal operation **on H2**, read the resulting
history, write those rows into DynamoDB, and compare the copy. That is genuine storage-fidelity
evidence and it is worth having. It is **not** evidence that the DynamoDB persister executes the
original mutation with equivalent failure, concurrency and transaction semantics. When this repository
reports a differential split, that is exactly the distinction being drawn, and the storage-path number
is the weaker of the two.

The current figures, from `reports/check-latest.json` (iteration 128, 8 of 8 gates, 0 fail, 694 adapter
tests): **78 storage-path and 189 query-path** differential comparisons. The split goes by test-class
name, so neither half is exact — `docs/COVERAGE-GAPS.md` §3 says how it is drawn. Read the
query-path number as the stronger evidence: those tests run generated Reladomo finders against
DynamoDB Local and compare the result sets with H2.

None of it has touched a real AWS endpoint, and none of it has run at scale. DynamoDB Local is the
only backend any test has used. Of the thirteen inspection acceptance cases, ten pass and three are
declared out of scope rather than passing.

## Eligibility, in one paragraph

Today Reladynamo suits a project that: uses bitemporal, audit-only or non-dated Reladomo entities with
standard axis names; addresses rows by a complete logical key or a foreign key with a configured
`ALL`-projected GSI; tolerates eventual consistency on those index reads; keeps units of work well
inside 100 actions and 4 MB; can accept that write conflict detection, while implemented and tested,
has been exercised only with two clients in **one JVM** and never across processes or under load; and
can migrate offline from an immutable H2 source with no path back. Everything outside that is either a
permanent boundary or an open finding, and both are named above.
