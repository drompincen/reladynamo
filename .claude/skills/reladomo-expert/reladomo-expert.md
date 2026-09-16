---
name: reladomo-expert
description: This skill guides design, implementation, review, debugging, and testing of Goldman Sachs Reladomo (formerly Mithra) Java ORM models, generated classes, runtime configuration, bitemporal behavior, finder queries, transactions, caches, and custom persistence SPI implementations; invoke it whenever work touches a `*MithraObject.xml`, `MithraRuntime` XML, dated object operation, Reladomo finder/list API, `MithraTestResource`, or non-SQL persister.
user-invocable: true
---

# Reladomo expert

Reladomo derives identity, queries, caches, persistence, and temporal rewrites from metadata; diagnose from object XML, generated metadata, runtime XML, then physical rows.

## Object model XML and generation

### Rules

1. **Define the durable relational contract in `*MithraObject.xml`, because the generator derives every typed persistence surface from that metadata.**
   Put `objectType`, `packageName`, `className`, and `defaultTable` on `MithraObject`.
   Declare each column with `Attribute name="..." javaType="..." columnName="..."`.
   Use `primaryKey="true"` on every identity component; include source attributes when identity is source-scoped.
   Treat XML changes as schema/API changes: regenerate, compile, migrate DDL, and run temporal row assertions.

2. **Model nullability, precision, and update behavior explicitly, because Java defaults cannot distinguish absent database values from real zero values.**
   Use `nullable="true"` only when SQL `NULL` is part of the domain.
   Specify `precision` and `scale` for `BigDecimal`; do not let database rounding define business behavior.
   Use `readOnly="true"` for columns Reladomo must read but never update.
   Map optimistic-lock/version attributes consistently with the database trigger or application update path.

3. **Choose primary keys as stable logical identity, because caches, portals, relationships, and update predicates all key through the generated primary-key attributes.**
   A dated object's full physical key also includes its temporal from columns.
   Do not put mutable business fields in the logical primary key.
   A missing key component causes cache aliasing or updates against more rows than intended.
   Verify compound-key equality with a lookup for two rows differing in only one component.

4. **Declare each as-of dimension with its physical boundary pair, because Reladomo selects rows by half-open temporal containment.**
   Typical mapping: `businessDate` uses `BUSINESS_DATE_FROM` and `BUSINESS_DATE_THRU`.
   Typical mapping: `processingDate` uses `IN_Z` and `OUT_Z`.
   The containment rule is `from <= asOf && asOf < thru`; the upper boundary is exclusive.
   Keep column names, infinity values, and temporal types aligned with the database DDL.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<MithraObject objectType="transactional" packageName="com.acme.domain"
             className="Position" defaultTable="POSITION">
    <Attribute name="accountId" javaType="long" columnName="ACCOUNT_ID" primaryKey="true"/>
    <Attribute name="productId" javaType="int" columnName="PRODUCT_ID" primaryKey="true"/>
    <Attribute name="quantity" javaType="double" columnName="QUANTITY"/>
    <AsOfAttribute name="businessDate" javaType="Timestamp"
                   fromColumnName="BUSINESS_DATE_FROM" toColumnName="BUSINESS_DATE_THRU"
                   infinityDate="9999-12-01 23:59:00.0"/>
    <AsOfAttribute name="processingDate" javaType="Timestamp"
                   fromColumnName="IN_Z" toColumnName="OUT_Z"
                   infinityDate="9999-12-01 23:59:00.0"/>
</MithraObject>
```

5. **Express relationships as typed joins in XML, because the generator uses them for navigation, joins, dependency order, and deep fetch.**
   Set `relatedObject`, `name`, `cardinality`, and a reverse relationship where navigation is bidirectional.
   Write the join in Reladomo relationship syntax using source and target attributes, not SQL column strings.
   Make ownership and dependent-delete behavior explicit; cardinality does not imply lifecycle ownership.
   Test a relationship with different source values and temporal dates, where accidental joins are easiest to expose.

6. **Regenerate all derived types after metadata changes, because their implementations embed attributes, finders, serialization, cache indexes, and SQL mappings.**
   `PositionAbstract` contains generated state access, setters, temporal operations, and framework hooks.
   `PositionFinder` exposes typed attributes, relationships, `findOne`, `findMany`, and portal metadata.
   `PositionList` adds typed list, ordering, deep-fetch, and bulk behavior.
   `PositionData` carries column values; `PositionDatabaseObject` implements relational read/write SQL behavior.
   Exact generated names vary with object type and generator configuration; inspect generated output before citing one.

7. **Put domain behavior only in hand-written extension classes, because regeneration owns and replaces the abstract, finder, list, data, and database-object outputs.**
   Hand-write `Position extends PositionAbstract` and add invariants or convenience methods there.
   Hand-write configured list subclasses only when the generator template expects them.
   Never patch `*Abstract`, `*Finder`, generated `*List`, `*Data`, or `*DatabaseObject` files.
   Fix XML or templates and regenerate; a generated-file patch disappears and leaves environments inconsistent.

## Runtime configuration and portals

### Rules

8. **Bind each generated finder through `MithraRuntime`, because a portal needs a cache, object factory, reader, and persister before any finder can execute.**
   Runtime entries name the business class and select relational or pure-object construction.
   A connection manager resolves database/source identifiers to JDBC connections for relational objects.
   The generated finder reaches its singleton `MithraObjectPortal`; the portal delegates reads and writes to its configured persistence components.
   Fail startup on missing mappings rather than discovering an uninitialized portal on first traffic.

9. **Choose cache mode from the complete access contract, because finder evaluation and database round trips change materially across full, partial, and no cache.**
   Full cache loads every row and can answer supported operations in memory; size it for all versions, not logical objects.
   Partial cache retains fetched objects and primary-key/query indexes but may query the store on misses.
   No cache minimizes retained domain state but forfeits identity reuse and in-memory query benefits.
   Monitor heap, initial load time, hit rate, eviction/refresh behavior, and notification lag before changing modes.

10. **Configure source-aware connection managers consistently, because the source attribute participates in routing as well as logical identity.**
   Every source value returned by the connection manager must map to the correct schema and timezone assumptions.
   Include the source predicate in finder operations when the model requires it.
   A correct primary key sent to the wrong source is still the wrong row.
   Test two sources containing the same logical key and different values.

11. **Use `MithraPureObjectFactory` only for non-relational pure objects, because it supplies full-cache data without a JDBC `*DatabaseObject`.**
   The 18.1.0 interface declares `setFactoryParameter(String)`, `loadFullCache()`, and `reloadFullCache()`.
   The runtime passes the configured parameter and asks the factory to populate or repopulate the portal cache.
   Pure objects still use generated finders and portal identity semantics.
   Do not mistake “pure” for a general durable store adapter; define durability and refresh behavior explicitly.

12. **Trace portal-to-persister binding before debugging SQL, because the same finder API can terminate in JDBC, a pure cache, or a custom SPI implementation.**
   Confirm the runtime class entry, finder portal, object factory/deserializer, reader, and persister instance.
   `MithraObjectReader` owns query, count, refresh, aggregate, and cache-loading entry points.
   `MithraObjectPersister` extends that reader with update, insert, delete, purge, and batch operations.
   A wrong runtime entry can produce valid objects backed by the wrong store behavior.

## Bitemporal modelling

### Rules

13. **Separate business time from processing time, because “when true” and “when learned” answer different historical questions.**
   `businessDate` selects the fact effective at a real-world instant.
   `processingDate` selects the database belief visible at a system/audit instant.
   A late correction changes processing history while targeting an earlier business interval.
   Query both dimensions explicitly when reproducing an earlier report.

14. **Read every bitemporal row as a rectangle, because one row covers a half-open business interval crossed with a half-open processing interval.**
   Physical columns are `(businessFrom, businessThru, processingIn, processingOut)`.
   A row is visible when both `businessFrom <= B < businessThru` and `processingIn <= P < processingOut`.
   Adjacent intervals meet exactly at a boundary without overlap.
   Dump all four timestamps plus logical keys when diagnosing a temporal mutation.

15. **Treat infinity as the configured `Timestamp` sentinel, because the database stores a finite value rather than mathematical infinity.**
   Obtain or compare against the model's generated/configured infinity value, not a newly guessed date.
   Use `Timestamp.equals` or millisecond/nanosecond comparison only after normalizing database precision.
   Never compare `Timestamp` references with `==`.
   Do not substitute `null`; `null` and the infinity sentinel have different query semantics.

16. **Select the director that matches the declared dimensions, because each director rewrites a different set of temporal boundaries.**
   `AuditOnlyTemporalDirector` manages processing-time history for audit-only objects.
   `GenericBiTemporalDirector` manages both business and processing dimensions.
   `GenericNonAuditedTemporalDirector` manages business-time segments without retaining processing-time audit history.
   Do not call business-until operations on an audit-only model and expect a synthetic business axis.

## Temporal operation row-set semantics

The following rules describe the logical effect. Under `GenericBiTemporalDirector`, a correction
first closes superseded current-processing rows at the transaction's processing time and inserts
replacement current-processing rows; unchanged fragments may be copied so the business timeline
remains contiguous. Non-audited models split/update rows directly because no processing history is
retained.

### Rules

17. **Use `insert` to create existence from the object's business date onward, because it adds a current row whose business and processing upper bounds are infinity.**
   Bitemporal shape: `[businessDate, ∞) × [now, ∞)` for a new logical identity.
   It does not mean “append a duplicate physical row” into an already occupied current rectangle.
   Audit-only shape has only `[processingNow, ∞)`.
   Verify uniqueness across logical key plus active temporal ranges.

18. **Use `terminate` to end business existence at the object's business date, because later business-date queries must no longer see a current fact.**
   For bitemporal data, close the superseded processing slice at `now` and preserve it as history.
   Write replacement current knowledge only for business time before the termination boundary.
   For business-only data, shorten/delete affected segments without creating processing audit versions.
   Termination is logical temporal history, not physical deletion.

19. **Use `insertUntil(exclusiveUntil)` to create only a bounded business segment, because visibility must stop before the supplied upper boundary.**
   New logical interval: `[object.businessDate, exclusiveUntil)`.
   Under bitemporality its current processing interval begins at `now` and ends at infinity.
   Preserve any valid timeline strictly at or after `exclusiveUntil`.
   Reject `exclusiveUntil <= businessDate` before entering the transaction.

20. **Use `terminateUntil(exclusiveUntil)` to remove existence only inside a bounded business interval, because facts after the upper boundary must survive.**
   Removed interval: `[object.businessDate, exclusiveUntil)`.
   Preserve or recreate the prefix before the object date and the suffix from `exclusiveUntil` onward.
   Under bitemporality, close affected current-processing rows and write the corrected surviving slices at `now`.
   This is not equivalent to `terminate`; `terminate` has no surviving future suffix.

21. **Use `updateUntil(exclusiveUntil)` to replace values only inside a bounded business interval, because values before and after that interval remain independently valid.**
   Changed interval: `[object.businessDate, exclusiveUntil)`.
   Split at both boundaries when either falls inside an existing segment.
   Under bitemporality, retain old rectangles at closed processing intervals and create replacement rectangles at `now`.
   Adjacent equal-valued segments may be consolidated by framework behavior; assert visibility, not row count alone.

22. **Use `incrementUntil(exclusiveUntil)` for an atomic numeric delta over a bounded business interval, because read-modify-write in application code loses concurrent increments.**
   Delta interval: `[object.businessDate, exclusiveUntil)`.
   Preserve values outside the interval and split temporal ranges as required.
   In 18.1.0 the temporal SPI receives an `AttributeUpdateWrapper`, commonly a numeric increment wrapper.
   Execute inside a transaction; persisted no-transaction behavior rejects `incrementUntil`.

23. **Use ordinary generated setters for effective-forward correction, because the temporal director applies an update from the object's business date through infinity.**
   A setter on a bitemporal object is not necessarily one SQL `UPDATE`.
   It may close old processing rows, split business segments, and insert replacements.
   Use the generated `...Until` setter only when the change has an exclusive business end.
   Inspect rows after corrections that cross pre-existing future segments.

24. **Reserve `inPlaceUpdate` for explicit history repair, because it changes stored data without creating the normal processing-time audit version.**
   It mutates the enrolled physical/current data through an `AttributeUpdateWrapper`.
   Use only for controlled repair or recovery where rewriting history is intentional.
   Record operator, reason, affected keys, and before/after row images outside the overwritten history.
   Never use it as a performance shortcut for normal bitemporal updates.

25. **Use `purge` only to physically erase persisted versions, because it removes history rather than expressing a temporal end.**
   Purge targets physical data for the logical object/version set selected by framework behavior.
   It bypasses the business meaning of termination and defeats audit reconstruction.
   Restrict it to test cleanup, retention enforcement, or approved repair workflows.
   Confirm cache invalidation and dependent-row handling in the same transaction.

26. **Use `inactivateForArchiving(processingDateTo, businessDateTo)` only in archival workflows, because it writes supplied terminal boundaries instead of normal effective-now semantics.**
   The SPI passes both explicit timestamps to the director.
   It makes rows inactive for live queries while preparing them for archival movement.
   Validate both supplied bounds against the row's from/in values and configured infinity.
   Do not substitute this for `terminate`; caller-selected boundaries can rewrite the audit story.

## Finder API

### Rules

27. **Build queries from generated attributes and `Operation.and` or `Operation.or`, because typed operations preserve mapping, temporal, source, cache, and SQL semantics.**
   `Operation` in 18.1.0 declares `and(Operation)`, `or(Operation)`, and `matches(Object)` among its API.
   Parenthesize composition through variables; do not reconstruct precedence mentally in one long expression.
   Include source and every required as-of predicate.
   Avoid raw SQL escape hatches unless the finder cannot express the operation.

```java
import com.gs.fw.common.mithra.finder.Operation;
import java.sql.Timestamp;

public final class PositionQueries {
    private PositionQueries() {}

    public static Position find(long accountId, int productId, Timestamp businessDate) {
        Operation key = PositionFinder.accountId().eq(accountId)
                .and(PositionFinder.productId().eq(productId));
        Operation dated = key.and(PositionFinder.businessDate().eq(businessDate));
        return PositionFinder.findOne(dated);
    }
}
```

28. **Choose `findOne` only when metadata and predicates guarantee at most one result, because duplicate matches are a data/model failure rather than a list-selection problem.**
   Use `findOne` for a complete logical key plus required source/as-of constraints.
   Use `findMany` for collections, ranges, joins, and intentionally non-unique predicates.
   Do not call `findMany(...).get(0)` to suppress duplicate or missing-key defects.
   Assert duplicate behavior in tests after changing keys or temporal metadata.

29. **Add deep-fetch paths before resolving relationship graphs, because lazy navigation across a list silently becomes one query per parent or relationship level.**
   Call the generated list's `deepFetch(RelationshipFinder)` before iteration or resolution.
   Deep-fetch each path the response will traverse; nested paths require nested relationship finders.
   Reuse named fetch-profile methods for stable endpoint/report shapes.
   Verify SQL/query counts, not only elapsed time, because a warm cache can hide N+1 in tests.

```java
PositionList positions = PositionFinder.findMany(PositionFinder.accountId().eq(42L));
positions.deepFetch(PositionFinder.product());
positions.setOrderBy(PositionFinder.productId().ascendingOrderBy());
positions.forceResolve();
for (Position position : positions) {
    position.getProduct().getDescription();
}
```

30. **Apply generated `OrderBy` objects before list resolution, because database/cache ordering is deterministic only when the query carries an explicit ordering contract.**
   Chain orderings with the generated order-by API and finish with a unique tie-breaker.
   Do not rely on primary-key, insertion, cache, or SQL plan order.
   Set ordering before `forceResolve()` or iteration.
   Use the generated attribute's `ascendingOrderBy()` or `descendingOrderBy()`.

31. **Use Reladomo aggregation APIs for grouped work, because loading entities and reducing in Java wastes rows, heap, and cache churn.**
   Define aggregate attributes and group-by attributes with stable output names.
   Apply `HavingOperation` only after understanding group semantics; ordinary operations filter input rows.
   `MithraObjectReader.findAggregatedData` accepts operation, aggregate map, group-by map, having, bypass-cache, and bean class.
   Test empty groups, null inputs, decimal scale, and temporal predicates.

## Transactions and concurrency

### Rules

32. **Wrap one business unit in `MithraManager.executeTransactionalCommand`, because temporal rewrites and cache enrollment must commit or roll back together.**
   `TransactionalCommand<R>.executeTransaction(MithraTransaction tx) throws Throwable` returns the command result.
   Throw from the command to roll back; a swallowed exception permits commit.
   Nested `startOrContinueTransaction` joins the parent; nested commit does nothing and nested rollback rolls back the whole transaction.
   Keep remote calls and unbounded iteration outside the transaction unless atomicity requires them.

```java
import com.gs.fw.common.mithra.MithraManagerProvider;
import java.sql.Timestamp;

public final class PositionService {
    public void addUntil(Position position, double delta, Timestamp until) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
            position.incrementQuantityUntil(delta, until);
            return null;
        });
    }
}
```

33. **Set retry count around an idempotent transactional command, because Reladomo may re-execute the entire callback after a retriable database failure.**
   18.1.0 provides `executeTransactionalCommand(TransactionalCommand<R>, int retryCount)`.
   It also accepts `TransactionStyle`; style carries timeout and retry policy.
   Do not emit email, publish messages, or call non-idempotent services inside a retryable callback without an outbox/idempotency key.
   Re-read objects inside the callback; captured mutable instances may reflect an earlier attempt.

34. **Treat optimistic-lock failures as concurrent-write signals, because generated update predicates can include version or prior-value checks.**
   Confirm the object XML's optimistic-lock strategy and mapped version attribute.
   Retry only after rebuilding the command from authoritative inputs.
   Never convert an optimistic-lock exception into unconditional overwrite.
   Test two transactions updating the same logical key and business interval.

35. **Bound transaction scope to the required row set, because dated updates can lock and rewrite several physical rows for one logical object.**
   A single `updateUntil` may touch prefix, changed interval, suffix, and processing-history rows.
   Establish a stable object/key ordering when updating many identities.
   Set a measured timeout via `TransactionStyle`, not an arbitrary production-long default.
   Log logical keys and temporal bounds on deadlock or timeout.

## Persistence SPI

### Rules

36. **Implement `MithraObjectReader` as the query boundary, because portals delegate retrieval, count, refresh, aggregation, and cache loading through it.**
   Required surfaces include `find`, `findCursor`, `count`, `refresh`, `refreshDatedObject`, and `findAggregatedData`.
   Honor `OrderBy`, row count, bypass-cache, source routing, post-load filters, and implicit-join flags.
   Return `MithraDataObject` values compatible with generated metadata and cache identity.
   An adapter that implements key lookup only is not a complete reader.

37. **Implement `MithraObjectPersister` as plain physical persistence, because the temporal director has already translated domain intent into data-object mutations.**
   Exact core signatures include `insert(MithraDataObject)`, `delete(MithraDataObject)`, and `purge(MithraDataObject)`.
   Updates arrive as `update(MithraTransactionalObject, AttributeUpdateWrapper)` or a list of wrappers.
   Implement batch insert/delete/purge, operation deletes, mass-delete preparation, and transaction participation as required by workloads.
   Preserve affected-row and optimistic-lock expectations rather than silently succeeding on zero rows.

38. **Implement `MithraDatedObjectPersister` only for dated storage support, because it adds enrollment and range retrieval to the ordinary persister contract.**
   18.1.0 declares `List getForDateRange(MithraDataObject, Timestamp start, Timestamp end)`.
   It also declares `MithraDataObject enrollDatedObject(MithraDatedTransactionalObject)`.
   Return all physical data needed by the director to split and rewrite the target interval.
   Preserve exact from/thru and in/out values; truncation changes rectangle membership.

39. **Keep temporal semantics above the custom persister, because `TemporalDirector` owns insert, update, termination, splitting, incrementing, and archival decisions.**
   The director API receives the dated object, `TemporalContainer`, update wrapper, and any exclusive-until boundary.
   A custom store persists the resulting dated `MithraDataObject` inserts, updates, and deletes.
   Do not independently reimplement bitemporal splitting in the adapter or it will double-apply history changes.
   This layering lets a non-SQL store inherit Reladomo bitemporality when it honors physical mutation and transaction contracts.

40. **Study `PureMithraObjectPersister` as the reference non-SQL portal implementation, because it demonstrates the same reader/persister interfaces without a JDBC database object.**
   In 18.1.0 it implements `MithraObjectPersister`, `MithraDatedObjectPersister`, and `MithraTuplePersister`.
   Its write methods are no-ops and many query/range methods throw “not implemented”; its role is pure full-cache support, not durable storage.
   `loadFullCache()` and `reloadFullCache()` delegate to the portal's `MithraPureObjectFactory`.
   Copy the interface boundary, not those no-op durability semantics, when building a real custom store.

41. **Make custom persistence transaction-aware before claiming bitemporal correctness, because one logical mutation expands into multiple physical writes that must be atomic.**
   Stage all physical mutations until Mithra transaction commit and discard them on rollback.
   Provide repeatable reads or equivalent conflict detection for the temporal container's enrolled rows.
   Map optimistic conflicts and transient failures into exceptions Reladomo can classify correctly.
   Invalidate or publish cache changes only after durable commit.

## Testing

### Rules

42. **Initialize integration tests with `MithraTestResource`, because it wires runtime XML, databases, test data, and teardown into one repeatable lifecycle.**
   Add the runtime configuration used by the test before setup.
   Register test classes and their data files, then call setup in the fixture lifecycle.
   Tear down every resource to clear portals, transactions, connections, and static cache state.
   Keep tests isolated from developer-local schemas and clocks.

43. **Run relational tests against H2 with production-equivalent temporal DDL, because simplified keys or timestamp columns conceal the failures that matter.**
   Create logical-key plus temporal-from uniqueness exactly as production requires.
   Use `TIMESTAMP` precision compatible with the production database and infinity sentinel.
   Assert physical rows with SQL after each temporal operation, not only finder-visible objects.
   Add dialect-specific tests when production locking, precision, or generated SQL differs from H2.

44. **Store deterministic fixtures in Reladomo `.txt` test-data files, because `MithraTestResource` can load object rows without bespoke setup code.**
   Keep headers aligned with generated attribute names and include every temporal boundary column needed by the loader format.
   Include current infinity rows, closed processing rows, adjacent business intervals, and multiple sources.
   Use exact timestamp literals; never depend on the machine timezone.
   Give each test the smallest fixture that still exposes the target row topology.

45. **Assert temporal operations as before-and-after rectangle sets, because object-level assertions miss lost audit rows and accidental overlaps.**
   Assert logical key, values, business from/thru, and processing in/out for every physical row.
   Query at the instant before, exactly at, and immediately after each boundary.
   Freeze or capture processing time so expected `in/out` values are deterministic.
   Assert no overlapping current rectangles for the same logical identity.

## Common pitfalls

### Rules

46. **Count executed queries when traversing relationships, because a warm partial cache can hide a silent N+1 until production.**
   Symptom: latency and database calls grow linearly with parent-list size.
   Cause: relationship getters resolve during iteration without a matching deep-fetch path.
   Fix: deep-fetch the complete graph before resolution and assert a bounded query count.

47. **Keep all dependent mutations inside one transactional command, because a temporal setter outside the intended scope can commit separately or be rejected.**
   Symptom: half-applied business changes, “outside transaction” exceptions, or stale objects after rollback.
   Cause: transaction boundaries live in service code, callbacks, or threads that do not share context.
   Fix: make one command own lookup, validation, dated mutation, and dependent writes.

48. **Compare infinity by value and database precision, because `Timestamp` object identity and guessed sentinel dates are not temporal equality.**
   Symptom: current rows are classified as closed, or active-row predicates return nothing.
   Cause: `==`, `null`, `LocalDate.MAX`, or a sentinel differing by precision/timezone.
   Fix: use generated/configured infinity and normalized `Timestamp.equals` semantics.

49. **Design cache refresh and invalidation with every writer, because direct database or external-store writes bypass portal coherence.**
   Symptom: finders return old values after the physical store is correct.
   Cause: stale full cache, cached partial result, notification lag, or a custom persister publishing before commit.
   Fix: write through Reladomo or issue post-commit invalidation/reload with measurable lag.

50. **Change generator inputs instead of generated Java, because regeneration overwrites local fixes and creates classpath-dependent behavior.**
   Symptom: a bug returns after build, or CI differs from a developer checkout.
   Cause: edited `*Abstract`, `*Finder`, `*List`, `*Data`, or `*DatabaseObject` output.
   Fix: patch `*MithraObject.xml`, generator configuration, or the supported hand-written subclass and regenerate.

51. **Include every identity, source, and as-of constraint in finder operations, because an apparently unique business key can select multiple temporal or source rows.**
   Symptom: `findOne` reports multiple matches or returns a row from the wrong database.
   Cause: missing compound-key, source, `businessDate().eq(...)`, or processing-date predicate.
   Fix: centralize complete-key finder builders and test duplicate keys across dates and sources.

52. **Inspect physical row sets after every until-operation, because `until` is exclusive and preserves a suffix rather than truncating all future history.**
   Symptom: future values vanish, boundary instants return the wrong segment, or overlapping current rows appear.
   Cause: treating `terminateUntil` as `terminate`, or treating `updateUntil` as an inclusive end.
   Fix: assert prefix, target `[from, until)`, suffix, and closed processing-history rectangles.

## Review checklist

- Verify XML, generated output, DDL, runtime portal bindings, cache mode, source routing, and infinity agree.
- Verify every temporal mutation by complete rectangles and every list by deterministic order and bounded query count.
- Verify transactions are atomic and retry-safe; keep `TemporalDirector` as policy above physical persister mutations.
- Verify H2 fixtures cover infinity, closed audit history, splits, exclusive boundaries, and multiple sources.

## Field notes — behaviour verified against reladomo 18.1.0, not read from documentation

These were established by building an adapter against the persister SPI and diffing every result
against H2. Each cost real debugging time; several contradict what the API names suggest.

### Infinity is timezone-dependent, and that will bite you

`DefaultInfinityTimestamp.getDefaultInfinity()` is `9999-12-01 23:59:00` **in the JVM default
timezone**, not UTC. Any code that reconstructs infinity as a UTC constant agrees with Reladomo only
on a UTC machine.

The failure mode is silent and total: Reladomo's `AsOfEqOperation` special-cases infinity (because
`from <= inf AND to > inf` is empty when `to` is also infinity), and an equality check against the
wrong sentinel skips that special case — so **current-row queries return zero rows**, not wrong rows.

**Always read infinity from the generated `AsOfAttribute.getInfinityDate()`.** Never reconstruct it.
Note that `infinityDate="[com.gs...getDefaultInfinity()]"` in the XML is a *Java expression*, so any
tool parsing the XML without classloading cannot evaluate it.

### The dated write path hands you a wrapper, not a data object

On the dated path, `MithraObjectPersister` batch methods receive
`InTransactionDatedTransactionalObject`, not `MithraDataObject`. Casting directly throws
`ClassCastException`.

Worse, that wrapper holds **two** data objects. `zGetCurrentData()` may return the unpopulated one —
symptom: *"primary key attribute X cannot be null"*. Use **`zGetTxDataForRead()`**, which is the
version the transaction is actually writing.

**`insert` does not go through the wrapper; `update` and `terminate` do.** A write path validated
only by insert is not validated.

### The generated API differs per temporal flavour — at code-generation time

Reladomo decides this in the generator, which is a stronger guarantee than a runtime check and a
nastier surprise if you assumed otherwise:

| Operation | Bitemporal | Audit-only | Non-audited |
|---|---|---|---|
| `insertUntil` / `terminateUntil` | yes | **generated stubs that throw** | yes |
| `setXUntil` (updateUntil) | yes | **not generated** | yes |
| `incrementX`, `incrementXUntil` | yes | **not generated** | yes |
| `insertWithIncrement(Until)` | yes | **not generated** | yes |
| `inPlaceUpdate` | yes | yes | **rejected by `reladomogen`** — the build fails |

Assert these with **declared** methods (`getDeclaredMethods`), not `getMethods()`, which includes
inherited ones and will happily confirm a method the class never declared.

### `inPlaceUpdate` needs the generated method, not a plain setter

A plain `setNote(...)` is an ordinary dated update and creates a new processing version. Reladomo
emits **`setNoteUsingInPlaceUpdate`** when the XML attribute declares `inPlaceUpdate="true"`, and only
that method reaches `TemporalDirector.inPlaceUpdate`.

### Zero-length segments: `insertForRecovery` refuses, `insertUntil` does not

`insertForRecovery` calls `checkDatesAreWithinRange`, and `dataMatches` is half-open — so a
`from == to` rectangle matches **no** as-of date, including its own, and Reladomo raises *"business
date must be valid for to and from business dates"*.

`insertUntil` does **not** range-check. `insertUntil(from)` stores a degenerate `[from, from)` row
that `equalsEdgePoint()` can see and no as-of query ever will. Asymmetric by design.

### An unbounded `setX` ranges `[asOf, infinity)`

`GenericBiTemporalDirector.update` ranges to infinity, so a correction dated *earlier* than an
existing later segment **inactivates that later segment too**. A March write after a June update makes
June read as the March value; June survives only as history.

### "A non-audited correction destroys history" is too broad

The distinction is the date you correct at:

- correcting the **same** open business segment → prior value destroyed, one physical row;
- setting a value at a **later** business date → the timeline splits and the prior value survives,
  because it is still true for the earlier period.

Non-audited storage loses prior **beliefs**, not prior **business time**.

### Processing timestamps are clamped to 10ms

`createProcessingTimestamp` clamps via `/10*10`. Tests that write twice within the same 10ms window
and expect distinct processing versions will behave surprisingly.

### Fixtures that are more correct than production hide production's bugs

A test fixture that sets infinity from Reladomo's own sentinel will never reproduce the timezone bug
above — the two values agree in the fixture and disagree in production. Build fixtures the way
production builds them, or the suite tests a system you do not ship.
