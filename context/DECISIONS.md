# Architecture Decisions

<!-- Format:
## [Date] Decision Title
**Context:** Why this decision was needed
**Decision:** What was decided
**Consequences:** Trade-offs accepted
-->

## 2026-09-12 — Reladomo→DynamoDB integration seam

**Decision: bind a custom persister via `MithraAbstractObjectPortal.setMithraObjectReader`.**

Verified with `javap` against `reladomo-18.1.0.jar`:

- `public void setMithraObjectReader(MithraObjectReader)` exists and is public on
  `com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal`. **No Reladomo fork is required.**
- Bitemporal semantics live in `TemporalDirector` *above* the persister, so implementing
  `MithraDatedObjectPersister` inherits them rather than reimplementing them.
- `PureMithraObjectPersister` is the existing non-SQL precedent to model on.

**Rejected:** implementing `DatabaseType` — that is the JDBC dialect layer and would mean generating
SQL for a store that has none.

**Rejected as primary:** the pure-object route — it requires `*ObjectFactory` generation and so does
not work against existing transactional XML unchanged.

**Known risk (~55% confidence):** `mithraTuplePersister` is a `private transient` field on the portal
with a getter but **no setter**, supplied only through the constructor. After a reader swap the portal
still holds the original JDBC tuple persister. Harmless for normal dated transactional work; a problem
if Reladomo temp-tuple / analytic temp tables are exercised.

**Fallback (~90% confidence):** hand-written `*DatabaseObject` subclasses that *are* the Dynamo
persister from construction, so both fields are correct from birth. Preferred over reflection into the
private field, which is a last resort and would pin us to 18.1.0.

**Also decided:** table-per-object (not classic single-table) — Reladomo finders are per-object;
sort key `v1#P#<processingDateFrom>#B#<businessDateFrom>`; infinity from
`AsOfAttribute.getInfinityDate()` encoded to sort last.

Design detail: `docs/design/01-xml-to-ddb-mapping.md`, `02-java-config-and-bootstrap.md`,
`03-json-item-codec.md`.

## 2026-09-12 — Spike result: portal rebinding proven in a running JVM

`reladynamo-spike` — 2 tests green. A `reladomogen`-generated bitemporal `SpikeBalance` on H2, whose
portal was re-pointed via `MithraAbstractObjectPortal.setMithraObjectReader` at a recording reader.
`findMany().forceResolve()` routed through it (asserted on call count, so a bypass fails the test).

**The architecture is viable.** Not "the method exists" — it actually intercepts.

Residual risk confirmed, not resolved: `getMithraTuplePersister()` still returns the original
JDBC-backed instance after the swap, since the field is constructor-supplied and private. Pinned by a
test so it cannot quietly change. Still to prove: whether the dated transactional write path ever
touches it.

Also verified: `maven.compiler.release=11` emits class-file major 55.

## 2026-09-13 — Write path confirmed: one setter binds both halves

`WritePathSpikeTest` (green). `MithraAbstractObjectPortal` exposes `getMithraObjectPersister()` with
**no matching setter**, which raised the worst-case question: could reads route to the adapter while
writes silently continued to JDBC? That failure mode would look like success.

**Answer: no.** A `MithraObjectReader` that also implements `MithraDatedObjectPersister`, installed via
`setMithraObjectReader`, is returned by `getMithraObjectPersister()` — asserted with `isSameAs`.

**Consequence:** the adapter needs exactly **one** seam, not two. `DynamoDbPersister` implements
`MithraObjectReader` + `MithraDatedObjectPersister` and is installed with a single call. Strategy A is
fully viable for both directions.

Fallback C (hand-written `*DatabaseObject` subclasses) is now needed **only** if the dated
transactional path turns out to touch `mithraTuplePersister`, which remains constructor-supplied and
unswappable. That is a narrower risk than previously recorded.

## 2026-09-13 — Sort-key format conflicts (raised by the mapping agent's OBJECTIONS.md)

The agent implemented the design as instructed and filed five objections. Three are real conflicts,
and I created the first one. Resolutions:

**1. Sort-key timestamp format — `TemporalEncoder` wins; the design doc is updated.**
Design §2.4 specified ISO-8601 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (23 chars); the tested `TemporalEncoder`
emits `yyyyMMddHHmmssSSS` (17). Both are fixed-width, UTC and lexicographically sortable, so the
choice is cost versus readability: 34 vs 46 bytes of sort key on *every item*, against ISO being
easier to read in the AWS console. Keeping the 17-char form — it already has 7 passing tests and the
saving is per-item and permanent, while the readability loss is confined to debugging. Mitigation: the
explain plan renders keys human-readably, so console archaeology is not the normal debugging path.
**Action: update `docs/design/01-xml-to-ddb-mapping.md` §2.4 rather than leave the docs disagreeing.**

**2. Non-temporal sort key — the DESIGN wins; my task instruction was wrong.**
I told the agent `Flavour.NONE` has no sort key; the design says `v1#ND`. The agent implemented `null`
and flagged that "a later table-creator that assumes every item has an SK will need a decision" —
correct, and that is the deciding argument. A uniform key schema across every table keeps table
creation, the executor and the planner from special-casing non-dated entities. **Use `v1#ND`.**

**3. Entity token case — the DESIGN wins.** `v1#CUSTOMER#…` uppercased, not `v1#Customer#…`.
It matches the design's examples and the `/dynamodb-architect` convention, and removes any dependence
on Java class-name casing in a persisted key.

**4/5. Error-code hygiene — accepted.** Add a dedicated code for XML parse/XXE failures instead of
overloading `CFG-001`, and keep the distinct `MithraTempObject` message.

These are applied at integration, not mid-flight — the agent is still running.

## 2026-09-13 — BigDecimal is stored as `S`, not `N` (raised by the codec agent)

DynamoDB's `N` type **trims trailing zeros**, so `1.10` round-trips as scale-1 `1.1`. For money in a
bitemporal ledger that is silent data loss — the value compares equal but the scale, which carries
presentation and rounding meaning, is gone.

`BigDecimal` is therefore stored as `S` via `toPlainString()`, while still rejecting values outside
DynamoDB's numeric envelope (38 significant digits, exponent range, negative scale) so a later switch
back to `N` remains possible.

Cost: `S` cannot be used in a native numeric key condition or `BETWEEN` on a numeric axis. No current
access pattern needs that — temporal ranges use the sort key, which is unaffected.

My task brief and design 01 both said `N`. Design 03 (which owns wire types) said `S` and gave the
trailing-zero reason. **The agent followed the more specific authority and was right.** I would have
shipped the `N` bug.

## 2026-09-13 — Nulls are stored as explicit DynamoDB `NULL`, not omitted

Omission cannot distinguish "this value is SQL NULL" from "this attribute did not exist in the schema
version that wrote the item". Explicit `NULL` keeps those distinct, while decode still maps a *missing*
attribute to Java null so additive schema evolution keeps working. Omission stays reserved for sparse
GSI keys, which is a persister concern rather than a codec one.
