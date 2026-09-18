# Reladynamo

**A generic Reladomo persistence adapter for Amazon DynamoDB — with Reladomo's bitemporal semantics
preserved exactly.**

[![build](https://github.com/drompincen/reladynamo/actions/workflows/build.yml/badge.svg)](https://github.com/drompincen/reladynamo/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-GitHub%20Pages-1B6470.svg)](https://drompincen.github.io/reladynamo/)
Java 11+ · Reladomo 18.1.0 · AWS SDK for Java 2.x

**Documentation site: <https://drompincen.github.io/reladynamo/>**

> **New here?** See [start-here.md](start-here.md). The repository ships no executable scripts —
> they are stored as `*.sh.txt` and generated with `java scripts/GenerateScripts.java`.

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

<!-- ci:run-in-tempdir -->
```bash
git clone https://github.com/drompincen/reladynamo.git && cd reladynamo
```

<!-- ci:run -->
```bash
mvn clean install
```

Run the demos — each is a standalone Maven project that runs against both H2 and DynamoDB:

<!-- ci:run -->
```bash
cd demos/03-car-classifier/project && mvn clean test
```

Every shell block in this README runs verbatim in CI, in the `readme` job in
[`build.yml`](.github/workflows/build.yml). An HTML comment above each block in the Markdown source tells
CI how to run it.

## Install

**Nothing is published yet** — not to Maven Central, not to any repository. The version is
`0.1.0-SNAPSHOT` and you install from source: the Quickstart's `mvn clean install` puts every module in
your local `~/.m2`. A consumer then declares:

```xml
<dependency>
    <groupId>io.reladynamo</groupId>
    <artifactId>reladynamo-ddb</artifactId>  <!-- brings reladynamo-core -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

| Module | Contents |
|---|---|
| `reladynamo-core` | XML → mapping, key strategy, query planner. No AWS types. |
| `reladynamo-ddb` | Persister, writer, item codec, plan executor, table creator, backfill. The one to depend on. |
| `reladynamo-test-kit` | `LocalDynamoDb` in-process harness and row-set differs, for test scope. DynamoDB Local is `provided`, so declare it yourself — [`demos/03-car-classifier/project/pom.xml`](demos/03-car-classifier/project/pom.xml) shows how. |
| `reladynamo-spike`, `reladynamo-bench` | Internal: the original seam spike, and JMH benchmarks (`-Pbench` only). Not for consumers. |

**Java.** Sources compile with `maven.compiler.release=11`, so the adapter targets Java 11 and later,
and CI asserts every emitted class of every module is class-file major ≤ 55. Execution on a real JDK 11
is proven in CI for `reladynamo-core`; for `reladynamo-ddb` and `reladynamo-test-kit` it is **not
proven**, only inferred from bytecode level and API surface — `LocalDynamoDb` imports DynamoDB Local
types that are Java 17 bytecode, so those modules cannot even be compiled by a JDK 11 compiler
([details](docs/JAVA11-VERIFICATION.md)). **Building the adapter and running its tests needs JDK 17+**
for that reason; the full gate needs JDK 21, for the pet-store demo. Maven 3.9+.

Direct dependencies, from the POMs:

| Dependency | Version | Scope | Licence |
|---|---|---|---|
| `com.goldmansachs.reladomo:reladomo` | 18.1.0 | compile | Apache-2.0 |
| `software.amazon.awssdk:dynamodb` | 2.25.50 (BOM) | compile | Apache-2.0 |
| `org.slf4j:slf4j-api` | 2.0.9 | compile | MIT |
| `com.amazonaws:DynamoDBLocal` | 2.5.3 | test (`ddb`), provided (`test-kit`) | Amazon Software License — never distributed |
| `com.h2database:h2` | 2.1.210 | test | MPL 2.0 / EPL 1.0 |
| `com.goldmansachs.reladomo:reladomo-test-util` | 18.1.0 | test | Apache-2.0 |
| `org.junit.jupiter:junit-jupiter` | 5.9.3 (BOM) | test | EPL 2.0 |
| `org.assertj:assertj-core` | 3.27.0 | test | Apache-2.0 |
| `net.jqwik:jqwik` | 1.8.5 | test | EPL 2.0 |
| `org.openjdk.jmh:jmh-core` | 1.37 | `reladynamo-bench` only | GPL-2.0 with Classpath Exception |

A `maven-enforcer` rule fails the build if DynamoDB Local, its `sqlite4java` dependency or H2 reach
compile or runtime scope; that is what keeps the MIT claim true. The jqwik and JMH licences are taken
from their published POMs; the rest are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

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

Five visual walkthroughs on the [documentation site](https://drompincen.github.io/reladynamo/), for
readers who would rather see the mechanism than read the source:

- **[The Bitemporal Seam](https://drompincen.github.io/reladynamo/bitemporal-seam.html)** —
  where the adapter plugs into Reladomo, the two time axes, and how a row becomes a DynamoDB item.
- **[Bitemporality in Pictures](https://drompincen.github.io/reladynamo/bitemporality-in-pictures.html)** —
  business date against processing date on a grid, with each temporal operation drawn as a split.
- **[Your Table on DynamoDB](https://drompincen.github.io/reladynamo/table-on-dynamodb.html)** —
  the same object as relational rows and as DynamoDB items, key by key.
- **[Planning a Bitemporal Query](https://drompincen.github.io/reladynamo/query-planner.html)** —
  how an arbitrary predicate becomes key conditions, filters, in-memory residuals, or a refusal.
- **[What You Give Up](https://drompincen.github.io/reladynamo/what-you-give-up.html)** —
  transactions, arbitrary predicates, item size, index consistency, and the workaround for each.

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
                   toIsInclusive="false" isProcessingDate="false" futureExpiringRowsExist="true"
                   infinityDate="[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]"/>
    <AsOfAttribute name="processingDate" fromColumnName="IN_Z" toColumnName="OUT_Z"
                   toIsInclusive="false" isProcessingDate="true"
                   infinityDate="[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]"/>

    <Attribute name="balanceId" javaType="int" columnName="BALANCE_ID" primaryKey="true"/>
    <Attribute name="quantity"  javaType="double" columnName="QUANTITY"/>
</MithraObject>
```

`futureExpiringRowsExist` is Reladomo's own semantics, not the adapter's, and it changes which rows you
get: without it, closing an open-ended row that has already started also cuts that row's `THRU_Z` back
to the transaction time plus a day, rather than leaving it at infinity. It is set here so the worked
example in [Your Table on DynamoDB](https://drompincen.github.io/reladynamo/table-on-dynamodb.html)
matches what a reader will see.

### 2. Derive the DynamoDB mapping from that same XML

```java
EntityMapping mapping = new MithraObjectXmlParser().parse(xml);
```

Everything physical is derived: table name from `<DefaultTable>`, partition key from the primary key,
sort key from the temporal axes. No second schema to keep in step.

### 3. Bind the persister to the portal

```java
ItemCodec codec = new ItemCodec(mapping);
PhysicalDesign design = PhysicalDesign.builder(mapping)
        .infinityFrom(BalanceFinder.getFinderInstance())
        .build();

DynamoDbPersister persister = new DynamoDbPersister(
        BalanceFinder.getFinderInstance(), mapping,
        new DynamoDbWriter(client, mapping, codec, new DefaultKeyStrategy(), design.gsis()),
        new QueryPlanner(), new QueryPlanExecutor(client, codec), design, PlannerConfig.defaults());

MithraAbstractObjectPortal portal =
        (MithraAbstractObjectPortal) BalanceFinder.getMithraObjectPortal();
portal.setMithraObjectReader(persister);
```

One call binds **both** halves — `getMithraObjectPersister()` returns the same instance. Your finder
call sites do not change.

All seven arguments are needed for a persister that reads. There is a shorter three-argument
constructor, but it builds a **write-only** persister: every read refuses by name, saying it was built
write-only, rather than returning an empty result that would look like a working query.

## How data is stored

Table per object. Every item carries:

| Key | Format | Example |
|---|---|---|
| `pk` | `v1#<CLASS>#<pk components>` | `v1#BALANCE#42` |
| `sk` | `v1#P#<processingFrom>#B#<businessFrom>` | `v1#P#20260601000000000#B#20260101000000000` |
| audit-only / business-only `sk` | `v1#P#<processingFrom>` / `v1#B#<businessFrom>` | one axis, same encoding |
| non-dated `sk` | `v1#ND` | keeps the key schema uniform across all tables |

Timestamps are fixed-width, UTC, and lexicographically sortable, so temporal ranges become native sort-key
conditions. **Processing-major ordering is deliberate** — and it has a consequence worth knowing: a
`businessDate`-only predicate is a non-contiguous *suffix* of the sort key and therefore cannot be a
native range condition. The planner handles it as a filter rather than pretending otherwise.

## Key design guide

In short: **keep the default key strategy and one table per object, and add a GSI only for an access
path you have promised and measured.** The reasoning is in the [topology decision](docs/TOPOLOGY-DECISION.md)
and the [indexing prescription](docs/INDEXING-PRESCRIPTION.md). Both were written against the
2026-09-14 source; for defects they name that have since closed, see [tracking](docs/INSPECTION-TRACKING.md).

**The default, `DefaultKeyStrategy`,** is the grammar in the table above. Two consequences:

- The class token is the **simple** class name, upper-cased: `com.crm.Payment` and `com.pets.Payment`
  both key as `v1#PAYMENT#…`. Harmless with a table per object; a collision if two entities ever share
  a table. Renaming a class changes its stored keys, so a rename is a data migration.
- Key components must be String, boolean, integral, char, `Timestamp`, `Date`, `Time`, `BigDecimal` or
  `byte[]`, and must not contain `#`. `float`/`double` keys and `#` are refused by name, never written.
  Keys are checked against the 2048-byte (`pk`) and 1024-byte (`sk`) limits.

**When to override: not in 0.1.0.** `DynamoDbWriter` accepts any `KeyStrategy`, but the read path
does not: the planner encodes keys with the fixed v1 grammar (`PartitionKeyEncoder`), so a custom
strategy writes items that finders cannot find, and nothing checks that the two agree. The cases that
really need another layout (one object too hot for a partition, parents colocated with children,
consolidated tables) are re-keying migrations with their own procedure (topology decision §3–4), not a
configuration switch.

**Topology.** One table per object, no LSIs, no shared-table mode. A single table would buy no
atomicity, because `TransactWriteItems` already spans tables. It would not save requests either:
Reladomo resolves each relationship through that entity's own portal.

**GSI rules.**

1. **None by default.** An index exists only if you declare it with `PhysicalDesign.builder(mapping).addGsi(spec)`,
   pass `design.gsis()` to the `DynamoDbWriter` (which stamps the index keys), and create tables with
   `TableCreator.create(design)`. All three must see the same list.
2. **The proven profile is a foreign key with `ALL` projection:** `GsiSpec.foreignKey("gsi_customerId", "customerId")`.
   HASH is `v1#GSI#CUSTOMERID#<value>`, RANGE is the base `sk`, and rows with a null FK are left out.
   This is what makes deep fetch work: 24 children across 8 parents in one request, in the differential suite.
3. **The sparse current-row index is optional, so measure first.** `GsiSpec.sparseCurrent(name, pkJavaNames)` indexes only
   rows whose processing-to is infinity. The planner uses it for current-processing reads with a business
   as-of when `PlannerConfig.estimatedVersionsPerKey` is above 4 (default 16), a threshold nobody has calibrated.
   Declare at most one per entity. It is tested against DynamoDB Local, but not differentially.
4. **Only `ALL` projection.** `KEYS_ONLY` and `INCLUDE` are refused by `TableCreator` (`RELADYNAMO-CFG-014`):
   the adapter does not hydrate missing attributes. `GsiSpec.uniqueAttribute` builds a `KEYS_ONLY` spec,
   so it is refused today.
5. **Eventually consistent, so kept off correctness paths.** GSI plans read with `ConsistentRead=false`
   and are never chosen for refresh, date-range or delete planning, or when `PlannerConfig` sets
   `inTransaction(true)` or `allowGsi(false)`. **The persister does not yet detect a live Reladomo
   transaction by itself.** Under the default config, a find inside a transaction can still use a GSI
   (indexing prescription §6).
6. **Add one index per promised access path, not one per relationship.** An unindexed non-key path is refused
   (`RELADYNAMO-PLAN-001`) rather than scanned. Every `ALL` index adds write units on each entry change;
   §5 of the prescription says when that pays.

## Limits, stated plainly

- **Transactions.** DynamoDB has no interactive transaction. A Reladomo transaction commits as one
  `TransactWriteItems` across all entity tables, which caps at 100 actions and 4 MB. Scopes wider than
  that are refused rather than chunked, because chunking would only hide the loss of atomicity.
- **Arbitrary predicates.** DynamoDB queries start from an exact partition-key value. Predicates that
  cannot reach one require a Scan, which is **opt-in only** — the planner refuses by default rather
  than silently running one.
- **Item size.** 400 KB per item, 2048-byte partition key, 1024-byte sort key.
- **GSI consistency.** Global secondary indexes are eventually consistent — DynamoDB permits no
  consistent read on one, and the planner marks every index plan accordingly. `refresh` therefore never
  uses an index: it is a strongly consistent `GetItem` against the base table. Inside a transaction,
  any read that follows a staged write is refused outright (`RELADYNAMO-TXN-006`) rather than served
  from either the index or committed state.
- **`BigDecimal` is stored as a string**, because DynamoDB's `N` type trims trailing zeros and would
  silently change the scale of monetary values.

## Operations

**None of this has been measured against real DynamoDB.** Each figure below is either one of DynamoDB's
billing rules or an estimate the planner computes, so treat this section as a method rather than data.
Per-row CPU is not where the cost is ([performance](docs/PERFORMANCE.md)): request count and items
examined are.

### Capacity planning

- **Start on-demand.** `TableCreator` creates `PAY_PER_REQUEST` tables by default. With
  `TableCreator.Options.builder().billingMode(BillingMode.PROVISIONED).readCapacityUnits(r).writeCapacityUnits(w)`
  it gives every GSI the **same**
  RCU/WCU as its table. That is a placeholder, not a plan: size each index from its own traffic.
- **One table per object.** The CRM demo has 46 tables. Check your account's per-Region table quota and the per-table GSI quota before you deploy.
- **Reads pay for history.** Without a current-row index, a bitemporal as-of read queries the object's
  whole partition and filters it (see the plan below), so cost grows with the number of stored versions.
  Heavily corrected objects cost the most, and they are also the hot-partition risk.
- **Writes.** A Reladomo transaction commits as one `TransactWriteItems`, billed at twice the ordinary
  write units. A correction that closes `c` rows and inserts `d` rows is `c + d` item writes, plus
  index maintenance on every `ALL` GSI the changed rows belong to ([worked example](docs/INDEXING-PRESCRIPTION.md#5-write-cost-and-where-an-index-stops-paying)).

### Cost model

Every plan carries a read estimate built from DynamoDB's rule: reads are charged on bytes **examined**,
in 4 KB blocks, at half price when eventually consistent.

```
estimatedRcu = ceil(estimatedItemsExamined × avgItemBytes / 4096) × (consistentRead ? 1 : 0.5)
```

Its inputs come from `PlannerConfig.builder().estimatedVersionsPerKey(n).avgItemBytes(n)`, which default
to 16 and 1024. Set them from your data, or the estimate means nothing. It exists to compare access paths
at plan time. It is not a bill: it has no prices and does not model a GSI's projected size.

The guards that bound cost fail by name instead of degrading silently:

| Guard (`PlannerConfig`) | Default | Refusal |
|---|---|---|
| `allowTableScan` | `false` | `RELADYNAMO-PLAN-001` |
| `pkFanOutLimit`, partition keys per IN/OR | 100 (hard max 1000) | `RELADYNAMO-PLAN-002` |
| `maxPages` per query | 64 | `RELADYNAMO-PLAN-006`, never a truncated result |
| `inMemoryRowCeiling` per query | 50,000 | `RELADYNAMO-PLAN-007` |

### Monitoring

**Reladynamo has no metrics hook and emits no logs.** The adapter has no listener, metrics or logging API
(`slf4j-api` is declared but never called), and it does not record consumed **write** capacity. Production
metrics come from DynamoDB itself, through CloudWatch's per-table and per-GSI consumed capacity, throttling
and latency, and from whatever you configure on the `DynamoDbClient` you pass in. The adapter's own
signal is the read explain data below. For each access path, watch items examined against items returned
(a wide gap is a filter doing a key's job), requests per finder call (fan-out), `PLAN-00x` refusals, and GSI
throttling, which back-pressures writes to the base table.

### Explain plan

There are two views of each query:

- `QueryPlan.explain()` returns an `ExplainPlan`: the static decision, with no I/O.
- `QueryPlanExecutor.lastExplain()` returns an `ExecutionExplain`: what the most recent `execute` did,
  taken from DynamoDB's `ScannedCount`, `Count` and `ConsumedCapacity`.

Both need the read path, so build the persister with all seven arguments and keep a reference to the
executor. The three-argument constructor shown in Usage is write-only, and its read methods refuse by name.

```java
ItemCodec codec = new ItemCodec(mapping);
PhysicalDesign design = PhysicalDesign.builder(mapping)
        .infinityFrom(BalanceFinder.getFinderInstance())
        .build();
QueryPlanExecutor executor = new QueryPlanExecutor(client, codec);
DynamoDbPersister persister = new DynamoDbPersister(
        BalanceFinder.getFinderInstance(), mapping,
        new DynamoDbWriter(client, mapping, codec, new DefaultKeyStrategy(), design.gsis()),
        new QueryPlanner(), executor, design, PlannerConfig.defaults());
// bind to the portal as in Usage step 3, run a finder, then:

ExecutionExplain actual = executor.lastExplain();        // null until the first execute
System.out.println(actual.plan().explain());             // the static plan
System.out.printf("examined=%d returned=%d rcu=%.1f pages=%d requests=%d %dms%n",
        actual.actualItemsExamined(), actual.actualItemsReturned(), actual.consumedCapacityRcu(),
        actual.pageCount(), actual.requestCount(), actual.durationMs());
```

Here is a real plan for a current-row bitemporal read, printed by `FinderDrivenDifferentialTest` (wrapped here):

```
QueryPlan{class=io.reladynamo.ddb.differential.domain.DiffBalance, index=PRIMARY, method=QUERY,
  key=#pk = :pk, filter=#OUT_Z = :v0 AND #FROM_Z <= :v1 AND #THRU_Z > :v1, residual=,
  examined~=16, rcu~=4.0, returned~=1, fastPath=CURRENT_ASOF}
```

| Field | Meaning |
|---|---|
| `index` | `PRIMARY`, or the GSI name |
| `method` | `GET_ITEM`, `QUERY`, `QUERY_FAN_OUT`, `SCAN`, or `EMPTY` (no rows possible, no request sent) |
| `key`, `filter` | The key condition and the server-side filter. Filtered-out items are still billed. |
| `residual` | Predicate evaluated in memory after decoding, where DynamoDB cannot express it |
| `examined~`, `rcu~`, `returned~` | Plan-time estimates |
| `fastPath` | `POINT_GET` (exact key), `CURRENT_ASOF` (current-row read) or `NONE` |

Two caveats. `lastExplain()` is a single field that every `execute` on that executor overwrites, and it is
not thread-safe, so under concurrent use it can describe another thread's query: use it for diagnostics,
not as a metrics feed. And when a fan-out collapses to a PartiQL `IN`, `ExecuteStatement` reports no
`ScannedCount`, so `actualItemsExamined` counts returned items and understates the real work.

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

| Demo | Build JDK | Source level | Entities | Shape |
|---|---|---|---|---|
| [`01-crm-bitemporal`](demos/01-crm-bitemporal) | 17 | 11 | ~46 | Bitemporal + audit-only + plain |
| [`02-petstore-unitemporal`](demos/02-petstore-unitemporal) | 21 | 21 | ~22 | One `AsOfAttribute` |
| [`03-car-classifier`](demos/03-car-classifier) | 17 | 11 | 5 | Bitemporal **rules** |

## Building

<!-- ci:run -->
```bash
java scripts/GenerateScripts.java   # generate scripts from their *.sh.txt sources
mvn clean install                   # all modules
bash scripts/check.sh               # the full gate, including the H2-vs-DynamoDB differential suite
```

Java 11 is the **compatibility floor, not the ceiling**: sources compile with
`maven.compiler.release=11` and the gate verifies every emitted class is class-file major ≤ 55.

Tests use DynamoDB Local **in-process** — no Docker required.

## Licence

MIT — see [LICENSE](LICENSE). Reladomo is Apache-2.0 and DynamoDB Local ships under the Amazon
Software Licence (test scope only); see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
