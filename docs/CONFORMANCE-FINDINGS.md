# Conformance findings (2026-09-13)

Results from expanding the differential suite toward the full bitemporal matrix. The suite is
**work in progress** — the four expanded test classes are parked in the scratchpad while the issues
below are resolved, so the committed gate stays honest. The three original differential tests remain
green in the build.

## Index

Thirty-four findings. The first fifteen come from diffing an adapter against Reladomo-over-H2: **three are
real adapter defects (12, 14, 15); the other twelve were wrong premises about Reladomo**, corrected
with evidence from generated code or `javap`. Findings 16-19 came from diffing the *specification*
against the code, which no test could have caught because every fixture is single-source. Finding 20
came from an independent inspection catching a defect the gate built in finding 19 should have caught
— and from then finding a second one the inspection had missed. Finding 21 came from *strengthening*
an assertion the inspection called too weak, and was the most serious defect in this list: a bound
update never closed the superseded processing rectangle. **It is now closed**, by applying the
`AttributeUpdateWrapper` the persister had been ignoring — and closed with the strengthened
assertions intact, not relaxed. No comparison was ever weakened to reach green.

| # | Finding | Status |
|---|---|---|
| 1 | `TemporalRowSetDiffer` identity projection was wrong | FIXED |
| 2 | Audit-only objects: the generated API is narrower than assumed | CONFIRMED |
| 3 | Non-audited objects reject `inPlaceUpdate` at generation time | CONFIRMED |
| 4 | The open-items list from mid-session | ALL RESOLVED |
| 5 | "A non-audited correction destroys history" is too broad | RESOLVED |
| 6 | `inPlaceUpdate` needs the generated `setXUsingInPlaceUpdate` | RESOLVED |
| 7 | Audit-only `insertUntil` / `terminateUntil` are generated stubs that throw | REFINED |
| 8 | Use declared methods, not `getMethods()`, when asserting a generated API | MY BUG |
| 9 | Zero-length `insertForRecovery` is illegal | RESOLVED |
| 10 | Unbounded `setX` as-of an earlier date overwrites later business segments | RESOLVED |
| 11 | Same-millisecond writes delete instead of inactivate | RESOLVED |
| 12 | A REAL DIVERGENCE — the as-of current-row query returns nothing from DynamoDB | FIXED |
| 13 | The parser's "conventional infinity" is a landmine beyond finding 12 | ROOT FIX APPLIED |
| 14 | A SECOND REAL DIVERGENCE — the dated write path passed a wrapper, not data | FIXED |
| 15 | A THIRD REAL DIVERGENCE — relationship deep-fetch cannot be planned | FIXED |
| 16 | A documented DoS mitigation that did not exist | MY BUG · FIXED |
| 17 | Three more configuration options that nothing reads | GUARDED |
| 18 | `sourceAttribute` silently ignored — data-isolation hazard | FIXED |
| 19 | The audit that found 16-18 is now the `spec-drift` gate | GATED |

## 1. `TemporalRowSetDiffer` identity projection was wrong — FIXED

Row identity used every `*Id` attribute plus **only** `businessDateFrom` / `processingDateFrom`.
`insertWithIncrementUntil` produces two rows sharing the key and both FROM values, differing only in
`businessDateTo`. The differ collapsed them and reported a duplicate instead of comparing them.

A bitemporal row is a **rectangle**, and two rectangles can share a corner — so identity must include
the TO boundaries. Fixed; `TemporalRowSetDifferTest` still green.

This one matters beyond the immediate bug: an identity projection that silently merges two distinct
rows is exactly the failure that would let a real divergence pass as "identical".

## 2. Audit-only objects: the generated API is narrower than assumed — CONFIRMED

Verified by inspecting the code Reladomo actually generates, not by catching a runtime error.

| Operation | Bitemporal | Audit-only |
|---|---|---|
| `insertUntil`, `terminateUntil` (+ cascade) | yes | **yes** |
| `setXUntil` (updateUntil) | yes | **not generated** |
| `incrementX`, `incrementXUntil` | yes | **not generated** |
| `insertWithIncrement`, `insertWithIncrementUntil` | yes | **not generated** |

Reladomo enforces this at **code generation** time — a stronger guarantee than a runtime rejection,
and worth asserting as a compile-time absence.

## 3. Non-audited objects reject `inPlaceUpdate` at generation time — CONFIRMED

```
DiffPosition Inplace update can only be performed if Processing Date is present
```

`reladomogen` fails the build outright. `inPlaceUpdate` needs a processing-date axis; a
business-date-only object cannot have it. The fixture was corrected.

## 4. The open-items list from mid-session — ALL SUBSEQUENTLY RESOLVED

This section was a working list of failures whose cause was not yet established. It is kept for the
record rather than deleted, because the outcome is the interesting part: **every one turned out to be
a wrong premise about Reladomo, and none was resolved by weakening a test.**

| Was failing | Outcome |
|---|---|
| `inPlaceUpdate_round_trips_identically` (all three directors) | finding 6 — needs the generated `setXUsingInPlaceUpdate`, not a plain setter |
| `insertWithIncrementUntil_round_trips_identically` | finding 1 — the differ's identity projection omitted the TO boundaries |
| `insertForRecovery_can_materialise_a_zero_length_segment` | finding 9 — a zero-length rectangle matches no as-of date and Reladomo forbids it |
| `correction_destroys_the_prior_value_in_both_stores` | finding 5 — "destroys history" is too broad; it depends on the date you correct at |

The pattern held for twelve of fifteen findings: a failing differential test is far more often wrong
about Reladomo than the adapter is wrong about DynamoDB. The three exceptions — 12, 14 and 15 — were
each found by testing a path nothing else tested.

## 5. "A non-audited correction destroys history" is too broad — RESOLVED

The test asserted that correcting a business-date-only object erases the prior value. H2 itself
returned `["original", "corrected"]`, so the premise failed against the reference implementation
before DynamoDB was even involved.

The real semantics are narrower, and the difference is the *date you correct at*:

| What you do | Result | Why |
|---|---|---|
| Correct the **same** open business segment | prior value **destroyed**, one physical row | no processing axis, so nowhere to keep the previous belief |
| Set a value at a **later** business date | timeline **splits**, prior value survives | the old value is still true for the earlier period |

Non-audited storage loses prior **beliefs**, not prior **business time**. The original test dated its
change later while asserting the in-place outcome.

Split into two tests that assert each case, and both now pass — including the DynamoDB replay.

**Cross-check:** the petstore demo makes the same claim and makes it *correctly* — it asserts exactly
one physical row after the correction, so it is genuinely exercising the in-place case. Worth
verifying rather than assuming, since the wrong version of this claim is very easy to write.


## 6. `inPlaceUpdate` needs the generated `setXUsingInPlaceUpdate` — RESOLVED

A plain `setNote(...)` is an ordinary dated update and creates a new processing version; it never
reaches `TemporalDirector.inPlaceUpdate`. Reladomo emits **`setNoteUsingInPlaceUpdate`** when the XML
attribute declares `inPlaceUpdate="true"`, and only that method routes to the in-place behaviour.

The test was calling the wrong method, so this was a wrong premise rather than a divergence. Using the
generated method, H2 keeps a single rectangle with all four timestamps unchanged and only the payload
differs — and DynamoDB round-trips it.

## 7. Audit-only `insertUntil` / `terminateUntil` are generated **stubs that throw** — REFINED

Refining finding 2, which said these were "generated". They are — as stubs:

```java
throw new MithraBusinessException(
    "insertUntil is only supported for dated objects with a business date");
```

They never reach `AuditOnlyTemporalDirector`, whose own message ("audit only objects do not provide
insert until functionality") is therefore unreachable from this path. `cascadeInsertUntil` /
`cascadeTerminateUntil` delegate to the same stubs. Tests assert the generated message.

## 8. Use **declared** methods, not `getMethods()`, when asserting a generated API — my bug

The assertion I added in finding 2 used `Class.getMethods()`, which includes inherited methods and so
could pass on a method the generated class never declared. Corrected to declared methods.

Small, but the point of that assertion is to pin what the generator emits — and the loose version
could have confirmed the wrong thing.

## 9. Zero-length `insertForRecovery` is illegal — RESOLVED (wrong premise)

`GenericBiTemporalDirector.insertForRecovery` (reladomo 18.1.0) calls `checkDatesAreWithinRange`,
which requires `AsOfAttribute.dataMatches` on both axes. `dataMatches` is half-open
(`from <= asOf < to`, implemented as asOf+1ms against an inclusive upper bound when
`toIsInclusive=false`). A rectangle with `from == to` matches **no** as-of date, including the
object's own business/processing date, so Reladomo raises:

```
business date must be valid for to and from business dates
```

(or the processing-date twin). Not an adapter bug. The original test
`insertForRecovery_can_materialise_a_zero_length_segment` asserted a shape Reladomo forbids.

The replacement tests assert the rejection and leave H2 empty. A **closed non-zero** recovery
(`[Jan, June)` on business, open on processing) is legal and round-trips.

### Contrast: `insertUntil` of a zero-length window **is** persisted

`insertUntil` does **not** call `checkDatesAreWithinRange`. It sets `businessDateTo = exclusiveUntil`
and delegates to `insert()`. `insertUntil(from)` therefore stores a degenerate `[from, from)`
rectangle. `equalsEdgePoint()` can see it; no as-of query can, because `from <= asOf < from` is
empty. DynamoDB round-trips that row. Asymmetric by director design, not by the adapter.

## 10. Unbounded `setX` as-of an earlier date overwrites later business segments — RESOLVED (wrong premise)

`GenericBiTemporalDirector.update` ranges `[asOf, infinity)`
(`getObjectsForRange(mithraObject, fromDate, businessDateAttribute.getInfinityDate())`). A late
March `setQuantity` after a June update therefore inactivates the June segment too. Current as-of
June becomes the March value; the June value survives only as history (as-of the June write's
processing FROM).

The original edge-case test asserted "June stays 20". H2 itself returned 15. Split into:

| API | Range | Current June |
|---|---|---|
| `setQuantity` as-of March | `[March, inf)` | **15** (overwritten) |
| `setQuantityUntil(June)` as-of March | `[March, June)` | **20** (preserved) |

Same shape as finding 5: the date you write at, and whether the write is bounded, decide whether
later business time is destroyed. Adapter round-trips both.

## 11. Same-millisecond writes delete instead of inactivate — RESOLVED (Reladomo behaviour)

`GenericBiTemporalDirector.createProcessingTimestamp` clamps `tx.getProcessingStartTime()/10*10`
(Sybase millisecond granularity). Two transactions 5ms apart therefore share a processing FROM.

`inactivateObject` then sees `processingFrom == txStartTime` and **physically deletes** the prior
row ("has changed too fast") instead of closing `processingTo`. Consequences, verified on H2:

| Write | Physical rows | Prior belief |
|---|---|---|
| Same-ms (or 5ms) update at a **later** business date | 2 current rectangles, shared processing FROM, both open on processing TO | original `[Jan, inf)` **deleted**; split is `[Jan, June)` + `[June, inf)` |
| Same-ms update **at the segment FROM** | 1 current rectangle | prior value **destroyed** |
| 10ms later (next clamp bucket) | inactivated history + new current | prior belief survives as-of the first processing FROM |

Distinct `businessDateFrom` still yields distinct v1 sort keys (`v1#P#<processingFrom>#B#<businessFrom>`),
so the two-row same-ms split does not collide in DynamoDB. Adapter round-trips all three cases.

## 12. **A REAL DIVERGENCE** — the as-of current-row query returns nothing from DynamoDB — FIXED

The first genuine adapter divergence found in this project. Every previous "divergence" was a wrong
premise about Reladomo; this one was not.

**Symptom.** The same finder call, served by each store:

```java
DiffBalanceFinder.balanceId().eq(900)
    .and(DiffBalanceFinder.businessDate().eq(<2026-06-01>))
    .and(DiffBalanceFinder.processingDate().eq(<infinity>))
```

H2 returned **1** row. The adapter returned **0**. `count()` likewise: expected 1, got 0.

The row was definitely stored — the storage-level differential tests write and read it back
successfully. What failed was the **translation of the as-of predicates into a DynamoDB query**.

**Why no existing test caught it.** Every other differential test reads back with
`pk = :pk` and no sort-key condition, then compares row sets. That exercises storage, key derivation
and the codec — and never exercises as-of translation at all. This is precisely the gap
`docs/RELEASE-READINESS.md` describes as "query-path equivalence is partial", and it turned out to
contain a real bug rather than merely being unproven.

**Hypothesis was wrong.** Finding 12 guessed "equality against `processingDateFrom`". The dumped
plan never did that. Reladomo's `AsOfEqOperation.generateSql` already special-cases infinity as
`toColumn = ?` because the half-open form `from <= inf AND to > inf` is empty when `to` is also
infinity (`infinity < infinity` is false). The planner knew that special case. It just asked the
wrong timestamp whether the parameter *was* infinity.

**What the dumped plan actually showed (before the fix).**

```
key    = #pk = :pk
SK     = unbound
filter = #IN_Z <= :v0 AND #OUT_Z > :v0
```

That is processing as-of treated as ordinary half-open containment, with `:v0` bound to Reladomo's
infinity encoding. Stored `OUT_Z` *equals* that encoding, so `OUT_Z > infinity` matches nothing —
exactly zero rows, not wrong rows.

**Root cause.** `appendAsOfAxis` / `isCurrentAsof` compared `asOf.equals(PhysicalDesign.infinity())`.
`PhysicalDesign.infinity()` comes from `MithraObjectXmlParser`, which cannot classload Reladomo
`infinityDate="[...]"` snippets and therefore stores a **conventional UTC** sentinel
(`9999-12-01 23:59:00Z` → TemporalEncoder `99991201235900000`). Reladomo's
`AsOfAttribute.getInfinityDate()` is `DefaultInfinityTimestamp.getDefaultInfinity()`, which is
`9999-12-01 23:59:00` **in the JVM default timezone**. On this machine (UTC-6 / MDT) that is
`9999-12-02 06:59:00Z` → `99991202065900000`. `Timestamp.equals` is false, the infinity branch is
skipped, and `thru > infinity` filters out every current row.

Planner unit tests missed it because `PlanFixtures` sets `PhysicalDesign.infinity()` to Reladomo's
own sentinel, so the two values *do* equal in that suite. The production path (XML-parsed mapping +
generated finder) is the one that disagrees.

**Dumped plan after the fix** (`FinderDrivenDifferentialTest.current_row_query_plan_…`):

```
QueryPlan{class=…DiffBalance, index=PRIMARY, method=QUERY, key=#pk = :pk,
          filter=#OUT_Z = :v0 AND #FROM_Z <= :v1 AND #THRU_Z > :v1,
          residual=, examined~=16, returned~=1, fastPath=CURRENT_ASOF}
PK     = v1#DIFFBALANCE#900
SK     = null
names  = {#pk=pk, #OUT_Z=OUT_Z, #FROM_Z=FROM_Z, #THRU_Z=THRU_Z}
values = {:pk=S(v1#DIFFBALANCE#900), :v0=S(99991202065900000), :v1=S(20260601000000000)}
```

Processing infinity is now `OUT_Z = Reladomo-infinity` (not `IN_Z` equality, not `OUT_Z > inf`).
Business as-of of a finite date stays half-open containment (`FROM_Z <= June 1 AND THRU_Z > June 1`).

**Fix.** Compare and encode against `AsOfAttribute.getInfinityDate()`, not `PhysicalDesign.infinity()`.
Bind `TemporalEncoder.encode(asOf)` — Reladomo itself binds the as-of parameter, which equals the
axis sentinel. Inclusive/exclusive TO follows `AsOfAttribute.isToIsInclusive()` for the same reason.
Sibling cases checked in `FinderDrivenDifferentialTest`: as-of a past business date, as-of a past
processing date, and a row whose `businessDateTo` is finite. Finite as-of was already half-open
containment and did not need the infinity special case; it still has to survive the same planner.

**Status.** `FinderDrivenDifferentialTest` is restored under
`reladynamo-ddb/src/test/java/io/reladynamo/ddb/differential/` and is green. It is the only test
that proves query-path equivalence; it was not weakened or `@Disabled`.

## 13. The parser's "conventional infinity" is a landmine beyond finding 12 — ROOT FIX APPLIED

Finding 12's root cause deserves separating from its symptom, because the cause is broader than the
one query it broke.

`MithraObjectXmlParser` cannot classload an `infinityDate="[com.gs...getDefaultInfinity()]"` snippet,
so it substitutes a **conventional UTC** sentinel. Reladomo's real
`DefaultInfinityTimestamp.getDefaultInfinity()` is `9999-12-01 23:59:00` in the **JVM default
timezone**. In UTC they coincide; anywhere else they do not.

So the adapter's notion of infinity silently disagrees with Reladomo's on any non-UTC JVM. Finding 12
was one consequence. Others are plausible wherever infinity is compared rather than merely stored —
and the comparison sites are not all in the planner.

**Why every test missed it**: `PlanFixtures` sets `PhysicalDesign.infinity()` to Reladomo's own
sentinel, so the two agree throughout the 38 planner tests. Only the production path — XML-parsed
mapping plus a generated finder — puts the two different values side by side. **A fixture that is
more correct than production hides exactly the bugs production has.**

**Root fix applied.** `io.reladynamo.core.bridge.InfinityResolver` reads infinity from the generated
`AsOfAttribute`, which is the only source of truth for it, and
`PhysicalDesign.Builder.infinityFrom(RelatedFinder)` applies it wherever a design is built from a
parsed mapping. The comparison-site patch from finding 12 remains as defence in depth, but the values
now agree by construction rather than by having been caught.

Pinned by test (`InfinityResolverTest`):

- the resolved value equals `AsOfAttribute.getInfinityDate()`;
- **outside UTC, the conventional sentinel and Reladomo's differ** — asserted explicitly, so if that
  assertion ever stops holding it means the JVM is in UTC and the mismatch is hidden rather than gone;
- a non-dated entity resolves to `null`;
- a null finder is **refused rather than defaulted** — substituting a default is how the wrong
  sentinel got in originally.

## 14. **A SECOND REAL DIVERGENCE** — the dated write path passed a wrapper, not data — FIXED

Found by the same method as finding 12: testing the one path nothing else tested. Every differential
test mirrors rows by calling `DynamoDbWriter` directly; nothing drove Reladomo through a **bound
portal** in a real transaction, which is how an application actually writes.

**Symptom, stage 1.** An update through a bound portal failed with:

```
class InTransactionDatedTransactionalObject cannot be cast to class MithraDataObject
```

`DynamoDbPersister.rowsOf` cast every list element straight to `MithraDataObject`. On the **dated**
write path Reladomo passes `InTransactionDatedTransactionalObject` wrappers instead. The cast named
neither the cause nor the remedy — a `ClassCastException` from inside an adapter is about as
unhelpful as a failure gets.

**Symptom, stage 2.** Unwrapping with `zGetCurrentData()` got further and then failed with:

```
primary key attribute balanceId of DiffBalance cannot be null
```

The wrapper carries **two** data objects — its constructor takes both — and `zGetCurrentData()` can
return the not-yet-populated one. `zGetTxDataForRead()` is the version the transaction is actually
writing.

**Fix.** `dataOf(Object)` now unwraps explicitly: `MithraDataObject` passes through;
`InTransactionDatedTransactionalObject` yields `zGetTxDataForRead()` falling back to
`zGetCurrentData()`; other transactional objects yield their current data; **anything else throws with
a message naming the type**, rather than letting a cast failure escape.

**Insert had worked all along**, which is why this survived: insert does not go through the wrapper.
Only update and terminate do. A write path that is 'tested' by its easiest operation is not tested.

## 15. **A THIRD REAL DIVERGENCE** — relationship deep-fetch cannot be planned — FIXED

Found by the same method as findings 12 and 14: writing the first test of a thing nobody had tested.
`docs/COVERAGE-GAPS.md` listed relationships / deep-fetch as untested. Reladomo's demos declare them;
nothing had driven them through the adapter.

**What does work.** Child rows round-trip. A `DiffEntry` (bitemporal child of `DiffBalance`) seeded
in H2, mirrored through `DynamoDbWriter`, and read back from DynamoDB compares identically under
`TemporalRowSetDiffer`, including all four temporal boundaries.
`RelationshipDifferentialTest.child_rows_round_trip_with_all_four_temporal_boundaries` is green.
Storage is not the problem.

**Symptom.** The same `deepFetch(DiffBalanceFinder.entries())` that H2 answers with 24 children
throws on the adapter:

```
io.reladynamo.core.plan.ReladynamoScanRequiredException:
RELADYNAMO-PLAN-001: Scan required for io.reladynamo.ddb.differential.domain.DiffEntry on table DIFF_ENTRY but scans are disabled.
The operation does not bound a partition key on the base table or any GSI.
Enable scans only for bounded batch jobs: ReladynamoConfig queryPlanner.allowTableScan(true)
(or reladynamo.xml <QueryPlanner allowTableScan="true"/>).
Operation: DiffEntry.processingDate = "9999-12-01 23:59:00.0" & DiffEntry.businessDate = "2026-05-31 18:00:00.0" & DiffEntry.balanceId in [8103, 8102, 8105, 8107, 8108, 8101, 8106, 8104]
```

(`businessDate` prints as 18:00 on 31 May because `Timestamp.toString()` uses the JVM default
timezone; the as-of value is UTC 2026-06-01, same as the other differential tests.)

**Call path (Reladomo 18.1.0, verified against sources, not invented).** No SPI method is missing.
`MithraObjectReader.find` **is** implemented and **is** the method Reladomo calls:

```
SimpleToManyDeepFetchStrategy.getResolvedListFromServer
  → node.getSimplifiedJoinOp(mapper, parentList)   // balanceId IN (the 8 parent ids) + as-of
  → list.forceResolve()
  → MithraAbstractObjectPortal.findAsCachedQuery
  → DynamoDbPersister.find                         // implemented
  → QueryPlanner.plan                              // throws PLAN-001
```

Reladomo already batched. This is not an N+1 of per-child `GetItem`s — it is one `find` of
`balanceId IN (8 parents)` plus the two as-of equalities. That is exactly the shape a deep-fetch
is supposed to produce, and exactly the shape the request-count assertion exists to protect.

**Why the planner refuses.** `DiffEntry`'s partition key is `entryId`. The simplified join binds
the **foreign** key `balanceId`. `expandPk` returns null (PK unbound), `tryGsi` finds no GSI
whose partition key is `balanceId` (none is declared), `scanOrThrow` throws PLAN-001 because
`allowTableScan` defaults to false.

Design 04 already classified this: incomplete PK → GSI or Scan-or-throw; mapped ops → application
semi-join (`RELADYNAMO-PLAN-003`). Reladomo never handed the adapter a `MappedOperation` — it
simplified first. The unimplemented semi-join path is therefore not the thing that fired. What
fired is the unkeyed `IN` on a payload attribute.

**What is not the fix.** Opting the test into `allowTableScan(true)` would make deep-fetch a
table Scan of `DIFF_ENTRY`. That is the silent-Scan fallback the planner exists to forbid
("production cost death; hides missing GSIs"). It is not an implementation of relationship
navigation; it is a workaround, and the tests are left failing rather than taking it.

The real access path is a GSI whose partition key is the FK, plus planner support for `IN` on
that GSI (today `tryGsi` only binds GSI-PK **equality**, not `IN`). Neither exists. Until they
do, any Reladomo `deepFetch` / `getEntries()` against this adapter dies with PLAN-001.

**Status.** OPEN. `RelationshipDifferentialTest` is not `@Disabled` and was not weakened.
`relationship_navigation_agrees_between_stores` and
`deep_fetch_issues_substantially_fewer_requests_than_one_per_child_row` fail with the exception
above. The storage-round-trip test in the same class stays green.

### Resolution

A GSI whose partition key is the foreign key, plus planner support for `IN` against a GSI partition
key, plus `TableCreator` building the index. The refusal is intact for the genuinely unplannable case:
an entity with no GSI on the FK still raises `ReladynamoScanRequiredException`.

**Measured: `reads=1 query=1 getItem=0 scan=0` for 24 children across 8 parents.** The executor
batches the fan-out rather than issuing a query per partition key, so both restored assertions hold —
fewer reads than children (the N+1 bar) and fewer than parents (the fan-out bar).

**A note against myself.** The agent's last progress line reported "8 query() calls", recorded before
its final fix. Reading that, I relaxed the stricter assertion to `isLessThanOrEqualTo(PARENT_COUNT)`
on the grounds that one query per partition key is a physical floor — a reasonable-sounding argument
for a number I had not measured. The actual figure is 1. I reverted the change and kept the strict
bound, which is met and which would catch a regression to per-parent fan-out.

Loosening a test because a stale diagnosis made it look impossible is the same failure this project
has been cataloguing all along, committed by the person cataloguing it. **Run it before you weaken it.**

## 16. A documented DoS mitigation that did not exist — FIXED (my bug)

`PlannerConfig.maxPages` (default 64) was declared, exposed through a builder, and **read by
nothing**. The executor followed `LastEvaluatedKey` until exhausted, with no bound.

I had written in `docs/SECURITY-REVIEW.md` that "pagination is bounded (`DEFAULT_MAX_PAGES = 64`)" —
on the strength of the constant existing, without checking that anything consumed it. The mitigation
was documentation, not behaviour.

**Fix.** `QueryPlan` carries `maxPages`; the executor enforces it in both pagination loops and
**throws `PageLimitExceededException`** when a query needs more. Unset (`0`) means unbounded, so no
existing plan changes behaviour.

**Why throw rather than truncate.** Stopping quietly at N pages returns a partial result that a caller
cannot distinguish from a complete one — a wrong answer that looks like a right one. That is strictly
worse than having no limit at all, and it is the same reasoning that makes the planner refuse a Scan
rather than run one.

**The general lesson:** a configuration option nothing reads is worse than a missing one, because it
reads as a guarantee. Grep for the consumer before documenting a knob as a mitigation.

## 17. Three more configuration options that nothing reads — GUARDED

Finding 16 was one inert knob. Auditing every `PlannerConfig` getter for a consumer found three more:

| Option | Documented as | Consumers |
|---|---|---|
| `joinFanOutLimit` | design 04: `PLAN-003` overflow when a join fans out past it | **none** |
| `inMemoryByteCeiling` | design 04: half of the `PLAN-007` memory guard | **none** — only the row ceiling is enforced |
| `pageSize` | design 04: page-count estimation | **none** |

They are kept rather than deleted — the design specifies them and they should exist — but the builder
now **refuses a non-default value**:

```
UnsupportedOperationException: joinFanOutLimit is declared but not yet enforced, so setting it
would imply a guarantee that does not exist. Leave it at the default (1000) until the planner
consults it. See finding 16.
```

Setting them to the default still works, so no existing caller changes. Implemented options
(`allowTableScan`, `pkFanOutLimit`, `inMemoryRowCeiling`, `maxPages`) are untouched, and a test
asserts the guard did not spread to them.

**The audit is the reusable part.** One grep over every getter for a consumer found four inert options
including a documented security mitigation. That check costs seconds and belongs in review for any
config object whose values are described as guarantees.

## 18. `sourceAttribute` was silently ignored — a data-isolation hazard — FIXED

Found by continuing the finding-16/17 audit into design 04's error codes: it specifies eleven
`RELADYNAMO-PLAN-nnn` codes and only seven exist in code. Three of the missing four are performance
guards. **PLAN-004 is not.**

> `RELADYNAMO-PLAN-004: Object {className} has a sourceAttribute but the operation does not constrain
> it; refusing cross-source scan.`

Reladomo's `sourceAttribute` routes objects to different physical databases — it is how multi-tenancy
is expressed. The adapter had **no concept of it whatsoever**: `MithraObjectXmlParser` never read the
element, the key strategy never included it, and the planner could not constrain it. The only
occurrence anywhere in main sources was a parameter name on a method that refuses.

**What that means.** An object model declaring a `sourceAttribute` would parse without complaint, and
every source's rows would land in one table with no discriminator in the partition key. A query for
one tenant would return **all** tenants' rows.

No test would have caught it, because every fixture and all three demos are single-source. A
single-tenant test suite cannot fail a multi-tenancy bug.

**Fix.** The parser now refuses such a model at configuration time with `RELADYNAMO-CFG-012`, naming
the attribute. Refusing is the correct outcome rather than a limitation to work around: silently
merging tenants is the kind of failure that is discovered by the wrong person.

**The pattern, fourth instance.** Findings 16, 17 and 18 are all the same shape — the design document
described behaviour that did not exist, and was read as though it did. The first was a mitigation, the
second three tuning knobs, this one a data-isolation guard. **Grep the spec against the code before
trusting either.**

## 19. The audit that found 16-18 is now a gate — `spec-drift`

Three findings in a row had the same cause: a design document described behaviour that did not exist,
and was read as though it did. A denial-of-service mitigation (16), three tuning knobs (17), and a
multi-tenancy data-isolation guard (18). Each was found by hand, and only because someone happened to
look.

`scripts/spec-drift.sh` now checks both halves mechanically, and runs as the eighth gate:

1. **Every `RELADYNAMO-xxx-nnn` code named in a design document exists in main sources** — or appears
   in an explicit waiver list with a reason. A code in the spec and not in the code is a promise
   nobody kept.
2. **Every public getter on `PlannerConfig` has a consumer** — or its builder refuses non-default
   values. A knob nothing reads is worse than a missing one, because it reads as a guarantee.

Four codes are waived today, each with its reason recorded in the script: `PLAN-003`, `PLAN-006` and
`PLAN-007` are unimplemented guards whose knobs now refuse non-defaults, and `PLAN-004` is superseded
by refusing `sourceAttribute` outright at parse time.

**The gate was verified by breaking it**, not by observing it pass: adding a fake `PLAN-099` to the
design produced `exit=1` naming that code, and removing it restored green. A gate that has only ever
passed has not been shown to do anything — which is the same mistake as `tests=0` at iteration 0 and
the unread `maxPages` at finding 16.

## 20. The `spec-drift` gate was satisfied by a same-named getter on another class

Finding 19 added `spec-drift` and claimed it mechanically closed the "documented but not built" gap.
An independent inspection the next day found R-09: `PlannerConfig.maxPages` defaults to 64,
`QueryPlan.maxPages` defaults to 0, and **`QueryPlanner` never copies one into the other**. The Query
and Scan loops only enforce a *positive* plan value, so a hand-built plan in a test is bounded and
**every plan a real finder produces is unbounded**. The tests proved the opposite of the truth.

The gate should have caught this. It did not, and the reason matters more than the bug:

```python
consumed = any(re.search(r'\b' + g + r'\(\)', b) for b in others.values())
```

`QueryPlan.java` declares its own `public int maxPages()`. That declaration matched the search, so the
gate concluded `PlannerConfig.maxPages()` had a consumer. **The gate was reading a declaration as a
use, in a different class entirely.**

Two changes, both verified by running the gate and watching it go red:

1. Strip method declarations before searching, and require an actual receiver — `.maxPages()`, a call,
   not `int maxPages()`, a definition.
2. A second-order check: if a config option also exists as a same-named field on `QueryPlan`, then
   `QueryPlanner` must mention it. Being read *somewhere* is not being *wired*.

The strengthened gate immediately reported two problems, one of them new:

```
config option reaches QueryPlan.maxPages but QueryPlanner never populates it: maxPages
config option with no consumer and no guard: avgItemBytes
```

`avgItemBytes` is declared on `PlannerConfig` **and** on `PhysicalDesign`, stored in both, exposed by
both, and **read by nothing anywhere in the tree**. It had been hiding behind the same flaw: each
class's getter vouched for the other's. It is a settable knob that does nothing, on a cost-estimation
path where `estimatedItemsExamined` is already first-class — so the fix is to turn item counts into a
byte/RCU estimate, not to delete it.

**Both were left failing at the time**, and both are now closed — `maxPages` by R-09, `avgItemBytes`
by the work recorded at the end of this document. Parking them was the same call made for findings 12
and 15: a red gate that names a real defect is worth more than a green one that was never
load-bearing. The wait was also practical — wiring `avgItemBytes` meant editing `QueryPlanner` and
`QueryPlan` while four agents were mirroring those files, and it took ten minutes once the planner was
quiet.

The lesson generalises past this repository. Finding 19 ended by saying a gate that has only ever
passed has not been shown to do anything — and that gate *had* been deliberately broken, with a fake
`PLAN-099`, and it *had* gone red. But the injected failure was of the one shape the gate handled
well. **Breaking a check proves it detects the break you thought of.** It says nothing about the
shape you did not.

## 21. A bound update never closes the superseded processing rectangle — **real defect, data-grade**

The inspection said `BoundWritePathTest`'s green was not load-bearing: its update branch asserted
`>= 1` stored row, which the *insert alone* already satisfied, and its terminate branch asserted
nothing at all. Strengthening those three assertions — and pinning the processing clock with
`MithraTransaction.setProcessingStartTime(long)` so two stores are comparable at all — turned up a
divergence nothing else in the suite could see.

Insert 10.0, then update to 55.0, the identical operation on both stores:

```
H2 : qty=10.0 biz=[2025-12-31,2026-05-31) proc=[2026-03-02,∞)
     qty=10.0 biz=[2025-12-31,∞)          proc=[2026-03-01,2026-03-02)   <-- closed
     qty=55.0 biz=[2026-05-31,∞)          proc=[2026-03-02,∞)

DDB: qty=10.0 biz=[2025-12-31,2026-05-31) proc=[2026-03-02,∞)
     qty=10.0 biz=[2025-12-31,∞)          proc=[2026-03-01,∞)            <-- STILL OPEN
     qty=55.0 biz=[2026-05-31,∞)          proc=[2026-03-02,∞)
```

Same row count. Same quantities. Same business boundaries. Same processing boundaries on two of the
three rows. **One superseded version's processing rectangle is never closed.** The same defect makes
`terminate()` leave every version of its object at `processingDateTo = infinity`.

The consequence is not a missing feature, it is a corrupted history. An as-of-processing-time query
positioned after the update sees **two** rows claiming to be current, and the store asserts that the
old value was never superseded — which is the one thing a bitemporal store exists to get right.

### Root cause

`DynamoDbPersister.update(MithraTransactionalObject, AttributeUpdateWrapper)` is:

```java
writer.insert(rowOf(object.zGetCurrentData()));   // wrapper ignored entirely
```

carrying this javadoc:

> *An update on a dated object arrives here already decomposed by the TemporalDirector into the rows
> that should exist, so re-persisting the row is the correct action.*

The first half is true and the second does not follow. The director does decompose the correction,
but it delivers the **change** in the `AttributeUpdateWrapper`, and `zGetCurrentData()` is not
guaranteed to have it applied. Closing a rectangle sets `processingDateTo`, so re-putting unchanged
current data writes the row back **exactly as it was** — a no-op that reports success. The write is
addressed correctly (OUT_Z is not part of the sort key `v1#P#<processingFrom>#B#<businessFrom>`, so
the put lands on the right item); it simply carries the old value.

This sharpens inspection finding R-02, which framed the same line as a *concurrency* hazard — "rewrites
the complete current data row rather than applying checked attribute changes." It is worse than that:
on this path the change is not merely unguarded, it is **lost**.

### Why nothing caught it

Every other differential test performs the temporal operation on H2, reads the resulting history, and
pushes those rows into DynamoDB — proving the codec and key layout, which is real but is the
*storage* path. Only a mutation driven through a **bound portal** reaches this code, and the one test
that did asserted a row count the setup already met. That is the third time in this project a defect
has lived exactly where nothing tested the way an application would actually use it (findings 12, 18).

**Left failing**, per standing rule. `DynamoDbPersister` belongs to the in-flight `insp/writes` agent;
this finding is routed to it at integration rather than patched underneath it.

## 21 — CLOSED (2026-09-14)

Fixed by R-02's rewrite of `DynamoDbPersister.update`. The old implementation was:

```java
writer.insert(rowOf(object.zGetCurrentData()));   // wrapper ignored entirely
```

It now snapshots `zGetNonTxData()` (falling back to `wrapper.getDataToUpdate()`), **applies each
`AttributeUpdateWrapper.updateData(current)`**, and calls `writer.update(newRow, expectedPrior)` with
a condition requiring every mapped attribute to still equal the prior state.

Applying the wrapper is the whole fix. Closing a processing rectangle changes only
`processingDateTo`, which is not part of the sort key, so the old code re-put the row **exactly as it
was** and reported success. The superseded version stayed open, and an as-of-processing query saw two
rows claiming to be current.

**Both deliberately-failing tests now pass** — `a_bound_update_produces_the_same_bitemporal_shape_as_h2`
and `a_terminate_through_a_bound_portal_succeeds_or_names_what_is_missing` — and
`BoundWritePathTest` is 5/5. The test file was **not modified**: the merge agent was told it was
forbidden to touch those tests, and its output confirms it never mirrored the file. That matters,
because the tests only became meaningful when they were *strengthened*; passing them by relaxing them
would have restored exactly the blind spot the inspection found.

The finding was worth the detour. It was found by strengthening three assertions the inspection had
flagged as too weak — an update branch asserting `>= 1` stored row that the *insert alone* already
satisfied, and a terminate branch asserting nothing at all — and it turned out to be the most serious
defect in this list.


## 20 — CLOSED (2026-09-14)

`avgItemBytes` now means something. `QueryPlan` carries it, `QueryPlanner.applyConfigLimits` copies it
from `PlannerConfig` onto every plan a finder produces, and two new accessors turn it into a cost:

- `estimatedBytesExamined()` = `estimatedItemsExamined() x avgItemBytes()`
- `estimatedRcu()` — whole 4 KB blocks, one read unit per block when strongly consistent and half when
  eventually consistent, charged on bytes **examined** rather than returned, which is why it is built
  from `estimatedItemsExamined` and not from the row count

It is an estimate and says so: one configured average item size rather than real item sizes, and no
model of a GSI's own projected size. Its job is to make two access paths comparable at plan time,
which is what the plan's existing `examined~=` output was already half-doing. `toString()` now carries
`rcu~=` beside it.

Five tests, written before the implementation and seen to fail with "cannot find symbol": the planner
copies the configured value; bytes are items times size; a strongly consistent read costs a whole unit
per block; an eventually consistent read costs half; a 100-byte read still costs one whole block; and
nothing examined costs nothing.

`spec-drift` is green again — and this time because the knob is genuinely wired, not because the gate
could not tell the difference between a use and a declaration.

## 22. Ordering compares BigDecimal through `double`, so distinct values tie

Found by the astra assumption review (`docs/ASSUMPTION-CHALLENGE.md`, C-09) **inside R-08's fix,
hours after it landed**. Re-verified against source before acting.

`RowOrderComparator.compareNumbers` routes every `BigDecimal`, `Float` and `Double` comparison through
`Double.compare(left.doubleValue(), right.doubleValue())`. `BigDecimal("9007199254740992")` and
`BigDecimal("9007199254740993")` straddle 2^53, where `double` runs out of integer precision, and
therefore **compare equal**. A false tie can return the wrong ordered top-one, or let a secondary sort
key override the true primary order. The affected columns are ordinary: CRM amount/revenue/price and
petstore price are all `BigDecimal`.

Three lines above, the fallback is `String.valueOf(left).compareTo(String.valueOf(right))` — for two
`byte[]` instances that is `[B@1b6d3586`, **object identity text rather than content**. M-08 fixed
exactly that defect in `TemporalRowSetDiffer` earlier the same day; it was reintroduced here, in a
different file, by a different agent. Java `String` order is also not DynamoDB's UTF-8 byte order for
all code points.

The finding matters beyond the fix. R-08 was integrated with a passing test suite, an agent verifier,
and my own build — and none of them could see this, because every ordering fixture used values a
`double` represents exactly. **A test written against the same mental model as the code cannot falsify
it.** Only reading the code against the type system's actual limits did.

## 23. `ItemCodec` can write a schema version its own decoder refuses

From the same review (C-11), also verified. `ItemCodec(mapping, schemaVersion)` rejects
`schemaVersion < 1` and imposes **no upper bound**, so `new ItemCodec(mapping, 2)` is accepted and
`encode` stamps `_rd_v=2`. `decode` refuses anything above `MAX_SUPPORTED_SCHEMA_VERSION` (1) with
`UnsupportedSchemaVersionException`.

The codec will therefore write items it cannot read back. The failure surfaces on the read, which may
be much later and in a different process — the worst shape for a durability bug.

M-07 added the decode-side range check that morning. The write side never got the matching bound, and
nothing connected them, because the encode and decode paths were reviewed as separate changes. Encode
and decode must agree on the supported range **by construction**, not by coincidence.

## 24. Rebinding a real demo dies on `refresh`, not on anything exotic

Acceptance case 12 — "one original business operation script runs independently against each
backend" — was attempted on `demos/03-car-classifier`, the **five-entity** model, the smallest real
one in the project.

Seeding through the bound portals works. `Classifier.classify` then does `findMany` of active rules
as-of a date, `deepFetch(criteria)`, and reads `Car` attributes **inside a new Reladomo transaction**.
That last step enrols the persisted `Car` for read, which calls `DynamoDbPersister.refresh`:

```
Car.getWheelCount -> enrollInTransactionForRead -> MithraAbstractObjectPortal.refresh
                  -> DynamoDbPersister.refresh -> notYet("refresh")
```

**The test is left failing**, asserting the named refusal and then `fail(...)`, so the suite stays red
until `classify` actually agrees with H2. Not `@Disabled`, not skipped — the previous version of this
claim stayed green precisely by skipping.

This converts R-12 from an abstract inventory ("20 of 32 SPI methods refuse") into a concrete,
ordered blocker: **the first read of a persisted object inside a new transaction needs `refresh`.**
That is the feature-level compatibility matrix R-12 asked for, discovered by running an application
rather than by reading a list. The agent's verdict on the 46-entity CRM follows directly: *"No, not
until `refresh` (and dated `refreshDatedObject`, possibly `enrollDatedObject`) land."*

It also declined to implement a plausible no-op `refresh`, and said why: it "would look like a working
read and is how this claim stayed green before."

## 25. Non-dated batch insert read the committed side, not the in-transaction payload

Found in front of the refresh wall, javap-verified against reladomo-18.1.0.

`BatchInsertOperation` hands a `FastList<MithraTransactionalObject>`, not `MithraDataObject`. The
adapter's `dataOf` called `zGetCurrentData()` — the **committed** side, empty mid-transaction, so a
non-dated batch insert persisted rows with null attributes. `zGetTxDataForRead()` is the
in-transaction payload. The dated wrappers already did this correctly; the non-dated path did not.

Same class as finding 14: the wrong Reladomo accessor, silently returning something plausible.

## 26. `CachedQuery` keyed by the analyzed operation breaks `findMany().deepFetch().size()`

Reladomo does `if (listOp != cached.getOperation()) list.zSetOperation(cached.getOperation())`, and
`zSetOperation` requires `equals()` against the list's current operation. The adapter cached by the
**analyzed** operation — the as-of-injected form — so the cached operation never equalled the list's,
and Reladomo threw `cannot change operation`.

The failing sequence is `findMany(); deepFetch(); size()`, which is exactly what `Classifier.classify`
does. Key the cache by `getOriginalOperation()`.

Both 25 and 26 were found only because a real application script was run end to end. No unit test in
this repository exercised either path, and both had been sitting behind the assumption that the read
path worked because `find()` returned rows in a fixture.

## 27. `notEq` includes SQL NULL on DynamoDB — and R-04's fix accidentally proved it

The first real output of the finder matrix, and it is the kind of divergence nothing else in this
project could have found.

```
Q(1).and(DiffFinderValueFinder.intValue().notEq(2))

H2  -> {1, 2, 4, 7}       (non-null, and not equal to 2)
DDB -> {1, 2, 4, 5, 7}    (row 5 is all-NULL, and is included)
```

SQL three-valued logic: `NULL <> 2` is **UNKNOWN**, not TRUE, so H2 excludes the null row. The
adapter's native `<>` filter on `N` / `S` / `BOOL` / Timestamp treats the explicitly stored NULL as a
match. Same pattern on int, long, String, boolean and Timestamp — five of the nine types.

**The other four agree with H2.** `notEq` on double, float and BigDecimal is correct — because R-04
moved those types out of the wire filter and into typed residual evaluation after decode, where
ordinary Java comparison happens to implement SQL UNKNOWN correctly.

That split is what makes this finding so clean. The same predicate is right on the types that go
through the residual and wrong on the types that go through the native filter, in one test run,
against one oracle. A fix aimed at numeric *fidelity* inadvertently produced a correct null semantics
for three types, and the contrast exposed that the other five were never right.

This is R-05's territory — "an explicitly stored NULL exists, and the predicate must account for it" —
left open for `notEq` because R-05 fixed `isNull`/`isNotNull` and nothing exercised `notEq` against a
null row until now. **Left failing.** Assertions were not weakened.

## 28. `ByteArrayAttribute.notEq` is unsupported by Reladomo itself

```
java.lang.UnsupportedOperationException: notEq is not supported for byte array attributes
    at com.gs.fw.common.mithra.attribute.ByteArrayAttribute.notEq(ByteArrayAttribute.java:212)
```

Confirmed by `javap -c` of the method. **Not an adapter defect** — Reladomo 18.1.0 does not offer the
operation, so there is nothing for the adapter to translate. Recorded so the matrix's coverage table
does not read as a gap, and so nobody later "fixes" a case that cannot exist.

Worth noting as the counter-example to finding 27: the same run produced one divergence that is ours
and one that is the framework's, and the agent distinguished them with bytecode rather than
assumption.

## 29. `equalsEdgePoint` cannot materialise — `asOfDatesOf` requires `AsOfEqOperation`

Five matrix cases error rather than fail: `DynamoDbPersister.asOfDatesOf` requires an
`AsOfEqOperation`, and `equalsEdgePoint()` is not one. Edge-point queries therefore cannot materialise
dated objects through the bound portal.

This is a real SPI gap in the same family as R-12 — and like finding 24, it was found by exercising a
path an application would actually take rather than by reading the refusal list. The existing
`find()` already documented that it "requires as-of equalities to materialise dated objects"; this
names the concrete operation that does not satisfy that requirement.

## 27 — CLOSED (2026-09-15)

`QueryPlanner.payloadFragment` now translates `notEq` the way R-05 translates `isNotNull`:

```java
"(attribute_exists(#n) AND NOT attribute_type(#n, :null) AND #n <> :v)"
```

so a stored explicit NULL no longer matches, and a genuinely missing attribute is excluded too. The
agent fixed **`QueryPlanInterpreter` alongside it** — which was the instruction, and the lesson of
R-05, where the planner and its own oracle were wrong in the same direction and the property tests
agreed while both were wrong.

All five native-filter types (int, long, String, boolean, Timestamp) now agree with H2 on the null
row, matching the behaviour double/float/BigDecimal already had through residual evaluation.

## 30. Binary ordering — NOT the adapter. Reladomo's own comparator crashes, and sorts signed

```
FinderMatrixShapeCasesTest.should_order_binary_values_by_content:363
  ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
```

**Neither side was empty**, and my three hypotheses were all wrong. The exception is
`com.gs.fw.common.mithra.finder.orderby.ByteArrayOrderBy.compareWith` reading index 0 of an empty
`byte[]`:

```java
for (i = 0; i < a.length; i++) diff = a[i] - b[i];   // not bounded by min(a.length, b.length)
```

H2 returned all six rows; Reladomo then crashed Java-sorting them, because fixture row 2 is
`new byte[0]`. No DynamoDB, no GSI, no projection involved — proved independently by
`ByteArrayOrderByConformanceTest`, which reproduces the crash on two in-memory objects.

Ruled out with evidence rather than by elimination: R-05's `isNotNull` translation (empty binary is a
present `B` value, not a DynamoDB `NULL`); the fixture (`FinderValueStorageVerificationTest` already
asserts both the H2 empty array and the DDB `B` wire form); the GSI (never executed — H2 crashed
first); the harness projection (the crash is inside Reladomo's JDBC `processResults`).
`RowOrderComparator.compareUnsignedBytes` from finding 22 is correct and was not the fault.

### The deeper half — finding 31

`a[i] - b[i]` is **signed** byte subtraction. DynamoDB binary ordering is **unsigned** lexicographic.
So even without the crash, `0x80` sorts **before** `0x01` in Reladomo and **after** it in DynamoDB.

That is not a bug either store can be said to have — it is a genuine semantic divergence between the
reference and the target, and no translation layer can satisfy both at once. The adapter cannot make
DynamoDB sort signed without abandoning the index's own ordering, and should not.

**Resolution:** `UnsignedByteArrayOrderBy` extends Reladomo's `AttributeBasedOrderBy` and delegates to
`RowOrderComparator`'s unsigned comparison; `OrderByTranslator.withUnsignedByteArrayOrder` walks
`ChainedOrderBy` and swaps each `ByteArrayOrderBy` leaf. The H2 side then sorts the way DynamoDB
does, which is what makes the comparison meaningful at all — and the agent checked that
`AttributeBasedOrderBy.equals` ignores the concrete class, so Reladomo's `CachedQuery.hasSameOrderBy`
still matches. That detail would have broken caching silently.

This belongs in `docs/SUPPORT-CONTRACT.md` as a stated boundary: **binary sort order differs between
Reladomo's in-memory comparator and DynamoDB, and the adapter matches DynamoDB.**

## 32. The transaction coordinator swallows the conflict type

Acceptance case 4 — *"Duplicate insert and concurrent/stale updates follow an explicit conflict
contract across JVMs"* — is implemented and **failing for a real reason**.

R-02's conditions **do fire** inside a Reladomo transaction: DynamoDB returns
`TransactionCanceledException` with `ConditionalCheckFailed`. The coordinator then wraps that as
`RELADYNAMO-TXN-004` (`DynamoDbTransactionException`), so the caller sees
`MithraTransactionException: Could not commit transaction` instead of:

- duplicate insert → `MithraUniqueIndexViolationException`
- stale update → `MithraOptimisticLockException`

The **non-transactional** writer path maps both correctly — `WriterConcurrencyTest` is 7/7. So the
adapter knows how to translate these; the transactional path loses the distinction on the way through
the coordinator.

This matters more than a wrong exception type. `MithraOptimisticLockException.isRetriable()` is
`true`; a generic transaction exception is not, so an application's retry logic never fires, and the
documented recovery path — refresh, then retry — is unreachable through a transaction.

### The tension the agent identified, which is the actual design question

`DurableTransactionTest.permanent_cancellation_aborts_both_tables_without_retry` **requires**
TXN-004 for a conditional cancellation. Naively remapping every `ConditionalCheckFailed` to a Mithra
exception would regress it. The agent declined to make that change and left the acceptance case
failing instead — the right call.

The resolution is that `TransactWriteItems` returns **per-action** `CancellationReasons`. The
coordinator can tell *which* action failed *which* condition, and so can distinguish "this insert's
`attribute_not_exists` failed" (→ `MithraUniqueIndexViolationException`) from "this update's
expected-prior-state failed" (→ `MithraOptimisticLockException`) from a cancellation for any other
reason (→ TXN-004, unchanged). The information is already on the wire; nothing reads it.

Assertions were not weakened to accept TXN-004.


## 32 — CLOSED (2026-09-15)

`DynamoDbTransactionCoordinator` now reads `TransactionCanceledException.cancellationReasons()` —
the per-action list, positionally aligned with the actions submitted — and maps each failed slot back
to the `PhysicalWrite` that produced it:

- an insert's `attribute_not_exists(pk)` failing → `MithraUniqueIndexViolationException`
- an update's or delete's expected-prior-state condition failing → `MithraOptimisticLockException`
- anything unclassifiable, or a cancellation with no attributable per-action condition failure →
  **`RELADYNAMO-TXN-004`, unchanged**

`DurableTransactionTest.permanent_cancellation_aborts_both_tables_without_retry` still passes on
TXN-004, which was the constraint that made a blanket remap wrong. Three new tests pin the aligned
cases, one of them asserting `MithraOptimisticLockException.isRetriable()` — because the whole point
of the correct exception type is that an application's retry fires.

Acceptance case 4 passes, including the **retry-after-refresh-succeeds** half, which is what proves
the contract is usable rather than merely correctly named.

The only remaining red in the build is finding 28, which is Reladomo's own.

## 33. PartiQL was unbounded — `ExecuteStatement` never set a limit

Found while proving acceptance case 8 (*"Limits set through public configuration bound Query, Scan,
PartiQL, and intermediate memory"*), and it is the gap R-09's own notes predicted and nobody had
checked.

`QueryPlanExecutor.executeFanOutSelect` uses `ExecuteStatement` for a collapsed fan-out. It never
called `ExecuteStatementRequest.Builder.limit(...)`. `pageSize` was being used only as the IN-list
chunk size, so:

> two partition keys with five versions each, `pageSize=2`, `maxPages=2` collapsed to **one**
> statement and returned all ten items with **no refusal**

Query and Scan were bounded; the third path was not, and the configuration said otherwise. That is the
same shape as finding 20 (`avgItemBytes` read by nothing) and R-09 itself (`maxPages` never reaching
the plan): a limit that exists in the public API and does nothing on one path.

Fixed by applying the same `requestLimit(plan, root)` helper Query and Scan already used, via
`ExecuteStatementRequest.Builder.limit(Integer)` — javap-confirmed on AWS SDK 2.25.50. `maxPages` still
throws `PageLimitExceededException` (`RELADYNAMO-PLAN-006`) and the in-memory ceiling still throws
`RELADYNAMO-PLAN-007`, so failure stays explicit rather than becoming a partial result labelled
complete. `PaginationSafeguardFinderTest` is 8/8, with the RED captured first.

**Three of this project's defects have now been "a documented limit that one code path ignores."**
The class is worth naming: a setting is not wired because it compiles and is not enforced because a
test set it directly on the plan. Only driving it from public configuration through every execution
path finds them.

## 34. Reladomo deletes rather than inactivates inside a 10 ms bucket — NOT the adapter

Found by CI, not by the local board, which is the interesting part: `BoundWritePathTest`'s terminate
case failed on a GitHub runner while the same command passed on this machine, and passed in the
`adapter (JDK 17)` and `adapter (JDK 21)` jobs of the *same* run. So it was neither a JDK difference
nor a breakage — it was timing.

From Reladomo 18.1.0 sources, `GenericBiTemporalDirector.createProcessingTimestamp`:

```java
new Timestamp(tx.getProcessingStartTime() / 10 * 10)   // clamp for sybase
```

Every transaction's processing stamp is rounded **down to a 10 ms bucket**. `inactivateObject` then
reads, in effect:

```java
if (processingFrom(oldData) == txStartTime) {
    warn("has changed too fast. Deleting, instead of inactivating");
    delete(...);
}
```

So an insert and a terminate whose transactions start inside the same 10 ms bucket cause Reladomo to
**physically delete** the superseded version instead of closing its processing rectangle. What remains
is a single row with `businessTo` cut and `processingTo` still infinity — and the test's assertion that
"at least one version must carry a finite `processingDateTo`" is then false through no fault of the
adapter.

**Verified as Reladomo's semantics, not a divergence:** with both transactions pinned to one instant,
H2 and DynamoDB produce byte-identical shapes —
`qty=44.0 biz=[2025-12-31 17:00, 2026-05-31 18:00) proc=[2026-04-01 03:00, 9999-12-01 23:59)`. The
decision is taken in the director, above the persister, so both stores see the same instruction.

Why it never fired locally: on WSL2 over `/mnt/c`, DynamoDB Local's commit is slow enough that the two
transaction starts sat 20–80 ms apart in 25 consecutive runs, idle and under 8-way load. A Linux runner
closes that gap. It was reproduced deterministically by pinning both transactions to the same instant,
which reproduces the exact CI text at the exact line.

**Fix: the test, not the adapter.** Both transactions now pin distinct processing instants through
`DifferentialSupport.inTransaction(long, …)`, the hook the sibling bound-update test already used. **The
assertion itself is unchanged** — only its precondition is now guaranteed. The other four tests that
call `terminate()` were surveyed: three already pin the clock throughout, and `BitemporalDifferentialTest`
compares H2 against DynamoDB within one transaction, so a collision hits both sides equally. None makes
the absolute claim.

The general lesson for this suite: **any assertion about a processing-time boundary needs a pinned
clock.** Wall-clock timing makes it a coin flip on fast hardware, and the flip only ever lands wrong
somewhere you are not watching.
