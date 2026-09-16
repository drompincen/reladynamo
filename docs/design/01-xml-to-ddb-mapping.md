# Reladynamo Design: XML → Physical DynamoDB Mapping & Declarative Configuration

**Status:** design (Chapter 3 of Reladynamo plan)  
**Agent:** schema  
**Baseline:** Java 11 · Reladomo 18.1.0 · AWS SDK v2 · DynamoDBLocal 2.5.3  
**Non-goals:** reimplementing bitemporal directors; JDBC `DatabaseType`; mutating generated finder/XML contracts

---

## 0. Executive summary

Reladynamo turns an existing Reladomo object model (`*MithraObject.xml`) into a DynamoDB physical design
**automatically**, with an optional override file (`reladynamo.xml`). The derivation is a **pure function**:

```text
(MithraObjectXmlModel, Optional<ReladynamoConfig>) → PhysicalDesign
```

Defaults must work for a simple object with **zero** Reladynamo-specific config. Overrides exist for keys,
table names, capacity, GSIs, attribute-name compression, TTL, and multi-tenant source routing.

**Core decisions (rationale + rejected alternative in each section):**

| Decision | Choice | Rejected |
|---|---|---|
| Table topology | **Table-per-object** (default) | Classic single-table for all domain types |
| Config surface | Separate **`reladynamo.xml`** | Extending `MithraRuntime` XML |
| Sort-key order (bitemporal) | `processingFrom` **then** `businessFrom` | Business-first; null infinity |
| Relationships → GSI | **Opt-in / index-driven**, never every relationship | Auto-GSI for every FK |
| `sourceAttribute` | **Key prefix** by default; table-per-source opt-in | Always separate tables/clients |
| LSI | **Never** derived by default | LSI for temporal alternate sort |

Architecture constraints already decided (do not re-litigate): persister SPI (`MithraObjectReader` /
`MithraObjectPersister` / `MithraDatedObjectPersister`); bitemporal logic stays in `TemporalDirector`;
`PureMithraObjectPersister` is the structural precedent.

---

## 1. Full input surface (`*MithraObject.xml` → DynamoDB)

This section is the **contract**. Status values:

- **supported** — mapped and persisted with defined semantics  
- **supported-with-constraints** — accepted only under stated limits; otherwise fail at config/startup  
- **rejected at config time** — fail fast with an actionable message; never discover at query time  

Sources: Reladomo `mithraobject.xsd` (18.x lineage), `/reladomo-expert`, `/dynamodb-architect`.

### 1.1 Top-level object kinds

| Construct | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `MithraObject` | Durable domain type → one `PhysicalDesign` (table + keys + indexes + codec) | **supported** | Primary target of Reladynamo |
| `MithraPureObject` | In-memory / factory-backed Reladomo type with no JDBC columns | **rejected at config time** (unless explicitly bound as “memory-only, no DDB”) | Pure objects use `MithraPureObjectFactory`, not a durable persister. Reladynamo must not silently invent a table. |
| `MithraTempObject` | Temporary / scratch Reladomo type | **rejected at config time** | Not a durable store contract |
| `MithraInterface` | Shared attribute/relationship contract for generation | **supported-with-constraints** | No physical table of its own; attributes inherited by implementing objects are flattened into each implementer’s design |
| `MithraEmbeddedValueObject` | Dependent value type mapping one or more columns | **supported-with-constraints** | Flattened into owning item attributes; no separate table or PK |
| Resource list (`Mithra` / `*Resource`) | Generation classpath, not persistence | **ignored** (generation-time only) | Not a runtime DDB concern |

**Rejected alternative:** treating `MithraPureObject` as “full-cache DynamoDB.” That conflates Reladomo’s
pure-cache path with durable storage and would diverge from `PureMithraObjectPersister` semantics.

### 1.2 Object header attributes

| Construct | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `PackageName` / `ClassName` | Logical type identity; default table name stem; PK entity prefix | **supported** | Finder/portal key |
| `DefaultTable` | Default DynamoDB **table name** (unless overridden in `reladynamo.xml`) | **supported** | Preserves operator familiarity; DDB table names are not SQL schemas |
| `objectType="transactional"` | Writes allowed (`Put`/`Update`/`Delete`/`TransactWriteItems`) | **supported** | Maps to write-capable persister |
| `objectType="read-only"` | Reads only; any write API fails | **supported** | Persister write methods throw; table may still exist for migration/import |
| `disableForeignKeys` | Ignored for DDB (no FK enforcement) | **supported** (no-op) | Documented no-op; warn once at startup |
| `superClassType="table-per-subclass"` | Each concrete class → own table; attributes duplicated per Reladomo rules | **supported-with-constraints** | Matches Reladomo’s physical expectation; no polymorphic single-item polymorphism |
| `superClassType="table-for-all-subclasses"` | One table for hierarchy; discriminator attribute required in config or XML | **supported-with-constraints** | Needs explicit `entityType` attribute or config discriminator; factory `createObject` remains app concern |
| `superClassType="table-per-class"` | Separate tables per class including non-leaf | **supported-with-constraints** | Reject if hierarchy depth > 5 (Reladomo’s own guidance); joins across levels are residual/in-memory |
| `SuperClass` (generated or plain) | Attribute inheritance for design derivation | **supported** | Flatten inherited attributes into design |
| `DatedTransactionalTemporalDirector` | Class name override for director | **supported** (ignored by DDB layer) | Director stays above persister; Reladynamo must not re-read this to invent temporal rules |
| `UpdateListener` | App callback | **supported** (ignored by DDB layer) | Portal/runtime concern |
| `initializePrimitivesToNull` | Java construction only | **supported** (ignored by DDB layer) | Codec still distinguishes null vs absent for nullable attrs |
| `Import` | Generation only | **ignored** | — |

### 1.3 `Attribute` — java types

Valid Reladomo types (XSD): primitives, `String`, `Date`, `Time`, `Timestamp`, `BigDecimal`, `byte[]`.

| Java type | DynamoDB attribute type | Status | Notes |
|---|---|---|---|
| `boolean` / `Boolean` | `BOOL` | **supported** | Prefer `BOOL` over `0/1` `N` |
| `byte`/`short`/`int`/`long` (+ wrappers) | `N` (decimal string) | **supported** | SDK v2 `AttributeValue.n` |
| `float`/`double` (+ wrappers) | `N` | **supported-with-constraints** | Warn: binary floating point; prefer `BigDecimal` for money |
| `BigDecimal` | `N` | **supported** | **Never** store as `S`; honor `precision`/`scale` on write validation |
| `String` | `S` | **supported** | Empty string allowed; null means omit or NULL token per null policy |
| `Date` | `S` as `yyyy-MM-dd` | **supported** | Fixed-width, lexicographic |
| `Time` | `S` as `HH:mm:ss.SSS` | **supported-with-constraints** | Normalize precision per `modifyTimePrecisionOnSet` |
| `Timestamp` | `S` as UTC ISO-8601 with millis (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`) | **supported** | Millisecond floor for key encodings; full value also stored on payload attrs |
| `byte[]` | `B` | **supported-with-constraints** | Item 400 KiB limit; reject config if declared without overflow policy when `maxLength` suggests large blobs |
| Enumerations / custom types not in XSD list | — | **rejected at config time** | Force explicit mapping via embedded value or String |

**Null policy (payload attributes):**

- `nullable="true"`: omit attribute or store typed null marker — **decision: omit attribute** for sparse size; reconstruct Java null on read.  
- `nullable="false"`: require present value on write; fail insert/update if missing.  
- Primary-key components: never null (Reladomo already constrains this except rare multi-column nullable PK fragments — those are **rejected at config time** for DynamoDB because key attributes cannot be null).

**Rejected alternative:** storing every null as DynamoDB `NULL` type. That defeats sparse GSIs and inflates items.

### 1.4 Attribute flags

| Flag | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `name` | Logical Reladomo name; codec key unless compressed | **supported** | — |
| `columnName` | Default physical attribute name in item (before compression map) | **supported** | Keeps parity with SQL mental model; compression can shorten |
| `primaryKey="true"` | Participates in partition-key construction | **supported** | At least one required |
| `mutablePrimaryKey="true"` | PK can change in Reladomo | **supported-with-constraints** | DDB item identity is immutable → implement as delete+put under new key inside transaction when possible; reject if dated (temporal identity explosion) |
| `primaryKeyGeneratorStrategy="SimulatedSequence"` | Sequence allocation before insert | **supported-with-constraints** | Sequence state stored in a dedicated control item/table; factory SPI retained (see §1.4.1) |
| `primaryKeyGeneratorStrategy="Max"` | Max(existing)+1 | **supported-with-constraints** | Hot-key / contention risk; require explicit `allowMaxPkStrategy="true"` in config; prefer SimulatedSequence |
| nested `<SimulatedSequence>` | Control-store parameters for sequence allocation | **supported-with-constraints** | See §1.4.1 for each attribute |
| `identity="true"` | DB identity column | **rejected at config time** | DynamoDB has no identity columns; use SimulatedSequence |
| `nullable` | See null policy | **supported** | — |
| `maxLength` | Validate String/`byte[]` before write | **supported** | Fail write with clear error; optional truncate only if Reladomo `truncate="true"` |
| `truncate` | Truncate strings to `maxLength` | **supported** | Match Reladomo behavior |
| `precision` / `scale` | BigDecimal validation | **supported** | Reject out-of-range writes |
| `readonly` | Disallow updates to attribute | **supported** | Persister rejects update wrappers targeting it |
| `poolable` / `trim` / `finalGetter` | JVM/generation concerns | **supported** (no DDB effect) | — |
| `timezoneConversion` | Timestamp normalization on codec boundary | **supported-with-constraints** | DDB always stores UTC `S`; `convert-to-database-timezone` requires configured zone; prefer `convert-to-utc` or `none` with UTC app convention |
| `timestampPrecision` | `nanosecond` vs `millisecond` | **supported-with-constraints** | **Key encodings always millisecond**; payload may retain nanos in a shadow attr if needed — see OPEN QUESTION |
| `useForOptimisticLocking` | Conditional write predicate | **supported** | Map to `ConditionExpression` on version/timestamp attr |
| `inPlaceUpdate` | Non-chaining update flag | **supported** (director/app) | Persister still does plain update of dated data object |
| `defaultIfNull` | Read-time default | **supported** | Codec applies on read |
| `setAsString` | Sybase workaround | **ignored** with warn | Irrelevant to DDB |
| `Property` children | Metadata bag | **supported** (opaque) | Available to custom strategies |

#### 1.4.1 Nested `<SimulatedSequence>` attributes

Reladomo nests sequence configuration under the PK attribute. Reladynamo maps these onto a
**sequence control store** (default: items in a shared table `reladynamo_sequences`, overridable),
not onto DynamoDB native sequences (none exist).

| Attribute | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `sequenceName` | Partition key (or SK) identity of the sequence control item | **supported** | Passed through to `MithraSequenceObjectFactory`; also names the DDB control item |
| `sequenceObjectFactoryName` | FQCN of `MithraSequenceObjectFactory` | **supported** | Reladomo SPI retained; factory may be backed by DDB conditional updates |
| `hasSourceAttribute` | Whether sequence counter is per-source | **supported** | If true, control-item key includes `SRC#<source>` (aligned with §4) |
| `batchSize` | How many IDs to reserve per borrow | **supported-with-constraints** | Implemented via atomic add on a `nextValue` Number attr; large batches reduce round-trips but increase gap-on-crash |
| `initialValue` | Starting counter | **supported** | Used only when control item is first created (`attribute_not_exists`) |
| `incrementSize` | Step between IDs | **supported** | Encoded in control item; multi-site disjoint ranges remain an app concern |

**Control-item sketch:** `PK = v1#SEQ#<sequenceName>[#SRC#<source>]`, `SK = META`, attributes
`nextValue (N)`, `incrementSize (N)`, `batchSize (N)`. Allocation uses a conditional update /
transactional write so concurrent inserters cannot mint duplicate IDs.

**Rejected alternative:** scanning the entity table for `Max` on every insert. That is the legacy
`Max` strategy and concentrates load on the entity partition.

### 1.5 `AsOfAttribute`

| Construct | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `name` (`businessDate` / `processingDate` conventionally) | Temporal dimension metadata | **supported** | Drives SK grammar and dated persister selection |
| `fromColumnName` / `toColumnName` | Payload attributes holding from/thru (and SK uses from) | **supported** | Director already fills these on data objects |
| `infinityDate` | Sentinel Timestamp (Java snippet in XML) | **supported** | Resolved at generation/runtime; encoded as fixed-width UTC string in keys |
| `infinityIsNull="true"` | Infinity stored as SQL NULL | **rejected at config time** | Null keys/sorts break DynamoDB ordering and Reladynamo’s lexicographic SK contract |
| `isProcessingDate="true"` | Marks processing dimension | **supported** | Selects SK component order role |
| `futureExpiringRowsExist` | Termination semantics in director | **supported** (no DDB special-case) | Director owns behavior; items still store thru as given |
| `toIsInclusive` | Half-open vs inclusive thru semantics | **supported** (query planner) | Persister stores timestamps as-is; planner builds filters matching Reladomo |
| `defaultIfNotSpecified` | Finder default as-of | **supported** (portal/finder) | Not a storage concern |
| `timezoneConversion` / `timestampPrecision` / `poolable` | Same as Attribute | **supported-with-constraints** | Same rules |

**Bitemporal shapes:**

| Shape | AsOf count | SK profile |
|---|---|---|
| Non-dated | 0 | `v1#ND` |
| Business-only (`GenericNonAuditedTemporalDirector`) | 1, not processing | `v1#B#<businessFrom>` |
| Audit-only | 1, processing | `v1#P#<processingFrom>` |
| Bitemporal | 2 | `v1#P#<processingFrom>#B#<businessFrom>` |

### 1.6 `SourceAttribute`

| Construct | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `SourceAttribute` (`int` or `String` only) | Multi-tenant / multi-database routing key | **supported** | See §4 |
| Appears in logical PK automatically (Reladomo) | Included in DynamoDB PK encoding | **supported** | Prevents cross-source aliasing |

### 1.7 `Relationship`

| Cardinality / feature | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `one-to-one` / `many-to-one` | Navigation via finder; parent PK lookup | **supported** | No automatic GSI |
| `one-to-many` | Child query by FK attributes | **supported-with-constraints** | GSI only if §2.5 rules fire or config opts in |
| `many-to-many` | Join object or dual edges | **supported-with-constraints** | Require explicit join `MithraObject` or config `edgeStrategy`; never auto dual-write edges |
| `reverseRelationshipName` | Bidirectional navigation metadata | **supported** | Does not duplicate GSI |
| `relatedIsDependent` | Cascade insert/delete ordering | **supported-with-constraints** | Map cascades to `TransactWriteItems` when ≤100 items; otherwise sequenced writes with documented non-atomicity |
| `orderBy` | In-memory or SK-aligned order | **supported-with-constraints** | Native order only if matches SK/GSI SK |
| `parameters` (parameterized relationship) | Runtime join params | **supported-with-constraints** | Usually residual filter; reject auto-index |
| `foreignKey` / `directReference` | Reladomo TX reorder / cache | **supported** (no DDB schema effect) | — |

### 1.8 `Index` (Reladomo cache index)

Reladomo `Index` is primarily a **cache** index declaration, not DDL.

| Index kind | DynamoDB meaning | Status | Rationale |
|---|---|---|---|
| `unique="true"` on non-PK attributes | Candidate for **sparse uniqueness** via dedicated item or GSI+conditional | **supported-with-constraints** | GSI cannot enforce uniqueness (§dynamodb-architect). Default: create uniqueness **reservation item** pattern (`UQ#<index>#<value>` → PK) with conditional put; optional GSI for lookup only |
| `unique="false"` | Cache-only hint | **supported** (no auto GSI) | Avoid write amplification for every cache index |
| Config `promoteIndexToGsi="..."` | Explicit GSI derivation | **supported** | Escape hatch |

### 1.9 Embedded values & misc

| Construct | Status | Notes |
|---|---|---|
| `EmbeddedValue` / nested mappings | **supported** | Flatten to attributes; PK flags on mappings honored |
| `TransactionalMethodSignature` | **ignored** by DDB | Generation/TX boundary only |
| `EnumerationAttribute` (interface models) | **supported-with-constraints** | Persist underlying javaType |
| Computed attributes (XSD commented / unused) | **rejected** if encountered | Not in stable 18.1 contract |

### 1.10 Support matrix summary (quick scan)

```text
SUPPORTED CORE: MithraObject, attributes, PK, asOf, sourceAttribute, transactional/read-only,
                relationships (navigation), DefaultTable, SimulatedSequence (with control store)

CONSTRAINED:    Max PK strategy, mutable PK (non-dated only), byte[], float/double, inheritance,
                one-to-many GSI promotion, many-to-many, unique Index → reservation items,
                timezoneConversion, dependent cascades >100 items

REJECTED:       MithraPureObject as durable, MithraTempObject, identity columns, infinityIsNull,
                nullable PK components in DDB keys, auto-GSI for every relationship, LSI defaults
```

---

## 2. Derivation rules → physical design

### 2.1 Algorithm overview

```text
derive(model: MithraObjectModel, cfg: ReladynamoConfig | ∅) → PhysicalDesign

1. validateModel(model)                         // §1 rejects
2. mergeConfig(model, cfg) → EffectiveConfig    // defaults + overrides
3. chooseTableTopology(effective)               // §2.2
4. buildPartitionKeySpec(effective)             // §2.3
5. buildSortKeySpec(effective)                  // §2.4
6. buildAttributeCodec(effective)               // names, types, compression
7. buildSecondaryIndexes(effective)             // §2.5–2.6
8. buildCapacityAndTtl(effective)               // §3
9. buildAccessPatternMatrix(effective)          // required artifact
10. validatePhysicalDesign(design)              // §3.4 fail-fast
11. return immutable PhysicalDesign
```

`PhysicalDesign` is an immutable value object (Java 11: final class, final fields — **no records**).

### 2.2 Single-table vs table-per-object

#### Decision: **table-per-object is the default**

**Argument for table-per-object (chosen):**

1. **Reladomo’s unit of persistence is the portal/finder, not the application aggregate.**  
   Each class has its own `MithraObjectPortal`, reader, and persister. `PureMithraObjectPersister`
   is bound per portal. A 1:1 table↔portal mapping keeps lifecycle, metrics, IAM, and backups aligned
   with how operators already think about Reladomo classes.

2. **Access patterns are predominantly per-type.**  
   `CustomerFinder.findMany(...)` does not need to co-query `Order` items in the same DynamoDB
   `Query` the way a single-table design would. Relationship navigation issues a **second** finder
   call (possibly deep-fetch). Colocating types does not remove that second logical query unless we
   also reinvent Reladomo’s relationship engine.

3. **Operational isolation.**  
   Different objects differ in retention, throughput, encryption, and blast radius. Per
   `/dynamodb-architect` rule 10: prefer multiple tables when entities have unrelated access patterns,
   retention, or ownership boundaries.

4. **Evolution cost.**  
   Changing one object’s key schema migrates one table, not a shared mega-table.

**Argument for single-table (rejected as default):**

- Fewer tables; possible `TransactWriteItems` across entity types without cross-table transactions
  (still same-account/region limits).
- Classic “one query returns parent+children” — **but Reladomo deep-fetch is multi-finder**, so the
  win is smaller than in hand-written DynamoDB apps.

**Opt-in escape hatch:** `reladynamo.xml` may set `tableMode="shared"` and `sharedTableName="..." `
for a closed set of types that truly share access patterns (rare). Shared mode requires unambiguous
entity prefixes in PK/SK and an overloaded index plan.

### 2.3 Partition key construction

**Grammar (versioned):**

```text
PK = "v1"
   + optional source segment
   + "#" + entityToken
   + "#" + join(pkComponents, "#")
```

| Piece | Rule |
|---|---|
| `entityToken` | `ClassName` uppercased, or `DefaultTable`, or config `entityPrefix` |
| `pkComponents` | All `primaryKey="true"` attributes in **XML declaration order**, each encoded |
| `source` | If `SourceAttribute` present: see §4 (`SRC#<source>` before entity by default) |

**Component encoding:**

| Type | Encoding |
|---|---|
| Integral / BigDecimal | Fixed-width decimal where needed for sort-safety **inside PK only if multi-attr order matters**; prefer unpadded for high-cardinality IDs that are only equality-matched |
| String | Raw; `#` and leading/trailing spaces **rejected** at write validation (delimiter safety) |
| Timestamp/Date in PK (rare) | Same fixed UTC forms as SK |
| boolean | `T` / `F` |

**Multi-column PK example:**

```text
Position: accountId=42, productId=7
PK = v1#POSITION#42#7
```

**With source:**

```text
PK = v1#SRC#DESK_A#POSITION#42#7
```

**Rejected alternative:** hashing the PK into a single opaque string. That destroys supportability and
makes `begins_with` entity scans impossible for admin tools.

**Hot-key note:** if a single logical PK absorbs extreme write rates (unusual for Reladomo business
keys, more common for event-like objects), config may enable write sharding — **not default**.

### 2.4 Sort key construction (temporal)

#### Decision: processing-from **then** business-from

For bitemporal objects:

```text
SK = v1#P#<processingDateFrom>#B#<businessDateFrom>
```

**Why this order:**

1. **`TemporalDirector` decomposes mutations as processing-layered writes.**  
   A correction closes current processing rows (`OUT_Z = now`) and inserts new rows with
   `IN_Z = now`. Grouping by `processingFrom` first clusters each “belief layer,” then orders
   business segments within that layer — matching how audit reconstruction walks history.

2. **`getForDateRange` and enroll paths are identity-scoped.**  
   The partition already isolates one logical object. Within that collection, processing-major order
   makes “all business fragments written under processing instant P” a `begins_with` friendly prefix
   when `P` is known exactly (insert batch for one director step).

3. **Online as-of reads remain filter-friendly.**  
   Point-in-time as-of (`from <= asOf < thru` on both dimensions) is **not** a pure SK equality
   regardless of order, because thru bounds live in payload attributes. Item collections per logical
   key are expected to be **bounded** (versions, not unbounded event streams). Therefore:

   ```text
   Query(PK = ...) → FilterExpression on from/thru pairs → ≤ few pages
   ```

   is the default as-of plan. SK order optimizes director-shaped scans and range dumps, not magic
   containment without filters.

**Rejected alternative (business-first):** better for “all processing versions of one business
segment,” which is a less common Reladomo interactive path than processing-layered audit.

#### Timestamp encoding

- Always **UTC**.  
- Always **fixed-width** `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (23 chars).  
- Lexicographic order ≡ chronological order.  
- No unpadded nanos in the **key** (nanos optional in payload — OPEN QUESTION below).

#### Infinity encoding

- Use the model’s configured infinity `Timestamp`, rendered with the same fixed-width UTC pattern.  
- Typical Reladomo sentinel `9999-12-01 23:59:00.0` → `9999-12-01T23:59:00.000Z`.  
- **Never** `null`, never empty string, never `Infinity` literal.  
- Because the sentinel is near the max calendar end, it sorts **after** all real timestamps — correct
  for “open-ended” segments when comparing from-points.

`infinityIsNull=true` is rejected (§1.5) precisely so this property holds.

#### Other temporal shapes

| Shape | SK |
|---|---|
| Non-dated | `v1#ND` |
| Business-only | `v1#B#<businessFrom>` |
| Audit-only | `v1#P#<processingFrom>` |
| Bitemporal | `v1#P#<processingFrom>#B#<businessFrom>` |

**Collision / tie-break:** Reladomo should not emit two data objects with identical logical PK and
identical from-pair. If it does, second put must fail a condition (`attribute_not_exists` or version
check). No silent overwrite.

### 2.5 Which relationships justify a GSI

Apply `/dynamodb-architect`: indexes only for **named access patterns**.

| Situation | Auto GSI? | Why |
|---|---|---|
| `many-to-one` / `one-to-one` toward target PK | **No** | Target `GetItem`/`Query` by its own PK |
| `one-to-many` where child FK ⊂ child PK | **No** | Child PK/`begins_with` already supports parent-scoped listing |
| `one-to-many` where child FK ⊄ child PK (classic Order→OrderItem by `orderId` while item PK is `id`) | **Yes, candidate** | Promote if Reladomo `Index` unique/non-unique lists those FK attrs **or** config `gsi` declares it |
| Reverse relationship only | **No** | Metadata duplicate |
| Parameterized relationship | **No** | Values not known for keying |
| `many-to-many` | **No** auto | Require join object design |
| Unique business key (`email`, `externalId`) via `Index unique="true"` | **Uniqueness item**, optional GSI | GSI alone cannot enforce uniqueness |

**Default projection for derived GSIs:** `INCLUDE` of attributes needed to hydrate a `MithraDataObject`
for list screens is often too wide. Prefer:

1. `KEYS_ONLY` + `BatchGetItem` when items are large or rarely listed in bulk  
2. `INCLUDE` of non-temporal scalar fields commonly filtered — only when measured  

**Never default to `ALL`.**

### 2.6 Index overloading, sparse indexes, projections

Concrete Reladynamo application of architect rules:

1. **No LSIs by default.** Temporal alternate orders change; LSI is immutable and couples to item
   collection size limits. Reject config `lsi` unless user accepts table-replacement evolution.
2. **Sparse GSIs** for “current only” views: e.g. attribute `gsi1pk` present only when
   `processingThru == infinity` (and optionally business current). Writers must **remove** sparse keys
   when a row is closed — absence creates sparsity, not a status filter.
3. **Overloading** one GSI across object types only in `tableMode="shared"`, with mandatory prefixes
   (`CUSTOMER#...` vs `ORDER#...`). Table-per-object designs use per-table GSIs named
   `gsi_<indexName>`.
4. **Budget:** each GSI is +1 write path on indexed mutations; startup validation warns if >2 GSIs
   derived without explicit `acknowledgeWriteAmplification="true"`.

### 2.7 Access-pattern matrix (required output of derivation)

Example for bitemporal `Customer`:

| Access pattern | Key used | Key condition | Bound |
|---|---|---|---|
| Get version by identity + froms | base | `PK=..., SK=v1#P#...#B#...` | 1 |
| As-of B,P | base | `PK=...` + filter containment | versions/object |
| `getForDateRange` | base | `PK=...` (+ optional SK prefix) | versions/object |
| Find by email (unique index) | UQ item or GSI | `PK=UQ#EMAIL#...` or GSI PK | 1 |
| List addresses for customer | Address table GSI | `GSI1PK=CUST#id` | addresses/customer |

Scans are **not** an access pattern for interactive finders; planner must opt-in.

### 2.8 Payload shape

Each item stores:

- PK, SK (and GSI key attrs when present)  
- All mapped attributes under physical names  
- Temporal from/thru columns as ordinary attributes (even though from also appears in SK) — required
  for containment filters and H2 parity  
- Optional `_rd_v` (Reladynamo codec version) integer  

**Rejected:** storing only SK timestamps without payload from/thru — breaks thru filters and
conformance with H2 column layout.

---

## 3. Declarative configuration format

### 3.1 Separate `reladynamo.xml` (decision)

**Chosen:** a separate optional file, referenced from application bootstrap (and optionally pointed to
from a MithraRuntime property / system property / classpath default `reladynamo.xml`).

**Why not extend `MithraRuntime` XML?**

| Criterion | Extend MithraRuntime | Separate `reladynamo.xml` |
|---|---|---|
| Schema ownership | Reladomo XSD — forking/extending breaks tooling & upgrades | Reladynamo-owned XSD |
| Optionality | Empty extension still couples parsers | Zero file → pure defaults |
| Reviewability | Mixes cache/connection concerns with DDB physics | Clear DDB boundary |
| Multi-backend tests | H2 runtime XML stays vanilla | DDB config only on DDB path |

**Rejected alternative:** Java fluent DSL only. XML matches Reladomo operators’ workflow and is
diff-friendly; a Java API can still mirror the same model for tests.

### 3.2 Zero-config defaults

For a simple non-dated transactional object with one long PK `id`, no relationships needing GSI:

```text
tableName          = DefaultTable or ClassName
billingMode        = PAY_PER_REQUEST
PK                 = v1#<ENTITY>#<id>
SK                 = v1#ND
GSIs               = none
compression        = none
TTL                = off
source routing     = n/a
```

Startup succeeds with only Reladomo object XML + DynamoDB client binding.

### 3.3 Annotated example — bitemporal `Customer` + relationship

**Object XML (existing, unchanged):**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<MithraObject objectType="transactional"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:noNamespaceSchemaLocation="mithraobject.xsd">
    <PackageName>com.acme.domain</PackageName>
    <ClassName>Customer</ClassName>
    <DefaultTable>CUSTOMER</DefaultTable>

    <AsOfAttribute name="businessDate" fromColumnName="FROM_Z" toColumnName="THRU_Z"
                   toIsInclusive="false"
                   infinityDate="[com.acme.domain.InfinityTimestamp.getInfinity()]"
                   isProcessingDate="false"/>
    <AsOfAttribute name="processingDate" fromColumnName="IN_Z" toColumnName="OUT_Z"
                   toIsInclusive="false"
                   infinityDate="[com.acme.domain.InfinityTimestamp.getInfinity()]"
                   isProcessingDate="true"/>

    <Attribute name="customerId" javaType="long" columnName="CUSTOMER_ID" primaryKey="true"/>
    <Attribute name="email" javaType="String" columnName="EMAIL" maxLength="256" nullable="false"/>
    <Attribute name="displayName" javaType="String" columnName="DISPLAY_NAME" maxLength="128"/>

    <Relationship name="addresses" relatedObject="Address" cardinality="one-to-many"
                  reverseRelationshipName="customer" relatedIsDependent="true">
        Address.customerId = this.customerId
    </Relationship>

    <Index name="idxEmail" unique="true">email</Index>
</MithraObject>
```

**Optional `reladynamo.xml`:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Reladynamo physical-design overrides.
  Entire file is optional; per-object sections are optional.
-->
<Reladynamo xmlns="https://reladynamo.dev/schema/reladynamo"
            version="1">

  <!-- Client / account defaults -->
  <DynamoDefaults billingMode="PAY_PER_REQUEST"
                  tablePrefix=""
                  keyGrammarVersion="v1"
                  acknowledgeWriteAmplification="false"/>

  <Object className="com.acme.domain.Customer">
    <!-- Override table name (default would be CUSTOMER) -->
    <Table name="prod_customer" billingMode="PAY_PER_REQUEST"/>

    <!-- Key strategy: default is fine; shown for documentation -->
    <Keys strategy="default"
          entityPrefix="CUSTOMER"
          sortKeyTemporalOrder="PROCESSING_THEN_BUSINESS"/>

    <!-- Unique email: reservation item pattern (default for unique Index) -->
    <UniqueConstraint indexName="idxEmail"
                     mode="RESERVATION_ITEM"
                     reservationTable="prod_customer_uq"/>

    <!-- Explicit GSI not required for Customer itself; child Address declares FK GSI -->
    <AttributeNames>
      <!-- compression: short wire names, long Reladomo names remain in model -->
      <Map logical="displayName" physical="dn"/>
      <Map logical="email" physical="em"/>
    </AttributeNames>

    <!-- TTL optional: only for archival copies, NOT as bitemporal thru -->
    <!-- <Ttl attribute="purgeAfterEpochSec" enabled="false"/> -->
  </Object>

  <Object className="com.acme.domain.Address">
    <Table name="prod_address"/>
    <Gsi name="byCustomer"
         partitionKeyAttributes="customerId"
         partitionKeyPrefix="CUST"
         sortKeyAttributes="addressId"
         projection="KEYS_ONLY"
         sparse="false"/>
  </Object>

</Reladynamo>
```

### 3.4 Overrides catalog

| Override | Default | Purpose |
|---|---|---|
| `Table/@name` | `DefaultTable`/`ClassName` | Physical table name |
| `Table/@billingMode` | `PAY_PER_REQUEST` | On-demand vs `PROVISIONED` |
| `Table/@readCapacityUnits` etc. | n/a | Only if provisioned |
| `Keys/@strategy` | `default` | Future alternate grammars (`v2`, hashed) |
| `Keys/@entityPrefix` | class/table token | PK entity segment |
| `Keys/@sortKeyTemporalOrder` | `PROCESSING_THEN_BUSINESS` | Must match stored data |
| `Gsi` | none auto unless §2.5 | Alternate access paths |
| `UniqueConstraint` | reservation for unique Index | Uniqueness without trusting GSI |
| `AttributeNames/Map` | identity | Compression / rename |
| `Ttl` | off | Epoch-seconds Number attr; **never** reuse temporal thru |
| `SourceRouting` | `KEY_PREFIX` | See §4 |
| `tableMode` | `perObject` | `shared` opt-in |
| `allowMaxPkStrategy` | false | Gate Max generator |
| `allowLsi` | false | Gate dangerous LSI |

### 3.5 Startup validation — top 10 error messages

Validation runs before any finder traffic. Messages are **exact** contracts for tests.

1. **Missing primary key**  
   `RELADYNAMO-CFG-001: Object com.acme.domain.Customer has no primaryKey="true" attribute; DynamoDB partition key cannot be derived.`

2. **Pure object bound to DynamoDB**  
   `RELADYNAMO-CFG-002: MithraPureObject com.acme.domain.ScratchCache cannot be bound to DynamoDB; use MithraPureObjectFactory or convert to MithraObject.`

3. **infinityIsNull**  
   `RELADYNAMO-CFG-003: Object com.acme.domain.Customer AsOfAttribute businessDate sets infinityIsNull=true; Reladynamo requires a concrete infinity Timestamp for lexicographic sort keys.`

4. **Identity column**  
   `RELADYNAMO-CFG-004: Attribute com.acme.domain.Customer.id has identity="true"; DynamoDB has no identity columns. Use primaryKeyGeneratorStrategy="SimulatedSequence" or assign keys in application code.`

5. **GSI references unknown attribute**  
   `RELADYNAMO-CFG-005: reladynamo.xml Gsi 'byCustomer' on com.acme.domain.Address references attribute 'custId' which does not exist on the MithraObject model (known: customerId, addressId, ...).`

6. **Billing mode incomplete**  
   `RELADYNAMO-CFG-006: Table prod_customer billingMode=PROVISIONED but readCapacityUnits/writeCapacityUnits are missing or ≤ 0.`

7. **TTL attribute type**  
   `RELADYNAMO-CFG-007: Ttl attribute 'purgeAfterEpochSec' on com.acme.domain.Customer must map to a Number (epoch seconds); javaType=String is invalid.`

8. **Delimiter-unsafe PK string config**  
   `RELADYNAMO-CFG-008: Attribute com.acme.domain.Customer.code is part of the DynamoDB partition key and allows values containing '#'; forbid '#' in key attributes or supply a Keys escapeStrategy.`

9. **Write amplification acknowledgment**  
   `RELADYNAMO-CFG-009: Object com.acme.domain.Order derives 3 GSIs; set DynamoDefaults acknowledgeWriteAmplification="true" after reviewing write cost, or remove unused Gsi entries.`

10. **Sort-key order mismatch with existing table tag**  
    `RELADYNAMO-CFG-010: PhysicalDesign for com.acme.domain.Customer requests sortKeyTemporalOrder=BUSINESS_THEN_PROCESSING but table prod_customer is tagged rd:skOrder=PROCESSING_THEN_BUSINESS; key grammar changes require a new table migration (see evolution matrix).`

Additional high-value checks (same fail-fast class): nullable PK component; `Max` without allow flag;
`many-to-many` without join strategy; shared table without prefixes; LSI without `allowLsi`.

---

## 4. Multi-tenancy and `sourceAttribute`

Reladomo `SourceAttribute` routes objects to different **databases**. DynamoDB has tables, accounts,
and regions — not JDBC URLs.

### 4.1 Default rule: **key prefix** (same table)

```text
PK = v1#SRC#<sourceValue>#<ENTITY>#<pk...>
```

| Property | Behavior |
|---|---|
| Isolation | Logical; IAM still table-scoped |
| Cross-source query | Impossible by PK equality without knowing source (good) |
| Ops | One table to backup/monitor |
| Cardinality | Source values should be moderate; very high fan-out still OK if PK remains high-cardinality overall |

Connection-manager analogue: `ReladynamoSourceRouter` maps source → `{tableOverride?, client?}`.

### 4.2 Opt-in: **table-per-source**

```xml
<SourceRouting mode="TABLE_PER_SOURCE"
               tableNamePattern="prod_customer_{source}"/>
```

Use when compliance requires physical separation, different capacity, or distinct backup policies.

### 4.3 Opt-in: **separate clients / regions**

```xml
<SourceRouting mode="CLIENT_PER_SOURCE">
  <Source value="US" clientRef="ddbUs" region="us-east-1"/>
  <Source value="EU" clientRef="ddbEu" region="eu-west-1"/>
</SourceRouting>
```

Use for geo residency. **Do not** pretend global tables merge Reladomo transactions across regions
(`/dynamodb-architect` global-table rules).

### 4.4 Rejected as default

| Mode | Why not default |
|---|---|
| Always table-per-source | Operational explosion for desk/source cardinality |
| Source only as non-key attribute | Cross-tenant leakage via forgotten filters; violates Reladomo identity |
| Single shared PK without source | Cache/store aliasing across sources |

---

## 5. Evolution (change matrix)

Cross-reference: `/dynamodb-architect` Operations change matrix (§Change matrix). Reladynamo-specific
notes below.

| Change | In place? | Reladynamo path |
|---|---:|---|
| Add optional Reladomo attribute | **Yes** | Deploy codec that reads missing as null/default; no table DDL |
| Remove attribute | **Yes** (soft) | Stop writing; old items retain bytes until cleanup job |
| Change attribute meaning/type | **No (safe)** | New physical name + dual-read; do not reuse name |
| Add GSI (new access pattern) | **Yes, async** | Create GSI; backfill key attrs if sparse/derived; wait ACTIVE |
| Change GSI keys/projection | **No** | New GSI → cut reads → delete old |
| Remove GSI | **Yes, destructive** | Prove unused; delete |
| Change table PK/SK grammar (`v1`→`v2`, temporal order flip) | **No** | New table + dual-write/stream + backfill + cutover |
| Add LSI | **No** | New table only |
| Enable TTL / PITR / streams | **Yes** | Table settings; TTL must not redefine temporal thru |
| Change capacity mode | **Usually** | Update table; not a key migration |
| Add `sourceAttribute` to existing object | **No** | PK grammar changes → new table migration |
| Promote unique Index to reservation items | **Additive** | Deploy writers; backfill reservations; then enforce |
| Compression map rename | **Additive** | Dual-read old/new physical names during rollout |

**Hard rule:** persisted key encodings are durable APIs. Incompatible grammar ⇒ `v2` table or
`keyGrammarVersion` bump with migration, never silent reinterpretation.

---

## 6. Testability (pure derivation, `/tdd`)

### 6.1 Purity requirement

```java
public final class PhysicalDesignDeriver {
    public PhysicalDesign derive(MithraObjectModel model, ReladynamoConfig config) {
        // no AWS calls, no I/O, no time, no randomness
        ...
    }
}
```

Unit tests assert on `PhysicalDesign` with AssertJ; **DynamoDBLocal appears only in integration/
conformance modules**, not in derivation tests.

### 6.2 Per-rule test map (RED → GREEN)

| Rule | Example test name | Asserts |
|---|---|---|
| Table-per-object default | `should_use_default_table_name_when_no_override` | `design.tableName() == "CUSTOMER"` |
| PK single attr | `should_build_partition_key_when_single_long_pk` | pattern `v1#CUSTOMER#{id}` |
| PK multi attr | `should_join_pk_components_in_xml_order` | order stable |
| Source prefix | `should_prefix_source_when_source_attribute_present` | `SRC#` segment |
| Non-dated SK | `should_use_nd_sort_key_when_no_as_of` | `v1#ND` |
| Bitemporal SK order | `should_place_processing_before_business_in_sort_key` | `P#` before `B#` |
| Infinity encoding | `should_encode_infinity_as_fixed_width_utc` | `9999-12-01T23:59:00.000Z` |
| Reject infinityIsNull | `should_reject_model_when_infinity_is_null` | CFG-003 |
| No auto GSI many-to-one | `should_not_create_gsi_for_many_to_one_relationship` | indexes empty |
| one-to-many FK GSI | `should_derive_gsi_when_fk_not_in_child_pk_and_index_declared` | GSI present |
| Unique index → reservation | `should_plan_reservation_item_for_unique_index` | constraint mode |
| Compression map | `should_apply_attribute_name_compression` | physical `dn` |
| Config unknown attr | `should_fail_startup_validation_when_gsi_attribute_missing` | CFG-005 |
| Shared table opt-in | `should_require_entity_prefix_when_table_mode_shared` | validation error |

### 6.3 Suggested types (Java 11-safe sketches)

```java
package com.reladynamo.design;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PhysicalDesign {
    private final String className;
    private final String tableName;
    private final KeySpec partitionKey;
    private final KeySpec sortKey;
    private final List<GsiSpec> gsis;
    private final List<UniqueConstraintSpec> uniqueConstraints;
    private final AttributeCodecSpec codec;
    private final BillingMode billingMode;
    private final SourceRoutingSpec sourceRouting;

    public PhysicalDesign(
            String className,
            String tableName,
            KeySpec partitionKey,
            KeySpec sortKey,
            List<GsiSpec> gsis,
            List<UniqueConstraintSpec> uniqueConstraints,
            AttributeCodecSpec codec,
            BillingMode billingMode,
            SourceRoutingSpec sourceRouting) {
        this.className = Objects.requireNonNull(className, "className");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        this.sortKey = Objects.requireNonNull(sortKey, "sortKey");
        this.gsis = Collections.unmodifiableList(gsis);
        this.uniqueConstraints = Collections.unmodifiableList(uniqueConstraints);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.billingMode = Objects.requireNonNull(billingMode, "billingMode");
        this.sourceRouting = Objects.requireNonNull(sourceRouting, "sourceRouting");
    }

    public String className() { return className; }
    public String tableName() { return tableName; }
    public KeySpec partitionKey() { return partitionKey; }
    public KeySpec sortKey() { return sortKey; }
    public List<GsiSpec> gsis() { return gsis; }
    public List<UniqueConstraintSpec> uniqueConstraints() { return uniqueConstraints; }
    public AttributeCodecSpec codec() { return codec; }
    public BillingMode billingMode() { return billingMode; }
    public SourceRoutingSpec sourceRouting() { return sourceRouting; }
}
```

```java
package com.reladynamo.design;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class TemporalKeyEncoder {
    private static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC);

    private TemporalKeyEncoder() {}

    public static String encodeTimestampUtcMillis(Timestamp ts) {
        if (ts == null) {
            throw new IllegalArgumentException("timestamp key component must not be null");
        }
        return UTC_MILLIS.format(ts.toInstant());
    }

    public static String bitemporalSortKey(Timestamp processingFrom, Timestamp businessFrom) {
        return "v1#P#" + encodeTimestampUtcMillis(processingFrom)
                + "#B#" + encodeTimestampUtcMillis(businessFrom);
    }
}
```

```java
package com.reladynamo.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class TemporalKeyEncoderTest {
    @Test
    void should_place_processing_before_business_in_sort_key() {
        Timestamp p = Timestamp.from(Instant.parse("2026-08-11T14:32:09.123Z"));
        Timestamp b = Timestamp.from(Instant.parse("2020-01-01T00:00:00.000Z"));

        String sk = TemporalKeyEncoder.bitemporalSortKey(p, b);

        assertThat(sk).isEqualTo("v1#P#2026-08-11T14:32:09.123Z#B#2020-01-01T00:00:00.000Z");
        assertThat(sk.indexOf("#P#")).isLessThan(sk.indexOf("#B#"));
    }

    @Test
    void should_reject_null_timestamp_for_key_encoding() {
        assertThatThrownBy(() -> TemporalKeyEncoder.encodeTimestampUtcMillis(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }
}
```

### 6.4 Parser isolation

`MithraObjectXmlParser.parse(String xml) → MithraObjectModel` is separately tested with fixtures
copied from Reladomo samples. Deriver tests may use hand-built `MithraObjectModel` fakes to avoid
XML coupling when testing a single rule (`/tdd` rule: test one behaviour).

### 6.5 Conformance boundary

Derivation unit tests **never** start DynamoDBLocal. A higher-level test may take `PhysicalDesign`
and call `TableCreator.create(design, dynamoDbClient)` — that is integration, not derivation TDD.

---

## 7. Wiring sketch (persister binding, not re-litigated)

```java
// Illustrative bootstrap — Java 11, AWS SDK v2
PhysicalDesign design = new PhysicalDesignDeriver()
        .derive(model, ReladynamoConfigLoader.loadOptional("reladynamo.xml"));
DesignValidator.validateOrThrow(design);

DynamoDbClient client = DynamoDbClient.builder().build();
DynamoDbItemCodec codec = new DynamoDbItemCodec(design.codec());
MithraDatedObjectPersister persister =
        new DynamoDbDatedObjectPersister(client, design, codec);
// Portal binding uses Reladomo runtime hooks analogous to PureMithraObjectPersister installation.
```

Bitemporal correctness remains in `GenericBiTemporalDirector`; the persister only reads/writes the
data objects it is handed.

---

## 8. OPEN QUESTIONS

1. **OPEN QUESTION:** Should payload `Timestamp` attributes retain nanosecond precision in a companion
   attribute (e.g. `IN_Z_nanos`) while keys stay millisecond, or truncate all timestamps to millis for
   H2/DDB parity simplicity? Conformance suite must decide before GA.

2. **OPEN QUESTION:** For unique constraints, is a **separate reservation table** preferred over
   reservation items colocated in the entity table under `UQ#` PK prefix? Colocation simplifies
   transactions; separate tables isolate hot email namespaces.

3. **OPEN QUESTION:** Exact Reladomo 18.1.0 portal API for installing a custom
   `MithraDatedObjectPersister` without a generated `*DatabaseObject` — spike in Chapter 1 must
   confirm; this design assumes it is possible (kill criterion in plan).

4. **OPEN QUESTION:** Whether `timezoneConversion=convert-to-database-timezone` is supported in v1 or
   deferred (recommend defer; require UTC).

5. **OPEN QUESTION:** Maximum expected versions per logical key before as-of `Query+Filter` needs a
   sparse “current” GSI by default — needs measurement on real corpora.

---

## 9. Alternatives rejected (summary table)

| Topic | Rejected | Why |
|---|---|---|
| Single-table default | All types in one table | Finder/portal granularity; ops isolation; weak deep-fetch synergy |
| MithraRuntime extension | Embed DDB config in Reladomo runtime XML | Schema ownership; upgrade fragility |
| Business-first SK | `B#...#P#...` | Worse match to director processing layers |
| Null infinity | `infinityIsNull` | Breaks sort and key validity |
| Auto-GSI every relationship | Convenience indexes | Write amplification; architect rules |
| Default LSI for temporal | Alternate sort | Immutable; migration trap |
| GSI-enforced uniqueness | Unique email via GSI only | Eventually consistent; races |
| Reimplement bitemporal in codec | Store “current row only” | Violates architecture; H2 divergence |

---

## 10. Implementation checklist (downstream, not this deliverable)

- [ ] `mithraobject` subset parser + model POJOs  
- [ ] `reladynamo.xsd` + config loader  
- [ ] `PhysicalDesignDeriver` + `DesignValidator` (CFG-001…010)  
- [ ] `TemporalKeyEncoder` / `PartitionKeyEncoder`  
- [ ] Table DDL synthesizer (`CreateTableRequest` SDK v2)  
- [ ] Unit tests per §6.2 (TDD)  
- [ ] Integration: design → DynamoDBLocal table → round-trip one bitemporal object  

---

## Document control

| Field | Value |
|---|---|
| Deliverable path | `agents/schema/output/schema.md` |
| Skills applied | reladomo-expert, dynamodb-architect, java-expert, tdd |
| XSD reference | goldmansachs/reladomo `mithraobject.xsd` |
| Plan chapter | 3 — XML-driven mapping |

---

# Amendment (Claude, 2026-09-13) — sort-key timestamp format

§2.4's ISO-8601 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (23 chars) is **superseded** by the implemented and
tested `io.reladynamo.core.temporal.TemporalEncoder` format `yyyyMMddHHmmssSSS` (17 chars). Both are
fixed-width, UTC and lexicographically sortable; the compact form saves 12 bytes per temporal
component on every item, which is permanent per-item cost, against a readability loss confined to
console debugging. See `context/DECISIONS.md`.

Unchanged from §2.4: the composition `v1#P#<processingDateFrom>#B#<businessDateFrom>`, the
processing-major ordering, and `v1#ND` for non-dated entities.
