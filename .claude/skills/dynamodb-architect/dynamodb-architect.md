---
name: dynamodb-architect
description: Design, review, refactor and debug DynamoDB data layers — access patterns first, key and index design, capacity and cost, and the migration paths for changes that cannot be made in place. Use whenever work touches a DynamoDB table, schema, GSI, or a throttling or cost problem.
user-invocable: true
---

# DynamoDB architect

DynamoDB rewards designing from access patterns and punishes designing from entities. This skill
covers the four decisions that determine whether a table works at scale: how items are keyed, what
is indexed, what capacity mode and item shape cost, and which changes require a migration rather
than an edit.

**Work in this order.** Enumerate access patterns, then choose keys, then add indexes only where a
pattern demands one, then size and cost it. Reversing that order produces a schema that needs a
migration to answer its first unanticipated question.

## Before designing against an existing table

Read the real thing rather than assuming its shape:

```bash
bash scripts/ddb-introspect.sh --all --region <region> --days 14
```

That emits `dynamodb_data_model.json` (keys, indexes, projections, item sizes, real operation mix
from CloudWatch) and `dynamodb_observed.json` (billing mode, throttling, TTL, PITR, replicas). It
is read-only; the sampled scan is opt-in and capped. Design review starts from that, not from what
someone remembers the table looks like.

## Data modelling and key design

### Rules

1. **List access patterns before defining keys, because DynamoDB queries start from an exact partition-key value and cannot rescue an unknown access path with joins.**
   Record each operation as: caller, known inputs, result shape, ordering, consistency need, expected item count, and peak rate.
   Include writes and maintenance operations, not only reads.
   Reject a schema review that says only “store customers and orders”; require “list a customer's last 30 orders newest first.”

2. **Choose a partition key that spreads the hottest workload across many values, because storage diversity does not prevent one popular value from concentrating traffic.**
   Estimate requests per partition-key value at peak, including retries, fan-out, and batch jobs.
   Prefer high-cardinality, workload-aligned identifiers such as `CUSTOMER#<customerId>` over `tenantType`, country, status, or date alone.
   A globally unique ID distributes writes well but is useful only when callers know it; distribution without an access path is not a design.
   If one logical key is predictably hot, write-shard it as `EVENTS#<deviceId>#<shard>` and read all known shards in parallel.
   Sharding trades a hot key for read fan-out, merge logic, and a shard-selection rule; apply it only to measured or credible peak demand.

3. **Use the sort key to encode the range and order callers need, because items in one partition-key value can be queried by sort-key condition and returned in sort-key order.**
   Put the most important grouping component first, followed by orderable components.
   Use lexicographically sortable encodings: fixed-width numbers or ISO-style UTC timestamps, not unpadded `9`, `10`, `11` or locale dates.
   Add a stable tie-breaker when two items can share a timestamp, for example `ORDER#2026-08-11T14:32:09.123Z#01J...`.
   Do not depend on order across different partition-key values; DynamoDB supplies no global sort order.

4. **Build composite sort keys with an unambiguous, versioned grammar, because ad hoc concatenation creates collisions and migrations that every reader must understand.**
   Example grammar: `TYPE#<type>#DATE#<yyyy-mm-dd>#ID#<id>`.
   Escape or forbid the delimiter inside components, validate every component, and centralise encoding and parsing.
   Preserve numeric ordering with fixed width when values may be negative or have different digit counts.
   Prefixing by entity type lets a key condition such as `begins_with(SK, 'ORDER#')` select one family without filtering unrelated items.

5. **Encode hierarchy only when callers traverse it by known prefixes, because a hierarchical sort key supports prefix queries but not arbitrary descendant search.**
   `ORG#acme#SITE#denver#DEVICE#pump-17` supports organisation, site, and device prefixes in that order.
   It does not efficiently answer “find every `pump-17` across all organisations” unless another key or index supports that access pattern.
   Duplicate the path in a second index when reverse or alternate traversal is required; do not expect one hierarchy to serve both directions.

6. **Keep related items under one partition key when they are read together and their item collection remains bounded, because one query can retrieve the group without scanning.**
   An item collection is all base-table items sharing a partition-key value, together with their local-secondary-index representations when such indexes exist.
   Bound collections by business reality and retention: orders per customer may be bounded enough; every event for a global service is not.
   Split by time bucket or shard when a collection can grow without limit, then make the fan-out explicit in the repository API.
   Check current service limits before committing to a maximum-size assumption; do not invent a safe item-collection ceiling.

7. **Model one-to-many relationships around the parent identifier when parent-scoped retrieval dominates, because child items can share the parent's partition key.**
   Store the parent and children as distinct items so updates do not rewrite one ever-growing document.
   Use prefixes to distinguish entity types and reserve sort-key space for future relationships.
   Add a direct-lookup index or a separate mapping item when callers know only the child ID.

8. **Model many-to-many relationships as explicit edge items in both required directions, because DynamoDB does not perform joins or reverse an adjacency list automatically.**
   For users and projects, write `USER#u42 -> PROJECT#p7` and `PROJECT#p7 -> USER#u42` edges.
   Update both directions in one transaction when the relationship must never be half-visible.
   If temporary asymmetry is acceptable, use idempotent writes plus reconciliation and state that consistency contract in code.
   Store relationship attributes such as role and joined time on the edge, not only on either endpoint.

9. **Denormalise fields needed by a read response, because fetching referenced records one by one turns a query into latency and request amplification.**
   Copy stable display data such as customer name onto an order summary when the page must render without a second lookup.
   Decide whether copies are snapshots or maintained replicas.
   A snapshot intentionally preserves “name at purchase”; a replica requires fan-out updates, failure recovery, and a way to find every copy.
   Quantify the bargain: reads and latency saved versus extra writes, storage, transactional scope, and repair work.

10. **Choose single-table design only when colocated item families materially satisfy known access patterns, because shared key space adds coupling and operational complexity.**
    Single-table is a tactic for query composition, transactions, and request reduction; it is not a DynamoDB maturity badge.
    Prefer multiple tables when entities have unrelated access patterns, retention, encryption, backup, throughput, ownership, or deployment boundaries.
    Do not combine data merely because it belongs to one application.

11. **Represent every supported access pattern in a key-schema matrix, because prose hides scans, filters, and overloaded-index collisions.**

| Access pattern | Key used | Key condition | Expected bound |
|---|---|---|---|
| Get order by ID | base table | `PK = ORDER#o91`, `SK = META` | 1 item |
| List customer orders | base table | `PK = CUSTOMER#c18`, `SK begins_with ORDER#` | page size |
| List projects for user | base table | `PK = USER#u42`, `SK begins_with PROJECT#` | memberships per user |

    Treat a filter expression as post-read selection, not key design; filtered items still consume read work and can produce sparse pages.
    Treat a scan as an explicit batch/administrative choice, never as the hidden implementation of an interactive endpoint.

12. **Plan immutable key changes as data migrations, because a table's primary-key definition cannot be edited in place and an item's primary-key values identify the item.**
    To change an item's key value, write the item at the new key and delete the old item; protect concurrent writes during the move.
    To change the table partition-key or sort-key schema, create a new table, backfill it, capture or dual-write changes, validate, cut reads over, then retire the old table after rollback expires.
    A new secondary index can sometimes add an access path, but it does not change the base primary key and should not be presented as a primary-key migration.
    Treat key encodings as durable APIs: introduce `v2` items or a new table when parsing semantics change incompatibly.

### Worked example: customer orders

An order service must get an order by ID, list a customer's orders newest first, and show each order with the customer's name as it appeared at checkout.

**Before:** one item uses `PK = ORDER#<orderId>`, with `customerId` and an unindexed text timestamp as attributes.
`GetOrder(o91)` is efficient, but `ListOrders(c18)` requires a scan plus filtering and client-side sorting.
Adding a relational-style `customers` reference also causes one customer read per rendered order unless the application batches and caches perfectly.

**After:** write two purposeful representations.

| Item purpose | PK | SK | Selected attributes |
|---|---|---|---|
| Canonical order | `ORDER#o91` | `META` | `customerId`, totals, lines, status |
| Customer order summary | `CUSTOMER#c18` | `ORDER#2026-08-11T14:32:09.123Z#o91` | `orderId`, total, status, `customerNameAtCheckout` |

`GetOrder(o91)` performs an exact key read.
`ListOrders(c18, from, to)` queries `PK = CUSTOMER#c18` with a bounded `ORDER#<timestamp>` sort-key range and reverses scan direction for newest first.
The duplicated summary adds one write per order creation and further writes when replicated status changes.
The copied customer name is a snapshot, so a later customer rename does not fan out and historical receipts remain stable.
Write the canonical item and summary transactionally if callers must never observe one without the other.

### Worked example: organisation hierarchy and a hot-key repair

A monitoring system lists sites for an organisation, devices for a site, and recent readings for one device.

**Before:** all readings use `PK = ORG#acme` and `SK = READING#<timestamp>#<deviceId>`.
This creates one heavily used logical key, cannot isolate one device with a contiguous timestamp range, and mixes metadata with an unbounded stream.

**After:** separate bounded metadata traversal from time-series traffic.

| Item family | PK | SK | Access pattern |
|---|---|---|---|
| Organisation metadata | `ORG#acme` | `META` | Get organisation |
| Site metadata | `ORG#acme` | `SITE#denver` | List sites |
| Device metadata | `ORG#acme` | `SITE#denver#DEVICE#pump-17` | List devices at site |
| Reading | `DEVICE#pump-17#2026-08` | `TS#2026-08-11T14:32:09.123Z#01J...` | Query device readings for month and range |

The hierarchy query uses `begins_with(SK, 'SITE#denver#DEVICE#')`.
Reading buckets distribute storage and bound each device's collection, while a range spanning months issues one query per month and merges results.
If one device still exceeds acceptable write concentration, add a small shard suffix to the reading partition key; the reader must query every shard for that month.
Do not shard organisation metadata: its traffic and collection are bounded, so fan-out would buy nothing.

### Worked example: bidirectional project membership

The application must list projects for a user and members for a project, including each member's role.

**Before:** a project item contains a growing `memberIds` array.
Adding one member rewrites the item, concurrent edits contend, item size grows toward a hard service limit, and listing a user's projects requires scanning projects.

**After:** store two edge items per membership.

| Direction | PK | SK | Attributes |
|---|---|---|---|
| User to project | `USER#u42` | `PROJECT#p7` | `role=editor`, `joinedAt=...` |
| Project to user | `PROJECT#p7` | `USER#u42` | `role=editor`, `joinedAt=...` |

Queries now start from either known endpoint and page through its edges.
The cost is two writes, duplicated relationship attributes, and coordinated role changes.
Use a transaction for membership creation, removal, and role changes when authorization depends immediately on both views.

### Traps to call out in reviews

| Wrong instinct | What is true instead |
|---|---|
| “Normalise first; joins can reconstruct the view.” | DynamoDB has no server-side joins; shape items around reads and duplicate deliberately. |
| “A high-cardinality attribute is automatically a good partition key.” | Callers must know the value, and per-value traffic must still be acceptably distributed. |
| “A sort key sorts the whole table.” | Ordering exists only among items with the same partition-key value. |
| “Filters make a broad query efficient.” | Key selection determines what is read; filtering happens after that work. |
| “One giant tenant partition makes tenant reads easy.” | It can create concentrated traffic and unbounded collections; bucket or shard only the hot families. |
| “Single-table means every entity belongs in one table.” | Colocation must serve access patterns; separate operational boundaries often deserve separate tables. |
| “Embed every child in the parent.” | Growing arrays cause large rewrites, contention, and item-size risk; separate child items usually scale better. |
| “A secondary index is a free alternate table.” | It duplicates projected data, adds write and storage cost, and has its own key distribution and consistency semantics. |
| “Changing key names or delimiters is a refactor.” | Persisted keys are data; incompatible changes require dual-read/write or backfill and cutover. |
| “Denormalised copies will somehow remain correct.” | Each copy needs snapshot semantics or an explicit propagation and repair mechanism. |

### Review checklist

- Verify every online read names an exact partition key and, where needed, a sort-key condition.
- Verify peak traffic is estimated per key value, not averaged across the table.
- Verify each item collection has a credible size and retention bound.
- Verify composite-key encoding is deterministic, sortable, validated, and centrally implemented.
- Verify every duplicated field is labelled snapshot or maintained replica.
- Verify every bidirectional edge has an atomic or repairable update contract.
- Verify scans and filters are intentional and excluded from latency-sensitive paths.
- Verify immutable key changes have backfill, change capture, validation, cutover, rollback, and retirement steps.
- Verify exact current quotas and limits against authoritative service information before implementation.

## Secondary indexes

### Rules

1. **Choose a GSI unless the query must share the base partition key and requires strongly consistent reads.** A GSI may use any partition and sort keys, but its reads are eventually consistent; an LSI keeps the table partition key and can be read strongly consistently, but its key schema is fixed when the table is created.

2. **Create an LSI only when its permanent constraints are acceptable.** An LSI cannot be added, removed, or replaced after table creation, and each base-table partition key value shares a finite item-collection size across the table and all LSIs; choosing one can force a table migration later.

3. **Use a GSI when the alternate access path needs a different partitioning strategy.** A different GSI partition key redistributes the index independently of the base key, while an LSI keeps all alternate-sort-order traffic under the original partition key.

4. **Design every index from a named access pattern, including its key condition, order, and returned attributes.** An index created merely for possible filtering adds write cost and storage on every qualifying mutation without guaranteeing an efficient query.

5. **Put predicates that bound the read into index keys; do not rely on a filter expression for selectivity.** DynamoDB reads matching key ranges before applying the filter, so filtering reduces returned data but not the read work already performed.

6. **Select the smallest projection that serves the hot path.** `KEYS_ONLY` stores index keys plus the base-table primary key, `INCLUDE` adds named attributes, and `ALL` copies every attribute; wider projections consume more index storage and make more base-item updates propagate to the index.

7. **Treat a GSI query as a complete read or an explicit two-step read.** A GSI cannot fetch nonprojected attributes from the base table automatically; either project them or query keys and issue a `BatchGetItem`, accepting the extra request, latency, and consistency semantics.

8. **Account for projection cost in bytes, not only attribute count.** Attribute names and values occupy index storage, and an index entry is billed and provisioned according to its own item size; verbose names or large documents can push entries across capacity rounding boundaries.

9. **Make an index sparse by omitting its partition-key attribute from items that should not appear.** Only items containing valid values for every index key attribute are written to the index, making rare-state queries cheap without a boolean value on every item.

10. **Delete the sparse-index key when an item leaves the indexed state.** Writing `status = "CLOSED"` while leaving `openQueueKey` present keeps the item indexed; absence, not a filter on the indexed value, creates sparsity.

11. **Use low-cardinality constants as GSI partition keys only when the resulting traffic is safely bounded.** Keys such as `OPEN` or `GLOBAL` concentrate writes and reads into few logical index partitions; shard the value or add a distributing dimension when volume can become hot.

12. **Overload an index only when entity types have compatible traffic and unambiguous key formats.** Reusing one GSI for several access patterns reduces index count and write amplification, but unclear prefixes or colliding values can mix entity types and make queries unsafe.

13. **Budget one additional index write for each affected index entry.** A base-item put, update, or delete can also insert, update, or remove an entry in every index whose keys or projected data change; several indexes multiply write capacity, storage, throttling surfaces, and replication work.

14. **Do not assume every base-table update rewrites every index.** An index is affected when indexed keys or projected attributes change, or when the item enters or leaves that index; updates confined to nonprojected attributes do not need to change that index entry.

15. **Provision and monitor each GSI as a separate write path when using provisioned capacity.** A GSI that cannot accept propagated writes can throttle base-table writes, so table headroom does not compensate for an underprovisioned index.

16. **Expect propagation delay and design correctness outside a GSI.** A successful base-table write may not be visible in a GSI immediately, so uniqueness checks, read-after-write confirmation, and state-machine guards must use authoritative items and conditional writes rather than a GSI query.

17. **Keep index key values scalar and type-stable.** Index keys must use supported scalar key types, and items with mismatched key types can fail index backfill or writes; encode composite dimensions deliberately, for example `REGION#us-west-2#DAY#2026-08-11`.

18. **Plan GSI creation and deletion as online migrations, not instant schema edits.** Adding a GSI backfills existing eligible items while live writes continue, consumes resources, and is not usable until active; deleting it removes the access path and its data, so deploy readers and writers in a safe order.

19. **Assume index key schema and projection are immutable in place.** To change either, create a replacement GSI with the desired definition, wait for backfill, validate it, switch traffic, and then delete the old GSI; for an LSI, create and backfill a replacement table instead.

20. **Verify current service quotas and exact capacity limits before committing the design.** Index counts, projection limits, item-collection limits, and operational quotas can constrain a migration; use the current account and Region values rather than an agent's remembered number.

### GSI versus LSI

| Decision | GSI | LSI | Consequence |
|---|---|---|---|
| Partition key | May differ from the table | Must equal the table partition key | Only a GSI can regroup data across base partitions. |
| Sort key | Independent | Alternate sort key within the same base partition key | An LSI provides another ordering of one item collection. |
| Read consistency | Eventual only | Eventual or strong | Do not put correctness-critical read-after-write logic on a GSI. |
| Lifecycle | Can be added and deleted | Defined only with table creation | An LSI schema change requires a new table. |
| Capacity mode | Uses its own provisioned throughput settings when applicable | Consumes table throughput | A GSI needs independent capacity planning in provisioned mode. |
| Storage constraint | Separate index storage | Contributes to the per-partition-key item collection limit | An unbounded tenant or account key is dangerous with LSIs. |
| Nonprojected data | Cannot be fetched through the index query | May be fetched from the table, at added read cost | GSI projection omissions require a separate base-table read. |

An LSI decision forecloses changing its alternate sort key, projection, or existence without replacing the table. It also forecloses unbounded growth for one table partition-key value because the item collection has a service limit. A GSI avoids those lifecycle constraints, but forecloses strongly consistent index reads and introduces an independently throttled propagation path.

### Projection choices

| Projection | Index entry contains | Use when | Cost consequence |
|---|---|---|---|
| `KEYS_ONLY` | Index keys and base primary-key attributes | The query selects candidates for a later base-table read | Smallest entries, but often adds a second network round trip and base-table reads. |
| `INCLUDE` | Keys plus an explicit attribute list | A stable, narrow response is read frequently | Avoids base reads while copying only the named payload into each entry. |
| `ALL` | All base-item attributes | The indexed view must serve most of the item | Highest storage exposure; unrelated large attributes can enlarge index writes and entries. |

Projection is not a free covering-index switch. If `description`, `auditTrail`, or a growing JSON document is projected, every relevant change must propagate and the copied bytes remain stored for every index entry. Conversely, a narrow projection can cost more overall if every request immediately fetches the base item. Estimate both paths with representative item sizes and request rates.

### Worked example: replace a scan with a sparse operational queue

An orders table starts with:

```text
PK = TENANT#<tenantId>
SK = ORDER#<orderId>
attributes: fulfillmentStatus, promisedAt, customerId, totalCents
```

The access pattern is: “for one warehouse, list the next 50 orders requiring picking, earliest deadline first.” The first implementation scans the table and filters `fulfillmentStatus = "READY"`. As history grows, it reads closed and cancelled orders before discarding them.

Add this GSI:

```text
GSI1PK = PICKQUEUE#<warehouseId>   # present only while READY
GSI1SK = <promisedAt>#ORDER#<orderId>
projection = INCLUDE(customerId, totalCents)
```

Before, the worker scans unrelated history. After, it issues `Query(GSI1PK = "PICKQUEUE#DEN-03", limit 50, ascending)` and reads only queue entries in deadline order. The `orderId` suffix makes ordering deterministic when deadlines match.

When an order becomes ready, atomically set `fulfillmentStatus`, `GSI1PK`, and `GSI1SK` on the base item. When picking starts, remove both GSI key attributes with an update. The removal generates index write work, but the steady-state index contains only actionable orders. Do not keep `GSI1PK` and add a filter for status; that destroys sparsity and leaves stale work discoverable.

If all warehouses used `GSI1PK = "READY"`, a busy fleet would converge on one key. Partitioning by warehouse makes each worker queryable and distributes traffic. If a single warehouse is still too hot, use deterministic shards such as `PICKQUEUE#DEN-03#00` through `#07` and merge eight ordered queries in the application; this trades simple pagination for distributed load.

### Worked example: migrate an immutable access path

A support system stores tickets as:

```text
PK = ORG#<organizationId>
SK = TICKET#<ticketId>
GSI1PK = ASSIGNEE#<agentId>
GSI1SK = <updatedAt>#TICKET#<ticketId>
projection = INCLUDE(subject, priority, status)
```

The original access pattern lists an agent's tickets by last update. A new requirement needs open tickets ordered by priority and SLA deadline. Changing `GSI1SK` in place is impossible, and filtering the old index still reads closed tickets and cannot establish the required order.

Create a replacement:

```text
GSI2PK = ASSIGNEE#<agentId>#STATUS#OPEN
GSI2SK = P<priority>#<slaDeadline>#TICKET#<ticketId>
projection = INCLUDE(subject, updatedAt)
```

Use a sparse key: only open tickets receive `GSI2PK` and `GSI2SK`. The migration sequence is:

1. Deploy writers that maintain the new attributes while still maintaining the old ones.
2. Backfill the new attributes on existing open tickets with rate limits, retries, and conditional guards against overwriting newer state.
3. Create the new GSI before or during the attribute backfill; items become eligible whenever their full new key exists.
4. Wait until the GSI is active, then compare sampled query results with authoritative base items and operational counts.
5. Deploy readers to use GSI2, retaining a rollback switch to GSI1.
6. After the rollback window, stop maintaining GSI1 attributes and delete GSI1 if no other pattern uses it.

The priority encoding must preserve lexical order: fixed-width numeric values or explicit rank tokens are safer than unpadded numbers. GSI2 remains eventually consistent, so ticket assignment and status transitions still use conditional updates on the base item.

If the original alternate path were an LSI, none of these index-only steps could replace it. Create a new table with the desired table and LSI definitions, dual-write or stream-copy changes, backfill and reconcile historical items, cut readers over, then retire the old table after a rollback period.

### Index overloading

One GSI can serve unrelated entity types when its key grammar keeps their query spaces separate:

```text
Customer item: GSI1PK = EMAIL#<normalizedEmail>, GSI1SK = CUSTOMER#<customerId>
Invoice item:  GSI1PK = ACCOUNT#<accountId>,   GSI1SK = DUE#<dueDate>#INVOICE#<invoiceId>
Shipment item: GSI1PK = TRACKING#<carrier>#<trackingNumber>, GSI1SK = SHIPMENT#<shipmentId>
```

This supports customer lookup by email, invoices by account and due date, and shipment lookup by tracking number with one GSI. Prefixes prevent accidental overlap, and each query names exactly one key shape. The saving is material: an item participates only when it carries that index's key attributes, so unrelated entities do not pay an entry merely because the GSI exists.

Do not overload when one workload can starve the others, when different projections would force a wide union of attributes, or when access control cannot safely distinguish entity types. In those cases, separate GSIs provide isolation and narrower entries even though they add operational and write cost.

### Traps to call out in review

- **Trap: “A secondary index is just another B-tree.”** It is a separately maintained distributed view. Its partition key controls load placement, and a GSI has lag and its own capacity behavior.
- **Trap: “A filter makes a broad query cheap.”** The key range determines what DynamoDB reads; the filter runs afterward.
- **Trap: “Project `ALL` now for flexibility.”** Flexibility becomes recurring storage and write propagation for attributes the access pattern may never return.
- **Trap: “A missing projected field can be fetched automatically.”** A GSI returns only its projection; application code must fetch the base item explicitly.
- **Trap: “Use an LSI because local sounds faster.”** “Local” describes sharing the base partition key, not a general latency advantage, and it imposes creation-time and item-collection constraints.
- **Trap: “Add an index for every query.”** Each qualifying index is another materialized write target; reshape or overload compatible patterns before multiplying indexes.
- **Trap: “A boolean sparse index is sparse.”** If every item stores the indexed boolean, every item appears in the index. Omit the index key on nonmembers.
- **Trap: “A GSI can enforce uniqueness.”** Propagation is asynchronous, so two writers can both observe no result. Reserve a uniquely keyed base-table item with a conditional put instead.
- **Trap: “Renaming an index field changes the index definition.”** Attribute names used as index keys are part of the immutable schema. Build a replacement index and migrate.
- **Trap: “Deleting the old GSI completes migration.”** First remove all reader dependencies and retain a rollback interval; deletion destroys that access path and requires a new backfill to recreate it.

## Capacity, cost and performance

### Rules

1. **Choose on-demand capacity for unknown, intermittent, or rapidly changing traffic, because it removes capacity forecasting and charges for completed request units rather than reserved throughput.** Do not treat it as unlimited: sudden concentration on one key or an abrupt jump beyond the table's recent traffic can still throttle.

2. **Choose provisioned capacity for steady, measurable traffic, because scheduled capacity or auto scaling can cost less than on-demand when utilization stays high.** Set separate read and write targets and leave headroom; auto scaling reacts after metrics arrive and cannot prevent every short spike.

3. **Compare modes with measured consumed capacity, not request count, because item size, consistency, transactions, indexes, and retries change the units consumed by otherwise identical-looking operations.** Include normal peaks and batch jobs in the sample.

4. **Estimate every read from the bytes DynamoDB must read before projection or filtering, because returning fewer attributes or filtered rows generally does not make the underlying read cheaper.** A query that examines large items and returns one small attribute is billed from the examined item sizes.

5. **Keep frequently read or written items small, because DynamoDB rounds item size up in fixed billing-unit increments.** Splitting a cold payload from a hot summary can reduce capacity use, but it adds another access path and possibly another request.

6. **Model strong consistency and transactions explicitly, because they consume more capacity than ordinary eventually consistent operations.** Do not derive a budget from eventual, nontransactional tests and apply it to strongly consistent or transactional production calls.

7. **Count every index write, because a base-table mutation can also consume write capacity on each global secondary index whose projected data or key changes.** Sparse indexes cost nothing for items absent from the index, but indexed items add storage and write work.

8. **Spread high-rate traffic across many partition-key values, because DynamoDB routes an individual partition-key value to a limited physical placement.** High table-wide capacity does not rescue a single overloaded key reliably.

9. **Shard an unavoidable hot write key deliberately, because adding a bounded suffix distributes writes across partition-key values.** Accept that reading the logical aggregate now requires querying every shard and merging results; choose the shard count from measured peak load with growth headroom.

10. **Retry throttled requests with capped exponential backoff and jitter, because immediate synchronized retries amplify overload.** Bound total attempts, preserve idempotency, and surface exhaustion rather than hiding sustained capacity failure behind latency.

11. **Inspect the throttling reason and resource identifier returned by the service and correlate them with consumed-capacity and throttling metrics, because “the table has spare capacity” does not distinguish a hot key, an index bottleneck, an account limit, or provisioned exhaustion.** Diagnose the named resource, not only the base table.

12. **Treat adaptive capacity as assistance, not a schema guarantee, because it can shift available throughput toward busier partitions but cannot create unlimited throughput, override account or table ceilings, or make one pathological key scale without bound.** Design distribution first.

13. **Use `Query` for request paths and reserve `Scan` for bounded maintenance or analytics, because a scan reads items across the table or index and consumes capacity in proportion to data examined.** Run large scans with controlled page size, rate limits, checkpoints, and preferably away from latency-sensitive workloads.

14. **Never claim that a scan filter saves read capacity, because DynamoDB reads a page before applying the filter.** A filter can reduce response bytes and application work, but `ScannedCount` can remain high while `Count` is low and the read charge follows the examined data.

15. **Make irreversible key-design changes through a new table or index and a migration, because a table's primary partition key and sort key cannot be replaced in place.** Create the destination schema, backfill it, capture concurrent changes with dual writes or DynamoDB Streams, verify parity, switch reads and writes, then retire the old table after a rollback window.

### Capacity-mode decision

| Condition | Prefer | Consequence to plan for |
|---|---|---|
| New workload with no trustworthy traffic profile | On-demand | Higher unit price may buy useful uncertainty reduction; load concentration can still throttle. |
| Bursty or idle for long periods | On-demand | Charges follow requests instead of idle provisioned units. |
| Stable baseline with predictable peaks | Provisioned plus auto scaling | Lower cost may be possible, but scaling lag requires headroom. |
| Predictable event at a known time | Provisioned with scheduled scaling, or validated on-demand | Capacity must be raised before the event; reactive scaling is late. |
| Strict cost ceiling | Provisioned with alarms and admission control | The ceiling can manifest as throttling rather than a surprise bill. |
| Unbounded public traffic | Neither mode alone | Add quotas, caching, backpressure, and abuse controls; capacity mode is not admission control. |

Changing capacity mode is operationally possible; it is not a primary-key migration. AWS limits how frequently some mode changes can occur, so check the current service rule before automating frequent switches.

### What drives the bill

Account for these components separately:

- reads and writes, with rates determined by capacity mode;
- bytes per item as evaluated by the operation, rounded to the applicable unit boundary;
- eventual versus strong consistency and transactional versus nontransactional access;
- reads and writes against global secondary indexes;
- table and index storage, including projected index attributes;
- optional features such as backups, point-in-time recovery, Streams, global replication, exports, imports, and data transfer;
- failed conditional writes, transactional coordination, and retries where the operation's documented billing rules apply.

For standard reads, one read-capacity unit covers a strongly consistent read of up to 4 KiB; an eventually consistent read of that size uses half as much read capacity. For standard writes, one write-capacity unit covers a write of up to 1 KiB. Round each item up before totaling: ten 100-byte writes consume ten write units, not one 1,000-byte unit. Transactional operations use higher multipliers. Check current pricing and feature-specific billing before producing a monetary forecast.

Batch APIs reduce network round trips; they do not combine items into one billing unit. A batch of small writes is still charged item by item, and unprocessed entries must be retried with backoff.

### Worked example: separate the hot summary from the cold document

An order service starts with this schema:

```text
Table: Commerce
PK = TENANT#northwind
SK = ORDER#2026-08-11#98142
attributes = status, total, customerName, shippingAddress, 18 KiB fraudReport
```

The dashboard runs `GetItem` with eventual consistency 200 times per second and projects only `status` and `total`. Projection does not change the capacity calculation: DynamoDB still reads the roughly 18 KiB item, rounded in 4 KiB read chunks. The large cold report therefore makes every dashboard refresh materially more expensive.

Refactor the item collection:

```text
PK = TENANT#northwind, SK = ORDER#2026-08-11#98142          // 0.8 KiB summary
PK = TENANT#northwind, SK = ORDER#2026-08-11#98142#REPORT   // 18 KiB report
```

Now the dashboard reads only the summary, while the fraud-review screen fetches the report on demand. The before/after changes the bytes examined, unlike a projection expression. The tradeoff is an extra read when a caller needs both records and application logic to keep them consistent.

This schema still risks a tenant hot spot if one tenant dominates traffic. If that is plausible, use a higher-cardinality partition key such as `TENANT#northwind#ORDER#98142` and place related order records under that key. Changing the existing table primary key requires the migration described in Rule 15.

### Worked example: shard a celebrity write key

An event-ingestion table initially stores minute counters as:

```text
PK = VIDEO#v_8472
SK = MINUTE#2026-08-11T15:34Z
UpdateItem: ADD viewCount :one
```

A live concert sends 20,000 increments per second to the same item. Raising table capacity does not ensure that this one partition-key value can absorb the rate, and concurrent updates contend on the same item.

Write-shard the counter:

```text
PK = VIDEO#v_8472#SHARD#00..31
SK = MINUTE#2026-08-11T15:34Z
UpdateItem: ADD viewCount :one
```

Select the suffix using a stable hash of an event identifier, or a random value when duplicate-event handling is solved elsewhere. A read of the minute total now issues 32 keyed queries or gets and sums the counters. Cache or periodically roll up completed minutes so readers do not fan out on every request.

Before deployment, load-test the candidate shard count using realistic item sizes and concurrency. Increasing from 32 to 64 shards later is an application protocol change: version the key format, let readers understand both generations, dual-write or backfill as required, then retire the old generation. It does not require changing the table's declared primary-key attributes.

### Throttling diagnosis

Throttling is a control response, not proof that DynamoDB is generally unavailable. It commonly results from:

- consumed provisioned throughput exceeding the configured table or index capacity;
- concentrated traffic exhausting what one key or physical partition can serve;
- on-demand traffic rising too abruptly from a low recent baseline;
- exceeding an account-level or service quota;
- a global secondary index receiving a write pattern its capacity cannot accept, which can backpressure base-table writes.

At the client, throttling appears as an exception for single-item operations or as unprocessed items/keys in batch responses. SDK retries may convert it into high tail latency and eventual timeout, so record attempt count, final error, operation, table or index, and throttling reason. Do not report only the HTTP status.

Use this order when debugging:

1. Identify whether the table or a specific index is named.
2. Compare consumed capacity, configured capacity, and throttle metrics over the same interval.
3. Inspect key-frequency distribution; averages conceal a hot partition key.
4. Check item sizes, consistency choices, transactions, and retry volume for a unit-cost jump.
5. Check account and table quotas and any recent on-demand traffic step-change.
6. Fix distribution or capacity, then verify that retries and tail latency return to normal.

### Scan controls

A filter expression is evaluated after items are read. For example, scanning `Commerce` with `status = 'LATE'` may examine 10 million orders and return 2,000; it is still a 10-million-item scan for capacity purposes. Model a queryable key or a sparse index such as `GSI1PK = STATUS#LATE`, `GSI1SK = promisedAt#orderId` when “find late orders” is a recurring access pattern.

Parallel scan shortens wall-clock time by applying more concurrent pressure; it does not reduce total read work. Limit concurrency, paginate deliberately, and persist segment checkpoints for restartable jobs. If analytics must repeatedly read most of the table, export or replicate data into a system designed for that workload instead of competing with production requests.

### Explicit traps

- **Trap: “One request equals one unit.”** False. Units depend on rounded item size and operation semantics; a request can consume fractions, one unit, or many units.
- **Trap: “Projection makes a large item cheap.”** False. Projection reduces returned attributes, not the item size used for the read calculation.
- **Trap: “A filter turns a scan into an index lookup.”** False. Filtering happens after the read; use a key condition on a suitable table or index.
- **Trap: “Unused table capacity guarantees no throttling.”** False. A hot key, index, quota, or sudden on-demand ramp can throttle while table-level averages look comfortable.
- **Trap: “Adaptive capacity fixes a low-cardinality partition key.”** False. It redistributes available capacity under service constraints; it does not remove per-key concentration.
- **Trap: “Auto scaling handles spikes immediately.”** False. Metrics, alarms, and updates take time; pre-scale known events and keep headroom.
- **Trap: “Batch operations are bulk-discount operations.”** False. They reduce calls, while capacity is still calculated from the individual items.
- **Trap: “More scan segments make the scan cheaper.”** False. Parallelism changes elapsed time and pressure, not the amount examined.
- **Trap: “A primary key can be altered like a relational index.”** False. Create a new table with the new key, copy and synchronize data, cut over, and retain rollback capability.
- **Trap: “A global secondary index is a free read optimization.”** False. It adds storage and write cost, can throttle independently, and is eventually consistent.

## Operations, refactoring and debugging

### Rules

1. **Treat a DynamoDB Stream as a short-lived change log, not a durable queue.** Process records idempotently and alarm on iterator age, because records expire after 24 hours and a stalled consumer can permanently miss changes.
2. **Choose the stream image deliberately.** Use keys only for invalidation, new images for projections, and old plus new images for change comparison, because larger records increase downstream work and a keys-only stream cannot reconstruct deleted data.
3. **Give each independent stream application its own consumer path and failure handling.** A search indexer, audit writer, and notification sender must not share one checkpoint, because one poison record or slow dependency otherwise blocks unrelated work.
4. **Assume stream delivery is at least once and ordering is only per item key.** Deduplicate side effects and never infer a total table order, because retries can repeat records and different partitions advance independently.
5. **Make stream processing failure explicit.** Configure bounded retries, a failure destination or durable quarantine, and an alert, because infinite retry hides lag while dropping failures silently corrupts projections.
6. **Use TTL only for asynchronous garbage collection.** Filter expired items in reads when expiry matters, because an item remains readable and billable until the background deletion occurs.
7. **Store TTL as an epoch timestamp in seconds in a Number attribute.** Validate units and attribute type at writes, because milliseconds or strings will not expire when intended.
8. **Do not use TTL as a scheduler or exact deletion deadline.** Use a scheduled workflow for prompt actions, because TTL deletion timing is nondeterministic and can take days.
9. **Enable point-in-time recovery for production tables and test restoration.** Recovery that has never been restored is only an assumption, and restoration creates a new table rather than rewinding the existing one.
10. **Take named on-demand backups for durable milestones, not as a substitute for point-in-time recovery.** A release or compliance snapshot needs an intentional retention decision, while point-in-time recovery covers accidental changes within its rolling window.
11. **Plan restores as migrations.** Recreate omitted surrounding configuration, validate data, redirect traffic, and retire the old table later, because table restore does not magically replace the live table or every integration attached to it.
12. **Design global-table writes so concurrent updates in different Regions are acceptable under last-writer-wins.** Conflict resolution selects one replicated item image using service timestamps; it does not merge attributes or honor an application version counter.
13. **Avoid multi-Region writes to the same logical item when losing either update is unacceptable.** Route ownership to one Region or write immutable, uniquely keyed events, because two successful local writes can converge with one entire item image discarded.
14. **Do not claim cross-Region atomicity from a local transaction.** A transaction is atomic in the Region where it runs, while global-table replication occurs afterward and concurrent remote writes may still conflict.
15. **Use transactions only for invariants spanning items.** Prefer a conditional single-item write when possible, because transactional reads and writes consume twice the capacity of their non-transactional equivalents and add latency and contention.
16. **Bound every transaction and make its retry safe.** Keep the item set small, use conditions to detect stale state, and supply a client request token where the API supports it, because timeouts leave the caller uncertain whether the commit occurred.
17. **Make every externally retried command idempotent.** Persist a request identifier with the result or use a conditional marker item in the same transaction, because SDK, queue, and network retries can repeat a successful mutation.
18. **Separate idempotency from uniqueness.** A request token prevents the same request from applying twice; a conditional put on a stable business key prevents two different requests from claiming the same email, invoice number, or reservation.
19. **Classify schema changes before editing code.** Attribute names and shapes are application conventions, but table keys and local secondary indexes are physical choices, because confusing the two turns a deploy into an unrecoverable cutover.
20. **Make compatible changes additively.** Write the old and new representations, deploy readers that prefer new and fall back to old, observe, then stop the old write, because mixed application versions and old rows coexist during rollout.
21. **Replace an index rather than mutating its key schema.** Create a new global secondary index with a new name, wait until it is active, validate it, switch reads, then delete the old index, because a GSI partition or sort key cannot be changed in place.
22. **Create a new table to change the table primary key or any local secondary index design.** Dual-write or stream-replicate into the new table, backfill, reconcile, cut over, and retain a rollback window, because those definitions cannot be altered after table creation.
23. **Backfill history only when old items must satisfy the new access pattern or invariant.** A new optional field needed only on future writes requires no backfill; a new GSI whose key attributes are absent from old items does.
24. **Throttle backfills and make them restartable.** Use segmented scans, checkpoints, conditional updates, and a capacity budget, because an uncontrolled scan-and-write job competes with production and retries can overwrite newer values.
25. **Debug throttling at the exact resource and operation.** Separate table from index, reads from writes, and base-table work from stream or backfill work, because aggregate graphs conceal the partition or index actually rejecting requests.
26. **Debug bills by decomposing every charged feature.** Inspect table and index reads and writes, storage, backups, streams, replication, exports, and data transfer, because a quiet base table can still have expensive secondary work.
27. **Reject unbounded scans in request paths.** Require a key-bounded Query, a strict limit plus continuation token, or an offline job, because a filter expression is applied after items are read and does not cap consumed capacity.

### Change matrix

| Change | In place? | Safe path and consequence |
|---|---:|---|
| Add or remove an ordinary attribute | Yes | Deploy tolerant readers; items have no enforced uniform schema. |
| Change an attribute's meaning or type | Not safely | Add a new attribute, dual-write, optionally backfill, then remove fallback. Reusing the name makes old and new values ambiguous. |
| Change provisioned capacity or on-demand mode | Usually | Update configuration, but check current service restrictions and switching limits before scheduling. |
| Enable point-in-time recovery, TTL, or streams | Yes | Update table settings; account for activation time and consumer changes. |
| Change stream view type | Disruptive | Disable and re-enable the stream, then move consumers to the new stream ARN; the old and new logs are distinct. |
| Add a global secondary index | Yes, asynchronously | Create it and wait for backfill to finish; construction consumes resources and queries cannot use it until ready. |
| Change a GSI key or projection | No | Build a replacement GSI, cut reads over, then delete the old one. |
| Remove a GSI | Yes, destructive | Prove no caller uses it, delete it, and expect recreation to require another backfill. |
| Change table partition or sort key | No | Migrate to a new table and cut over. |
| Add, remove, or change an LSI | No | Migrate to a new table; LSIs are fixed at table creation. |
| Change encryption or replication topology | Configuration-dependent | Check current regional and key constraints, test the transition, and avoid inventing an exact limit. |

### Worked example: idempotent order placement

The `Commerce` table uses `PK=ACCOUNT#<accountId>` and `SK=ORDER#<orderId>` for orders. Inventory reservations use `PK=SKU#<sku>` and `SK=RESERVATION#<orderId>`.

Access pattern: `PlaceOrder(accountId, orderId, sku, requestId)` must create one order and one reservation even if the API gateway retries after a timeout.

Use one transaction containing:

1. `Put PK=REQUEST#<requestId>, SK=RESULT` with `attribute_not_exists(PK)` and the serialized response.
2. `Put PK=ACCOUNT#<accountId>, SK=ORDER#<orderId>` with a condition that the order does not exist.
3. `Put PK=SKU#<sku>, SK=RESERVATION#<orderId>` with a condition that the reservation does not exist.

On retry, first read `REQUEST#<requestId>` consistently and return its stored result. If concurrent attempts race, one transaction wins and the other receives a condition failure; the loser reads the marker and returns the same result.

Do not implement this as `GetItem`, then two independent `PutItem` calls. Two callers can both observe absence, and a timeout between puts can leave an order without a reservation.

The cost consequence is deliberate: each transactional write is billed at twice the capacity of its non-transactional equivalent. If the invariant can be modeled within one item, use one conditional update instead.

### Worked example: replacing an access pattern and index

Before, support lists open tickets by assignee with GSI `TicketsByAssignee`:

```text
Table key:  PK=TENANT#northwind, SK=TICKET#8472
GSI key:    GSI1PK=ASSIGNEE#maya, GSI1SK=2026-08-10T14:32:00Z
Query:      GSI1PK = "ASSIGNEE#maya"
```

The new requirement is to list by tenant, assignee, and status, newest first. Changing `GSI1PK` in place is impossible, and reusing it with new semantics makes old rows disappear from the new query.

After, add `TicketsByQueueV2`:

```text
GSI key: GSI2PK=TENANT#northwind#ASSIGNEE#maya#STATUS#OPEN
         GSI2SK=2026-08-10T14:32:00Z#TICKET#8472
Query:   GSI2PK = "TENANT#northwind#ASSIGNEE#maya#STATUS#OPEN"
```

Cut over in this order:

1. Deploy writers that maintain both GSI key pairs whenever assignee or status changes.
2. Create `TicketsByQueueV2` and wait until DynamoDB finishes building it and reports it active.
3. Backfill `GSI2PK` and `GSI2SK` on historical open tickets with conditional updates that do not replace newer values.
4. Reconcile until the new keys are present on every historical item the new access pattern must return.
5. Compare sampled query results and counts against the old path plus status filtering.
6. Deploy readers behind a flag, switch gradually, and monitor empty pages, latency, and throttling.
7. Stop writing old keys only after rollback is no longer needed; delete the old GSI in a later change.

The historical backfill is genuinely required because existing open tickets must appear immediately. If the requirement applied only to tickets created after launch, dual-writing new tickets would be sufficient and scanning history would add cost without value.

### Global-table conflict example

Region A reads customer item `{name: "Ari", tier: "gold"}` and writes `{name: "Aria", tier: "gold"}`. At nearly the same time, Region B writes `{name: "Ari", tier: "platinum"}`.

Last-writer-wins does not produce `{name: "Aria", tier: "platinum"}`. All replicas eventually keep one whole winning image, so either the name edit or tier edit is lost. Model independent updates as separate items, route that customer to one writer Region, or record immutable commands under unique sort keys and derive the customer view.

### Debugging checklist

#### Throttling

- Identify the throttled API, table, and index from the exception and metrics; do not tune the whole account blindly.
- Check consumed capacity, throttle-event metrics, latency, and request count over the same time window.
- Look for a hot partition key: one tenant, status bucket, date bucket, or low-cardinality GSI key can throttle below table-wide capacity.
- Check item size and consistency mode; larger items and strongly consistent reads consume more capacity.
- Check conditional-write retries and transaction conflicts; aggressive immediate retry amplifies load.
- Check index write amplification: each changed indexed attribute can add index writes, and a constrained GSI can throttle base-table writes.
- Check scans, backfills, imports, and batch jobs that started near the incident.
- Apply exponential backoff with jitter, cap concurrency, and fix key distribution; raising total capacity alone does not cure one hot key.

#### Surprise bills

- Compare billed read and write units with request volume and deployment dates.
- Request consumed-capacity details in a sample environment to expose table and per-index work.
- Count every GSI projection and update; a small base write may fan out to several index writes.
- Find scans whose filter discards most items, repeated first-page reads, and clients that never stop paginating.
- Check transaction use, strong consistency, item growth, retries, and batch jobs.
- Check global-table replicated writes and inter-Region effects, not just local application traffic.
- Check backup storage, point-in-time recovery, exports, restores, stream consumers, and change-data pipelines.
- Verify whether provisioned capacity sits idle or on-demand traffic has predictable peaks that merit a different capacity plan.

#### Unbounded scans

- Search code for `Scan`, parallel-scan workers, and loops that follow `LastEvaluatedKey` without a page or item budget.
- Remember that `Limit` bounds items evaluated per call, not necessarily matching items returned after a filter.
- Require a maximum wall time, page count, byte or item budget, cancellation, and a returned continuation token.
- Replace request-path scans with a table-key Query, a purpose-built GSI, or a maintained aggregate.
- For offline scans, isolate concurrency, rate-limit reads, checkpoint each segment, and monitor production throttling.
- Treat an empty page with a continuation key as incomplete, because filtering can remove every item from a page even when more data remains.

### Traps to state during review

- **Trap: “TTL means the row is gone at expiry.”** True: expiry only marks eligibility for asynchronous deletion; readers must enforce time validity themselves.
- **Trap: “A stream is Kafka attached to the table.”** True: it has short retention, retry duplicates, per-key ordering, and requires an external consumer and durable failure policy.
- **Trap: “Global tables merge non-overlapping attributes.”** True: concurrent item images conflict under last-writer-wins; attribute-level intent is not merged.
- **Trap: “Transactions make DynamoDB relational.”** True: they protect a bounded set of explicit items but do not add joins, foreign keys, or cross-Region serializability.
- **Trap: “A filter makes a scan cheap.”** True: DynamoDB reads candidates first and filters afterward, so rejected items still consume capacity.
- **Trap: “A new GSI automatically indexes the business field.”** True: only items containing valid values for the declared index keys enter the index; deriving those keys for old rows is application work.
- **Trap: “Schemaless means key design is easy to change.”** True: ordinary attributes are flexible, while the table primary key and LSIs are immutable physical design choices.
- **Trap: “Dual-write means call two tables and hope.”** True: independent writes can split; use a stream/outbox-style repair path, reconciliation, idempotency, and an explicit source of truth.
- **Trap: “Exact quotas and prices can be recalled from memory.”** True: limits and prices vary by mode, Region, and service revision; check the current authoritative figures before approving a capacity or cost plan.

## When not to use this

Skip it for a query against a table you already understand, a one-line config change, or a
question about the AWS SDK's syntax rather than the data model. It earns its place when the shape
of the data is in question — a new table, a new access pattern, a schema review, a cost or
throttling investigation, or any change to a key or index.

## Verify against the table

Every claim here is a design rule, not a measurement of your system. Before acting on a
consequential recommendation, confirm it against the actual table: `describe-table` for the
schema, CloudWatch for the traffic, and the item sizes from the introspection output. A design
that is right in general can still be wrong for a workload whose distribution nobody checked.
