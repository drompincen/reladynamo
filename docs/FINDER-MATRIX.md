# Generated-finder differential matrix

## Decision and acceptance bar

Implement a JUnit 5 matrix in `reladynamo-ddb` whose stimulus is a **generated finder**, whose DDB data is written by **DynamoDbWriter → ItemCodec**, and whose reads traverse **bound portal → DynamoDbPersister → QueryPlanner → QueryPlanExecutor → DynamoDB Local**. Execute the identical finder and shape through the saved H2 reader. Compare complete returned values and multiplicities; compare sequences when ordering is requested. H2 is the query oracle. Neither `QueryPlanInterpreter`, `Operation.matches`, nor an expected set computed from the planner may supply the reference answer.

This is a prescription, not an implemented/passing suite. Source inspected on 2026-09-15; concurrent integration means findings marked closed in a log are not proof of the current source. R-03 through R-08 fixes are substantially present. The point is to prove application behaviour and keep it proved. The generated edge-point materialisation and original-operation cache-key issues described below are concrete integration blockers, not reasons to relax tests.

Use one new non-dated fixture for the type matrix; reuse `DiffBalance`, `DiffAudit`, and `DiffEntry` for temporal and relationship cases. One Local process and one table **per object**, shared across cases. Do not change table-per-object topology to make the test harness smaller.

## 1. What was verified

Read the inspection's six query findings and closing green-test critique, the support contract, conformance finding 21 and its closure, the four core contracts (`TemporalEncoder`, `TemporalMapping`, `AttributeMapping`, `EntityMapping`), planner, executor, fan-out collapse, item/value/IEEE codecs, writer/key/GSI configuration, persister, and differential support and tests. The decided designs 01–04 remain the architecture; actual contract classes take precedence over proposed class names in design sketches.

### Existing fixtures and tests

All fixtures are in `io.reladynamo.ddb.differential.domain`, XML under `reladynamo-ddb/src/test/resources/reladomo/models`, registered by `ReladomoClassList.xml` and `DiffMithraRuntime.xml` (partial caches), with H2 DDL in `diff-schema.sql`.

| Existing class | Actual metadata | Reuse / limitation |
|---|---|---|
| DiffBalance | int balanceId PK; double quantity, String label non-null; nullable String note with inPlaceUpdate; business and processing axes | Temporal rows and bound mutations; entries one-to-many relationship already generated |
| DiffAudit | int auditId PK; same payload; processing axis only, AuditOnlyTemporalDirector | Audit boundary cases |
| DiffEntry | int entryId PK; int balanceId FK, double amount, String label, all non-null; both axes | Cold deep fetch via explicit ALL GSI |
| DiffPosition | int positionId PK; quantity, label, nullable note; business-only director | Exists, but business-only is not enumerated in the current support contract's three flavours; see exclusions |
| DiffNumeric | int id PK; non-null float rate, double quantity, BigDecimal amount (18,4); no dates | Cannot hold the precision witness or nullable nine-type matrix |

`NumericPredicateFinderTest` does use generated operations and codec rows, but asserts hand-listed IDs and directly invokes adapter count; it does not execute the H2 finder. `NullPredicateDynamoDbTest` includes real service evaluation but uses planned/executor calls and scan fixtures. `GetItemFilterFinderTest` has useful point-filter regressions, still without paired H2 execution. Consolidate shared plumbing without deleting their distinct coverage before this matrix replaces it.

`DifferentialSupport.extract` casts every result to `MithraDatedTransactionalObject`: it cannot extract the new non-dated fixture. Prescribe a common `MithraObject.zGetCurrentData()` extraction path using `MithraDataAccessor`, with correct primitive-null handling. `Store.assertAgrees` copies an H2 result into DDB before comparing it: **do not use it as the matrix runner**. `BoundWritePathTest` now has a full-boundary comparison, but retains methods accepting broad named failures. Those are not the outcome policy below.

### API verification evidence

The requested Windows invocation was attempted exactly and failed with `WSL ... UtilBindVsockAnyPort ... socket failed 1`. A Linux JDK 11 was available and successfully ran `javap` against the **same** installed Reladomo 18.1.0 jar and the existing generated test classes. Full signatures: [JAVAP-API.txt](JAVAP-API.txt). Cache/count/wildcard/edge bytecode: [JAVAP-BYTECODE.txt](JAVAP-BYTECODE.txt).

Reproducible substitute used here:

- executable: `/tmp/claude-1000/-mnt-c-Users-drom-IdeaProjects-reladynamo/68d9bcd8-2762-49bc-91b9-a5fbd4934a97/scratchpad/jdk11/jdk-11.0.32.1+1/bin/javap`
- jar: `/mnt/c/Users/drom/.m2/repository/com/goldmansachs/reladomo/reladomo/18.1.0/reladomo-18.1.0.jar`
- generated-class classpath addition: `<repo>/reladynamo-ddb/target/test-classes`
- `-c -p` additionally used for the bytecode evidence.

| Verified declaring type | API used in this prescription |
|---|---|
| MithraList | `setBypassCache(boolean)`, `forceResolve()` |
| DomainList (MithraList parent) | `count()`, `setOrderBy(OrderBy)`, `setMaxObjectsToRetrieve(int)`, `deepFetch(Navigation)` |
| RelatedFinder and existing generated DiffBalanceFinder | `findOne(Operation)`, `findOneBypassCache(Operation)`, `findMany(Operation)`, finder/portal accessors |
| MithraAbstractObjectPortal | `getMithraObjectReader()`, `setMithraObjectReader(MithraObjectReader)`, `getCache()`, `clearQueryCache()` |
| Cache | `clear()` |
| Attribute | `isNull()`, `isNotNull()`, ascending/descending ordering |
| Integer/Long/Double/FloatAttribute | typed eq/notEq/greaterThan/lessThan; `in` and `notIn` take respective Eclipse Collections primitive sets |
| BooleanAttribute | boolean eq/notEq; BooleanSet in/notIn; no ordered comparisons |
| BigDecimalAttribute, NonPrimitiveAttribute | BigDecimal eq/notEq/ranges; inherited `in(Set<Type>)` (use Set<BigDecimal>, not DoubleSet) |
| StringAttribute | string eq/notEq/ranges, startsWith/endsWith/contains and their `not...` counterparts; `wildCardEq(String)`; inherited Set<String> in |
| TimestampAttribute | Timestamp eq/notEq/ranges; inherited Set<Timestamp> in |
| ByteArrayAttribute | eq/notEq(byte[]), `in(Set<byte[]>)`, ordering; no greaterThan/lessThan |
| Operation | `and(Operation)`, `or(Operation)`, nullable `Boolean matches(Object)`; **no general not()** |
| OrderBy | `and(OrderBy)` |
| AsOfAttribute | `eq(Timestamp)`, `equalsEdgePoint()`, `equalsInfinity()`, `getInfinityDate()` |
| MithraObject | `zGetCurrentData()` |
| MithraManagerProvider / MithraManager / MithraTransaction | manager access, `executeTransactionalCommand`, `setProcessingStartTime(long)` |
| DiffBalance / generated abstract and inherited dated interfaces | Timestamp constructor, setBalanceId/setQuantity/setLabel, insert, terminate, getEntries |

There is **no `StringAttribute.like` API** in this jar. The LIKE axis below uses `wildCardEq`: `*` means any-length, `?` one character, apostrophe escapes wildcard/apostrophe. This is verified against StringAttribute bytecode and the matching Reladomo sources. Passing SQL `%`/`_` as wildcard syntax would test a different operation. Simple patterns optimise to startsWith/endsWith/contains; `a?mid*z` exercises general wildcard evaluation. No fabricated `Operation.not()` or `AsOfAttribute.greaterThan()` is prescribed.

Before executing the new fixture tests, regenerate and run javap on `DiffFinderValueFinder`, `DiffFinderValueAbstract`, and list/data types. They do not exist today, so their generated signatures cannot honestly be claimed verified now. The field/type specification below determines them; report any generator incompatibility instead of substituting an untyped operation. The inherited APIs above have been verified.

## 2. Cold-cache execution protocol (mandatory)

### Same operation, independent backend runs

1. Capture each original reader once with `portal.getMithraObjectReader()`. Keep separate real adapters/executors/codecs per object and separate test recording wrappers. Bind **all** involved portals together. Execute these tests serially, outside transactions except the two explicit mutation scripts. No unrelated test may concurrently use these static portals.
2. Seed H2 from the typed fixture manifest with prepared JDBC inserts; seed DDB independently from the **same manifest** using the production writer and codec. Do not seed DDB from the H2 query under test. Supply every mapped value, including all physical temporal boundaries. Read back fixture storage once before tests to check cardinality, types, and bytes. Also require H2 finder snapshots to contain exactly the mapped attribute-name set and equal the manifest rows, including primitive null flags: sharing MithraDataAccessor must not let the same omitted field disappear from both answers. Setup requests are outside measured intervals.
3. Before **each backend and each invocation**, clear each involved portal's object cache with `getCache().clear()` **and** query cache with `clearQueryCache()`. This is safe only with no active transaction and no retained domain references. Discard old lists, objects, relationship lists, operations with retained state, and deep-fetch trees. Store the H2 answer as immutable value snapshots, not Reladomo objects. Build the operation afresh from one shared recipe for each backend.
4. For findMany, create the generated list, call `setBypassCache(true)` before any size/get/iteration/count, set ordering/maximum/deep-fetch before resolution, then resolve. For ordinary findOne cases, clear both caches and call the generated `findOne(op)`; a separate bypass regression calls generated `findOneBypassCache(op)`. A list's bypass flag is not a findOne option.
5. For **count**, create a new unresolved generated list and immediately call `count()`. Never call size/forceResolve/get first and never replace it with adapter.count or collection.size. Bytecode proves `AbstractOperationBasedList.count` can return cached resolved size; only its unresolved branch calls portal.count, which calls the bound reader's count. Bypass alone does not invalidate an already resolved size.
6. Snapshot every returned mapped attribute before changing bindings. In finally, restore **every saved reader**, clear both cache layers again, and discard all references. H2 and DDB use identical keys, so no ID remapping or special equality is needed.

Bytecode establishes that `bypassCache=true` on a non-pure portal skips local query-cache lookup and reaches `findFromServer`, except a `zIsNone()` short circuit. It also establishes that disabling the portal cache alone has relationship/transaction qualifications. Therefore do not rely on `setDisableCache`, a fresh list, or a parent bypass flag to make a relationship cold. Both parent and child caches must be empty and all object references fresh. A fresh JVM per invocation is unnecessary; a dedicated suite fork plus the clearing protocol is sufficient, **subject to the measured request assertions**.

### Proof of adapter execution

Install a recording decorator around the **actual bound persister interfaces**, forwarding every call, recording `find`/`count` and object type. It must also implement/forward dated and transactional interfaces used by binding; a reader-only proxy would break writes. Alternatively instrument the same calls with a test spy without replacing implementation. Retain the real QueryPlanExecutor and observe its per-invocation `lastExplain` only after success; never use a stale explain after an exception.

Use a counting AWS SDK v2 client that forwards requests to Local. Record method, table, index, key bindings, pagination tokens, and response count; split Query/GetItem/ExecuteStatement/BatchGetItem/Scan into separate counters. Count each real request once, not both builder overload and request overload. Counters increment **on entry**, so errors cannot erase attempted reads. Include PartiQL: counting only Query/GetItem would misclassify collapsed fan-out as a cache hit.

For every MATCH invocation in this matrix:

- The bound reader's relevant find or count entry count increases; the real executor is observed (successful explain correlates to that invocation).
- DDB read request delta is **strictly positive**, even for an expected empty result. None of the empty-result cases uses a contradiction or empty IN that Reladomo may simplify away.
- Scan delta is exactly zero. For ExecuteStatement, record that the statement binds a complete base/GSI partition-key equality or finite IN; no Scan call alone does not prove a PartiQL statement is bounded.
- Assert the request family explicitly named in a case. This is a coverage precondition, not an alternative oracle. A test taking a different route has not covered the specified branch.
- H2 run records reader entry and an actual JDBC read as well, so a warmed DDB object cannot become the H2 oracle. During DDB measurement, forbid SQL reads for these fixture tables. Connection-manager initialisation is outside measurement; any fallback SELECT is a failure.

For REFUSE before execution: bound reader entry delta >=1, **all DDB data-read deltas zero**, exact code/type checked. This proves the finder reached the adapter even when correct refusal precedes a service call. Do not demand a service request for a planner refusal.

For deep-fetch graph comparison record parent and child request counts separately across the whole resolve/navigation interval. Before starting, both portals are cold. The one-query child path must have a positive child read count; `reads < 8` alone permits zero and is not sufficient.

### GSI readiness, not assertion retries

After codec seeding, outside measured intervals, poll the raw ALL GSI using a **known expected manifest set**, bounded to 5 seconds, until every seeded physical identity and value is visible. Fail setup if it never converges. Clear caches and reset counters afterwards. Do not retry the finder until its answer happens to equal H2. Request counts exclude this readiness barrier. Base-table reads remain strongly consistent; GSI requests must set consistentRead=false.

## 3. Outcome policy: a refusal is a narrow contract assertion

Each parameter expansion declares immutable metadata: case ID, fixture version, operation recipe, shape/order/max, planner configuration, expected route, expected H2 cardinality/identity witnesses, comparison mode, and **exactly one** outcome:

- **MATCH_H2**: H2 must complete, DDB must complete, all result assertions apply. Any PLAN code, RESIDUAL code, unsupported SPI method, AWS validation exception, timeout, cast failure, or absent request is a failure.
- **REFUSE_CONTRACT**: H2 must complete and satisfy the fixture witnesses; DDB must throw the predeclared exception type with one exact anchored error code, at the predeclared phase. DDB returning rows is also a failure: it changed the declared support boundary. The declaration contains the support-contract section and a quotation identifying the unsupported input class, plus the concrete mapping/config reason it applies.

**No `MATCH_OR_REFUSE`, no catch-all PLAN prefix, no containsAnyOf(method names), no dynamic allowlist inferred from the exception actually thrown.** Unwrap only transparent invocation/framework wrappers; retain the entire cause chain. Match the typed adapter exception and anchored `RELADYNAMO-PLAN-001:` diagnostic, not arbitrary text deep inside a different error. Audit the declared refusal registry in review; changing a MATCH to REFUSE is a contract change, not a test repair.

The current `docs/SUPPORT-CONTRACT.md` explicitly supports refusing access without a partition key (Tier 1, “Query needs a partition key”; eligibility requires complete logical key or configured ALL FK GSI). `ReladynamoScanRequiredException.plan001` supplies the source-verified code. Accordingly, **only PLAN-001 for the three listed missing-access-path cases is accepted here**.

The support contract is not an exhaustive list of PLAN codes and is stale about several integrated fixes. Design 04 and source contain PLAN-002/006/007/009/012 and RESIDUAL-001; their existence is **not** enough to add them to this suite's success allowlist. If overflow/sort-disabled/mapped-residual refusal conformance is added later, first obtain an explicit support-contract entry with input scope and phase. Do not use those codes to absorb failures in a complete-key endsWith, numeric, history, count, or ordering case. Tier 2 “not implemented” prose also does not authorise broad success-or-refusal tests for finding 21.

A known implementation blocker is still MATCH_H2 with a failing required test. Track the blocker separately. No `@Disabled`, assumption abort, `xfail`, or “expected failure” contributes a passing case or closes a finding. H2 setup failure is a harness failure, never a successful adapter refusal.

## 4. Complete new fixture definition

Create `DiffFinderValue.xml` alongside the existing models, register it in both model class list and partial-cache runtime, generate via the existing Maven Reladomo generation step, and add the standard handwritten extension/list/database classes following DiffNumeric. No custom operation classes or manual generated-code edits.

- Object type: transactional; package `io.reladynamo.ddb.differential.domain`; class `DiffFinderValue`; default table `DIFF_FINDER_VALUE`.
- Temporal flavour: NONE. No AsOfAttribute, SourceAttribute, identity, inheritance, sequence, version, or in-place flags.
- Declare primary keys in **scopeId then rowId** order. scopeId is an ordinary domain key, **not** Reladomo source routing.
- All attributes below are direct Attribute children. maxLength applies to string fields; binary DDL accommodates the stress payload without adding a new attribute.

| Java name | XML javaType | column/item name | Nullable | Other metadata / H2 type |
|---|---|---|---|---|
| scopeId | int | SCOPE_ID | false | primaryKey=true; INTEGER |
| rowId | int | ROW_ID | false | primaryKey=true; INTEGER |
| bucketId | int | BUCKET_ID | false | INTEGER; configured GSI partition attribute |
| intValue | int | INT_VALUE | true | INTEGER |
| longValue | long | LONG_VALUE | true | BIGINT |
| doubleValue | double | DOUBLE_VALUE | true | DOUBLE |
| floatValue | float | FLOAT_VALUE | true | REAL |
| decimalValue | BigDecimal | DECIMAL_VALUE | true | precision=30, scale=4; DECIMAL(30,4) |
| textValue | String | TEXT_VALUE | true | maxLength=128; VARCHAR(128) |
| booleanValue | boolean | BOOLEAN_VALUE | true | BOOLEAN |
| timestampValue | Timestamp | TIMESTAMP_VALUE | true | TIMESTAMP(3); database timezone UTC |
| bytesValue | byte[] | BYTES_VALUE | true | VARBINARY(65536) |

H2 primary key `(SCOPE_ID, ROW_ID)`. Use exactly these column names and type/nullability declarations, no generated defaults. DDB HASH `pk` S and RANGE `sk` S. Production DefaultKeyStrategy yields `v1#DIFFFINDERVALUE#<scopeId>#<rowId>` and `v1#ND`. Derive keys with that strategy, never duplicate its encoder. Configure **one** `GsiSpec.foreignKey("gsi_bucketId", "bucketId")`: ALL projection, physical HASH `gsi_bucketId` S, RANGE `sk` S. Provide the same GSI list to the writer for stamping and the PhysicalDesign for planning, and create through TableCreator. Do not assume XML relationship/index derivation supplies it.

Wire forms verified in actual AttributeValueCodec/ItemCodec:

| Attribute family | Non-null wire | Consequence |
|---|---|---|
| int/long | N with integral decimal text | native numeric filters possible |
| double | B, 8 bytes, raw IEEE bits big-endian | N equality is wrong; B lexical ordering is not numeric ordering |
| float | B, 4 bytes, raw IEEE bits big-endian | test independently of double dispatch |
| BigDecimal | S, `toPlainString()` | numeric equality may ignore parameter scale; lexical range order is wrong |
| String | S, including empty S | empty != null |
| boolean | BOOL | false != null; no numeric boolean encoding |
| Timestamp (including temporal boundaries) | S via TemporalEncoder, 17 UTC digits | exact millisecond equality and order; use existing encoder |
| byte[] | B, exact supplied bytes | content equality, not Java array identity |
| every nullable field | explicit NULL=true | attribute_exists is true; primitive zero/false cannot replace NULL |

Fixture verification checks those forms on codec-written items. It does not hand-build AttributeValues for the query fixture. `_rd_v=1` is produced by the codec. The sole schema-evolution variant below deliberately removes one field **after** a codec write and is labelled as such.

## 5. Exact fixture manifests and notation

All dates are `Timestamp` constructed from the stated UTC instant with millisecond precision. `I` is the generated axis's `getInfinityDate()` value, not a guessed calendar date. Verify both DiffBalance axes agree with their XML sentinel, and configure PhysicalDesign with `.infinityFrom(finder)` as existing tests do. Temporal interval notation is `[from,to)` because existing axes set toIsInclusive=false.

In tables, `V` means **DiffFinderValueFinder**, `B` DiffBalanceFinder, `A` DiffAuditFinder, `E` DiffEntryFinder. These are notation aliases, not helper APIs. `K(s,r)` expands exactly to `V.scopeId().eq(s).and(V.rowId().eq(r))`; `Q(b)` to `V.bucketId().eq(b)`; `C(b,p)` to `B.businessDate().eq(b).and(B.processingDate().eq(p))`; `H` to `B.businessDate().equalsEdgePoint().and(B.processingDate().equalsEdgePoint())`. Expand these literally in operation factories.

`S_t{...}` denotes a set containing exactly the listed typed values: IntHashSet/LongHashSet/DoubleHashSet/FloatHashSet/BooleanHashSet for primitive attributes; HashSet<String/BigDecimal/Timestamp> otherwise. `S_bytes` is Set<byte[]> of independently allocated arrays with the listed contents, no duplicate content entries. Populate with constructors/add; do not invent `in(List)` overloads. `D("...")` means new BigDecimal of that string, `T(ms)` a Timestamp at epoch millisecond ms, `hex(...)` a fresh byte[] with those bytes. These are fixture-building notation only.

### V: seven rows, scopeId=1, bucketId=1

Every cell is specified; payload null in row 5 means all nine nullable fields are null. Long values deliberately exceed exact double integer precision. Decimal stored scale is four; query parameters need not have scale four.

| rowId | intValue | longValue | doubleValue | floatValue | decimalValue | textValue | booleanValue | timestampValue | bytesValue hex |
|---|---:|---:|---:|---:|---|---|---|---|---|
| 1 | -10 | -9007199254740993 | -10.5 | -10.5f | -10.5000 | alpha | false | T(-1001) | 00 FF |
| 2 | 0 | 0 | +0.0 | +0.0f | 0.0000 | empty string | false | T(-1) | empty byte[] |
| 3 | 2 | 9007199254740992 | 2.5 | 2.5f | 2.5000 | beta | true | T(0) | 01 02 |
| 4 | 10 | 9007199254740993 | 10.5 | 10.5f | 10.5000 | alphabet | true | T(1) | 80 |
| 5 | null | null | null | null | null | null | null | null | null |
| 6 | 2 | 9007199254740992 | 2.5 | 2.5f | 2.5000 | zbeta | true | T(0) | 01 02 (fresh array) |
| 7 | 100 | 9007199254740994 | 100.5 | 100.5f | 100.5000 | aXmidYz | false | T(1001) | FF |

### Additional exact bundles

“Copy Vn” below copies all nine payloads of V row n, then applies the stated replacements. Always supply new independent arrays/Timestamps. Keys and bucket are supplied explicitly; no unspecified attributes.

| Bundle | Exact rows |
|---|---|
| X, cross-scope | one row `(scopeId=2,rowId=3,bucketId=2)`, copy V3 except textValue="other-scope", intValue=99 |
| L, IN boundary | six rows `(3,r,3)`, r=1..6, copy V4, replacing intValue with respectively -1,0,99,100,101,102; doubleValue with the same number as double; textValue with respectively "-1","0","99","100","101","102" |
| S, string patterns | nine rows `(4,r,4)`, r=1..9, copy V4, replacing textValue with respectively "aXmidYz", "a_mid_z", "amidYz", "aXmidYzx", "a*", "a%b", "a_b", "a?b", null |
| O, decimal ordering | three rows `(5,r,5)`, r=1..3, copy V4, replacing decimalValue respectively with 9007199254740993.0000, 9007199254740992.0000, 9007199254740994.0000 |
| F, branch values | four rows `(6,r,6)`, r=1..4, copy V4; (textValue,intValue) respectively ("A",1),("B",2),("A",3),("B",4) |
| M, missing nullable field | four rows `(7,r,7)`, r=1..4, copy V4, textValue respectively null,null,"","hello"; only on DDB row 2 remove TEXT_VALUE after writer insert; H2 rows 1 and 2 both SQL NULL |
| P, pagination | 24 rows `(8,r,8)`, r=1..24, copy V4, replacing intValue=r, decimalValue=D(r+".0000"), textValue=(r<=20 ? "miss" : "hit"), bytesValue=65536 bytes each equal to 0x5A. Reuse one immutable byte pattern in manifest construction, copy when snapshotting |
| Z, non-finite extended | three rows `(9,r,9)`, r=1..3, copy V4; doubleValue/floatValue respectively negative infinity, positive infinity, canonical NaN (`Double.NaN`/`Float.NaN`) |

### B: four DiffBalance rectangles for balanceId=201

Let B0=2026-01-01T00:00:00.000Z, B1=2026-06-01T00:00:00.000Z, B2=2026-09-01T00:00:00.000Z. P0=2026-03-01T09:00:00.000Z, P1=2026-03-02T09:00:00.000Z. `+1`/`-1` means one millisecond, not one day. Every row has note=null.

| Physical row | quantity | label | businessDateFrom/To | processingDateFrom/To |
|---|---:|---|---|---|
| t1 | 10.0 | old | B0,B2 | P0,P1 |
| t2 | 10.0 | prefix | B0,B1 | P1,I |
| t3 | 55.0 | corrected | B1,B2 | P1,I |
| t4 | 80.0 | future | B2,I | P0,I |

Bundle B' adds balanceId=202 with exactly the same four rows and payloads. Use B+B' only where specified. DDB sort keys are produced by DefaultKeyStrategy/TemporalEncoder, e.g. t3 is `v1#P#<encode(P1)>#B#<encode(B1)>`. H2 insert those four boundaries exactly. Do not create these historical seed rectangles via wall-clock insert calls.

### A: two audit-only rows for auditId=301

(quantity,label,note,processingDateFrom,processingDateTo): `(10.0,"old",null,P0,P1)` and `(55.0,"new","present",P1,I)`. There are no business attributes. This is the complete payload and temporal specification.

### G: relationship graph

DiffBalance parent IDs 401..408; each `(quantity=id-400 as double,label="parent-"+id,note=null,B0,I,P0,I)`. Each parent i has three DiffEntry children with entryId=`5000+3*(i-401)+j`, j=1..3, balanceId=i, amount=`j as double`, label=`"child-"+i+"-"+j`, boundaries B0,I,P0,I. Add decoy parent 409 with corresponding quantity=9.0 and label="parent-409", same dates and note; one decoy entry 5099, FK=409, amount=99.0,label="decoy",same dates. Add one historical version of child 5001 with amount=99.0,label="historical",B0,I,P0-1000,P0 (one second before P0). No other rows.

Configure DiffEntry with `GsiSpec.foreignKey("gsi_balanceId","balanceId")`, ALL, physical HASH `gsi_balanceId` and RANGE `sk`. Parent design has no GSI. Supply entryDesign.gsis() to its writer. Query graph at B1 and processing infinity. Expected graph is parents 401..408, exactly three current children per parent; exclude both decoys and the historical child. Compare each association, not just a flattened bag of 24 children.

## 6. Assertions and enumerated cases

### Common assertions, applicable to every row below

Unless a row says REFUSE, outcome is MATCH_H2. The fixture column selects the exact bundle above; sharing already seeded disjoint bundles is allowed because operations bound the scope. The H2 identity/cardinality witnesses below are **additional setup assertions**, never a substitute for H2-versus-DDB comparison.

For unordered results compare **multisets**, not sets: every mapped attribute and physical-version identity participates. Keep duplicates visible. For ordered results compare the **original returned sequence**, without sorting the DDB result in the assertion. Byte arrays compare by content; Timestamp compares exact epoch milliseconds and nanos; nullable primitives preserve their null flag; finite floats/doubles compare their exact represented values/bits; decimal returned scale and unscaled value compare exactly (seed scale is fixed four). Query *parameter* scale is not output scale. No tolerance, JSON stringification, double conversion of decimals, key-only projection, quantity-only projection, or dropped from/to column.

Explicitly assert no duplicate physical identity `(all logical PK attributes, all temporal from attributes)` where a finder should return distinct rows. Do not deduplicate in the harness before comparing. Count uses a fresh unresolved list, its own measured interval and cold reset, and exact H2 integer equality. Materialising an object may merge repeated rows in the cache, so count and full-history storage checks remain independently necessary.

### 6.1 Type × operator: 59 core invocations

Each row is a parameterised test method. `attr` is one of the **exact** generated accessors in the parameter table. Operation is `Q(1).and(attr.<operator>(...))`; shape is findMany, route is real Query on gsi_bucketId. This puts the filter on a service-evaluated Query; running everything as a point GetItem would reintroduce the translator/interpreter shared-oracle blind spot.

| Test method | Parameters | Exact predicate | Fixture / H2 witness | Finding / future defect |
|---|---|---|---|---|
| should_match_h2_for_eq_on_codec_values | all 9 types | attr.eq(p) | V; Eq IDs in next table | R-04; BOOL/Timestamp/binary type binding |
| should_match_h2_for_not_eq_on_codec_values | all 9 | attr.notEq(p) | V; all non-null IDs except Eq IDs | R-04/R-05; SQL UNKNOWN must not match |
| should_match_h2_for_greater_than_on_codec_values | int,long,double,float,decimal,String,Timestamp | attr.greaterThan(p) | V; GT IDs below | R-04; binary/decimal order and long precision |
| should_match_h2_for_less_than_on_codec_values | same 7 | attr.lessThan(p) | V; LT IDs below | R-04; negative/pre-epoch boundary |
| should_match_h2_for_small_in_on_codec_values | all 9 | attr.in(smallSet) | V; IN IDs below | R-04/R-07; typed IN extraction and byte content |
| should_match_h2_for_is_null_on_codec_values | all 9 | attr.isNull() | V; exactly row 5 | R-05; primitive null-population bugs |
| should_match_h2_for_is_not_null_on_codec_values | all 9 | attr.isNotNull() | V; exactly 1,2,3,4,6,7 | R-05; zero/false/empty are present values |

| type / attr | parameter p | smallSet | Eq IDs | GT IDs | LT IDs | IN IDs |
|---|---|---|---|---|---|---|
| int / V.intValue() | 2 | S_int{-10,2} | 3,6 | 4,7 | 1,2 | 1,3,6 |
| long / V.longValue() | 9007199254740992L | S_long{-9007199254740993L,9007199254740992L} | 3,6 | 4,7 | 1,2 | 1,3,6 |
| double / V.doubleValue() | 2.5d | S_double{-10.5d,2.5d} | 3,6 | 4,7 | 1,2 | 1,3,6 |
| float / V.floatValue() | 2.5f | S_float{-10.5f,2.5f} | 3,6 | 4,7 | 1,2 | 1,3,6 |
| BigDecimal / V.decimalValue() | D("2.5") | S_decimal{D("-10.5"),D("2.50")} | 3,6 | 4,7 | 1,2 | 1,3,6 |
| String / V.textValue() | "beta" | S_string{"alpha","beta"} | 3 | 6 | 1,2,4,7 | 1,3 |
| boolean / V.booleanValue() | true | S_boolean{false,true} | 3,4,6 | n/a | n/a | 1,2,3,4,6,7 |
| Timestamp / V.timestampValue() | T(0) | S_timestamp{T(-1001),T(0)} | 3,6 | 4,7 | 1,2 | 1,3,6 |
| byte[] / V.bytesValue() | hex(01 02), fresh allocation | S_bytes{hex(00 FF),hex(01 02)} | 3,6 | n/a | n/a | 1,3,6 |

### 6.2 String/residual, null and IN boundaries

All are findMany unless shape is specified. Query route means gsi_bucketId; G means GetItem. Each listed parameter is a separate invocation, not a loop stopping after the first failure.

| Test method | Fixture | Exact operation / parameters | Assertion beyond common equality | Finding |
|---|---|---|---|---|
| should_match_h2_for_string_functions | V | Q(1).and(V.textValue().startsWith("alpha")); endsWith("beta"); contains("pha") (substitute that leaf only) | Query; IDs respectively {1,4}, {3,6}, {1,4}; endsWith must exercise nonempty residual in observed plan | R-07; R-05 NULL exclusion |
| should_match_h2_for_negated_string_functions | V | Q(1).and(V.textValue().notStartsWith("alpha")); notEndsWith("beta"); notContains("pha") | Query; respective IDs {2,3,6,7}, {1,2,4,7}, {2,3,6,7}; exclude 5 | R-05/R-07; SQL three-valued negation |
| should_match_h2_for_general_like | S | Q(4).and(V.textValue().wildCardEq("a?mid*z")) | Query + generated residual; IDs 1,2; distinguish missing one-character slot and trailing suffix | R-07; incorrect internal wildcard translation |
| should_match_h2_for_like_specialisations | V | Q(1).and(V.textValue().wildCardEq(pattern)); patterns "alpha*", "*beta", "*pha*" | Query; IDs respectively {1,4}, {3,6}, {1,4}; suffix must not degrade into contains | R-07; LIKE specialisation correctness |
| should_preserve_literal_sql_wildcards | S | Q(4).and(V.textValue().wildCardEq(pattern)); patterns "a%b", "a_b" | IDs respectively 6 and 7; %/_ are literal in this API | R-07; SQL-vs-Reladomo pattern confusion |
| should_distinguish_explicit_null_missing_empty_and_text | M | Q(7).and(V.textValue().isNull()); Q(7).and(V.textValue().isNotNull()) | Query; respectively IDs 1,2 and 3,4; verify row 2 alone lacks TEXT_VALUE | R-05, schema-evolution null policy |
| should_apply_point_null_filters | V | K(1,5).and(attr.isNull()); K(1,5).and(attr.isNotNull()); attrs textValue,intValue,booleanValue | G; respectively one row and empty; all six query actual item | R-03/R-05; shared point-filter interpreter |
| should_apply_missing_field_point_null_filters | M | K(7,2).and(V.textValue().isNull()); K(7,2).and(V.textValue().isNotNull()) | G; one row / empty | R-03/R-05 |
| should_apply_filter_in_at_service_boundary | L | Q(3).and(V.intValue().in(S_int{0..n-1})); n=100,101 | Query; IDs {2,3} then {2,3,4}; n=101 is a payload IN under one known GSI key, not 101 partition keys; 100 has pushed filter, 101 nonempty residual | R-07; service's 100-element cap |
| should_apply_large_string_in_as_residual | L | Q(3).and(V.textValue().in(S_string{decimal strings of 0..100})) | Query + residual; IDs 2,3,4 | R-07; object-set residual extraction |
| should_apply_large_numeric_in_after_decode | L | Q(3).and(V.doubleValue().in(S_double{0.0..100.0 in steps of 1.0})) | Query + numeric residual; IDs 2,3,4 | R-04/R-07; typed large set and NULL semantics |
| should_match_h2_for_negative_zero_parameter | V | Q(1).and(V.doubleValue().eq(-0.0d)); Q(1).and(V.floatValue().eq(-0.0f)) | Query; row 2; positive-zero stored row, negative-zero parameter | R-04; bitwise equality used as numeric equality |

Escaped-literal wildcard optimisation is excluded from the core: local StringAttribute source turns a plain analysed pattern into eq with the original pattern, so an assumed match for `wildCardEq("a'*")` is not a verified H2 baseline. The S bundle retains literal-star/question-mark decoys to catch overmatching in the required general wildcard case. Expanding escape semantics requires a separate H2 baseline investigation; do not silently substitute a handwritten matcher.

### 6.3 Point reads, boolean structure, fan-out, count, and ordering

For fan-out tests record actual children via the successful executed plan. Do not create QueryPlans by hand. Correctness does not require every semantically valid fan-out to collapse, but the dedicated compatible-collapse case must observe ExecuteStatement so that conversion is covered. In incompatible-collapse regressions, accept separate requests or a semantically complete combined statement; the stated H2 result is mandatory.

| Test method | Fixture | Exact operation / shape | Assertion / route | Finding |
|---|---|---|---|---|
| should_match_h2_for_cold_find_one | V+X | K(1,3); generated findOne | G; V3, every field; request proves plain findOne reached persister | Future cache/PK collision |
| should_match_h2_for_bypass_find_one | V | K(1,3); findOneBypassCache | G; V3; same value as H2 | Cache no-op protection |
| should_reject_payload_mismatch_on_point_read | V | K(1,3).and(V.textValue().eq("CLOSED")); shapes findOne, findMany, count | G in all three; null, empty, zero respectively | R-03 |
| should_accept_payload_match_on_point_read | V | K(1,3).and(V.textValue().eq("beta")); findOne | G; V3, prevents “always empty” repair | R-03 |
| should_match_no_row_for_absent_complete_key | V | K(1,999); findOne and count | G; null / zero, positive request | Future materialisation of missing GetItem |
| should_keep_both_composite_key_components | V+X | K(1,3).or(K(2,3)); findMany | both complete payloads; count=2 in separate fresh count invocation | Future PK encoder/planner mismatch; M-02 analogue |
| should_match_h2_for_and_with_residual | V | Q(1).and(V.intValue().greaterThan(0)).and(V.textValue().endsWith("beta")) | Query; IDs 3,6 | R-07; pushed predicate plus residual |
| should_match_h2_for_or_between_pushed_and_residual | V | Q(1).and(V.intValue().eq(0).or(V.textValue().endsWith("beta"))) | IDs 2,3,6; no accidental AND/branch loss | R-06/R-07 |
| should_match_h2_for_nested_boolean_structure | V | Q(1).and(V.intValue().eq(0).or(V.textValue().endsWith("beta").and(V.booleanValue().eq(true)))) | IDs 2,3,6 | R-06/R-07; nested grouping |
| should_match_h2_for_distributed_dnf | V | Q(1).and(V.intValue().eq(0).or(V.intValue().eq(2))).and(V.textValue().eq("").or(V.textValue().endsWith("beta"))) | IDs 2,3,6; four DNF combinations; retain observed fan-out evidence | R-06/R-08 |
| should_match_h2_for_demorgan_negation | V | Q(1).and(V.intValue().notEq(2)).and(V.textValue().notEndsWith("beta")) | IDs 1,2,4,7; negation of (int=2 OR suffix beta) using actual leaf APIs; SQL null excluded | R-05/R-07 |
| should_preserve_distinct_or_value_bindings | F | K(6,1).and(V.textValue().eq("A")).or(K(6,2).and(V.textValue().eq("B"))) | IDs 1,2; forbid losing second branch | R-06 |
| should_not_admit_crossed_or_value_bindings | F | K(6,1).and(V.textValue().eq("B")).or(K(6,2).and(V.textValue().eq("A"))) | empty but positive service reads; first-child filter reuse would admit row 2 | R-06 |
| should_preserve_or_attribute_bindings | F | K(6,1).and(V.intValue().eq(1)).or(K(6,2).and(V.textValue().eq("B"))) | IDs 1,2; record generated names, do not manufacture same-name aliases | R-06; differing attribute/name maps |
| should_preserve_or_binary_bindings | V | K(1,1).and(V.bytesValue().eq(hex(00 FF))).or(K(1,3).and(V.bytesValue().eq(hex(01 02)))) | IDs 1,3; independently allocated bound arrays | R-06; typed binding equality |
| should_preserve_distinct_or_residuals | V | K(1,3).and(V.textValue().endsWith("beta")).or(K(1,4).and(V.textValue().endsWith("bet"))) | IDs 3,4; both residuals needed | R-06/R-07 |
| should_execute_compatible_fanout_through_partiql | F | V.scopeId().eq(6).and(V.rowId().in(S_int{1,2,3,4})).and(V.intValue().greaterThan(0)) | ExecuteStatement>=1, no Scan; all four complete rows | R-06; collapse/PartiQL conversion positive control |
| should_deduplicate_overlapping_or_results | V | Q(1).and(V.intValue().greaterThan(0)).or(Q(1).and(V.textValue().endsWith("beta"))) | findMany unique IDs 3,4,6,7; separate count=4, not 6 | R-08 |
| should_preserve_null_logic_inside_or | V | Q(1).and(V.intValue().isNull().or(V.textValue().endsWith("beta"))) | IDs 3,5,6; separate count=3 | R-05/R-07/R-08 |
| should_count_residual_matches_through_finder | V | Q(1).and(V.doubleValue().greaterThan(2.5d)).and(V.textValue().endsWith("z")); count | reader.count entered, Query>=1; count=1 (row 7), not server candidate count | R-04/R-07 |
| should_count_null_matches_through_finder | V | Q(1).and(V.booleanValue().isNull()); count | Query>=1; count=1 | R-05 |
| should_order_across_partitions_before_top_n | V | V.scopeId().eq(1).and(V.rowId().in(S_int{1,2,3,4,6,7})); setOrderBy(V.intValue().descendingOrderBy().and(V.rowId().ascendingOrderBy())); setMaxObjectsToRetrieve(1) | exact returned sequence [7]; ExecuteStatement or complete per-key fan-out; later partition has maximum | R-08 |
| should_order_ascending_and_descending_with_ties | V | Q(1).and(V.intValue().isNotNull()); order intValue ascending/descending, then rowId ascending | sequences [1,2,3,6,4,7] / [7,4,3,6,2,1]; original returned sequence | R-08 |
| should_order_decimals_without_double_rounding | O | Q(5); order decimalValue ascending/descending then rowId ascending; max=1 | [2] / [3]; also unbounded ascending [2,1,3] | R-08; conformance 22 |
| should_order_long_values_without_double_rounding | V | Q(1).and(V.longValue().isNotNull()); order longValue descending then rowId descending; max=3 | [7,4,6], exact H2 sequence | R-08; long narrowing near 2^53 |
| should_order_binary_values_by_content | V | Q(1).and(V.bytesValue().isNotNull()); order bytesValue ascending/descending then rowId ascending | exact H2 sequence; all 6 rows, array identity text must not determine order | R-08; conformance 22 |
| should_apply_limit_after_deduplication | V | overlapping OR operation above; order intValue descending then rowId ascending; max=3 | [7,4,3], no duplicate consumes a slot | R-08 |

Do not use unordered max-N as a parity assertion: two correct stores may choose different rows without an ordering contract. All cross-row limit cases define a total order. The composite-key lookup and ordinary generated findOne case intentionally test the public shape, not a rewritten list-first equivalent.

### 6.4 Temporal matrix

Use B only unless otherwise stated. Finite-as-of cases should take base-table Query; exact from-equality cases must take GetItem. `B.balanceId().eq(201)` is abbreviated `BK` below. Each timestamp or shape parameter is a separate invocation.

| Test method | Fixture | Exact finder operation / shape | H2 witness and additional assertion | Finding |
|---|---|---|---|---|
| should_match_h2_at_both_asof_dates | B | BK.and(C(B1+1,P0)); BK.and(C(B1+1,P1)) | t1 / t3; findOne and fresh count=1 for each | R-03; temporal visibility / finding 21 |
| should_apply_default_processing_asof | B | BK.and(B.businessDate().eq(B1+1)) | t3; default processing infinity injected by Reladomo; original recipe deliberately omits processing predicate | Future analysed/original operation mismatch; conformance 26 |
| should_match_h2_at_business_boundary | B | BK.and(C(b,I)); b=B0-1,B0,B1-1,B1,B2-1,B2 | respectively empty,t2,t2,t3,t3,t4; findMany; each makes a request | R-03; inclusive/exclusive temporal off-by-one |
| should_match_h2_at_processing_boundary | B | BK.and(C(B1+1,p)); p=P0-1,P0,P1-1,P1 | empty,t1,t1,t3; findMany and fresh count on P1 | R-03; finding 21 superseded rectangle |
| should_match_h2_for_infinity_on_both_axes | B | BK.and(B.businessDate().equalsInfinity()).and(B.processingDate().equalsInfinity()) | t4, not thru>infinity empty result | R-03; sentinel handling |
| should_match_h2_for_processing_only_asof | A | A.auditId().eq(301).and(A.processingDate().eq(p)); p=P0-1,P0,P1-1,P1,I | empty,old,old,new,new; Query; no synthetic business axis | Future flavour dispatch / R-03 |
| should_match_h2_for_audit_infinity | A | A.auditId().eq(301).and(A.processingDate().equalsInfinity()) | new row, Query | Future audit sentinel handling |
| should_filter_payload_on_exact_rectangle | B | BK.and(B.businessDateFrom().eq(B1)).and(B.processingDateFrom().eq(P1)).and(C(B1+1,I)).and(B.label().eq(label)); label="corrected","old" | G; t3 / empty; findOne and count 1/0 | R-03 |
| should_filter_business_asof_on_exact_rectangle | B | BK.and(B.businessDateFrom().eq(B0)).and(B.processingDateFrom().eq(P0)).and(C(B2,P0)) | G fetches t1 but result empty and count=0 (business upper bound) | R-03 |
| should_filter_processing_asof_on_exact_rectangle | B | BK.and(B.businessDateFrom().eq(B0)).and(B.processingDateFrom().eq(P0)).and(C(B1,P1)) | G fetches t1 but result empty and count=0 (processing upper bound) | R-03; finding 21 |
| should_match_h2_for_all_edge_rectangles | B | BK.and(H) | Query; exactly t1,t2,t3,t4 including every payload and four bounds; count=4 on fresh list | R-08; finding 21; edge materialisation blocker below |
| should_preserve_versions_in_overlapping_history_union | B | BK.and(H).and(B.label().startsWith("p").or(B.quantity().eq(10.0d))) | Query/fan-out; t1,t2 once each; count=2; do not dedup merely by balanceId | R-04/R-07/R-08 |
| should_match_h2_for_audit_edge_history | A | A.auditId().eq(301).and(A.processingDate().equalsEdgePoint()) | both audit versions, count=2 | Future edge/flavour materialisation |
| should_match_h2_for_business_from_range | B | BK.and(H).and(B.processingDateFrom().eq(P1)).and(B.businessDateFrom().greaterThan(B0)).and(B.businessDateFrom().lessThan(B2)) | Query bounded by processing prefix/business range; exactly t3; strict range removes t2 at lower boundary | R-06; inclusive BETWEEN must retain strict residual/filter |
| should_preserve_distinct_temporal_branch_bindings | B+B' | B.balanceId().eq(201).and(B.processingDateFrom().eq(P0)).and(B.businessDateFrom().greaterThan(B1)).and(B.businessDateFrom().lessThan(I)).or(B.balanceId().eq(202).and(B.processingDateFrom().eq(P1)).and(B.businessDateFrom().greaterThan(B0)).and(B.businessDateFrom().lessThan(B2))).and(H) | t4 of 201 plus t3 of 202; no cross-branch prefix/range reuse; count=2 | R-06; same-shaped sort conditions with different bindings |

**Concrete current blocker:** `DynamoDbPersister.asOfDatesOf` accepts only AsOfEqOperation. javap verifies AsOfEdgePointOperation extends AtomicEqualityOperation, not AsOfEqOperation. Edge queries may reach the executor and then fail materialisation. Their required outcome remains MATCH_H2. Missing work: carry each row's correct edge as-of dates into materialisation and preserve every rectangle; do not invent one shared date or call the executor directly to bypass the broken finder. `equalsEdgePoint()` denotes row-specific edge semantics, not equality to a literal default date. The range cases intentionally use physical Timestamp attributes plus edges, because AsOfAttribute has no range API.

**Second current blocker:** the read source still constructs `CachedQuery(op.getAnalyzedOperation(), orderBy)` although conformance 26 describes a fix keyed by original operation. The default-as-of and pre-resolution deep-fetch cases must exercise list lifecycle through size/iteration; success from a direct `adapter.find` call does not close that issue. Verify the integrated source again before implementing.

### 6.5 Cold relationships and declared refusals

| Test method | Fixture | Exact finder / shape | Required outcome and assertion | Finding |
|---|---|---|---|---|
| should_deep_fetch_cold_graph_before_resolution | G | B.findMany(B.balanceId().in(S_int{401..408}).and(C(B1,I))); setBypassCache(true); deepFetch(B.entries()); then size(), iterate parents and getEntries() | MATCH_H2; exact parent→children graph, all mapped fields/bounds; positive parent reads and exactly **one** child ExecuteStatement on gsi_balanceId with default pageSize=100; no child getItem/scan; no SQL during DDB phase | R-06/R-07; cold cache; conformance 26 |
| should_deep_fetch_cold_children_after_parent_resolution | G | same parent operation; bypass, forceResolve(), then deepFetch(B.entries()), then getEntries() for every parent | MATCH_H2; same graph; child cache was cleared before parent resolution and not preloaded; exactly one measured child ExecuteStatement | Future lazy/deep-fetch lifecycle and N+1 regression |
| should_refuse_non_key_search_without_scan | V+X only | V.textValue().eq("beta"); findMany | REFUSE_CONTRACT: H2 gives V3; ReladynamoScanRequiredException, PLAN-001 before executor reads; support Tier 1 “Query needs a partition key”; no scope/row PK or bucket equality | No-scan contract regression |
| should_refuse_incomplete_composite_key | V+X | V.rowId().eq(3); findMany | REFUSE_CONTRACT: H2 gives V3 and X; same exception/code/phase; scopeId missing and no GSI constraint | Missing composite-key component must not become cross-scope read |
| should_refuse_fk_query_without_configured_gsi | G | E.balanceId().eq(401).and(E.businessDate().eq(B1)).and(E.processingDate().equalsInfinity()); findMany; bind an entry adapter whose PhysicalDesign has no GSIs | REFUSE_CONTRACT: H2 gives current 5001,5002,5003; same PLAN-001 before any read; support eligibility “foreign key with a configured ALL-projected GSI”; base PK entryId absent | R-10/relationship access-path refusal |

All three refusals run the generated H2 query successfully first. Their fixture witnesses are nonempty; an H2 exception cannot accidentally validate a refusal. Run the first two with **only V+X present** in DIFF_FINDER_VALUE: their deliberately unscoped predicates would otherwise include rowId=3 from other bundles. Schedule that fixture phase before seeding the remaining Value bundles, with an explicit suite lifecycle rather than relying on JUnit's default method order. The existing tables are reused. It is fine that the physical child table still has its index in the last case: the tested configuration intentionally declares no usable access path. Restore the original adapter afterwards.

The exact one-child-request regression is intentionally stricter than “fewer than children”. This small graph is below the current 50-key PartiQL chunk and 100-row request page thresholds and existing suite evidence claims one request. If service pagination actually requires more, record the service evidence and revise the **fixture or explicit request budget** in review; do not quietly turn it into an unbounded positive-read assertion. The result comparison never changes.

### 6.6 Bound write scripts whose finder results expose conformance 21

These are **required core tests**, not snapshot-replay tests. Empty start per backend, same logical ID and exact script. Execute the H2 script with JDBC readers, snapshot it, clear caches, then execute independently with DDB-bound readers. Seed through the generated object insert, which reaches the production codec on DDB; never copy H2 post-write rows to DDB. Separate committed transactions use `tx.setProcessingStartTime(P0.getTime())` and then P1, both before constructing/finding/changing objects. Each finder in a new transaction starts from cold caches. If this calls an unimplemented refresh/enrollment SPI, report that exact missing step and leave the test failing; it is not a PLAN-001 support refusal.

| Test method | Exact setup and mutation | Exact finder assertions | Full-history assertion / finding |
|---|---|---|---|
| should_close_superseded_processing_rectangle_on_bound_update | ID=701. At P0: new DiffBalance(B0), setBalanceId(701), setQuantity(10.0d), setLabel("write"), setNote(null), insert(). Commit. At P1: generated findOne(B.balanceId().eq(701).and(C(B1,I))), assert non-null, setQuantity(55.0d), commit | Fresh findMany for id701 and C(B1+1,P1-1) gives qty10; C(B1+1,P1) and C(B1+1,I) give exactly one qty55; C(B1-1,I) gives exactly one qty10. Fresh counts are all 1; actual DDB read deltas positive | Exactly three rows, all label="write",note=null: qty10 B[B0,I) P[P0,P1); qty10 B[B0,B1) P[P1,I); qty55 B[B1,I) P[P1,I). Full H2 equality, including the closed old OUT_Z=P1. R-02/R-03/R-08; conformance 21 |
| should_close_superseded_processing_rectangle_on_bound_terminate | ID=702. Same insert at P0, qty10,label="write",note=null. At P1: findOne(id702.and(C(B1,I))), assert non-null, terminate(), commit | id702.and(C(B1+1,P1-1)) gives one qty10; id702.and(C(B1-1,I)) gives one qty10; id702.and(C(B1,I)) and C(B1+1,I) give empty and count0, with positive requests | Exactly two rows: qty10 B[B0,I) P[P0,P1); qty10 B[B0,B1) P[P1,I), all other payload unchanged. R-02/R-03; conformance 21 |

For both scripts additionally execute generated id equality AND H on **both** backends and compare full histories, with counts 3/2. Independently compare a direct H2 physical-row dump to a paginated, strongly consistent base-table DDB partition read decoded with ItemCodec. This supplemental storage observation detects an open superseded rectangle even if Reladomo object identity hides a duplicate. It is not a replacement for generated-finder assertions, and never writes data. Query all four bounds and all payloads on H2, not only quantity; consume all LastEvaluatedKey pages on DDB. Do not normalise processing timestamps: they are pinned and must match exactly.

Assert bound insert/update/delete or transaction-write request deltas as appropriate in addition to finder reads; the mutation must actually occur. No `if (found != null)` guard, no “at least one row”, no “some version has a finite out”, no selecting the first of duplicate current versions. If the baseline R-02 bug is restored, the direct histories must fail on the old row's P1 boundary and the post-update finder/count must expose the extra current version. Failure of one test must not prevent the independently registered terminate case from running.

### 6.7 Extended cases and real pagination

The IN 100/101 boundary and finding-21 scripts stay in core. The following `finder-matrix-slow` cases cover larger request/page boundaries. They use the **same harness and assertions**, never a hand-built QueryPlan.

| Test method | Fixture | Exact operation | Assertion | Finding |
|---|---|---|---|---|
| should_preserve_matches_after_filtered_empty_pages | Audit pagination bundle specified below | A.auditId().eq(302).and(A.processingDate().equalsEdgePoint()).and(A.label().eq("hit")); order A.processingDateFrom().ascendingOrderBy(); max=2; PlannerConfig pageSize=2,maxPages=64 | quantities [21.0,22.0]; record Query pagination and LastEvaluatedKey; require early filtered-empty pages, then the two matches; separate fresh count=4 | R-08/R-09; Limit counts examined rows |
| should_order_before_limit_across_real_megabyte_pages | P | Q(8); order decimalValue descending then rowId ascending; max=1; default pageSize=100,maxPages=64 | exact [24]; assert >=2 real service pages for ~1.5 MiB ALL-projected payload; top value cannot disappear after first page | R-08/R-09 |
| should_preserve_results_across_partiql_chunks | 51 rows `(scopeId=10,rowId=r,bucketId=10)`, r=1..51, copy V4 except intValue=r | V.scopeId().eq(10).and(V.rowId().in(S_int{1..51})).and(V.intValue().greaterThan(0)); order intValue ascending | exact 51 complete rows; default pkFanOutLimit=100, at least two ExecuteStatement calls; last key51 present and count51 via a fresh count | R-06/R-08; 50-key chunk boundary |
| should_match_h2_for_nonfinite_numeric_predicates | Z | Q(9).and(attr.eq(p)); Q(9).and(attr.notEq(p)); Q(9).and(attr.greaterThan(p)); attr doubleValue/floatValue, p=typed NaN or positive infinity | exact H2 multiset, no handwritten IEEE predicate oracle; verify JDBC seeding preserved canonical NaN/infinities first; any H2 rejection is a reported fixture prerequisite, not a skip | R-04; TypedNumericResidual currently assumes NaN equals nothing, which is not evidence of H2 SQL semantics |

The audit pagination bundle is exactly 24 DiffAudit versions for auditId=302 with consecutive processing intervals `[P0+r*1000,P0+(r+1)*1000)` for r=0..22 and `[P0+23000,I)` for r=23; quantity=r+1 as double, label="miss" for r<20 else "hit", note=null. The first slow case uses base sk ordering to guarantee empty filtered pages before versions 21,22. This also covers native ascending temporal ordering. It needs the required edge materialisation fix. A non-dated GSI has equal sk values and does **not** define rowId order, so it cannot supply that deterministic empty-page witness. The P bundle serves only the separate megabyte case. No attributes on DiffAudit are invented to hold padding.

Do not claim special float bit-pattern fidelity from H2: NaN payload preservation and stored negative-zero fidelity belong to the codec's raw-bit suite. The matrix's canonical non-finite cases concern **SQL predicate semantics**, use H2 as the oracle, and must report an H2 baseline limitation explicitly if one occurs. Ordinary signed-zero parameter and negative finite values remain in core.

## 7. Axis coverage and exclusions (why this is not Cartesian)

| Axis | Load-bearing combinations | Exclusion and justification |
|---|---|---|
| Operator × type | eq,notEq,small IN,null pair on all nine; ranges on seven ordered types; >100 payload IN on integral, object String, and IEEE residual families | boolean and byte[] greater/less are not exposed by their verified finder APIs. starts/ends/contains/wildCardEq belong to String; inventing numeric versions adds no application behaviour |
| Wire × execution | all types through real Query; point-filter String and explicit/missing/primitive NULL; point numeric materialisation also reached by fan-out and first findOne; binary bindings in fan-out | Do not run all 59 operators under every route: wire dispatch is shared, while the separate Query/GetItem/PartiQL and null cases cover the distinct paths. A full repeat would multiply runtime without a new mechanism |
| Boolean structure | single payload leaves under one key, AND pushed+residual, OR mixing the two, nested AND/OR, two-by-two DNF, De Morgan leaf negation, overlapping union | No fabricated general NOT node exists. Do not form every boolean tree for every type: int/String/boolean leaves distinguish pushdown, residual, null, and grouping; numeric type dispatch already has its own matrix |
| IN size | tiny sets; 100 and101 filter values; 51 partition values | Filter cap and partition fan-out cap are different. >100 PK default would test PLAN-002, not large filter residual. Guard refusal codes need an explicit support-contract entry before becoming accepted outcomes |
| Temporal | B/P separately and together, defaults, both infinities, edges, physical range, exact GetItem rejection on each axis, audit-only, independent bound writes | Repeating every payload type in both dated flavours is redundant with the non-dated type matrix and dated double/String residual/history cases. All temporal boundaries use the same verified Timestamp wire path |
| Business-only | actual DiffPosition metadata inventoried | User's required business-date axis is covered by DiffBalance. Current support contract names bi/audit/non-dated; business-only enum/fixture existence alone is not a promise. Add a declared contract row before claiming that fourth flavour supported |
| Relationship | existing one-to-many configured single-FK ALL GSI, two cold resolution orders, decoy and historical child, no-GSI refusal | Sparse-current/composite GSI and KEYS_ONLY/INCLUDE are outside the contract's proven surface; mapped existential residual is not the same as deepFetch. Do not force these through Scan or accept RESIDUAL-001 as generic success. Separate contract-expansion work is needed |
| Shape | ordinary/bypass findOne, findMany, true unresolved count, ascending/descending, total-order top-N, compound ties, duplicates, decimal/long/binary comparisons | Do not multiply every leaf by every shape: point rejection gets all three, count covers null/numeric residual/union/temporal closure, ordering covers the independent comparator types. Cursor/aggregates are Tier 2 gaps and outside this assignment |
| Ordering values | finite int,long,decimal,binary with ties and precision witnesses | All byte-array orders compare directly to H2, not guessed signedness. Unicode collation across databases needs an explicit collation contract; ASCII string predicates here avoid conflating that separate concern. Null sort placement could be added once declared; filtering nulls is explicit in current ordering recipes |
| Codec breadth | required nine types, NULL/missing/empty, pre-epoch Timestamp, scale-variant decimal parameters | byte/short/char/Date/Time and arbitrary NaN payloads are outside requested axes and already have codec-specific responsibilities. Never count their codec tests as generated-finder coverage |
| Cache | cold every backend, count path proof, warm-to-cold backend swap every case | All cache modes/classloader combinations are redundant to this goal; use supported partial caches with measured bypass. Full-cache load is explicitly unimplemented, not a way to initialise these tests |
| Storage and mutations | fixed read manifests independently seeded; two independently executed bound scripts with exact rectangles | No generic CRUD/concurrency/rollback matrix here: R-01/R-02 have separate suites. These two scripts are necessary because pure read seeding could never catch finding21's lost update wrapper |
| Service safeguards | real pagination and chunk completion within limits | PLAN-002/006/007/009 overflow acceptance is excluded until support-contract diagnostics are enumerated. The core MATCH cases cannot be converted to such refusals to pass |

No exclusion permits weakening a retained case. If implementing a retained case reveals a new defect or missing API/fixture support, preserve the failure and report the missing piece. In particular, current edge materialisation and cold transactional refresh gaps cannot be hidden by warm caches.

## 8. Runtime and execution plan

### Harness lifecycle

- One dedicated `finder-matrix` suite fork. Boot H2 once, start Local once, create tables once, register generated metadata once. Keep all fixture-table portal use serial within that fork. Use unique bundle key ranges above; immutable read fixtures are seeded once. Restore readers in finally even after an assertion fails.
- Four DDB tables for the core: DIFF_FINDER_VALUE, DIFF_BALANCE, DIFF_AUDIT, DIFF_ENTRY. Reuse them for additional bundles. This respects table-per-object. H2 and Local setup/teardown cannot run per parameter.
- Core manifests contain tens of rows; the shared wide Value fixture avoids nine generated entity types or per-case table creation. Seed through production writer batchInsert where appropriate; batches do not become a single oversized Reladomo transaction. Mutation scripts are isolated to 701/702 and each write transaction is well below service limits.
- Default config for all unspecified cases: allowTableScan=false; allowGsi=true; pkFanOutLimit=100; maxPages=64; pageSize=100; inMemoryRowCeiling=50000. Bitemporal reads use `.infinityFrom(...)`; no sparse-current GSI. Do not build hand-made plans to inject these values. pageSize=2 only for the deterministic audit pagination test.
- Run core on every `mvn test` in the supported Java build matrix. Exclude only tag `finder-matrix-slow` by default. Prescribe a Maven profile `finder-matrix-slow` that includes core **and** slow and explicitly overrides that exclusion; a profile running zero tests is a failure. New tags/profile/manifest counts are implementation work, not claimed existing switches.
- Treat failure as failure even if unrelated cases pass. Emit one report per parameter: operation, config, H2 and DDB value snapshots, exact code or result, request counts, route/explain, seed version, and timings. Print binary values as hex and timestamps as UTC millis+nanos for diagnostics only; do not compare diagnostic strings.

### Budget

Target **<=60 seconds median and <=90 seconds p95** for the whole core suite including H2/Local/table startup on a warm-dependency CI worker (2+ cores, 4 GiB). This is a target, not a measurement from this prescription. Expect roughly 150–180 independently reported core parameter invocations as the method tables expand, and a few hundred Local requests across H2/DDB/list/count observations; measure the exact count from the final registry and assert it stays nonzero and complete. The 59-type-leaf core count is exact.

Target slow extension **<=180 seconds total**, including core. Run it nightly and on planner/executor/codec/persister changes; the real 1 MiB-page, 50-key chunk, and canonical-special-value cases are the only added scale, not thousands of random finder trees. Start GSI readiness once per seed bundle, not once per query. Collect timing for seed, reset/bind, H2, DDB, and snapshot comparison so slow service startup does not prompt removal of meaningful assertions.

If core exceeds 90 seconds, first reuse Local/table/runtime lifecycle and remove repeated seed/readiness work; then profile request counts. Do not move any sole R-03–R-08 or finding21 witness into an optional job. A simple larger-than-100 filter IN is six stored rows and belongs on every build. Only the large-byte pagination fixture belongs in the slow subset.

## 9. Implementation order and red evidence required from the executing fleet

1. Generate the complete fixture and verify its generated APIs with javap. Implement the cold paired runner, request recorder, JDBC no-fallback detector, typed snapshots, and outcome registry. Validate fixture storage independently. The runner itself must reject zero requests and an unexpected refusal; do not confuse a missing symbol or broken seed with behavioural RED.
2. First run point mismatch, Query null pair, double/decimal range, distinct OR bindings, endsWith, ordered top-one, and bound-update closure. Save the decisive red assertion or exception with the counter evidence. Current fixes may already make a case green: do **not** label that RED or alter a correct assertion to manufacture a failure.
3. In an isolated integration checkout, demonstrate sensitivity by restoring one known defect at a time: drop GetItem filter (R-03); emit N for double (R-04); invert explicit-null translation **and interpreter together** (R-05); ignore branch value bindings (R-06); pass raw Map to residual (R-07); stop before sort/remove dedup (R-08); ignore the update wrapper closing processingTo (21). The named cases must fail against H2. Never mutate the H2 oracle. Restore each production mutation before proceeding.
4. Implement missing behaviour test-first, retain every comparison and request assertion, then execute the full core and slow suites with real Local. Keep Java 11: no records, sealed types, text blocks, Stream.toList, or SDK v1. Do not replace generated operations with fakes when encountering difficult APIs.
5. Completion requires: all retained MATCH cases execute and agree; exactly the three declared REFUSE cases reject with PLAN-001 at the specified phase; no skipped cases; all route/counter prerequisites met; independent history equality for update and terminate; measured runtime recorded. Report blocked cells explicitly until fixed. “N tests green” without the case/outcome manifest is insufficient.

## 10. Handoff limitations

No Java code, XML, Maven configuration, or repository source was changed for this task; this directory contains the prescription and verification evidence. No build or DynamoDB test run was performed, so no passing BUILD-LOG is claimed. The user-specific “prescribe, write no code” task overrides the generic fleet code-mirror/build deliverable.

The fleet protocol requested append-only checkpoints in `../PROGRESS.md`; the attempted append was denied with `Read-only file system` by the provided sandbox. Checkpoints are instead recorded in this output directory's PROGRESS.md. No permission bypass was attempted. This does not block the matrix document.
