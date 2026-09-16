# Reladynamo

**A generic Reladomo persistence adapter for Amazon DynamoDB — with Reladomo's bitemporal semantics
preserved exactly.**

[![build](https://github.com/drompincen/reladynamo/actions/workflows/build.yml/badge.svg)](https://github.com/drompincen/reladynamo/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-GitHub%20Pages-1B6470.svg)](https://drompincen.github.io/reladynamo/)
Java 11+ · Reladomo 18.1.0 · AWS SDK for Java 2.x

**Documentation site: <https://drompincen.github.io/reladynamo/>**

Point an existing [Reladomo](https://github.com/goldmansachs/reladomo) object model at DynamoDB
without changing your object model XML, your generated classes, or a single finder call site. Only
the runtime configuration differs.

---

## The core idea

Reladomo's bitemporal logic lives in its `TemporalDirector`, **above** the persistence layer. When you
call `terminate()` or `updateUntil()`, the director works out which rows should exist and hands the
persister plain inserts, updates and deletes of data objects that already carry their own
`businessDate` and `processingDate` boundaries.

So Reladynamo does not reimplement bitemporality — it **inherits** it:

```
  Finder / your code
        ↓
  MithraObjectPortal
        ↓
  TemporalDirector          ← bitemporal semantics live here, unchanged
        ↓
  MithraDatedObjectPersister ← Reladynamo replaces only this
        ↓
  DynamoDB
```

That is the whole design, and it is why "the same bitemporal features" is a claim we can test rather
than an aspiration. Any temporal reasoning added below that seam would diverge from a relational
Reladomo, which is exactly what the differential test suite exists to catch.

## Status

From the closed-loop gate (`scripts/check.sh`) as of 2026-09-16 — **8 of 8 gates passing**, 689
adapter tests, and **78 storage-path + 176 query-path** H2-vs-DynamoDB differential tests.

| Area | State |
|---|---|
| Architecture (portal binding, read + write seam) | **Proven in running code** |
| XML → DynamoDB mapping, key strategy, preflight validation | **Working** — unsupported shapes refused by name (`RELADYNAMO-CFG-nnn`) before any write |
| JSON item codec (incl. property-based fidelity tests) | **Working** |
| `Operation` → DynamoDB query planner (incl. adversarial fuzzer and finder matrix) | **Working, differentially verified** |
| Write path, including conditional writes | **Working, differentially verified** |
| Transactions | One Reladomo transaction commits as one `TransactWriteItems`; a failed transaction leaves no partial durable result |
| Required SPI paths | Exercised by the demo applications with the relational source disconnected |
| [2026-09-14 inspection](docs/INSPECTION-2026-09-14.md) | **10 of 13 acceptance cases pass**; the other three are declared out of scope for 0.1.0 ([tracking](docs/INSPECTION-TRACKING.md)) |

**Not production ready.** Nothing has touched a real AWS endpoint yet (tests use DynamoDB Local
in-process), and the adapter has not run under load. Online migration, reverse migration and Sybase ASE
extraction are out of scope for 0.1.0. Read [`docs/SUPPORT-CONTRACT.md`](docs/SUPPORT-CONTRACT.md)
before planning a migration, and [`docs/RELEASE-READINESS.md`](docs/RELEASE-READINESS.md) for what is
and is not proven.

## Quickstart

```bash
git clone https://github.com/drompincen/reladynamo.git && cd reladynamo
mvn clean install
```

Run the demos — each is a standalone Maven project that runs against both H2 and DynamoDB:

```bash
cd demos/03-car-classifier/project && mvn clean test
```

## Understanding bitemporality in 60 seconds

Start with [`demos/03-car-classifier`](demos/03-car-classifier). A decision table classifies cars, and
**the rules are bitemporal, not the cars**. The same unchanged 1985 Toyota MR2 row:

```
Car                                2012-06-01      2016-06-01      2021-06-01      2026-09-12
1985 Toyota MR2 coupe              COOL            EIGHTIES_COOL   RETRO           RETRO
1957 Chevrolet Bel Air coupe       CLASSIC         CLASSIC         CLASSIC         VINTAGE
```

Nothing about the cars changed. Taste did, and the rule table records when — so a classification can
be reproduced exactly as it would have been made at any past date.

The second axis is the subtle one. Correct a rule that was *wrong when it was written*, and re-running
a past classification now disagrees with the audit row stored at the time: *"what we said then"*
versus *"what we now think we should have said then"*. That distinction is the entire reason
bitemporal storage exists.

## Explainers

Two visual walkthroughs on the [documentation site](https://drompincen.github.io/reladynamo/), for
readers who would rather see the mechanism than read the source:

- **[The Bitemporal Seam](https://drompincen.github.io/reladynamo/bitemporal-seam.html)** —
  where the adapter plugs into Reladomo, the two time axes, and how a row becomes a DynamoDB item.
- **[Planning a Bitemporal Query](https://drompincen.github.io/reladynamo/query-planner.html)** —
  how an arbitrary predicate becomes key conditions, filters, in-memory residuals, or a refusal.

## Documentation

| Topic | Documents |
|---|---|
| Scope and readiness | [Support contract](docs/SUPPORT-CONTRACT.md) · [Release readiness](docs/RELEASE-READINESS.md) · [Migration guide](docs/MIGRATION.md) · [Security review](docs/SECURITY-REVIEW.md) |
| Design | [XML → DynamoDB mapping](docs/design/01-xml-to-ddb-mapping.md) · [Config and bootstrap](docs/design/02-java-config-and-bootstrap.md) · [Item codec](docs/design/03-json-item-codec.md) · [Query planner](docs/design/04-operation-to-ddb-query-planner.md) · [Topology decision](docs/TOPOLOGY-DECISION.md) · [Indexing prescription](docs/INDEXING-PRESCRIPTION.md) |
| Verification | [Inspection](docs/INSPECTION-2026-09-14.md) · [Tracking](docs/INSPECTION-TRACKING.md) · [Finder matrix](docs/FINDER-MATRIX.md) · [Conformance findings](docs/CONFORMANCE-FINDINGS.md) · [Coverage gaps](docs/COVERAGE-GAPS.md) · [Assumption challenges](docs/ASSUMPTION-CHALLENGE.md) · [Java 11](docs/JAVA11-VERIFICATION.md) · [Performance](docs/PERFORMANCE.md) |

## Usage

### 1. Keep your object model XML unchanged

```xml
<MithraObject objectType="transactional">
    <PackageName>com.acme.domain</PackageName>
    <ClassName>Balance</ClassName>
    <DefaultTable>BALANCE</DefaultTable>

    <AsOfAttribute name="businessDate" fromColumnName="FROM_Z" toColumnName="THRU_Z"
                   toIsInclusive="false" isProcessingDate="false"
                   infinityDate="[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]"/>
    <AsOfAttribute name="processingDate" fromColumnName="IN_Z" toColumnName="OUT_Z"
                   toIsInclusive="false" isProcessingDate="true"
                   infinityDate="[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]"/>

    <Attribute name="balanceId" javaType="int" columnName="BALANCE_ID" primaryKey="true"/>
    <Attribute name="quantity"  javaType="double" columnName="QUANTITY"/>
</MithraObject>
```

### 2. Derive the DynamoDB mapping from that same XML

```java
EntityMapping mapping = new MithraObjectXmlParser().parse(xml);
```

Everything physical is derived: table name from `<DefaultTable>`, partition key from the primary key,
sort key from the temporal axes. No second schema to keep in step.

### 3. Bind the persister to the portal

```java
DynamoDbPersister persister = new DynamoDbPersister(
        BalanceFinder.getFinderInstance(), mapping,
        new DynamoDbWriter(client, mapping, new ItemCodec(mapping), new DefaultKeyStrategy()));

MithraAbstractObjectPortal portal =
        (MithraAbstractObjectPortal) BalanceFinder.getMithraObjectPortal();
portal.setMithraObjectReader(persister);
```

One call binds **both** halves — `getMithraObjectPersister()` returns the same instance. Your finder
call sites do not change.

## How data is stored

Table per object. Every item carries:

| Key | Format | Example |
|---|---|---|
| `pk` | `v1#<CLASS>#<pk components>` | `v1#BALANCE#42` |
| `sk` | `v1#P#<processingFrom>#B#<businessFrom>` | `v1#P#20260601000000000#B#20260101000000000` |
| non-dated `sk` | `v1#ND` | keeps the key schema uniform across all tables |

Timestamps are fixed-width, UTC, and lexicographically sortable, so temporal ranges become native sort-key
conditions. **Processing-major ordering is deliberate** — and it has a consequence worth knowing: a
`businessDate`-only predicate is a non-contiguous *suffix* of the sort key and therefore cannot be a
native range condition. The planner handles it as a filter rather than pretending otherwise.

## Limits, stated plainly

- **Transactions.** DynamoDB has no interactive transaction. A Reladomo transaction commits as one
  `TransactWriteItems` across all entity tables, which caps at 100 actions and 4 MB. Scopes wider than
  that are refused rather than chunked, because chunking would only hide the loss of atomicity.
- **Arbitrary predicates.** DynamoDB queries start from an exact partition-key value. Predicates that
  cannot reach one require a Scan, which is **opt-in only** — the planner refuses by default rather
  than silently running one.
- **Item size.** 400 KB per item, 2048-byte partition key, 1024-byte sort key.
- **GSI consistency.** Global secondary indexes are eventually consistent and are never used inside a
  transaction or for `refresh`.
- **`BigDecimal` is stored as a string**, because DynamoDB's `N` type trims trailing zeros and would
  silently change the scale of monetary values.

## Migrating an existing application

`docs/MIGRATION.md` walks the five stages: lift, backfill, dual-write, cut over, and the way back.

The backfill tool proves its own work rather than reporting success:

```java
BackfillResult result = new Backfill(client, mapping, codec, keys, writer).run(rows);
if (!result.verified()) {
    throw new IllegalStateException(result.summary());
}
```

It reads back and diffs, it is idempotent so an interrupted run converges rather than duplicating,
and **an empty source is not treated as success** — "migrated 0 rows, verified" is indistinguishable
from a misconfigured query.

## Demos

| Demo | JDK | Entities | Shape |
|---|---|---|---|
| [`01-crm-bitemporal`](demos/01-crm-bitemporal) | 11 | ~46 | Bitemporal + audit-only + plain |
| [`02-petstore-unitemporal`](demos/02-petstore-unitemporal) | 21 | ~22 | One `AsOfAttribute` |
| [`03-car-classifier`](demos/03-car-classifier) | 11 | 5 | Bitemporal **rules** |

## Building

```bash
mvn clean install          # all modules
bash scripts/check.sh      # the full gate, including the H2-vs-DynamoDB differential suite
```

Java 11 is the **compatibility floor, not the ceiling**: sources compile with
`maven.compiler.release=11` and the gate verifies every emitted class is class-file major ≤ 55.

Tests use DynamoDB Local **in-process** — no Docker required.

## Licence

MIT — see [LICENSE](LICENSE). Reladomo is Apache-2.0 and DynamoDB Local ships under the Amazon
Software Licence (test scope only); see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
