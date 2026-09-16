# Topology decision: retain table-per-object

Review date: 2026-09-14 America/Denver. Source inspected in `/mnt/c/Users/drom/IdeaProjects/reladynamo`; this is a prescription, not an implementation or a passing-build report. Companion documents: [INDEXING.md](INDEXING.md), [CHALLENGE.md](CHALLENGE.md).

**Decision: ship table-per-object, with explicitly configured indexes per entity and no LSIs. Do not implement shared-table mode for 0.1.0. Prioritize the index write/read lifecycle and transaction correctness over table consolidation.**

**Evidence limitation:** the Reladomo seam below is verified against the local 18.1.0 source JAR. The mandatory independent `javap` check could not execute: Windows interop fails inside this environment. The exact failure and completion command are in §6. Do not label that gate passed. No source files were edited and no Maven build was run.

## 1. Why this is the right default

The adapter receives a query for one result portal and materializes one entity type with that portal's finder, mapping and cache. Reladomo deep fetch batches parents when resolving a relationship, but the child retrieval remains a separate result-type operation. The current seam provides no aggregate request containing a parent result plus the requested graph of child types.

The current keys also do **not** colocate related entities. A customer is under `v1#CUSTOMER#42`; its contact is under `v1#CONTACT#87`. Copying both into one physical table does not make `Query(pk = 'v1#CUSTOMER#42')` return that contact. The class prefix distinguishes identities; it is not a parent-child grouping key. To obtain the headline single-table benefit requires both a different access layout and a coordinator that consumes it.

This conclusion is consistent with current AWS guidance: multiple tables are sufficient when access patterns do not require fetching several entity types together. AWS does not prescribe single-table regardless of application architecture. [AWS modeling foundations](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/data-modeling-foundations.html).

Per-object tables also isolate GSI capacity failures, schema reconciliation, restores, retention policy and operational ownership. CRM `Attachment`/`Note` bodies, `Opportunity` corrections and plain reference data need not share the same operational policy. This is a reason to preserve isolation, not evidence that their production rates are known: the demos contain no representative workload trace.

Forty-six tables are an operational inventory, not forty-six unavoidable idle capacity bills. Use on-demand initially for unmeasured workloads; it charges for request consumption rather than reserving throughput separately on every idle table. Storage, backup and monitoring still cost money. Consolidation can improve utilization under provisioned capacity, but must be costed against measured traffic. [AWS on-demand mode](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/on-demand-capacity-mode.html).

The current default quotas are 2,500 tables per account/Region and 20 GSIs per table, both subject to quota management. Forty-six tables do not approach the former by themselves; a shared table with one GSI per entity already exceeds the latter for CRM. Request actual account quotas during deployment preflight. Do not solve table count by concentrating an unreviewed index count. [AWS quotas](https://docs.aws.amazon.com/general/latest/gr/ddb.html).

**Reject “single-table with per-entity GSIs” as the default hybrid.** It retains the per-entity query decomposition, pools operational failures, and consumes a shared index quota without gaining an aggregate query. Overloaded GSIs can reduce physical index count, but each item still needs an entry for every independently indexed access path. Fewer named indexes do not automatically mean fewer propagated writes.

**Correct a design-document argument:** design 01 §2.2 mentions cross-entity transactions as a single-table advantage. DynamoDB transactions already span tables in the same account and Region. Neither topology removes the 100-action/4-MB boundary. [AWS transaction behavior](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).

## 2. The Reladomo seam: what actually reaches the adapter

All source references in this section are entries under `com/gs/fw/common/mithra/` in:

`C:\Users\drom\.m2\repository\com\goldmansachs\reladomo\reladomo\18.1.0\reladomo-18.1.0-sources.jar`.

| Source entry and lines | Observed behavior | Consequence |
|---|---|---|
| `portal/MithraObjectReader.java:36–62` | `find(AnalyzedOperation, OrderBy, boolean forRelationship, int rowcount, int numberOfThreads, boolean bypassCache, boolean forceImplicitJoin)` returns `CachedQuery`. | No parameter carries a heterogeneous result bundle or deep-fetch graph. `forRelationship` is a flag, not a list of target portals. |
| `transaction/MithraObjectPersister.java:35–76` | Extends the reader; adds physical mutations, batches and transaction-participation handling. | No aggregate read interface is added. |
| `transaction/MithraDatedObjectPersister.java:27–32` | Adds `getForDateRange(MithraDataObject, Timestamp, Timestamp)` and `enrollDatedObject(MithraDatedTransactionalObject)`. | Dated storage adds row/range operations, not multi-type materialization. |
| `portal/MithraAbstractObjectPortal.java:201–208,558–561` | The setter replaces one reader field; the ordinary persister getter casts that same field; the tuple persister is a separate field. | Binding is per portal. Replacing the reader does not replace the tuple path. |
| Same portal, `1278–1300` | Both transactional and nontransactional server paths invoke that portal's reader `find`. | Transactional context does not turn a read into a multi-portal request. |
| `finder/Operation.java:89` | An operation exposes one result-object portal. | The output has one root type, even when a predicate mentions related types. |
| `finder/SingleLinkDeepFetchStrategy.java:106–112,137–140` | Builds an operation, gets its result portal/finder, and requests that finder’s list. | The relationship target is resolved through its own portal. |
| `finder/SimpleToManyDeepFetchStrategy.java:203–228,242–247` | Simplifies a join using the parent list where possible; resolves the resulting target list. Its IN-clause path calls the target operation's finder. | Several parent IDs can become one child-type request. This is the demonstrated optimization. |
| `finder/SimpleToOneDeepFetchStrategy.java:173–183,254–299` | Resolves a simplified target list or a complex target list, then associates results with parents. | To-one traversal also does not return heterogeneous entities through the reader. |
| `finder/ChainedDeepFetchStrategy.java:69–86`; `finder/DeepFetchNode.java:249–264` | Walks chained strategies/child nodes separately. | Multi-hop or sibling deep fetch is not fused into a graph read at this seam. |

The adapter reinforces the boundary: `reladynamo-ddb/.../persist/DynamoDbPersister.java`, `find`, holds one `RelatedFinder`, `EntityMapping`, `PhysicalDesign` and executor, decodes rows into that finder’s data objects, and returns that entity’s cached results. `QueryPlanner` plans against one `PhysicalDesign`; `FanOutSelect.tryCollapse` requires the same table and index across its children. There is no cross-portal graph dispatcher in these paths.

### Precise answer to “can one request span two types?”

**For returning a parent and children together: no, not through the current ordinary reader/deep-fetch contract. Reladomo resolves the root and relationship targets separately.** Cache hits can suppress calls and parent IDs can be batched, so this is not necessarily one network call per parent or even one call for every node.

**For expressing a predicate involving two types: yes.** A mapped operation can carry a relationship/join predicate into a reader whose *result* is still one entity type. It would be false to infer that every incoming operation references only that type's attributes. A SQL persister can evaluate that predicate with a join. The inspected adapter refuses mapped residual evaluation in `QueryPlanExecutor.passesResidual`; table colocation alone would not implement it.

An adapter could deliberately prefetch other types and populate other caches, or add an explicit aggregate API above the seam. That is a new feature with graph selection, invalidation, temporal-context and transaction obligations. The present interface does not supply the requested graph to the parent reader. Do not market the theoretical ability to perform arbitrary I/O inside `find` as an existing single-table optimization.

### What the relationship evidence establishes

The saved `reladynamo-ddb/target/surefire-reports/TEST-io.reladynamo.ddb.differential.RelationshipDifferentialTest.xml:80` contains:

```text
deep-fetch measured reads=1 query=1 getItem=0 scan=0 children=24 parents=8
```

This is existing run evidence, not a run performed in this review. `RelationshipDifferentialTest.java:204–249` first resolves eight parents, **resets the counter**, then fetches children. Its counter groups `executeStatement` and `batchExecuteStatement` into “query.” `QueryPlanExecutor.executeFanOutSelect` and `FanOutSelect` explain the one-call result: a homogeneous child GSI lookup with a PartiQL PK-IN predicate. It is not a single DynamoDB `Query` returning parent and child types.

Keep this real result. Strengthen its gate: the current assertions require 24 children and fewer than eight reads; they do not require exactly one request or zero scans. See INDEXING §7.

## 3. Migration cost: reversible layout, nontrivial operation

There are three different changes; do not describe all three as “change the table name.”

| Change | Re-key? | Required work |
|---|---|---|
| Copy per-object tables into a shared table, retaining the existing key and payload schema | **No**, for types whose complete key namespaces remain disjoint. | Copy every physical item; preserve `pk`, `sk`, `_rd_v`; merge compatible index definitions; validate type isolation and switch routing. There is no table alias that moves existing items. |
| Share overloaded GSI attributes while retaining base keys | Base keys: **no**. Index keys: usually **yes/new attributes**. | Stamp new index attributes on all eligible existing rows, create replacement GSIs, validate membership, switch plans, retire old indexes later. |
| Colocate contacts/orders/etc. under customer/parent keys, or split hot logical keys into new buckets | **Yes** for affected base items, or add explicitly maintained projection/edge items. | Redesign key derivation and reverse/direct lookups; migrate affected histories; address mutable relationships, concurrent writes and duplicate visibility. This is not absorbed by a version stamp. |

The reverse of the first move is a type-aware copy back into per-object tables. Again, O(number of physical versions), not O(number of logical objects). Operational work remains substantial at a billion versions even when every byte of the item is unchanged.

### The namespace is not globally safe today

`DefaultKeyStrategy.partitionKey` uses the **uppercased simple class name**, not the fully qualified class name. CRM and petstore both define `Payment`; both are audit-only and use the same logical field name/type. Equal IDs and processing-from timestamps can produce the same `pk+sk` in a merged table. No `_rd_v` value distinguishes these entities. Even differing sort-key profiles can cause heterogeneous items to share a logical partition and contaminate a temporal query.

Existing FK index keys also omit entity identity: `v1#GSI#<ATTRIBUTE>#<value>`. Shared GSI slots using that grammar would mix unrelated child types. Default table names can collide across models as well. Thus consolidation is a no-rekey operation only after a **complete namespace and index compatibility preflight**, not merely because keys contain `#<CLASS>#`.

### What versioning does and does not buy

`v1` is hard-coded into the current key encoders. `_rd_v` is the codec schema version: `ItemCodec` currently supports only version 1 and its transformation hook is empty. Neither provides routing epochs, alias resolution, dual reads, backfill state, CDC, or key translation. A topology-only copy can retain `_rd_v=1`; changing table routing is independent of payload version. Changing base-key grammar requires a key migration and readers that understand the chosen transition.

Prescribe before 0.1.0:

1. Keep existing persisted v1 keys. Record a deployment manifest mapping fully qualified entity name to table, exact key grammar, index specs and codec version. Reject accidental table sharing and duplicate storage namespaces at startup.
2. Keep table routing outside key identity. Resolve the same manifest in planner, writer, migration and table creation; a custom writer key strategy must not silently coexist with the planner's default encoding.
3. Treat the entity token as a stable storage identifier. A Java class/package rename is not permission to change stored keys. Add explicit stable-token support before claiming rename compatibility; do not silently rewrite existing prefixes.
4. Do not implement speculative online topology migration for 0.1.0. Preserve a reproducible, offline copy procedure and a verification manifest instead.

### Exact later offline consolidation procedure

1. Inventory complete physical key spaces and all GSI definitions. Refuse colliding entity/key/index namespaces; keep colliding types separate unless an explicit re-key mapping is approved as part of that migration.
2. Create the destination with the chosen schema. Configure access controls, recovery and index capacity before copying. Fence all source writes and drain transactions. A scan is not an immutable snapshot while writers remain active.
3. Copy all versions and required metadata in bounded, checkpointed batches. If the index schema changes, derive new keys from each row's own attributes/boundaries. Never replace processing-from with migration time.
4. Reconcile both directions over the **entire destination scope**: detect extra logical keys as well as missing/extra versions; compare every mapped value and base key, plus expected derived-index eligibility. Check index reads after propagation, not solely table reads.
5. Switch all readers/writers as one deployment routing epoch while writes remain fenced. Clear/reinitialize affected caches. Run cold-cache, source-disconnected reads and the original business-operation scripts against the new target.
6. Resume writes only after those checks pass. Retain the old copy for a defined rollback window. Before new destination writes, rollback is routing back; afterwards rollback needs a reverse copy/replay of every intervening mutation. A stale original table is not a rollback plan.

Current `Backfill` is not this procedure. It accepts an in-memory source list, performs unconditional upserts and verifies only partitions represented in that list. It now compares full keys/values, an improvement over the inspection, but does not certify destination-only logical keys, provide routing/cutover, or handle concurrent replay.

**Practical weighting:** no-rekey consolidation preserves a relatively cheap *design* option. It does not make moving data free. That weighs against taking shared-schema risks now when the expected read benefit is zero. Conversely, aggregate colocation is expensive regardless of today’s physical table count: starting with a shared table of entity-prefixed keys does not prepay that future redesign.

## 4. What would change the recommendation

Change it only on evidence from one of these cases:

- A cold-cache, source-disconnected application path supplies a graph/coordinator that fetches heterogeneous related items under one partition key and demonstrably reduces **total parent-plus-child** calls, p95/p99 latency and consumed capacity, preserving temporal contexts and failures.
- A real operating-cost trace shows material idle provisioned capacity or table-management overhead across many entities, common retention/security/restore needs, and a compatible overloaded-index design. Compare on-demand per-object tables first. The resulting move would be operational pooling, not a claimed join optimization.
- A target application explicitly needs a bounded strongly consistent aggregate projection that the existing normalized keys cannot serve. Review an aggregate read model alongside per-object authoritative history; do not infer it from XML cardinality alone.

Do not change it because “AWS usually says single-table,” because one test has one call, or because 46 feels large. Do not retain it if a representative workload actually proves aggregate colocation useful.

## 5. Execution order

1. Close the bytecode evidence gate below.
2. Retain topology; reject unsupported/ambiguous GSI specs immediately.
3. Complete request-time consistency enforcement, transaction correctness and index lifecycle tests.
4. Implement the sparse-current and composite/ordered index contracts in INDEXING, maintaining the Java 11 floor and AWS SDK v2.
5. Run demo-derived cold-cache access-pattern tests and measured history/correction workloads. Promote only explicit eligible indexes; publish consumed capacity and read consistency alongside request count.

## 6. Mandatory javap gate — blocked, not passed

The supplied executable was attempted twice, including once with the correct full package names. It exited before class inspection with:

```text
<3>WSL (4 - ) ERROR: UtilBindVsockAnyPort:307: socket failed 1
```

There is no Linux `javap` in PATH and `/usr/lib/jvm` is empty. The available `java` wrapper invokes another Windows executable. A network probe also failed DNS resolution. The matching source JAR was readable and was used directly. Existing project claims of previous javap verification are not substituted for this task’s check.

Run the following from an environment with working Windows interop; retain stdout/stderr. `-p -c` is needed to confirm dispatch, not just type names. All named Reladomo seam APIs above, and the temporal metadata used by INDEXING, are covered by this list:

```bash
"/mnt/c/Program Files/Java/jdk-21.0.11/bin/javap.exe" -p -c \
  -cp 'C:\Users\drom\.m2\repository\com\goldmansachs\reladomo\reladomo\18.1.0\reladomo-18.1.0.jar' \
  com.gs.fw.common.mithra.portal.MithraObjectReader \
  com.gs.fw.common.mithra.transaction.MithraObjectPersister \
  com.gs.fw.common.mithra.transaction.MithraDatedObjectPersister \
  com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal \
  com.gs.fw.common.mithra.finder.Operation \
  com.gs.fw.common.mithra.finder.AnalyzedOperation \
  com.gs.fw.common.mithra.finder.RelatedFinder \
  com.gs.fw.common.mithra.finder.SingleLinkDeepFetchStrategy \
  com.gs.fw.common.mithra.finder.SimpleToManyDeepFetchStrategy \
  com.gs.fw.common.mithra.finder.SimpleToOneDeepFetchStrategy \
  com.gs.fw.common.mithra.finder.ChainedDeepFetchStrategy \
  com.gs.fw.common.mithra.finder.DeepFetchNode \
  com.gs.fw.common.mithra.attribute.AsOfAttribute
```

Acceptance: confirm the reader signature; persister inheritance; one reader field changed by the setter; both portal server paths invoking that reader; target-finder resolution in the deep-fetch classes; one result portal on Operation; and axis-specific infinity/inclusivity metadata. If bytecode differs from the source, amend this decision before execution. Until then the decision is source-supported, **not independently bytecode-verified**.
