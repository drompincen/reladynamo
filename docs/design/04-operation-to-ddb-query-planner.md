# Reladynamo Design: Reladomo `Operation` → DynamoDB Query Planner

**Status:** design (highest-risk adapter component)  
**Agent:** planner  
**Baseline:** Java 11 · Reladomo 18.1.0 · AWS SDK v2 · DynamoDBLocal 2.5.3  
**Verification:** `javap` + `reladomo-18.1.0.jar` / `-sources.jar` against
`MithraObjectReader`, `Operation` and every `com.gs.fw.common.mithra.finder*`
implementation; as-of SQL copied from `AsOfEqOperation.generateSql`.  
**Non-goals:** reimplementing `TemporalDirector`; JDBC `DatabaseType`; mutating
generated finders; changing the decided key grammar.

Sibling designs this document consumes, not relitigates:

- Schema: table-per-object; PK `v1[#SRC#…]#<ENTITY>#<pk…>`; SK
  `v1#P#<processingDateFrom>#B#<businessDateFrom>` (bitemporal).
- Bootstrap: portal binding via `MithraAbstractObjectPortal.setMithraObjectReader`.
- Codec: payload attributes include the four temporal timestamps even though
  from-points are also in SK.

---

## 0. Executive summary

Reladomo hands the persister an arbitrary boolean `Operation` tree.
DynamoDB answers only: exact partition-key equality, optional sort-key
condition, optional post-read filter, or a full scan.

The planner is a **pure function**

```text
(AnalyzedOperation, OrderBy, PhysicalDesign, PlannerConfig) → QueryPlan
```

It either produces a plan whose DynamoDB reads, plus an optional residual
`Operation.matches`, are **exactly** the Reladomo row set, or it **throws** with
a stable `RELADYNAMO-PLAN-NNN` message. It never degrades a missing key into a
silent `Scan`.

| Decision | Choice | Rejected |
|---|---|---|
| Entry point | `find(AnalyzedOperation, …)` using `getAnalyzedOperation()` | Planning `getOriginalOperation()` (misses injected as-of) |
| OR of PKs | Fan-out to N `Query`/`GetItem`, default cap **100** | Silent `Scan`; unbounded fan-out |
| Business-date as-of | Filter (or sparse-current GSI range) | Native base-table SK range (suffix is not contiguous) |
| Current-row fast path | Single `Query` of one item collection; `GetItem` only when SK is fully known | Inventing a `CURRENT` SK (re-derives bitemporal) |
| Scan | Explicit config opt-in; else throw | Implicit scan; “filter makes scan cheap” |
| Filter vs residual | FilterExpression if DDB-expressible **and** projected; else in-memory `matches` | Pushing unexpressible predicates into DDB; using filter as a key substitute |
| GSI inside a Mithra TX | Never | GSI for `refresh` / `getForDateRange` (eventual) |
| Planner I/O | None. AWS lives in `QueryPlanExecutor` | Mixing `DynamoDbClient` into `QueryPlanner` |
| Tree access | Public Reladomo API + 18.1.0-pinned introspector | Generating SQL and parsing it |

---

## 1. The input surface

### 1.1 Entry point (verified with `javap`)

```text
public interface com.gs.fw.common.mithra.portal.MithraObjectReader {
  CachedQuery find(AnalyzedOperation analyzedOperation,
                   OrderBy orderby,
                   boolean forRelationship,
                   int rowcount,
                   int numberOfThreads,
                   boolean bypassCache,
                   boolean forceImplicitJoin);
  Cursor findCursor(AnalyzedOperation, Filter postLoadFilter, OrderBy,
                    int rowcount, boolean bypassCache, int maxParallelDegree,
                    boolean forceImplicitJoin);
  int count(Operation op);
  List computeFunction(Operation, OrderBy, String sqlExpression, ResultSetParser);
  MithraDataObject refresh(MithraDataObject, boolean) throws MithraDatabaseException;
  MithraDataObject refreshDatedObject(MithraDatedObject, boolean) throws MithraDatabaseException;
  List findAggregatedData(Operation, Map<String,MithraAggregateAttribute>,
                          Map<String,MithraGroupByAttribute>, HavingOperation,
                          boolean bypassCache, Class bean);
  void loadFullCache();
  void reloadFullCache();
  RenewedCacheStats renewCacheForOperation(Operation op);
  Map extractDatabaseIdentifiers(Operation op);
  Map extractDatabaseIdentifiers(Set sourceAttributeValueSet);
}
```

`MithraObjectPersister` adds `deleteUsingOperation`, `deleteBatchUsingOperation`,
`findForMassDelete`, `prepareForMassDelete` / `prepareForMassPurge`.
`MithraDatedObjectPersister` adds `getForDateRange(MithraDataObject, Timestamp, Timestamp)`
and `enrollDatedObject`.

`PureMithraObjectPersister.find` (and most query methods) throw
`"not implemented"` — it is a structural precedent, not a query engine.

**`find` arguments the planner actually uses:**

| Argument | Planner meaning |
|---|---|
| `analyzedOperation` | Source of the predicate. **Must** call `getAnalyzedOperation()`, not `getOriginalOperation()`. `AsOfEqualityChecker` injects default as-of ops; dated objects without as-of throw `MithraBusinessException` from `getAsOfOperationForTopLevel`. |
| `orderby` | Native SK order vs in-memory sort (§6.3) |
| `forRelationship` | Hint: expect FK/PK equality; still planned, not short-circuited |
| `rowcount` | Max rows returned to Reladomo (`<=0` = unbounded). **Not** DynamoDB `Limit` when a filter exists (§2.7) |
| `numberOfThreads` | Parallelism cap for PK fan-out; `<=1` = serial |
| `bypassCache` | Ignored by planner (portal cache is above us) |
| `forceImplicitJoin` | Ignored as SQL join style; mapped ops always become application semi-joins |

`AnalyzedOperation` (18.1.0) exposes: `getOriginalOperation()`,
`getAnalyzedOperation()`, `hasAsOfAttributes()`, `getAsOfOperationForTopLevel(AsOfAttribute)`.

### 1.2 How the tree is read (18.1.0 access constraints)

Reladomo did not design `Operation` as a public visitor. Verified:

| Type | Public walk API | 18.1.0 hole |
|---|---|---|
| `AtomicOperation` | `getAttribute()` | — |
| `AtomicEqualityOperation` | `getParameterAsObject()` | — |
| `AsOfEqOperation` | `getParameter()` → `Timestamp` | — |
| `AsOfEdgePointOperation` | `getEdgeAttribute()`, `getAsOfAttribute()` | — |
| `InOperation` / `SetBasedAtomicOperation` | `getSetSize()`, `getSetValueAs*()` | — |
| `MappedOperation` | `getMapper()`, `getUnderlyingOperation()` | — |
| `EqualityOperation` | `zExtractEqualityOperations()`, `getEqualityOpCount()`, `addEqAttributes` | — |
| `AndOperation` | none | `private final InternalList operands` |
| `OrOperation` | none public | `protected Operation[] getOperations()` |
| `MultiEqualityOperation` | `operatesOnAttribute` | `private AtomicOperation[] atomicOperations` |
| `RangeOperation` | `getDirection()`, `getStaticExtractor()` | parameter via extractor, not a getter |
| `StringLikeOperation` | none public for pattern | field via introspector |

**Decision:** `ReladomoOperationAccess` (package `io.reladynamo.plan.reladomo`)
uses public API everywhere it exists, and `setAccessible` on the three 18.1.0
fields/methods above. A startup self-check constructs `And`/`Or`/`MultiEquality`
via generated finders and fails fast if Reladomo layout changed.

**Rejected:** parsing `generateSql` / `zToString` — locale- and SQL-dialect-shaped,
and as-of `generateSql(SqlQuery)` is a no-op (the real SQL is the overload with
`ObjectWithMapperStack`).

**Rejected:** planning only `zExtractEqualityOperations()` and treating the rest
as residual — that cannot fan-out OR of partition keys, which this design
forbids degrading to Scan.

### 1.3 Classification of every `Operation` implementation

Inventory: `reladomo-18.1.0.jar` contains **322** `finder/` class files. Below,
every **concrete `Operation` implementor** is classified. Abstract bases are
listed so type-switch code is exhaustive. Nested `$` classes, SQL helpers,
mappers, deep-fetch, order-by, and sqcache types are **not** `Operation`s.

Legend:

- **K** — may become a DynamoDB **key condition** (PK equality or SK
  `= / < / <= / > / >= / BETWEEN / begins_with`) when the attribute is the
  physical PK or SK component under the chosen index.
- **F** — DynamoDB **FilterExpression** (post-read; still billed).
- **R** — **residual** in-memory `Operation.matches` (or equivalent).
- **X** — **rejected at plan time** (throw `ReladynamoUnplannableOperationException`).
- **E** — empty plan, no DynamoDB call.
- **J** — application **semi-join** (plan related object, then this object).

When a row lists several of K/F/R, the planner picks the leftmost that the
attribute’s role and the chosen index allow.

#### 1.3.1 Boolean algebra and constants

| Class | Fate | Rule |
|---|---|---|
| `All` | Scan **or** X | “Return every row of this type.” Requires scan opt-in (§3). With as-of on a dated type, still a table scan + temporal filter — still a scan. |
| `None` | **E** | `zIsNone()==true`. Result is empty `CachedQuery`. |
| `NoOperation` | identity | Reladomo AND-identity. Strip during normalize. |
| `AndOperation` | decompose | Flatten; intersect constraints. |
| `OrOperation` | decompose | Same-PK ORs → FilterExpression `OR`. Distinct PKs → fan-out. Past cap → X (`PLAN-002`), never Scan. |
| `NegatableOperation` | interface | `zNegate()` during NOT-push; Reladomo has no general `NotOperation`. |

#### 1.3.2 Equality, inequality, range, IN (typed families)

All typed `*EqOperation` extend `AtomicEqualityOperation`. All typed
`*NotEqOperation` extend `AtomicNotEqualityOperation`. All typed `*GreaterThan*`
/ `*LessThan*` extend `RangeOperation`. All typed `*InOperation` extend
`InOperation`; `*NotInOperation` extend `NotInOperation`.

| Family (package) | Concrete classes | Fate |
|---|---|---|
| boolean | `BooleanEqOperation`, `BooleanNotEqOperation` | **K** if that attr is the entire PK (rare); else **F** (`=`, `<>`) |
| byte | `ByteEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | **K** if PK/SK scalar of this type (Reladynamo PK encoding is `S`, so **F** on payload `N` unless the attr is a PK *component* consumed into PK string — then **K** via encoded PK, not native `N` compare) |
| char | `CharEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | same as byte |
| integer | `IntegerEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | same |
| long | `LongEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | same; `LongEq` on a PK component is the common **K** |
| short | `ShortEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | same |
| float | `FloatEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | **F** (payload `N`); **X** if used as PK component (schema already warns; planner refuses keying on binary float) |
| double | `DoubleEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | same as float |
| bigdecimal | `BigDecimalGreaterThan/GreaterThanEquals/LessThan/LessThanEqualsOperation` | **F**; equality/IN go through `NonPrimitive*` |
| date | `DateEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | **F** on payload `S` (`yyyy-MM-dd`); **K** only if that attr is a GSI SK with the same encoding |
| time | `TimeEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | **F** |
| timestamp | `TimestampEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | **F** on payload; **K** only when the attribute is a **from-column** and SK prefix is determined (§2.4). These are **not** as-of ops. |
| string (non-primitive) | `NonPrimitiveEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEquals/In/NotInOperation` | **K** if PK/GSI-PK component (equality/IN fan-out) or GSI SK (`begins_with` / range when encoding matches); else **F** |
| byte[] | `ByteArrayEqOperation`, `ByteArrayInOperation` | **F** (`=`, `IN` on `B`); **X** as PK (Reladynamo keys are `S`) |
| abstract | `AbstractAtomicOperation`, `AtomicEqualityOperation`, `AtomicNotEqualityOperation`, `RangeOperation`, `AtomicSetBasedOperation`, `InOperation`, `NotInOperation`, `GreaterThanOperation`, `GreaterThanEqualsOperation`, `LessThanOperation`, `LessThanEqualsOperation` | dispatch only |

**IN on a partition-key component:** **K** via fan-out of equalities, not
DynamoDB `IN` in `KeyConditionExpression` (unsupported). **IN on a sort key**
with PK bound: fan-out `Query` with `SK =` each value, or `BatchGetItem` when
SK is fully determined. **IN on a non-key:** **F** (`IN`). DynamoDB
FilterExpression `IN` is capped at 100 values — larger sets become **R** (or
chunked OR of IN-lists, still **F**, still billed).

**NOT IN / `<>`:** never **K**. Always **F** if expressible (`<>`, `NOT IN`),
else **R**. A `NOT IN` on the only PK is still a scan of everything else → **X**
unless scan opted in.

**Range on PK:** DynamoDB forbids inequality on partition key → **X** unless a
GSI uses that attribute as **sort** key, in which case **K** on that GSI.

#### 1.3.3 Null, self-compare, multi-column

| Class | Fate | Rule |
|---|---|---|
| `IsNullOperation` | **F** `attribute_not_exists(#a)` | Reladynamo omits nullables; never **K** (key attrs cannot be null). |
| `IsNotNullOperation` | **F** `attribute_exists(#a)` | — |
| `AtomicSelfEqualityOperation` | **F** `#a = #b` | Same-item attr compare; never **K**. |
| `AtomicSelfNotEqualityOperation` | **F** `#a <> #b` | — |
| `MultiEqualityOperation` | decompose | AND of atomics; the usual `accountId.eq.and(productId.eq)` shape. |
| `MultiInOperation` | fan-out or **F**/**X** | Tuple-IN. If the tuple **covers the full PK**, fan-out `GetItem`/`Query` per tuple (cap 100). If it covers a GSI PK, fan-out GSI queries. Otherwise **F** cannot express tuple-IN → **R** after a keyed query, or **X** if no key remains. |
| `RelationshipMultiEqualityOperation` | treat as `MultiEquality` | `getOrCreateMultiEqualityOperation()` then same path. Cache-oriented; still a real `Operation`. |

#### 1.3.4 String LIKE family

| Class | Fate | Rule |
|---|---|---|
| `StringStartsWithOperation` | **K** `begins_with` if SK/GSI-SK with matching encoding; else **F** `begins_with` | — |
| `StringEndsWithOperation` | **R** | DynamoDB has no `ends_with`. |
| `StringContainsOperation` | **F** `contains` | Function on payload; not a key. |
| `StringNotStartsWith/NotEndsWith/NotContainsOperation` | **F** or **R** | `NOT begins_with` / `NOT contains` are valid filters; not-ends-with is **R**. |
| `StringLikeOperation` | **F** if pattern is `foo%` → `begins_with`; `%foo` → **R**; `%foo%` → `contains` **only if** no other `%`/`_`; `_` wildcards → **R** | Never **K** unless reduced to `begins_with` on an SK. |
| `StringNotLikeOperation` | **F**/**R** as the negation of the above | — |
| `StringWildCardEqOperation` | same as LIKE | Reladomo wildcard `*` / `?` mapped analogously; `?` (single char) is **R**. |
| `StringWildCardNotEqOperation` | **F**/**R** | — |

#### 1.3.5 As-of / temporal

| Class | Fate | Rule |
|---|---|---|
| `asofop.AsOfOperation` | interface | Implemented by the three below. |
| `asofop.AsOfEqOperation` | **F** (containment), sometimes **K** prefix | **Never** equality on SK. Translates to Reladomo’s SQL (§2.4). Consumed into the temporal fast path when PK is complete. |
| `asofop.AsOfEqInfiniteNullOperation` | **X** at config, **X** at plan if seen | Schema rejects `infinityIsNull`. If a model still produces this op, refuse: keys cannot be null. |
| `asofop.AsOfEdgePointOperation` | **F** or **K**-prefix | “As of the row’s own from/to edge.” Equality between as-of and a timestamp attribute. Useful for `getForDateRange` / director loads; SK prefix only when the edge is `from` **and** processing-from is bound. |
| `timestamp.TimestampAsOfEqualityMapper` / `asofop.AsOfTimestampEqualityMapper` / `AsOfEqualityMapper` | mapper, not `Operation` | Relationship join on as-of; handled as part of **J**. |

As-of is **not** `TimestampEqOperation` on the from-column. Confusing them
returns the wrong rectangle (H2 divergence).

#### 1.3.6 Relationship / mapped / exists

| Class | Fate | Rule |
|---|---|---|
| `MappedOperation` | **J** | EXISTS-style relationship: plan `getUnderlyingOperation()` on `getMapper().getFromPortal()`, collect join keys, rewrite as IN/eq on left attributes, plan left. Related scan is still gated by **that** object’s scan policy. |
| `NotExistsOperation` | **J** anti-join | Same two-phase; left rows whose join key is absent from the right result. Right-side scan still gated. |
| `tempobject.TupleExistsOperation` | **X** | SQL temp-tuple / analytic path. Reladynamo does not implement `MithraTuplePersister` durability (`DECISIONS.md` known risk). Message `PLAN-011`. |

Chained `LinkedMapper` is recursive semi-join. Intermediate result cap =
`PlannerConfig.joinFanOutLimit` (default 1000). Overflow → `PLAN-003`, not a
scan of the left table.

**Rejected:** translating mapped ops into DynamoDB `Scan` + in-memory nested
loops over the whole related table.

#### 1.3.7 Source

`SourceOperation` is an **interface** implemented by `AtomicEqualityOperation`.
A source equality is **K**: it is a PK prefix (`v1#SRC#<source>#…`) under the
schema default, or a table/client router under `TABLE_PER_SOURCE` /
`CLIENT_PER_SOURCE`. Missing source on a sourced object → `PLAN-004` (Reladomo
would also fail routing).

#### 1.3.8 Aggregation / having (not `Operation`)

`HavingOperation` is a separate type, used only by `findAggregatedData`.

| Class | Fate |
|---|---|
| `HavingAndOperation` / `HavingOrOperation` | **R** after in-memory group |
| `HavingAtomicOperation` + `HavingEq/NotEq/GreaterThan/GreaterThanEquals/LessThan/LessThanEqualsFilter` | **R** via `HavingOperation.zMatches(AggregateData, …)` |

There is no DynamoDB `GROUP BY`. Aggregation is always in-memory on the
**already-planned** row set. Having never becomes a key condition.

`computeFunction(…, String sqlExpression, …)` is **X** (`PLAN-010`): it is a
raw SQL escape hatch.

#### 1.3.9 Non-Operation finder types (explicitly out of classification)

Mappers (`EqualityMapper`, `FilteredMapper`, `ChainedMapper`, `LinkedMapper`,
`AbstractMapper`, …), `SqlQuery`, `WhereClause`, `JoinClause`, deep-fetch
strategy classes, `orderby.*`, `sqcache.*`, `paramop.*`, `ResultSetParser`,
`UpdateCountHolder*`, `FinderUtils`. These are not planner inputs.

---

## 2. The planning algorithm

### 2.1 Types (Java 11 value classes — **no records**)

```java
package io.reladynamo.plan;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import io.reladynamo.design.PhysicalDesign;
import java.util.Objects;

public final class PlanningRequest {
    private final AnalyzedOperation analyzedOperation;
    private final OrderBy orderBy;
    private final PhysicalDesign design;
    private final PlannerConfig config;
    private final int rowcount;
    private final int numberOfThreads;
    private final PlanningPurpose purpose;

    public PlanningRequest(AnalyzedOperation analyzedOperation, OrderBy orderBy,
            PhysicalDesign design, PlannerConfig config, int rowcount,
            int numberOfThreads, PlanningPurpose purpose) {
        this.analyzedOperation = Objects.requireNonNull(analyzedOperation, "analyzedOperation");
        this.orderBy = orderBy;
        this.design = Objects.requireNonNull(design, "design");
        this.config = Objects.requireNonNull(config, "config");
        this.rowcount = rowcount;
        this.numberOfThreads = numberOfThreads;
        this.purpose = Objects.requireNonNull(purpose, "purpose");
    }

    public AnalyzedOperation analyzedOperation() { return analyzedOperation; }
    public OrderBy orderBy() { return orderBy; }
    public PhysicalDesign design() { return design; }
    public PlannerConfig config() { return config; }
    public int rowcount() { return rowcount; }
    public int numberOfThreads() { return numberOfThreads; }
    public PlanningPurpose purpose() { return purpose; }
}
```

`PlanningPurpose` is an enum-like final class with constants `FIND`, `COUNT`,
`CURSOR`, `AGGREGATE`, `DELETE`, `REFRESH`, `DATE_RANGE`, `CACHE_LOAD` —
**not** a Java 12+ sealed type.

`QueryPlanner.plan(PlanningRequest)` is **pure**: no `DynamoDbClient`, no
`System.currentTimeMillis`, no I/O, no randomness.

### 2.2 Top-level algorithm (not a sketch)

```text
plan(req) → QueryPlan
  op ← req.analyzedOperation.getAnalyzedOperation()
  if op.zIsNone() → EmptyPlan

  if req.purpose ∈ {REFRESH} → planRefresh(req)          // §6.5; GetItem
  if req.purpose ∈ {DATE_RANGE} → planDateRange(req)     // §6.6; Query PK
  if req.purpose ∈ {CACHE_LOAD} → planFullScan(req)      // §3; explicit

  tree ← ReladomoOperationAccess.decompose(op)           // And/Or/atomic
  norm ← normalize(tree)                                 // §2.3
  if norm is NONE → EmptyPlan

  dnf ← distributeToDnf(norm)                            // list of conjunctions
  if dnf.clauseCount > req.config.pkFanOutLimit → throw PLAN-002

  candidatesPerClause ← []
  for clause in dnf:
      candidatesPerClause.add(planConjunction(clause, req))   // §2.4–2.6

  chosen ← []
  for cands in candidatesPerClause:
      legal ← [c in cands if c.accessMethod != SCAN or req.config.allowTableScan]
      if legal is empty:
          if any SCAN in cands → throw PLAN-001 with the Operation toString
          else throw PLAN-005 (no access path)
      chosen.add(minCost(legal))                         // §2.6

  if chosen.size == 1 → attachOrderBy(chosen[0], req)    // §6.3
  else → FanOutPlan(chosen, merge=UNION_DEDUPE_PK_SK, parallelism=req.numberOfThreads)

planConjunction(clause, req) → List<CandidatePlan>
  extract:
      pkEq     : equality on every base-PK component (incl. source)
      pkIn     : IN on a subset of PK components with the rest equality-bound
      gsiEq    : equality on each GSI’s PK attributes
      asOf     : AsOfEq / AsOfEdgePoint per AsOfAttribute
      fromEq   : equality on from-columns (processingFrom / businessFrom)
      other    : everything else

  cands ← []
  if pkEq complete:
      cands.add(baseTablePlan(pkEq, asOf, fromEq, other, req))
  if pkIn present and remaining PK components equality-bound:
      cands.add(fanOutPkIn(pkIn, …))                     // still Queries, not Scan
  for gsi in req.design.gsis():
      if gsi PK fully equality-bound
         and req.purpose allows GSI (§2.6):
          cands.add(gsiPlan(gsi, …))
  if cands empty:
      cands.add(scanCandidate(other, asOf, req))         // illegal unless opted in
  return cands
```

### 2.3 Normalise the tree

Order of rewrites (each is a total function on the decomposed tree):

1. **Strip** `NoOperation`. `All AND x → x`. `All OR x → All`. `None AND x → None`.
   `None OR x → x`.
2. **Constant-fold equalities** on the same attribute: `a=1 AND a=2 → None`.
   `a=1 OR a=1 → a=1`. Range intersection: `a>5 AND a<3 → None`;
   `a>=1 AND a<=1 → a=1`.
3. **Push NOT** only through `NegatableOperation.zNegate()` (typed). There is no
   generic Reladomo `NOT` node. Double-not on `NotExists` is `MappedOperation`.
4. **Flatten** nested `And` / `Or`.
5. **Lift** `MultiEqualityOperation` / `RelationshipMultiEqualityOperation` into
   a bag of atomics.
6. **Do not** convert OR to Scan. **Do not** drop predicates that look
   “expensive”.

DNF distribution is next, with an explicit explosion cap equal to
`pkFanOutLimit`. If `AND(OR(a,b), OR(c,d), …)` would exceed the cap, **stop
distributing** and keep the residual OR inside a **FilterExpression** on a
single keyed Query when a common PK exists. If there is **no** common PK,
throw `PLAN-002` rather than Scan.

Worked PK-OR example:

```text
PositionFinder.accountId().eq(1).and(PositionFinder.productId().eq(10))
  .or(PositionFinder.accountId().eq(2).and(PositionFinder.productId().eq(20)))
```

Two complete PKs → two `Query` (or `GetItem` if non-dated) in a `FanOutPlan`.
**Not** a Scan with a filter on accountId.

### 2.4 Partition key, fan-out, and why businessDate is not an SK range

#### Partition-key extraction

A conjunction has a **complete base PK** when every `primaryKey="true"`
attribute (and `SourceAttribute` if present) has an equality (or a singleton
IN). Values are encoded with `PartitionKeyEncoder` from the schema design
(`v1#SRC#…#POSITION#42#7`). That encoded string is the DynamoDB PK
`KeyConditionExpression`: `#pk = :pk`.

IN of size `N` on one PK component, others bound → `N` encoded PKs → fan-out.

#### Fan-out limit

| Knob | Default | Hard max |
|---|---|---|
| `PlannerConfig.pkFanOutLimit` | **100** | 1000 |

**Rationale for 100:** DynamoDB `BatchGetItem` and `TransactGetItems` max 100
items; Reladomo’s own `AtomicSetBasedOperation.IN_CLAUSE_BULK_INSERT_THRESHOLD`
is 1000 for SQL temp tables, which we do not have. 100 keeps one round-trip
when SK is fully known (`BatchGetItem`) and a bounded parallel `Query` set
otherwise.

**Past the limit:** throw, never Scan:

```text
RELADYNAMO-PLAN-002: Partition-key IN/OR fan-out of {n} exceeds pkFanOutLimit={limit}
for {className}. Narrow the operation, raise query.pkFanOutLimit, or split the call.
Operation: {op}
```

Exception type: `io.reladynamo.plan.ReladynamoUnplannableOperationException`
(extends `MithraBusinessException`, `isRetriable()==false`).

**Rejected:** “IN of 500 account ids → Scan + `IN` filter.” That bills the
whole table.

#### Why `businessDate` cannot be a native base-table SK range

Decided SK (bitemporal):

```text
SK = v1#P#<processingDateFrom>#B#<businessDateFrom>
```

DynamoDB sort-key conditions compare the **entire** SK string lexicographically.
A range / `begins_with` therefore selects a **contiguous prefix** of that string.

`businessDateFrom` sits **after** `processingDateFrom`. Two items:

```text
v1#P#2020-01-01T00:00:00.000Z#B#2010-01-01T00:00:00.000Z
v1#P#2021-01-01T00:00:00.000Z#B#1990-01-01T00:00:00.000Z
```

The 1990 business-from row sorts **after** the 2010 row because 2021 > 2020 on
the processing prefix. The set “all rows with `businessFrom <= X`” is **not** a
contiguous SK interval. `begins_with(SK, '…#B#')` is also impossible: the
prefix up to `#B#` includes a varying processing timestamp.

`AsOfEqOperation` is not even a predicate on `businessFrom` equality. Reladomo
18.1.0 `AsOfEqOperation.generateSql` (sources) is:

```text
if parameter.equals(infinity):
    toColumn = ?
else if toIsInclusive:
    fromColumn < ? AND toColumn >= ?
else:   // default half-open
    fromColumn <= ? AND toColumn > ?
```

Containment uses **both** from and thru. Thru is a payload attribute, not in
the SK at all. So even a contiguous from-range would still need a thru filter.

**What the planner does instead:**

| Situation | SK condition | Filter |
|---|---|---|
| Complete PK, any as-of | **none** (query the item collection) | containment on from/thru per §2.4.1 |
| Complete PK + **equality** on `processingFrom` (the from-attribute, not as-of) | `begins_with(SK, v1#P#<thatFrom>#B#)` and, if `businessFrom` also equality, `SK =` full encoding → `GetItem` | remaining as-of / payload |
| Complete PK + processingFrom equality + businessFrom range | `SK BETWEEN v1#P#<p>#B#<lo> AND v1#P#<p>#B#<hi>` | thru / other |
| Sparse **current** GSI present (`gsi` SK = `v1#B#<businessFrom>`, key omitted when `processingThru ≠ infinity`) and processing as-of is infinity | GSI `SK <= v1#B#<asOfB>` (and `>= v1#B#` prefix) | `businessThru` vs as-of; see fast path §4 |
| Processing as-of not infinity, no processingFrom equality | no SK | both dimensions’ containment |
| Incomplete PK | not a base Query | GSI or Scan-or-throw |

Item collections per logical key are assumed **bounded** (versions, not event
streams) — schema §2.4. The planner still records `estimatedItemsExamined =
config.estimatedVersionsPerKey` (default **16**) for costing.

#### 2.4.1 As-of → FilterExpression (exact Reladomo semantics)

Let `inf` be `AsOfAttribute.getInfinityDate()`. Encoding: schema UTC millis.
Payload attribute names come from the codec (from/to column names, possibly
compressed).

Processing dimension, `AsOfEq(processingDate, P)`:

- If `P.equals(inf)`: `#outZ = :inf`  
  (Reladomo special case: `infinity < thru` is false when thru is infinity.)
- Else if `toIsInclusive`: `#inZ < :P AND #outZ >= :P`
- Else: `#inZ <= :P AND #outZ > :P`

Business dimension, `AsOfEq(businessDate, B)`: the same pattern on
`#fromZ` / `#thruZ`.

Both dimensions AND together. This is **F**, not residual, because it is
expressible and must run before we hydrate `MithraDataObject`s — and because
H2 conformance requires the same four-timestamp predicate.

`AsOfEdgePointOperation`: filter `#asOfAttr` corresponding edge; do not invent
director rules.

### 2.5 Unconsumed predicates: FilterExpression vs residual

After keys and as-of filters are assigned, leftover atomics are classified:

```text
toFilterExpression(pred) if
    pred is expressible in DynamoDB FilterExpression
    AND every referenced attribute is stored on the item
    AND (chosen index is base table OR the attribute is in the GSI projection)
    AND not a mapped/semi-join remainder
else
    residual Operation (AND of leftover Reladomo ops; evaluated via matches())
```

Expressible FilterExpression subset (SDK v2 condition language):

`= <> < <= > >= BETWEEN IN AND OR NOT attribute_exists attribute_not_exists
begins_with contains`

Not expressible (always residual): `ends_with`, SQL `LIKE` with `_` or internal
`%` beyond a single `contains`, wildcard `?`, self-join across items, mapped
EXISTS remainder after keys are rewritten, `Having*`, custom extractors,
`byte[]` inequality, Reladomo `matches` that consults related objects.

**Why not put everything residual:** FilterExpression cuts response bytes and
avoids codec work. **Why not put everything in FilterExpression:** unprojected
GSI attributes cannot be filtered server-side; unexpressible predicates would
silently return extra rows if we pretended they were filters.

**Billing rule (stated for implementers and tests):** FilterExpression does
**not** reduce RCU. `ExplainPlan.estimatedItemsExamined` is computed **before**
filter selectivity. Tests must not assert “filter on status=ACTIVE scanned 1
item” unless the key condition already bounds to that item. Selectivity may be
logged as `estimatedItemsReturned` only.

**Rejected:** applying Reladomo `matches()` only, skipping FilterExpression —
wastes network on dated item collections that are still larger than the as-of
hit. As-of **must** be a FilterExpression for the fast path.

### 2.6 Choose base table vs GSI and cost

Candidate construction is in §2.2. Cost each **legal** candidate:

```text
examined = estimatedItemsExamined(plan)          // before filter
pages    = ceil(examined / config.pageSize)      // default pageSize 100
rcu      = examined * ceil(avgItemBytes / 4096.0)
           * (consistentRead ? 1.0 : 0.5)
cost     = rcu + pages * 0.25 + requestCount * 0.5
           + (accessMethod == SCAN ? 1.0e9 : 0.0)   // Scan never wins a tie against Query
           + (index is GSI ? 0.1 : 0.0)            // slight preference for base table at equal RCU
```

`avgItemBytes` comes from `PhysicalDesign` (codec estimate) or config default
1024.

**GSI legality:**

- GSI reads are eventually consistent. **Forbidden** when
  `purpose ∈ {REFRESH, DATE_RANGE, DELETE}` or when
  `PlannerConfig.allowGsi == false` or when a Mithra transaction is active
  (`PlannerConfig.inTransaction`, set by the persister, not guessed).
- GSI PK must be fully bound by equality.
- If the GSI projection is `KEYS_ONLY` / `INCLUDE`, payload predicates that
  need non-projected attrs become residual **after** a `BatchGetItem` of base
  keys. That extra round-trip is in the cost (`requestCount += ceil(n/100)`).

**Pick:** minimum `cost` among legal candidates. Tie-break: `GET_ITEM` over
`QUERY` over `FANOUT` over `SCAN`; base table over GSI (strong consistency).

**Rejected:** always use the base table (would ignore a unique-email GSI).
**Rejected:** always prefer GSI (wrong inside a transaction; stale reads).

### 2.7 DynamoDB `Limit` vs Reladomo `rowcount`

DynamoDB `Limit` bounds items **evaluated**, not items matching a
FilterExpression. Using `Limit=rowcount` with an as-of filter returns truncated
**wrong** results.

Rule: **never** set Query `Limit` to `rowcount` when `filterExpression != null`
or `residual != null`. Page until `returnedCount == rowcount` or
`LastEvaluatedKey == null`. Cap pages with `PlannerConfig.maxPages`
(default 64). Exceed → `PLAN-006` (too much examined for this find).

When there is **no** filter and **no** residual (pure key), `Limit=rowcount` is
correct and required for `find` top-N.

---

## 3. Scan policy

A silent full Scan is how this adapter dies in production.

### 3.1 Opt-in

```java
public final class PlannerConfig {
    private final boolean allowTableScan;
    private final int pkFanOutLimit;          // default 100
    private final int joinFanOutLimit;        // default 1000
    private final int parallelScanSegments;   // default 4; only if allowTableScan
    private final int maxPages;               // default 64
    private final int inMemoryRowCeiling;     // default 50_000
    private final int inMemoryByteCeiling;    // default 32 * 1024 * 1024
    private final int estimatedVersionsPerKey;// default 16
    private final boolean allowGsi;
    private final boolean inTransaction;
    private final boolean allowScanDelete;    // default false; extra gate
    // … constructor, getters, builder …
}
```

Config surface (must match javaconfig/XML later):

```xml
<QueryPlanner allowTableScan="false"
              pkFanOutLimit="100"
              parallelScanSegments="4"
              allowScanDelete="false"/>
```

Default **`allowTableScan=false`**.

### 3.2 Exception type and exact message

Type: `io.reladynamo.plan.ReladynamoScanRequiredException`
extends `ReladynamoUnplannableOperationException`
extends `com.gs.fw.common.mithra.MithraBusinessException`.

`isRetriable()` remains false (scanning is a design choice, not a blip).

Message **contract** (tests assert exact text, `{…}` interpolated):

```text
RELADYNAMO-PLAN-001: Scan required for {className} on table {tableName} but scans are disabled.
The operation does not bound a partition key on the base table or any GSI.
Enable scans only for bounded batch jobs: ReladynamoConfig queryPlanner.allowTableScan(true)
(or reladynamo.xml <QueryPlanner allowTableScan="true"/>).
Operation: {operation}
```

`All` uses the same exception (it *is* a scan). Do not special-case `All` into
an implicit scan.

Related messages (same type hierarchy, different code):

```text
RELADYNAMO-PLAN-002: Partition-key IN/OR fan-out of {n} exceeds pkFanOutLimit={limit} for {className}. Narrow the operation, raise query.pkFanOutLimit, or split the call. Operation: {op}
RELADYNAMO-PLAN-003: Relationship semi-join for {className}.{relationship} produced {n} keys, exceeding joinFanOutLimit={limit}.
RELADYNAMO-PLAN-004: Object {className} has a sourceAttribute but the operation does not constrain it; refusing cross-source scan.
RELADYNAMO-PLAN-005: No DynamoDB access path for {className}: predicates do not match the base key or any GSI, and the remaining operation is not a legal filter-only query. Operation: {op}
RELADYNAMO-PLAN-006: Query for {className} examined {pages} pages (maxPages={max}) without filling rowcount={rowcount}. Widen keys or raise query.maxPages.
RELADYNAMO-PLAN-007: In-memory {sort|aggregate|join} for {className} would materialise {n} rows ({bytes} bytes), exceeding inMemoryRowCeiling={rows} or inMemoryByteCeiling={bytesCap}.
RELADYNAMO-PLAN-008: deleteUsingOperation for {className} requires a Scan but allowScanDelete=false (allowTableScan is not sufficient for deletes).
RELADYNAMO-PLAN-009: OrderBy {orderBy} is not native sort-key order and in-memory sort is disabled (inMemoryRowCeiling=0) for {className}.
RELADYNAMO-PLAN-010: computeFunction is not supported on DynamoDB (raw SQL expression). Class: {className}.
RELADYNAMO-PLAN-011: TupleExistsOperation / temp-tuple queries are not supported by Reladynamo. Class: {className}.
```

### 3.3 Parallel scan when opted in

Only for `allowTableScan=true` **and** the chosen plan is `SCAN`.

- `totalSegments = PlannerConfig.parallelScanSegments` (default 4).
- Each segment is a `ScanRequest` with `segment`, `totalSegments`,
  `filterExpression` (as-of + leftover), exclusive start key per segment.
- Merge is unordered unless `OrderBy` forces in-memory sort (ceiling applies
  to the **merged** list).
- Parallelism does **not** reduce RCU (dynamodb-architect scan rule). Explain
  plan records `segmentCount` and `estimatedItemsExamined = tableItemCount`
  (unknown → `Integer.MAX_VALUE` for costing so Scan never wins against a
  Query of similar shape by accident).

`loadFullCache` uses this path with purpose `CACHE_LOAD`. It still requires
`allowTableScan=true` **or** `PlannerConfig.allowFullCacheLoad=true` (default
true only when Reladomo cache mode is FULL). Finder `All` does **not** get
that second gate.

---

## 4. The temporal fast path

The dominant Reladomo query:

```java
PositionFinder.accountId().eq(42L)
    .and(PositionFinder.productId().eq(7))
    .and(PositionFinder.businessDate().eq(businessDate));
// processingDate defaulted by AnalyzedOperation to infinity
```

### 4.1 Recognition

A conjunction is `CURRENT_ASOF` when:

1. Base PK is complete (all PK attrs + source if any) by equality, **and**
2. Every `AsOfAttribute` has an `AsOfEqOperation` (after analyze), **and**
3. The processing as-of, if the object has one, equals `getInfinityDate()`,
   **and**
4. No mapped / not-exists / self-join remains.

Non-dated complete PK is `POINT_GET`, not `CURRENT_ASOF`.

### 4.2 Why this is not (usually) `GetItem`

`GetItem` needs the full primary key **including SK**. SK contains
`processingDateFrom` and `businessDateFrom` of the **stored rectangle**, which
as-of does not name. Guessing `from = asOf` is wrong (a segment starting
Monday still covers Wednesday). Inventing `SK=CURRENT` would store a second
physical shape the director does not write — **forbidden**.

### 4.3 The plan (one Query, bounded collection)

```text
accessMethod     = QUERY
index            = PRIMARY
keyCondition     = #pk = :pk
filterExpression = (#outZ = :inf) AND (#fromZ <= :B) AND (#thruZ > :B)
                   -- or inclusive variants; omit processing clause if audit-only/business-only
residual         = leftover non-temporal predicates, else empty
consistentRead   = true
limit            = unset (filter present)
scanIndexForward = true
estimatedItemsExamined = estimatedVersionsPerKey   // default 16
estimatedItemsReturned = 1
```

Executor issues **one** `QueryRequest` (plus extra pages only if the collection
is huge; `maxPages` still applies). Typical collection is a handful of versions;
as-of returns 0 or 1 row for a unique logical key.

If a **sparse current GSI** exists (schema §2.6: GSI key present only when
`processingThru == infinity`, GSI SK = `v1#B#<businessFrom>`):

```text
accessMethod     = QUERY
index            = <that GSI name>
keyCondition     = #gsipk = :pk AND #gsisk BETWEEN :bPrefix AND :bAsOf
filterExpression = #thruZ > :B   -- from-bound already in SK
consistentRead   = false         -- GSI; FORBIDDEN in-transaction → fall back to PRIMARY
```

This is the only time business as-of becomes a **native range**, because the
GSI SK has no processing prefix.

Costing prefers the GSI when `!inTransaction && allowGsi` and
`estimatedVersionsPerKey > 4`; otherwise PRIMARY Query (strong consistency,
still one request).

### 4.4 When the fast path **is** `GetItem`

| Condition | SK | Method |
|---|---|---|
| Non-dated, complete PK | `v1#ND` | `GetItem` |
| Dated, equality on **both** from-attributes (refresh, enroll, director rewrite of a known rectangle) | `v1#P#…#B#…` | `GetItem` |
| Dated as-of (this section) | unknown | `Query` as above, **never** `GetItem` |

```java
GetItemRequest.builder()
    .tableName(design.tableName())
    .key(keyMap) // pk + sk AttributeValues
    .consistentRead(Boolean.TRUE)
    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
    .build();
```

---

## 5. Explain plan

Two objects: the **static** plan (pure, testable without AWS) and the
**runtime** snapshot (filled by the executor). Javaconfig sketched
`io.reladynamo.observe.QueryExplainPlan`; this document **owns** the fields
and is the contract tests assert.

### 5.1 Static plan

```java
package io.reladynamo.plan;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QueryPlan {
    private final String className;
    private final String tableName;
    private final String indexName;                 // "PRIMARY" or GSI name
    private final AccessMethod accessMethod;        // GET_ITEM, QUERY, SCAN, FANOUT, EMPTY
    private final String keyConditionExpression;    // nullable
    private final String filterExpression;          // nullable
    private final Map<String, String> expressionAttributeNames;
    private final Map<String, ExpressionValue> expressionAttributeValues;
    private final boolean consistentRead;
    private final boolean scanIndexForward;
    private final Integer dynamoLimit;              // null if filter/residual present
    private final int estimatedItemsExamined;
    private final int estimatedItemsReturned;
    private final String residualOperationDump;     // Operation.toString or empty
    private final OrderMode orderMode;              // NATIVE_SK, IN_MEMORY, NONE
    private final List<QueryPlan> fanOut;           // empty if not FANOUT
    private final int segmentCount;                 // scan only
    private final String fastPath;                  // CURRENT_ASOF, POINT_GET, NONE

    // constructor copies maps/lists to unmodifiable snapshots; null-hostile except
    // the documented nullable expressions.

    public String className() { return className; }
    public String tableName() { return tableName; }
    public String indexName() { return indexName; }
    public AccessMethod accessMethod() { return accessMethod; }
    public String keyConditionExpression() { return keyConditionExpression; }
    public String filterExpression() { return filterExpression; }
    public Map<String, String> expressionAttributeNames() { return expressionAttributeNames; }
    public Map<String, ExpressionValue> expressionAttributeValues() { return expressionAttributeValues; }
    public boolean consistentRead() { return consistentRead; }
    public boolean scanIndexForward() { return scanIndexForward; }
    public Integer dynamoLimit() { return dynamoLimit; }
    public int estimatedItemsExamined() { return estimatedItemsExamined; }
    public int estimatedItemsReturned() { return estimatedItemsReturned; }
    public String residualOperationDump() { return residualOperationDump; }
    public OrderMode orderMode() { return orderMode; }
    public List<QueryPlan> fanOut() { return fanOut; }
    public int segmentCount() { return segmentCount; }
    public String fastPath() { return fastPath; }

    public String toAssertableString() {
        return "QueryPlan{class=" + className
                + ", index=" + indexName
                + ", method=" + accessMethod
                + ", key=" + keyConditionExpression
                + ", filter=" + filterExpression
                + ", residual=" + residualOperationDump
                + ", examined~=" + estimatedItemsExamined
                + ", returned~=" + estimatedItemsReturned
                + ", fastPath=" + fastPath
                + "}";
    }
}
```

`AccessMethod` / `OrderMode` are Java 11 enums (allowed since Java 5; not sealed types).

`ExpressionValue` is a Reladynamo-owned tagged scalar (`S` / `N` / `B` / `BOOL` / `NULL`) so `QueryPlan` has **zero** `software.amazon.awssdk.*` types. The executor is the only module that calls `AttributeValue.builder()`. **Rejected:** putting SDK `AttributeValue` on the plan — that would force AWS onto planner unit tests and break the purity boundary in §7.1.

```java
package io.reladynamo.plan;

import java.util.Arrays;
import java.util.Objects;

public final class ExpressionValue {
    public enum Kind { S, N, B, BOOL, NULL }

    private final Kind kind;
    private final String s;
    private final byte[] b;
    private final Boolean bool;

    private ExpressionValue(Kind kind, String s, byte[] b, Boolean bool) {
        this.kind = kind;
        this.s = s;
        this.b = b == null ? null : Arrays.copyOf(b, b.length);
        this.bool = bool;
    }

    public static ExpressionValue s(String v) {
        return new ExpressionValue(Kind.S, Objects.requireNonNull(v, "s"), null, null);
    }

    public static ExpressionValue n(String decimal) {
        return new ExpressionValue(Kind.N, Objects.requireNonNull(decimal, "n"), null, null);
    }

    public static ExpressionValue b(byte[] v) {
        return new ExpressionValue(Kind.B, null, Objects.requireNonNull(v, "b"), null);
    }

    public static ExpressionValue bool(boolean v) {
        return new ExpressionValue(Kind.BOOL, null, null, Boolean.valueOf(v));
    }

    public static ExpressionValue nul() {
        return new ExpressionValue(Kind.NULL, null, null, null);
    }

    public Kind kind() { return kind; }
    public String s() { return s; }
    public String n() { return kind == Kind.N ? s : null; }
    public byte[] b() { return b == null ? null : Arrays.copyOf(b, b.length); }
    public Boolean bool() { return bool; }
}
```

Tests assert with AssertJ on `indexName()`, `accessMethod()`,
`estimatedItemsExamined()`, `fastPath()`, and expression strings. A performance
regression is a test that pins `estimatedItemsExamined` for a named finder
shape (e.g. current-row must stay `<= estimatedVersionsPerKey`, never
`Integer.MAX_VALUE`).

### 5.2 Runtime snapshot

Executor copies the static plan and adds:

| Field | Source |
|---|---|
| `actualItemsExamined` | sum of `ScannedCount` across pages |
| `actualItemsReturned` | sum of `Count` (pre-residual) then residual size |
| `consumedCapacityRcu` | `ConsumedCapacity.capacityUnits` (request `ReturnConsumedCapacity.TOTAL`) |
| `pageCount` | pages followed |
| `durationMs` | executor clock (injected `java.time.Clock`, not `new Date()`) |

`QueryExplainPlan` (observe package) = static fields + runtime fields.
`ExplainPlanListener.onPlan` fires **after** execution so tests can assert
actuals. Planner-only tests never call the listener.

Pin example:

```java
assertThat(plan.accessMethod()).isEqualTo(AccessMethod.QUERY);
assertThat(plan.indexName()).isEqualTo("PRIMARY");
assertThat(plan.fastPath()).isEqualTo("CURRENT_ASOF");
assertThat(plan.estimatedItemsExamined()).isLessThanOrEqualTo(16);
```

Integration (DynamoDBLocal) additionally:

```java
assertThat(snapshot.actualItemsExamined()).isLessThanOrEqualTo(16);
assertThat(snapshot.actualItemsReturned()).isEqualTo(1);
```

---

## 6. Other `MithraObjectReader` / persister entry points

### 6.1 `count(Operation)`

Same `plan()` with `purpose=COUNT`. Executor uses `Select.COUNT` on Query/Scan
so items are not shipped. Residual predicates **cannot** be counted by DynamoDB:
the executor must read items (or at least projected attrs needed by residual)
and count matches. If residual is empty, `Select.COUNT` is exact.

**Rejected:** `find` then `list.size()` as the only implementation — wasteful
when residual is empty.

### 6.2 `findCursor` + pagination

`Cursor` (18.1.0) is `Iterator` + `close()`. Implementation:

`DynamoQueryCursor` holds the `QueryPlan`, the SDK client, current
`exclusiveStartKey`, and an in-memory window of decoded `MithraDataObject`s.

- Each `hasNext`/`next` fills the window from the next DynamoDB page.
- `Filter` `postLoadFilter` (Reladomo argument) runs **after** residual
  matches, in memory.
- `rowcount` stops the cursor after that many **returned** objects.
- `close()` drops the window; does not call DynamoDB.
- Fan-out plans iterate children in PK order (sorted encoded PK) so the cursor
  is deterministic even if parallel fetch is used behind the window.

Do not hold an open HTTP stream; DynamoDB is page-at-a-time.

### 6.3 `OrderBy`

`OrderBy.mustUseServerSideOrderBy()` is a SQL hint; we interpret:

| Condition | Mode |
|---|---|
| `orderBy == null` | `NONE` (DynamoDB SK ascending by default) |
| Single attribute (or chain) that is exactly the chosen index’s SK, ascending | `NATIVE_SK`, `scanIndexForward=true` |
| Same, descending | `NATIVE_SK`, `scanIndexForward=false` |
| Anything else (payload attr, PK, chained mix, relationship attr) | `IN_MEMORY` |

In-memory: materialise **all** matching rows (still bounded by `rowcount` only
if native order already matches — it does not), sort with Reladomo’s
`OrderBy` `Comparator`, then apply `rowcount`.

Ceiling: `inMemoryRowCeiling` (50_000) and `inMemoryByteCeiling` (32 MiB).
Exceed → `PLAN-007`. Setting `inMemoryRowCeiling=0` disables in-memory sort
entirely (native SK order only) → `PLAN-009` if OrderBy is not SK-aligned.
**Rejected:** silently returning unsorted rows.
**Rejected:** sorting only the first page.

### 6.4 `findAggregatedData`

1. Plan the qualifying `Operation` as `FIND`/`AGGREGATE`.
2. Execute; decode to data objects (or a narrow projection of group-by +
   aggregate source attrs — OPEN QUESTION on projection).
3. Group in memory by `MithraGroupByAttribute`.
4. Reduce with `MithraAggregateAttribute.aggregate`.
5. Apply `HavingOperation.zMatches`.
6. Build beans of the requested class.

Ceiling: same in-memory caps. No DynamoDB `GroupBy`. Mapped aggregate
attributes (`createMappedOperation`) trigger the semi-join path first.

### 6.5 `refresh` / `refreshDatedObject`

The data object **already carries** PK components and from-timestamps.
Purpose `REFRESH`:

- Encode PK + SK from the data object (not from an as-of).
- `GetItem` strongly consistent.
- Missing item → Reladomo’s normal “deleted underneath us” path (return null /
  throw per portal; do not Scan).
- `lockInDatabase`: DynamoDB has no SELECT FOR UPDATE. Treat as
  `consistentRead=true` only. **Rejected:** inventing a lock item.

`refreshDatedObject` uses the dated object’s current data (same GetItem).

### 6.6 `getForDateRange(data, start, end)`

Director API: all physical rectangles for **this logical identity** overlapping
the business interval. PK is complete from `data`. Purpose `DATE_RANGE`:

```text
Query PK = encoded(data)
Filter: business ranges overlap [start, end)
        -- from < end AND thru > start   (half-open; honor toIsInclusive)
No processing as-of: return every processing version (director needs history)
consistentRead = true
GSI forbidden
```

This is the path that must **not** be the interactive as-of filter; it is
intentionally broader.

`enrollDatedObject` is not a query plan (write/identity); out of scope here.

### 6.7 `deleteUsingOperation` / `findForMassDelete` / `deleteBatchUsingOperation`

1. Plan with `purpose=DELETE` (GSI forbidden; Scan requires **both**
   `allowTableScan` and `allowScanDelete` → else `PLAN-008`).
2. `findForMassDelete` returns the matching `MithraDataObject` list (same as
   find, no rowcount unless Reladomo passes one).
3. `deleteUsingOperation` executes the plan then `BatchWriteItem` deletes
   (25 per batch) or `TransactWriteItems` when inside a Mithra transaction
   (100-item cap; chunk + documented non-atomicity across chunks —
   OPEN QUESTION with the write-side design).
4. `prepareForMassDelete` / `prepareForMassPurge`: no SQL temp table. No-op
   beyond validating the plan is legal. **Rejected:** implementing them as
   unbounded Scans “because SQL did”.

### 6.8 `loadFullCache` / `reloadFullCache` / `renewCacheForOperation`

- Full load: parallel Scan (§3.3) with `purpose=CACHE_LOAD`.
- Renew: plan the `Operation` as FIND, replace that cache slice; Scan still
  gated.

### 6.9 `extractDatabaseIdentifiers`

Walk the operation for `SourceOperation` values; map to table/client via
`SourceRoutingSpec`. No DynamoDB call. Incomplete source on a sourced object
→ `PLAN-004` at plan time, not at identifier extraction (extraction returns
the set it found; planner enforces).

### 6.10 `computeFunction`

Always `PLAN-010`. There is no SQL engine.

---

## 7. Testability (`/tdd`)

### 7.1 Purity boundary

```text
                    no AWS
                       │
   Reladomo Operation ─► QueryPlanner.plan ─► QueryPlan
                       │                       │
                       │                       ▼
                       │              QueryPlanInterpreter.accepts(itemMap)
                       │                       │
                       └────── matches(obj) ───┴──► oracle equality

   QueryPlan + DynamoDbClient ─► QueryPlanExecutor ─► rows + QueryExplainPlan
                                    (integration / conformance only)
```

Unit tests construct `Operation` trees from **generated finders** (preferred)
or public constructors (`LongEqOperation`, `AsOfEqOperation`, `AndOperation`,
`OrOperation`, `MappedOperation`). They call `planner.plan(...)` and assert
on `QueryPlan`. DynamoDBLocal is **forbidden** in `reladynamo-plan` unit tests.

`QueryPlanInterpreter` is a pure evaluator of key condition + filter
expression + residual against an in-memory `Map<String, ExpressionValue>`
(same type the plan uses; **no** AWS SDK types). It is production-visible
(package `io.reladynamo.plan.eval`) so the oracle is the same language the
executor later translates to SDK expressions. Executor tests still run
against DynamoDBLocal in the conformance module to catch SDK expression
divergences.

### 7.2 Per-behaviour test map (RED before GREEN)

| Test name | Assert |
|---|---|
| `should_use_getitem_when_nondated_pk_is_complete` | `GET_ITEM`, PRIMARY |
| `should_use_current_asof_query_when_pk_and_infinity_processing` | `QUERY`, `fastPath=CURRENT_ASOF`, filter contains `outZ` |
| `should_not_put_business_asof_in_base_sk_condition` | `keyCondition` has no `#B#` range |
| `should_use_current_gsi_range_when_sparse_gsi_configured` | index name, SK `BETWEEN` |
| `should_forbid_gsi_when_in_transaction` | PRIMARY chosen |
| `should_fan_out_or_of_two_partition_keys` | `FANOUT` size 2, no SCAN |
| `should_reject_or_fan_out_past_limit` | `PLAN-002` exact message |
| `should_throw_scan_required_when_all_and_scans_disabled` | `PLAN-001` exact message |
| `should_scan_when_all_and_scans_enabled` | `SCAN`, segmentCount=4 |
| `should_encode_asof_infinity_as_to_column_equality` | filter `#outZ = :inf` |
| `should_encode_asof_half_open_containment` | `from <= :B AND thru > :B` |
| `should_not_set_dynamo_limit_when_filter_present` | `dynamoLimit() == null` |
| `should_use_native_order_when_orderby_is_sk` | `NATIVE_SK`, `scanIndexForward=false` if desc |
| `should_reject_in_memory_sort_above_ceiling` | `PLAN-007` |
| `should_plan_mapped_exists_as_semijoin` | child plan on related class |
| `should_reject_tuple_exists` | `PLAN-011` |
| `should_reject_compute_function` | `PLAN-010` |
| `should_plan_get_for_date_range_as_pk_query_without_processing_asof` | DATE_RANGE shape |
| `should_plan_refresh_as_getitem` | `GET_ITEM` |
| `should_require_allow_scan_delete_for_mass_delete_without_pk` | `PLAN-008` |

RED evidence is a failing assertion on `QueryPlan` (or exception message), not
a compile error.

### 7.3 Adversarial generator

Property tests (jqwik, Java 11) in `reladynamo-plan`:

```text
generator:
  pick a PhysicalDesign fixture (non-dated | business-only | bitemporal)
  build a random Operation tree of depth ≤ 5 from:
     eq/neq/in/not-in/gt/lt on PK attrs, payload attrs, as-of (eq to random
     timestamp or infinity), AND/OR, optional MappedOperation to a stub related
     finder with eq on FK
  shrink on tree size

oracle for each (tree, seed):
  plan ← planner.plan(tree) OR catch ReladynamoUnplannableOperationException
  if refused: PASS (refusal is allowed)
  else:
     items ← 32 synthetic Dynamo items covering:
        matching PK, wrong PK, matching as-of, adjacent boundary
        (from=asOf, thru=asOf, from=asOf+1ms), closed processing, current
     for item in items:
        expected ← reladomo Operation.matches(hydratedDataObject)
        actual   ← interpreter.accepts(plan, item)
        assert expected == actual
```

Invariant: **never silently return the wrong row**. A plan that over-selects
without a residual that would reject the extra row is a **failing property**,
not a warning. Under-select is also a fail.

Boundary instants (as-of = from, as-of = thru, as-of = infinity) are a
**named** example set in addition to the property, because shrinking may not
hit them.

Seed is printed on failure (`jqwik` default). Fixture Reladomo objects: the
smallest generated `Position`-like type from the conformance model, not a
hand-rolled fake `Operation` that `matches` disagrees with.

### 7.4 Conformance (H2 vs DynamoDB)

The shared suite asserts **row identity including four timestamps**, not
plans. Plan pinning lives in unit tests so a cost regression fails the build
**before** DynamoDBLocal. Executor tests (DynamoDBLocal) assert
`actualItemsExamined` for the current-row case so an accidental Scan fails CI.

---

## 8. Executor sketch (not the planner; shows SDK v2 + Java 11)

```java
package io.reladynamo.plan;

import java.util.Collections;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.Select;

public final class QueryPlanExecutor {
    private final DynamoDbClient client;

    public QueryPlanExecutor(DynamoDbClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
    }

    public QueryResponse queryOnce(QueryPlan plan, Map<String, AttributeValue> startKey) {
        QueryRequest.Builder b = QueryRequest.builder()
                .tableName(plan.tableName())
                .keyConditionExpression(plan.keyConditionExpression())
                .expressionAttributeNames(plan.expressionAttributeNames())
                .expressionAttributeValues(ExpressionValues.toSdk(plan.expressionAttributeValues()))
                .consistentRead(Boolean.valueOf(plan.consistentRead()))
                .scanIndexForward(Boolean.valueOf(plan.scanIndexForward()))
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
        if (!"PRIMARY".equals(plan.indexName())) {
            b.indexName(plan.indexName());
        }
        if (plan.filterExpression() != null) {
            b.filterExpression(plan.filterExpression());
        }
        if (plan.dynamoLimit() != null) {
            b.limit(plan.dynamoLimit());
        }
        if (startKey != null && !startKey.isEmpty()) {
            b.exclusiveStartKey(startKey);
        }
        return client.query(b.build());
    }
}
```

No `Stream.toList()`, no text blocks, no records. `Boolean.valueOf` keeps the
source obvious under `--release 11`.

---

## 9. Worked plans

**A. Non-dated `CustomerFinder.id().eq(9)`**  
`GET_ITEM` `pk=v1#CUSTOMER#9`, `sk=v1#ND`. Fast path `POINT_GET`.

**B. Bitemporal current row (fast path)**  
`QUERY PRIMARY` `pk=v1#POSITION#42#7`, filter
`#OUT_Z = :inf AND #FROM_Z <= :B AND #THRU_Z > :B`. Examined ~16, returned 1.

**C. `accountId.in(1,2,3)` complete with `productId.eq(7)`**  
`FANOUT` of three Queries (or GetItems if non-dated). Size 3 ≤ 100.

**D. `status.eq("OPEN")` only**  
No PK. `PLAN-001` unless `allowTableScan` or a sparse GSI on status exists.

**E. `email.eq("a@b")` with unique GSI / reservation item**  
GSI Query or `GetItem` on reservation PK `UQ#EMAIL#…` then base `GetItem`.
In-transaction: skip GSI; if no base PK, `PLAN-005` (cannot strongly read by
email without the uniqueness item — reservation items **are** base-table
GetItem and are legal in TX).

**F. `positions.exists()` via `MappedOperation`**  
Plan right (positions) → keys → `accountId.in(those)` on left. If right would
Scan, throw unless the **Position** mapping allows scans.

---

## 10. Alternatives rejected

| Topic | Rejected | Why |
|---|---|---|
| Silent Scan fallback | Convenience | Production cost death; hides missing GSIs |
| Business-first native SK range | Treat as-of B as `SK BETWEEN` | Suffix not contiguous under processing-major SK |
| `SK=CURRENT` item | GetItem for as-of | Director does not write that shape; double bitemporal |
| DynamoDB `Limit=rowcount` with as-of | Naive top-N | Limit applies before filter → wrong rows |
| GSI for everything | Alternate keys as default | Eventual consistency breaks TX and refresh |
| Filter-only planning | `matches()` after Scan | Opposite of this design |
| Parse `generateSql` | Avoid introspector | As-of SQL is in a different overload; dialect-shaped |
| Unbounded PK IN | Fan-out until AWS throttles | Cap 100 + loud refusal |
| Re-derive terminate/updateUntil in filters | “current row” heuristics | Violates architecture; H2 divergence |
| Records / sealed / text blocks | Modern Java | Java 11 floor |

---

## 11. OPEN QUESTIONS

1. **OPEN QUESTION:** Exact portal hook to detect “inside Mithra transaction”
   for `PlannerConfig.inTransaction` without relying on `MithraManager` thread
   locals leaking into the planner’s purity? Persister can pass a boolean;
   confirm 18.1.0 `MithraTransaction` visibility.

2. **OPEN QUESTION:** `findAggregatedData` projection — decode full items vs
   a `ProjectionExpression` of group-by/aggregate attributes only. Full items
   are simpler and match H2 (which reads rows); projection saves RCU on wide
   JSON. Recommend full items in v1 unless measured.

3. **OPEN QUESTION:** Chunked transactional delete (>100 items) atomicity.
   Reladomo SQL deletes can be one statement. DynamoDB cannot. Coordinate with
   the write-side design: fail closed above 100 in a TX vs documented
   multi-transaction delete.

4. **OPEN QUESTION:** Whether Reladomo ever emits `AsOfEq` on a **mapped**
   related object without a top-level as-of (relationship default as-of).
   Semi-join must copy those defaults; needs a generated dated relationship
   fixture in conformance.

5. **OPEN QUESTION:** `MultiInOperation` public value extraction in 18.1.0
   (tuple values). Introspector may need one more private field; spike in
   implementation with a generated multi-column IN test.

6. **OPEN QUESTION:** Default `estimatedVersionsPerKey=16` vs measuring real
   corpora (schema OPEN QUESTION 5). Costing only; correctness does not depend
   on it. Pin in tests so a default change is visible.

---

## 12. Implementation checklist (downstream)

- [ ] `ReladomoOperationAccess` + 18.1.0 self-check
- [ ] `QueryPlanner.plan` pure function + `PlannerConfig`
- [ ] Exception types `PLAN-001`…`011` with exact messages
- [ ] `QueryPlan` / interpreter / adversarial jqwik suite
- [ ] `QueryPlanExecutor` (SDK v2 Query/GetItem/Scan/BatchGet)
- [ ] `DynamoQueryCursor`
- [ ] Wire `MithraObjectReader.find/count/findCursor/...` to planner+executor
- [ ] Conformance: H2 vs DynamoDBLocal for current-as-of, OR-fan-out, scan-disabled

---

## Document control

| Field | Value |
|---|---|
| Deliverable | `agents/planner/output/planner.md` |
| Skills | reladomo-expert, dynamodb-architect, java-expert, tdd |
| Reladomo | 18.1.0 (`javap` + sources for `AsOfEqOperation`, `MithraObjectReader`, `AnalyzedOperation`) |
| Key grammar | schema design: `v1#P#<processingFrom>#B#<businessFrom>` |

---

# Reviewer notes (Claude, 2026-09-12)

Verified against `reladomo-18.1.0.jar` with `javap`. All cited APIs exist as described:
`AnalyzedOperation.getAnalyzedOperation()` / `getOriginalOperation()`,
`Operation.matches(Object)`, `Operation.zGetAsOfOp(AsOfAttribute)`,
`Operation.getResultObjectPortal()`.

Planning on the **analyzed** operation rather than the original is correct and load-bearing — the
analyzed form carries the as-of predicates Reladomo injects, and planning the original would silently
drop them.

## Gap found — must be fixed in implementation

**`Operation.matches(Object)` returns `java.lang.Boolean`, not `boolean`.**

```java
public abstract java.lang.Boolean matches(java.lang.Object);
```

Reladomo returns `null` for "cannot determine from this object alone". The design specifies
`matches()` for the residual in-memory filter but never addresses the tri-state, so the obvious
implementation

```java
if (op.matches(candidate)) { ... }   // NPE when matches() returns null
```

throws `NullPointerException` on unboxing, intermittently, only for predicate shapes that yield
`null`. That is a latent production defect, not a style point.

**Required rule:** treat `null` as **not a pass** for filtering purposes, and additionally assert that
a `null` never reaches the residual filter for a predicate the planner claimed it could evaluate in
memory — a `null` there means the planner mis-classified the node, which is a planner bug that must
fail loudly in tests rather than silently drop rows.

Add an explicit case to the adversarial fuzzer for operations whose `matches()` returns `null`.
