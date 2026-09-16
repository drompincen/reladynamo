# Migrating a relational Reladomo application to DynamoDB

The object model does not change. The generated classes do not change. Your finder call sites do not
change. What changes is the runtime configuration — and the data has to get there.

## Stage 0 — establish the reference

Before moving anything, make the relational side reproducible: a known dataset and a passing suite.
That suite becomes the oracle for every later stage. Without it there is nothing to compare against
and "it looks right" is the only available verdict.

## Stage 1 — lift

Point the portal at the adapter:

```java
DynamoDbPersister persister = new DynamoDbPersister(
        BalanceFinder.getFinderInstance(), mapping,
        new DynamoDbWriter(client, mapping, new ItemCodec(mapping), new DefaultKeyStrategy()));

((MithraAbstractObjectPortal) BalanceFinder.getMithraObjectPortal())
        .setMithraObjectReader(persister);
```

One call binds both halves — `getMithraObjectPersister()` returns the same instance.

The mapping comes from the **same XML the generated classes came from**:

```java
EntityMapping mapping = new MithraObjectXmlParser().parse(xml);
```

All three demo projects keep their object models **byte-identical** between the H2 and DynamoDB runs —
the digest is checked in `demos/`, and that part of the claim is real: the same XML drives both.

**What the demos do not yet do is bind the portal.** `setMithraObjectReader` appears nowhere in
`demos/`. Each DynamoDB demo seeds H2 exactly as the H2 demo does, then *mirrors* the resulting rows
through `DynamoDbWriter` and compares the copy. That proves the mapping, the codec and the key layout
carry a real 46-entity model — which is worth proving — but it does **not** show unchanged business
services running against a disconnected relational source, and an earlier version of this page implied
that it did.

Closing that gap is acceptance case 12 of `docs/INSPECTION-2026-09-14.md`: one original business
operation script, run independently against each backend, with complete histories, results,
exceptions and rollback outcomes agreeing. It is blocked on the read-path and write-path findings
(R-02, R-03, R-07, and finding 21) landing first — a demo rebound onto a persister that loses a
temporal close would demonstrate the wrong thing convincingly.

## Stage 2 — backfill

`io.reladynamo.ddb.migrate.Backfill` copies existing history and **proves the copy landed**:

```java
Backfill backfill = new Backfill(client, mapping, codec, new DefaultKeyStrategy(), writer);
BackfillResult result = backfill.run(rowsFromRelational);

if (!result.verified()) {
    throw new IllegalStateException(result.summary());
}
```

Three properties worth knowing:

- **It reads back and diffs.** A partial copy leaves DynamoDB looking populated and being incomplete,
  and a run that merely reports "done" tells you nothing.
- **An empty source is not success.** `verified()` is false when zero rows were read. *"Migrated 0
  rows, verified"* is the most dangerous green a migration can print — it is indistinguishable from a
  misconfigured source query.
- **It is idempotent in key, not in ordering.** The item key includes the temporal boundaries, so the
  same logical row always lands on the same item, and re-running an interrupted migration converges
  rather than duplicating. That is a narrower guarantee than it sounds: a *stale* source row replayed
  after a newer destination write will overwrite it, because the writes are unconditional (finding
  M-05). Stable keys prevent duplicates; they do not settle ordering or ownership. Treat the source as
  immutable for the duration of the copy, or do not replay.

It refuses outright if a source row is missing a temporal boundary, rather than copying a row that
could never be addressed or read back.

## Stage 3 — dual-write and compare

Run both stores and diff continuously with the same machinery the test suite uses:

```java
RowSetDiff diff = TemporalRowSetDiffer.compare(relationalRows, dynamoRows);
if (!diff.isIdentical()) {
    log.error(diff.describe());
}
```

The differ compares temporal values **exactly** — no millisecond tolerance — and distinguishes
MISSING from EXTRA rows, because those have completely different causes.

## Stage 4 — cut over

Switch reads. Keep the relational store until the diff has been quiet for however long your
correction cycle is: a bitemporal system's whole point is that corrections arrive late, so a week of
agreement proves less here than in a non-temporal system.

## Stage 5 — the way back

**Not implemented. Do not plan a cutover that depends on it.**

An earlier version of this page said `Backfill` runs in whichever direction you supply rows. That was
wrong, and the inspection of 2026-09-14 caught it (finding M-06). `Backfill`'s destination is always
`DynamoDbWriter` and its verification client is always a DynamoDB client; supplying relational rows as
input cannot turn it into a relational importer. Only the *differ* is symmetric.

What exists today: `TemporalRowSetDiffer` will compare two row sets in either direction, so you can
**detect** divergence during a reverse migration you drive yourself. What does not exist: a DDB-to-H2
or DDB-to-ASE sink preserving temporal history and transaction boundaries.

A migration tool without a proven exit is a lock-in trap, so treat this as a live gap: either write
the reverse path against your own schema before cutover, or accept the lock-in knowingly. Rehearsing
rollback *after* destination-only writes have accumulated — not just before cutover — is the case that
actually matters, and it is the case nothing here covers yet.

## What to check before you start

| Question | Where |
|---|---|
| Does my object model map cleanly? | `MithraObjectXmlParser.parse` — throws `RELADYNAMO-CFG-nnn` with a reason |
| Will my queries plan, or Scan? | the planner refuses rather than degrading; see the explain output |
| Do my decimals survive? | stored as strings — DynamoDB's `N` trims trailing zeros |
| Are my transactions in scope? | **No transaction integration is implemented yet** — see finding R-01 in `docs/INSPECTION-2026-09-14.md`. Writes are durable on arrival, independently of whether the enclosing Reladomo transaction commits. The eventual ceiling is `TransactWriteItems`' 100 items / 4 MB. |
| Which temporal operations apply? | `docs/CONFORMANCE-FINDINGS.md` — several are director-specific |
