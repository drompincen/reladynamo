# Indexing prescription

**Keep per-object base tables. Make indexes explicit, complete from write through read, and selectable only under a declared consistency contract.** The proven baseline remains a single-attribute foreign-key GSI with `ALL` projection. Add processing-current indexes first; do not create a GSI for every XML relationship or every finder attribute.

This document specifies target behavior. It does not claim that new index profiles already work. Source evidence and the blocked `javap` gate are in [TOPOLOGY.md](TOPOLOGY.md). Java 11 and AWS SDK v2 apply to every implementation below.

## 1. Actual model and application inventory

Read all three `demos/*/ENTITIES.md` files and all `MithraObject` XML roots under each demo's `project/src/main/resources`. Counts exclude runtime/class-list XML and generated copies:

| Model directory | Objects | Bitemporal | Audit-only | Business-only | Non-dated | Explicit Relationship elements |
|---|---:|---:|---:|---:|---:|---:|
| `01-crm-bitemporal` | 46 | 29 | 10 | 0 | 7 | 59 |
| `02-petstore-unitemporal` | 22 | 0 | 10 | 11 | 1 | 27 |
| `03-car-classifier` | 5 | 2 | 1 | 0 | 2 | 1 |
| Total | 73 | 31 | 21 | 11 | 10 | 87 |

Every demo object has a **single-component** logical PK. The demos contain no explicit `Index` declarations. XML relationship count is not workload frequency and not the number of GSIs to deploy. Composite keys, source routing and many-to-many join mechanics need additional acceptance fixtures; these 73 objects do not prove them.

Use the XML over the prose where they differ. Classifier uses `active`, not `isActive`, and integer IDs. CRM's header says approximately 44 entities but the XML has 46. Petstore's POM currently compiles with release 21; its model is useful evidence, its passing build is not Java 11 runtime evidence.

### Observed application operations versus model-derived candidates

| Evidence | Actual operation/access requirement |
|---|---|
| CRM `query/CrmAsOfQueries.java:29–84` | Complete-key, two-axis reads of Address, TerritoryAssignment, Opportunity, ConsentRecord, Subscription, SalesRep, PriceBookEntry and Customer; Customer full history ordered processing-from then business-from. |
| Petstore `demo/DemoPrinter.java:80–100,127–131` and `PetstoreDemonstrationsTest` | Product business-as-of point reads and all business slices ordered by business-from; SalesOrder audit history ordered by processing-from; StockLevel/Pet/Employee business validity demonstrations. |
| Classifier `Classifier.java:30–68` | **Inside a transaction**, load ClassificationRule where business-as-of is supplied and `active=true`; deep-fetch RuleCriterion; evaluate all criteria in Java; highest priority wins, with lowest ruleId as tie-breaker. |
| Classifier `CarClassifierDemo.java:60,121–124` | Car lookup by carId; ClassificationResult lookup by resultId and processing-as-of. |
| Demo Dynamo differential test extractors | Unrestricted plain-entity enumeration and temporal edge-point enumeration for copying source snapshots. These are migration/export patterns, not evidence that an online list query has a key. |
| XML relationships in §2 | To-one lookup at the target PK; reverse/one-to-many lookup by a child FK, often with temporal context propagated/defaulted above the adapter. These are declared navigation capabilities, not measured traffic. |
| XML payload attributes in §3 | Natural-key lookup, compound FK filters, queues and ordered lists are **candidate patterns**. No claim that the demos execute all of them. |

The classifier is decisive: an `active` GSI alone does **not** make the unchanged demo compatible. Its read is transactional and it then follows a relationship. The planned GSI restriction must actually be enforced per request. An unchanged caller with neither a base-table key nor an approved bounded scan must be rejected. A future transactional membership/read-model design is a separate feature, not an implicit GSI fallback.

## 2. Model-derived relationship access manifest

For each listed **child entity / FK**, the reverse collection query has a precisely defined index candidate. To-one navigation in the opposite direction uses the parent's base table and needs no new index. Reverse names declared in XML count as navigation even if only the many-to-one side is written out.

Candidate selection rule: a deployment must enumerate the relationship paths it promises. For each promised collection path, configure the appropriate profile from §3 for the listed child/FK. Otherwise fail access-pattern preflight for that path. Do not silently create every candidate, and do not silently scan an unconfigured path. Merely parsing XML is insufficient to identify hot paths.

### CRM — exact child attributes available for indexed collection lookup

| Child entity | FK attributes, each an independent candidate unless an explicitly named compound pattern is requested |
|---|---|
| Account, Address, Contact, Contract, CreditRating | `customerId` on each |
| Customer | `industryId`, `ownerRepId` |
| Company | `parentCompanyId` (nullable) |
| CustomerSegmentAssignment | `customerId`, `segmentCode` |
| TerritoryAssignment | `customerId`, `territoryId`, `repId` |
| ConsentRecord, ContactEmail, ContactPhone | `contactId` on each |
| Opportunity | `customerId`, `stageCode`, `ownerRepId` |
| Quote | `opportunityId` |
| QuoteLineItem | `quoteId`, `productId` |
| Subscription | `contractId`, `productId` |
| PriceBookEntry | `priceBookId`, `productId` |
| Call | `contactId`, `customerId`, `repId` |
| EmailMessage | `contactId`, `repId` |
| Meeting | `customerId`, `repId` |
| TaskItem | `customerId` (nullable), `repId` |
| Note | `repId`; polymorphic `(entityType,entityId)` is a payload-derived candidate, not a declared relationship |
| CaseComment | `caseId`, `authorRepId` |
| CaseEscalation | `caseId`, `escalatedToRepId` |
| Campaign, OutreachSequence | `ownerRepId` on each |
| CampaignMember | `campaignId`, `contactId` |
| OutreachStep | `sequenceId`, `templateId` |
| OutreachEnrollment | `sequenceId`, `contactId` |
| SupportCase | `customerId`, `contactId`, `assignedRepId` |
| Invoice | `customerId`, `contractId` |
| InvoiceLine | `invoiceId`, `productId` |
| Payment | `invoiceId` |
| SalesRep | `teamId`, `managerRepId` (nullable) |
| Team | `managerRepId` |

Attachment has no declared relationship; its `(entityType,entityId)` payload suggests an explicit compound lookup, like Note. MessageTemplate, SlaPolicy and Territory have no outgoing collection lookup requirement from their own XML; inbound collection candidates are on their children above. Plain reference keys are Industry.industryCode, CustomerSegment.segmentCode, PipelineStage.stageCode, LeadSource.sourceCode, Product.productId, PriceBook.priceBookId and Tag.tagId.

### Petstore

| Child entity | FK collection candidates justified by a relationship/reverse relationship |
|---|---|
| Pet | `speciesId`, `breedId` (nullable), `kennelId` (nullable) |
| Breed | `speciesId` |
| Kennel, Employee | `storeId` on each |
| FeedingSchedule | `petId`, `productId` |
| VeterinaryVisit, Vaccination, GroomingAppointment | `petId` on each |
| Adoption | `petId`, `ownerId` |
| Product | `categoryId` |
| ProductCategory | `parentCategoryId` (nullable) |
| StockLevel | `productId`, `storeId` |
| SalesOrder | `storeId`, `customerId` (joins PetOwner.ownerId) |
| SalesOrderLine, Payment | `orderId` on each |
| PurchaseOrder | `supplierId` |
| PurchaseOrderLine, Shipment | `poId` on each |

PurchaseOrderLine.productId and SalesOrderLine.productId/petId have to-one navigation but no declared reverse collection; that navigation needs only target PK access. Do not create reverse GSIs for them without a promised reverse query. Similarly, `vetId` and `groomerId` are payload fields, not declared Employee relationships in these XML files.

### Classifier

RuleCriterion.ruleId is the one declared collection key, from ClassificationRule.criteria. ClassificationResult.carId/ruleId are plausible audit-list filters but have no XML relationships. ClassificationRule.active is the actual non-key equality predicate. The `operator` values in RuleCriterion are rule data evaluated in Java; they do not imply DynamoDB range indexes on Car.year, make, model, etc.

## 3. Physical index contract and access-pattern matrix

### Key notation and naming — normative

- `L` = existing complete base partition-key string from the configured key strategy; `S` = existing physical sort key. Preserve the base key schema `pk:S, sk:S`.
- `T(x)` = existing `TemporalEncoder.encode(x)`, 17 UTC digits. No new timestamp encoder.
- `K(values)` = `#`-joined components in the access manifest's declared order, using the same canonical component encoding on write and read. Validate types and reject `#` before a write. Null in any equality-key component makes that item ineligible for this index. A query for null cannot use an index that omits nulls.
- New index ID `a` is explicit, stable, unique per entity, lowercase letters/digits/underscore; physical attributes are `gsi_<a>_pk` and `gsi_<a>_sk`, both String. Do not derive all compound keys as `gsi_pk` or all current sort keys as `gsi_sk`. Those current `GsiSpec` names collide when independent definitions coexist. Reserve `cur` for the logical-PK processing-current profile.
- `E` = the stable entity storage token from the deployment manifest. For new index profiles `F(a,values) = v1#I#<E>#<a>#<K(values)>`. It prevents accidental cross-entity index mixing and distinguishes access paths. Source-scoped models remain refused until source routing exists; adding a tenant prefix without that implementation is not support.
- For new multi-object index sort keys, suffix with `#L#<L>#S#<S>` for deterministic physical identity. Existing legacy FK GSIs keep their old grammar until explicitly migrated.
- `I(n)` for new numeric **ordering** keys is an order-preserving fixed-width encoding, not decimal `toString`: for signed long values, shift by 2^63 in arbitrary-precision arithmetic and zero-pad to 20 decimal digits. Int values may use that same representation. Descending numeric fields use `I(-1-n)` computed without long overflow. Validate bounds. This is an integer ordering encoding, not a replacement for TemporalEncoder. Do not route arbitrary decimal/string collation ordering natively without separately proving its encoding.

The extra GSI fields are storage metadata, never mapped ORM payload. Validate all physical names against mapped names and other indexes. `GsiSpec` requires additional explicit metadata for these profiles; its existing constructor/factories do not implement this contract. Reject a profile unless writer, planner, table creator and executor all implement the same immutable spec.

### Access pattern → index → key → projection → write amplification

`+J` means one index-entry insertion/update/delete, charged by index-entry size; §5 expands this into units and correction costs. All profiles initially use `ALL`, because a normal finder materializes complete objects.

| Pattern and demo anchor | Selected access path | Exact physical key/range | Projection | Extra writes beyond base rows |
|---|---|---|---|---|
| Non-dated full PK: Car, Industry, ResultLabel | Base GetItem; BatchGetItem for a set of distinct exact keys | `pk=L, sk=v1#ND` | Full base item | 0 |
| Exact temporal physical version (all from-boundaries known) | Base GetItem | `pk=L, sk=S` | Full base item; locally check any remaining predicate | 0 |
| Complete PK, historical bitemporal B/P: CRM correction queries | Base Query | `pk=L`; if processing-from is exactly known, use processing prefix; otherwise apply both axis containment filters | Full base item | 0; read cost proportional to examined history, not result count |
| Complete PK, current processing at finite B: CRM current Address/Opportunity, classifier rule by ID | New `cur` GSI, eventual only | `gsi_cur_pk=L`, `gsi_cur_sk=v1#B#T(businessFrom)`; query range through B and test business upper bound; §4 | ALL | +J on insertion into current set; +J deletion when closed; +J on projected-current payload update |
| Audit-only current PK: Call, SalesOrder, ClassificationResult | New `cur` GSI, eventual only | `gsi_cur_pk=L`, `gsi_cur_sk=v1#A`; Query exact index key | ALL | Same sparse membership costs; stable valid state has at most one row |
| Business-only as-of: Product, StockLevel, Pet, Employee | Base Query | `pk=L`, `sk <= v1#B#T(B)` for exclusive-to axes; retain upper-bound containment filter; inclusive-to uses strict from bound | Full base item | 0; no processing history exists to remove with a current-processing GSI |
| Audit-only historical as-of | Base Query | `pk=L`, processing-from range through P (strict bound for inclusive-to); retain processing upper-bound filter | Full base item | 0 |
| Full history and native history order: CRM Customer, petstore Product/SalesOrder | Base Query, all pages | `pk=L`, all `sk`; natural temporal component order, forward/reverse only when every requested direction matches | Full base item | 0; temporal edge-point materialization is a separate current SPI gap |
| To-one navigation: Contact.customer, Pet.species, Invoice.contract | Target's base path above | Target complete logical PK plus its required temporal context | Full base item | 0 |
| Collection FK lookup including historical P, or business-only/plain collection | Existing legacy FK GSI for a single attr; new `fk_<attribute>_hist` for new profiles | Legacy HASH `gsi_<attribute>=v1#GSI#<ATTR>#K(value)`, RANGE base `sk`; new HASH `F(a,value)`, RANGE `S#L#L` (literal separators, with actual values substituted) | ALL | +J for each non-null indexed row inserted/deleted/projected update; changed FK costs delete old + insert new |
| Collection FK lookup at current processing: CRM Contact by customerId, petstore SalesOrderLine by orderId, RuleCriterion by ruleId | New `fk_<attribute>_cur` | HASH `F(a,value)` only on processing-open rows; bitemporal RANGE `v1#B#T(Bfrom)#L#L#S#S`; audit RANGE `v1#L#L#S#S` | ALL | +J per entering/leaving row; indexed payload changes +J; retains all current business segments |
| Parent IDs IN, deep-fetch of one child type | Same child FK index, batched transport | Explicit finite set of HASH equalities; compatible plans may collapse to PartiQL IN, with ≤50 values per current adapter chunk | ALL | Same as its single-FK profile; no extra write for batching |
| Compound filter: StockLevel(storeId,productId), PriceBookEntry(priceBookId,productId), Note/Attachment(entityType,entityId) | New explicit compound index `by_store_product`, `by_pricebook_product`, `by_entity` | HASH `F(a,ordered tuple)`; RANGE `S#L#L`; processing-open restriction only when profile explicitly current | ALL | +J per eligible row; any tuple change removes/adds entry; no extra uniqueness guarantee |
| Non-key exact lookup: Product.sku, ContactEmail.emailAddress, Account.accountNumber; audit lists ClassificationResult.carId | Explicit `by_sku`, `by_email`, `by_account_number`, `by_car` profiles | HASH `F(a,value)`; RANGE `S#L#L`; temporal filters as required | ALL | +J per eligible version; null values absent; key mutation costs 2J |
| Actual classifier rule discovery: active=true, B, processing infinity | **Unchanged transactional caller: reject without a supported base access path.** An explicitly eventual, nontransactional variant may use new `active_rules_cur` | Stamp only `active=true` and processing-open; HASH `v1#I#<E>#active_rules_cur#T`; RANGE `I(-1-priority)#I(ruleId)#S#S`; filter B containment | ALL | +J on active/current entry/exit, 2J on priority change; low-cardinality hotspot, bounded rule corpus required |
| Ordered operational candidate: TaskItem(repId,status) by dueDate, then taskId | New `tasks_by_rep_status_due_cur`, only when that exact pattern is promised | HASH `F(a,(repId,status))`, processing-open only; RANGE `T(dueDate)#I(taskId)#S#S` | ALL | +J per eligible row; dueDate/rep/status mutation costs delete+insert; all projected payload changes +J |
| Ordered sequence candidate: OutreachStep by sequenceId, stepNumber then stepId | New `steps_by_sequence_cur` | HASH `F(a,sequenceId)`, processing-open only; RANGE `I(stepNumber)#I(stepId)#S#S`; filter business containment | ALL | Sparse membership costs; sequence/step ordering changes cost 2J |
| Arbitrary OrderBy after a keyed fetch, including mixed directions or unproven collation | Existing selected access path + bounded exact in-memory sort | No new index unless exact repeated order is in the manifest | Complete rows | 0 additional index writes; consumes memory/read budget; sort after filter/dedup, before top-N |
| Non-key range alone, contains/endsWith alone, `.all()`, unbounded non-key findMany/export | No universal GSI | Refuse online by default; explicit bounded Scan for approved maintenance/small-reference workloads | Complete rows or separately defined export projection | 0 additional index writes; full read cost remains |

`S#L#L` in the matrix means “the existing base sort-key value, followed by literal `#L#`, followed by the logical-key string,” not the letters S and L. Similarly every symbolic term in the table substitutes its defined value. For the bitemporal current FK upper range at B, use the inclusive upper string `v1#B#T(B)#~`; the next segment marker is ASCII `L`, below `~`. For inclusive-to axes, use a strict upper key bound at `v1#B#T(B)` so rows beginning exactly at B are excluded. Always retain the full model-derived temporal predicate to handle infinity and boundaries.

Do not deploy historical and current variants indiscriminately. A promised current-only collection needs the current variant; a promised historical collection needs the history variant. If both are promised, the history variant is the complete fallback and the current variant is an optional measured acceleration. For business-only data the history profile already stores the current knowledge of all business intervals; duplicating it as “processing current” buys nothing.

A hash-key **prefix is not queryable**. In particular, an FK being a subset of a compound logical PK does not remove the need for another access path: all logical PK components are inside `pk`, and DynamoDB requires equality on the whole HASH value. Design 01 §2.5's contrary row must be corrected.

### Projection decisions

For 0.1.0, keep `ALL` as the only enabled normal-finder projection until narrow-projection hydration passes its own tests. Reject `KEYS_ONLY`/`INCLUDE` startup configurations today; do not decode their incomplete maps as full objects.

The future narrow path is explicit: query GSI base keys, deduplicate physical identities, BatchGetItem the base rows in chunks of at most 100 keys, retry unprocessed keys within a fixed request budget, decode full rows, re-evaluate the complete predicate, then order and limit. Missing/deleted candidates are dropped. A changed FK/payload is tested against the original operation. This can eliminate stale positives but **cannot discover newly matching keys absent from a lagging GSI**. Strong hydration does not make membership strongly consistent.

If that path is implemented, `KEYS_ONLY` is the candidate for large Note/Attachment bodies and other wide objects with sparse lookup demand; use `INCLUDE` only for a demonstrated covering read or useful candidate filtering. Translate projected Java names through EntityMapping to physical item names. Include `_rd_v` and all required payload/temporal fields if decoding without hydration; missing nullable fields are not proof of null. These are adapter prescriptions informed by [AWS projection behavior](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html).

## 4. Sparse processing-current design — complete lifecycle

### 4.1 Membership means current knowledge, not “latest timestamp”

For an entity with a processing axis, an item is eligible **iff its own processing-to boundary equals that axis's actual configured infinity sentinel**. Resolve the generated axis metadata once at binding and carry the resolved value into both writer and planner. Do not independently use the XML parser's conventional sentinel; it does not evaluate the infinity expression. Different axes can have different sentinels/inclusivity. The current adapter permits only one shared sentinel: for 0.1.0, reject differing axis sentinels before I/O unless per-axis metadata has been implemented in every consumer. Never guess that infinity is a timezone-independent string literal.

Reladomo source `attribute/AsOfAttribute.java:136,146,319–330` exposes infinity and inclusive-to behavior and gives infinity special matching semantics. Its bytecode verification remains part of TOPOLOGY §6.

For each row being encoded:

1. Start with a fresh full item image; preserve the director-supplied boundaries.
2. Derive base keys once from the shared key strategy.
3. For each sparse spec, evaluate its membership predicate from that row, not from wall-clock time and not from another version.
4. If eligible, set **both** distinct index-key attributes with the profile's key values. If ineligible, omit both attributes entirely. Do not write DynamoDB NULL, empty String, a boolean marker in a String key, or stale previous values.
5. Use the same computation for ordinary insert/update, bulk migration upsert, retries and repair. With today's full-replacement Put, omission removes the old index keys. If an UpdateItem implementation is introduced, it must explicitly REMOVE keys on exit as part of the same base-row write.
6. Check the final item size and every index key's byte limit after stamping. Never silently omit an index because the key is too large.

DynamoDB maintains an entry only when its required key attributes exist. Deleting those attributes makes a sparse item leave the index. [AWS sparse indexes](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-indexes-general-sparse-indexes.html).

For bitemporal `cur`, HASH is **exactly L**, not a single-PK GSI FK encoding. RANGE is `v1#B#T(businessFrom)`. For audit-only `cur`, use `v1#A`. Reject this profile on non-dated and business-only entities. Current FK profiles additionally require non-null complete FK tuples; a business interval that has ended remains indexed if processing-to is still infinity.

Example: the current knowledge for Address 7 consists of `[Jan,Mar)`, `[Mar,Jun)` and `[Jun,infinity)` business intervals. **All three** belong in `cur`, even in December. An April query must find the middle interval. Closing an obsolete processing row removes it; a retroactive correction can introduce multiple replacement rows and therefore multiple new entries. No temporal splitting is invented here: the index merely projects the rows supplied by the director.

### 4.2 Read algorithm

Eligibility requires all of the following:

- Complete logical PK for `cur`, or the declared full index tuple for a current collection index.
- Requested processing-as-of is the actual processing infinity sentinel. A finite “now” timestamp is **not** equivalent; it uses a history-capable path.
- The request explicitly permits eventual results and is outside an actual active transaction and outside refresh/enrollment/date-range/delete correctness paths.
- The index is ACTIVE **and** its application backfill/verification manifest is complete.
- Every required condition can be evaluated on its projection or by the declared hydration path.

Then:

1. For bitemporal PK lookup at finite B, Query the current index by `L`, upper-bounded by `v1#B#T(B)` for exclusive-to axes, strict `<` for inclusive-to. Use the appropriate business containment predicate: `from <= B < to`, or `from < B <= to` when inclusive-to.
2. At business infinity, query that logical key and test `businessTo == businessInfinity`. Do **not** use `businessTo > infinity`; that always rejects valid open rows. Processing-only current lookup queries exact `L/v1#A`.
3. Keep processing-to equality in validation as a defensive membership check. Apply all payload predicates and every explicit physical-from constraint too. A sparse path cannot ignore an exact processing-from constraint; either filter it or retain the exact base GetItem path.
4. For collection lookup, return every matching physical row across every required parent key and page. For one logical PK, the converged valid result must have at most one containing row. Multiple matches require authoritative base-key re-read; a persistent overlap is an integrity error. Never choose an arbitrary winner.
5. Preserve pagination, deduplication and ordering before rowcount. Empty filtered pages do not end a query.

**Do not implement `Limit=1` as the general sparse-current optimization.** Several business intervals can be current knowledge, and GSI propagation can transiently contain old and replacement entries. The initial implementation is the bounded range-query-plus-filter contract above, with all required pages. A later descending-predecessor optimization needs separate proofs for gaps, exact boundaries, extra predicates, duplicate starts, pending index changes and infinity; it is not authorized by a “current row” label.

The win is removal of closed processing history: work changes from all relevant physical history toward the number of current business segments. It is a GSI **Query**, never GetItem. There is no guarantee of one returned item, one evaluated item, or one request at arbitrary cardinality. “Current as-of is the hottest query” is a workload hypothesis to measure, not something XML establishes.

### 4.3 Changes required in the inspected implementation

| Component | Required behavior |
|---|---|
| `GsiSpec` | Give every new index explicit physical key names, ordered logical key components, sort profile, membership predicate and projection. Validate supported combinations. Preserve legacy FK names for existing deployments. Distinguish logical-PK current index from current FK index. |
| `DynamoDbWriter.stampGsiKeys` | Remove the sparse and multi-component skip behavior. Share exact derivation with planning; current logical PK must be `L`. Remove keys on membership exit. Do not convert a missing required mapped value into an absent indexed fact. |
| `PhysicalDesign` and binding | Resolve axis-specific names, infinity and inclusivity; one immutable storage spec must feed every component. Do not rely on the existing default FROM_Z/THRU_Z/IN_Z/OUT_Z for arbitrary XML. |
| `QueryPlanner.baseTablePlan/currentGsi/tryGsi` | Use the selected GSI's physical attributes, not base `pk/sk`. Require correct profile/complete tuple, actual request consistency and completed backfill. Current code uses base names, selects first sparse spec, and only supports business-as-of in that branch. Add audit-only handling. |
| `QueryPlanner.encodeGsiPk` | Encode the declared tuple itself. Current multi-attribute path calls the entity PK encoder; unrelated compound lookup attributes do not supply that entity PK. |
| `QueryPlanExecutor` | Honor full semantics after hydration/filter/dedup; distinguish single Query and PartiQL batches; expose evaluated work as unknown when the API does not report it. Do not represent returned item count as examined count. |
| `TableCreator` | Match desired key **types, names and projection**, not just key names. Validate sparse eligibility when checking backfill. Ineligible closed/null-FK rows must not trigger endless BACKFILL_INDEX_KEY. Existing `anyItemLacksAttribute` wrongly treats all omissions as missing work. |

### 4.4 Rollout/backfill sequence

For the first implementation, use an offline rollout: fence writes, deploy the shared derivation, stamp eligible existing items in checkpointed batches, verify expected versus actual membership over the whole table, create the index, wait for ACTIVE and query-level validation, then enable reader routing and resume writes. Closed history must remain keyless. Null FK rows are intentionally keyless. Existing populated but wrong keys must be detected; presence alone is not validation.

For later online rollout, require a conditional per-row metadata update against the observed row revision/expected boundaries; on conflict reread and rederive. An unconditional full Put of a scanned old row can reopen a corrected version or overwrite payload. Streams alone without ordering, retention and recovery handling are not a backfill protocol. Keep the new index unavailable to the planner until complete.

Do not modify GSI key schema/projection in place: create a new named index, verify, switch and retire. TableCreator must report an incompatible existing projection instead of declaring VALIDATE. Provision each index independently; copying base table capacity to every GSI is not capacity planning.

## 5. Write cost and where an index stops paying

Let `B` be final base-item bytes, including all derived keys. Let `J_a` be the full stored index-entry bytes, including base/index keys and its projection. Round base writes in 1-KB units, and each index entry independently. For a normal replacement base write, budget the larger old/new item size. Transactional base actions cost twice their ordinary write units. **Do not blindly double GSI maintenance along with base transaction actions**; account for index propagation separately and validate consumed units in AWS. [AWS GSI capacity rules](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html), [AWS transaction capacity](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).

Per affected index:

| Transition | Logical index maintenance |
|---|---|
| absent → absent | 0 |
| absent → eligible | one insertion, `ceil(J_new/1024)` |
| eligible → absent (including processing close) | one deletion, `ceil(J_old/1024)` |
| eligible, same index key, changed projected payload | one update, budget `ceil(max(J_old,J_new)/1024)` |
| eligible, changed index key | delete old + insert new, each rounded separately |
| eligible, unchanged keys/projection | no changed index content; confirm observed charges for the actual write API |

An `ALL` projection means ordinary payload changes matter. Adding another index also adds key attributes to the base item and, because ALL copies them, can increase the size of other ALL index entries. Recompute final sizes for the complete deployed index set, not each index in isolation. GSI maintenance does not consume additional entries in the submitted 100-action transaction limit; explicitly written guard, reservation and membership items do.

For a correction closing `c` rows and inserting `d` replacements:

- Base writes: `c+d` row actions before any guards; transactional base budget approximately `2 × sum(ceil(B_row/1024))`.
- Each all-history ALL index: approximately `c` projected updates plus `d` insertions, assuming unchanged index keys on the closed rows.
- Each processing-current ALL index: `c` entry deletions plus `d` insertions.

Thus sparse-current primarily saves **stored history and read work**, not necessarily writes per correction. A one-row close and three-row replacement is four base actions and four maintenance actions per relevant index. With two history GSIs and one current GSI, ≤1-KB items/entries cost approximately 8 transactional base write units plus 12 index write units, before retries/guards. It is not “one update plus one cheap index.”

Adopt an index only for a named promised query or when measured savings justify an optional optimization. Evaluate over the same time window:

`avoided base read cost > added index write cost + index storage + hydration read cost + amortized backfill cost`.

Use current regional pricing at deployment; no invented dollar figure is attached here. Also require the query's latency objective and consistency contract to pass. The existing `estimatedVersionsPerKey > 4` threshold is an uncalibrated heuristic: item sizes, surviving business segments, read/write ratio and lag tolerance determine the result. Default optional current indexes **off** until that comparison is supplied; do not derive 52 current indexes automatically just because 52 entities have processing axes.

Stop adding indexes when collection fan-in makes keys hot, large ALL projections dominate writes, lookups are rare relative to corrections, history is already a few cheap rows, or transactional correctness excludes the GSI. If an access pattern is required but its index is uneconomic/unsafe, declare the workload ineligible or change the application contract. Do not substitute an invisible scan.

## 6. Scale failures and required mitigations

### GSI lag and read-your-writes

GSI reads are eventually consistent. Strongly reading hydrated candidates does not recover missing members. An empty-query retry or a fallback only on empty results does not fix a partially stale collection. A multi-row transaction can also propagate to a GSI in pieces; a temporally inconsistent intermediate graph is possible. [AWS read consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html), [AWS transaction propagation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).

Required adapter behavior:

1. Carry consistency as request context, with strong/base behavior as default for correctness-sensitive operations. The persister must determine actual transaction participation at invocation time; the stored `PlannerConfig.inTransaction=false` default is not runtime evidence.
2. For complete-key read-your-writes, use base reads and the transaction's pending-write view when buffering exists. A strong read alone cannot see uncommitted buffered changes.
3. For FK/non-key membership requiring strong correctness, refuse unless a supported authoritative access path exists. A future transactionally maintained base-table membership projection can supply one, but adds writes, action-budget usage and conflict handling. It is not part of this GSI implementation.
4. Explicit eventual opt-in permits stale reads, not fabricated guarantees. Detect conflicting temporal matches; do not cache a lagged empty relationship indefinitely. Define invalidation/expiry for GSI-derived query results and test it across two processes. Current portal/cache integration does not demonstrate this.
5. Strong reads of many keys/pages do not constitute one transaction snapshot. If the business operation requires an atomic cross-entity view, a multi-call Query plan is insufficient even on base tables.

### Hot partitions: where they really arise

The full base PK includes entity and logical ID. DynamoDB hashes the **whole value**; a common `v1#CUSTOMER#` prefix does not concentrate all customers into one partition. Tenant/class dominance means table-level load if IDs distribute; it is not automatically a single hot HASH key.

Actual dangers are one heavily corrected logical object, a customer with extreme child write traffic on its FK GSI, `active=true`/status-only indexes, and a dominant tenant used as the sole index HASH key. Physical partitions have finite capacity; increasing table/index provisioned totals does not remove concentrated-key limits. Monotonically growing temporal sort keys can make splitting for heat ineffective. [AWS partition guidance](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/data-modeling-blocks.html), [AWS split-for-heat limits](https://aws.amazon.com/blogs/database/part-3-scaling-dynamodb-how-partitions-hot-keys-and-split-for-heat-impact-performance/).

For a measured hot **collection** index, add deterministic shards based on child logical identity. Record shard count and hash algorithm in the immutable index version; query every shard and globally merge/dedup/order. All versions of a child must map consistently, and shard-count changes require a backfill/versioned read transition. A reasonable deterministic contract is SHA-256 of UTF-8 `L`, interpreted unsigned and reduced modulo the declared shard count. Select that count from measured peak index write units and a per-shard capacity budget in the manifest; fail preflight if absent. Do not silently shard in the executor.

Sharding by logical identity does not fix one hot logical identity. Splitting its history by processing-time bucket changes direct addressing and requires searching older still-open slices as well as recent buckets. Keep that out of 0.1.0; reject the unsupported throughput profile or design a separate reviewed history layout. Never silently drop old buckets from an as-of query.

### Long history and the supposed 10-GB limit

**There is no 10-GB per-logical-key item-collection hard limit in this table design without LSIs.** The limit applies to an item collection in a table with one or more LSIs. It is not a general GSI/base-table cap. Retain the no-LSI policy; if an operator supplies a table with LSIs, detect it and apply/reject the additional constraint explicitly. [AWS LSI item collections](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/LSI.html).

What bites here is increasing examined bytes, pages, latency and client memory. Base bitemporal as-of currently often queries the entire logical partition with filters. Sparse current removes processing history for eligible reads; historical as-of still needs an explicit budget. Do not equate a “fast path” label with a bounded read. Enforce page, decoded-byte, raw-buffer and total-request budgets before allocations grow; return a named failure, never a partial complete list. Large historical exports need a cursor/checkpoint path, currently unimplemented at the persister seam.

Retention is a business contract. Do not TTL closed temporal rows merely to control size when the promise includes historical reconstruction. Archiving needs a documented query horizon or an archive-aware reader.

### Index write throttling and rollout pressure

An under-capacity or hot GSI can throttle base-table writes. On-demand is also subject to throttling. Per-object tables contain the impact within that entity; a shared table would enlarge it. Record the throttling reason and resource ARN, and expose per-index consumed write capacity, throttle counts, request latency and backfill progress. Retry retryable failures with bounded exponential backoff and jitter; never skip GSI stamps to make a write fit. [AWS GSI back pressure](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/gsi-throttling.html).

Provision each GSI from its actual entry transitions and projected bytes. `TableCreator` currently assigns the base table's RCU/WCU values to every GSI; replace that coupling with per-index settings. Throttle migration independently of application writes and stop index activation until verification is complete. Deleting an overloaded index is an operational change, not an automatic retry action.

### Round trips, billed work and false observability

The relationship result is a transport win. A PartiQL PK-IN select can avoid a Scan, but it does not turn multiple key lookups into one item's read charge. Require the actual HASH equality/IN in the generated statement and deny full-table PartiQL scans in the test environment. [AWS PartiQL SELECT](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-reference.select.html).

`QueryPlanExecutor.executeFanOutSelect` currently increments examined count by **returned items**. ExecuteStatement does not expose the Query API's `ScannedCount`; filtered-out work is not zero. Publish examined count as unavailable for this path, together with actual consumed capacity, response bytes, calls/chunks and pages. Use `ReturnConsumedCapacity=INDEXES` for diagnostics that distinguish base and index costs. [ExecuteStatement API](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_ExecuteStatement.html).

## 7. Acceptance gates for the executing fleet

Each new behavior needs a behavioral RED run, then GREEN against the real codec/planner/executor and generated finder operations. Preserve failing output. Mocked request shape alone is insufficient. Run on Java 11; production-AWS lag/capacity checks are additional gates, not something DynamoDB Local proves.

1. **Current lifecycle:** insert one open row; close it and insert three business fragments. Compare the complete base history and converged index membership. Closed rows have neither key; all three open-processing fragments do. Exercise terminate, purge, in-place update and repeated migration replay.
2. **Boundaries:** queries before the first segment, in each interval, in a gap, at both edges, at business infinity, at finite processing time and at processing infinity. Cover inclusive-to metadata and custom sentinels. Distinct axis sentinels must either pass the full per-axis implementation or be rejected at startup as specified in §4.1; they must never silently use a shared default.
3. **Selection correctness:** non-default base/index attribute names, composite logical PK, two independent composite GSIs on one entity, and exact-from predicates conjoined with as-of predicates. Unsupported profile combinations fail before table creation/writes.
4. **Sparse creation on existing data:** mix open/closed processing rows and null/non-null FKs; reconcile a missing index. Closed/null rows are accepted as intentional omissions. Wrong present keys are detected. Existing wrong projection is INCOMPATIBLE. Reader routing stays disabled until the manifest completes.
5. **Relationship baseline:** cold caches, resolve eight parents, measure that phase separately, fetch exactly 24 correct children in one child-phase ExecuteStatement request with zero Scan and zero full-table PartiQL execution. Assert the exact bound; do not leave `<8` as the only performance assertion.
6. **Relationship scale:** 51 and 101 parent keys, >1-MB processed data, filtered empty pages, mixed predicate bindings, nullable FK, FK reassignment, deep-fetch siblings and two hops. Follow all continuation tokens and enforce the global request/page budget across chunks and fallback queries.
7. **GSI consistency:** simulate missing newly matching members, stale removed members, partial propagation of a correction, and cached empty relationship results. Real active transactions must reject GSI paths even with default PlannerConfig. Test a same-process and second-process read after commit.
8. **Ordering/dedup:** late-partition maximum for descending top-one; overlapping OR branches count once by physical identity; three temporal versions of one logical key remain three history rows. Include exact BigDecimal comparisons and nulls. Native order is allowed only on the selected index's actual key profile. The new RowOrderComparator currently loses decimal precision through double conversion; fix before accepting general ordering.
9. **Projection:** until hydration is implemented, reject KEYS_ONLY/INCLUDE. Later verify mapped-name translation, missing nullable fields, unprocessed BatchGet keys, deleted candidates and FK changes between index query and hydration. Do not claim strong membership.
10. **Cost/scale:** benchmark 1, 4, 16, 256 and 4,096 history versions per key with both one and many current business segments; measure corrections per read, final item/index bytes, consumed capacity, p95/p99 and allocations. Compare base versus current index under the same consistency contract. Publish measured eligibility rather than retaining a universal `>4` threshold.
11. **End-to-end application:** bind the original demo finders, disconnect JDBC, clear caches and run original services. Classifier's transactional active-rule lookup must either use an explicitly supported authoritative path or fail by name. A snapshot-mirroring test cannot satisfy this gate.

## 8. Status of the two cited inspection findings

**R-10 remains source-confirmed:** writer skips sparse and compound GSIs; sparse planner uses base attributes; composite planning encodes the wrong domain; INCLUDE names are untranslated; narrow projections are decoded without hydration. Fixing only `stampGsiKeys` will not close it.

**R-08 has changed since the inspection:** current source has structured order translation, a row comparator, sort-before-limit handling and physical-key deduplication, with new tests. Do not repeat “no ordering, no dedup” as the present source state. This review did not execute those tests. Decimal comparator fidelity and native ordering on the new GSI profiles still need work; the new index designs must pass the full gate above.
