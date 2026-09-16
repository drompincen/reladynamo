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
| An item is capped at **400 KB** | A single wide row, or one with a large binary attribute, has no home. This is checked, but see R-13: the check currently runs before keys are appended. |
| **GSIs are eventually consistent** | A read through a secondary index can miss a write that has already committed. A query the planner routes to a GSI cannot offer read-your-writes. There is no `consistentRead` for a GSI — not as a limitation of this adapter, but as a property of the service. |
| **GSIs are not unique constraints** | A relational unique index that the application relies on for correctness has no equivalent. Uniqueness must be enforced by a conditional write on the base table's key, or not at all. |
| **No server-side joins, no aggregates over unindexed data** | Reladomo deep fetches become additional round trips. Aggregations either read everything or need a maintained rollup. |
| **Binary sort order is unsigned** | DynamoDB orders binary values by unsigned lexicographic comparison; Reladomo's in-memory `ByteArrayOrderBy` uses *signed* byte subtraction, so `0x80` sorts before `0x01` there and after it here. The adapter matches DynamoDB, and supplies `UnsignedByteArrayOrderBy` so an H2 comparison sorts the same way. An application that depends on Reladomo's signed order will see a different sequence. (Reladomo's comparator additionally crashes on an empty `byte[]` — finding 30.) |
| **Query needs a partition key** | Any operation that cannot supply one is a Scan. The planner refuses rather than silently degrading, which is the right behaviour and also means some working H2 queries will simply not plan. |

A project that depends on any Tier 1 row is not a migration candidate without changing the
application. That is a legitimate answer, and finding it out during preflight is much cheaper than
finding it out after cutover.

## Tier 2 — not implemented yet

These are gaps, not boundaries. Each has an open finding. None of them should be read as "coming
soon" — read them as "if your project needs this, it does not work today."

| Gap | Finding | Status |
|---|---|---|
| Durable transaction integration | R-01 | Writes are durable on arrival, regardless of whether the enclosing Reladomo transaction commits. **In progress.** |
| Conditional / optimistic-lock writes | R-02 | Inserts, updates and deletes are unconditional. Two JVMs can silently overwrite each other. **In progress.** |
| Reverse migration (DDB → relational) | M-06 | Does not exist. Only the differ is symmetric. |
| A relational source extractor, project discovery, job runner | M-03 | Does not exist. `Backfill` takes rows somebody else prepared. |
| Restartable, bounded, checkpointed backfill | M-04 | The whole source is held in memory; verification is O(V²) in versions per key. |
| Online migration (CDC, ordering, cutover fencing) | M-05 | Not implemented. Offline, immutable-source migration is the only supported shape. |
| Sybase ASE anything | M-03 | No ASE execution or extraction evidence exists. H2 success does not establish ASE compatibility for temporal types, source routing, collation, or identity/sequence usage. |
| Cursors, aggregates, refresh, full-cache load, mass delete/purge, batch update, dated enrollment, date-range access | R-12 | 20 of 32 persister SPI methods refuse by name. |

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

**Handled incompletely — the sharp edge.** Custom as-of attribute names become `<name>From`/`<name>To`
in the parser, while `DynamoDbWriter` and `Backfill` hard-code `businessDateFrom/To` and
`processingDateFrom/To`, and `PhysicalDesign.Builder` defaults temporal item names to
`FROM_Z`/`THRU_Z`/`IN_Z`/`OUT_Z` rather than deriving them from the parsed mapping. A model whose
temporal axis is called `validDate` needs more than the documented generic setup. Likewise a custom
`KeyStrategy` given to the writer is **not** automatically used by the planner, which has its own
`PartitionKeyEncoder` — changing write keys alone makes reads incompatible.

**Not handled for design derivation.** Inherited metadata, relationships and indexes declared in XML
do not yet drive physical design; GSIs are configured explicitly.

## Tier 4 — the GSI surface

`GsiSpec` is a public type and describes more than the writer implements (R-10):

- `stampGsiKeys()` **skips** sparse-current GSIs and composite GSI keys — yet the planner can still
  select a sparse-current path, and builds it from *base* key attribute names rather than the distinct
  sparse-current attributes `GsiSpec` declares. A configured optimisation can therefore be empty or
  invalid.
- `KEYS_ONLY` and `INCLUDE` projections are declarable, but `TableCreator.toProjection()` uses the
  declared Java names while the codec stores mapped item names, and the executor decodes GSI results
  as full rows without hydrating missing attributes from the base table.

**What is actually proven:** a single foreign-key GSI with an `ALL` projection. That is real — the
relationship differential tests fetch 24 children across 8 parents in one request with no scan. It is
also the only configuration with evidence behind it.

## What "verified" means here

The inspection's sharpest methodological point deserves repeating, because it changes how to read
every other status document in this repository:

> The right review unit is an observable application behavior with an executable acceptance case, not
> a checked chapter or a raw test count.

Several existing differential tests perform the temporal operation **on H2**, read the resulting
history, write those rows into DynamoDB, and compare the copy. That is genuine storage-fidelity
evidence and it is worth having. It is **not** evidence that the DynamoDB persister executes the
original mutation with equivalent failure, concurrency and transaction semantics. When this repository
says "56 storage-path, 6 query-path," that split is exactly the distinction being drawn, and the
storage-path number is the weaker of the two.

## Eligibility, in one paragraph

Today Reladynamo suits a project that: uses bitemporal, audit-only or non-dated Reladomo entities with
standard axis names; addresses rows by a complete logical key or a foreign key with a configured
`ALL`-projected GSI; tolerates eventual consistency on those index reads; keeps units of work well
inside 100 actions; does not depend on cross-JVM write conflict detection **yet**; and can migrate
offline from an immutable H2 source with no path back. Everything outside that is either a permanent
boundary or an open finding, and both are named above.
