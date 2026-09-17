# crm-bitemporal-demo

Standalone Reladomo 18.1.0 + H2 2.1.210 CRM that is the H2 reference implementation
for Reladynamo. The same object model is later expected to persist to DynamoDB and
return identical as-of results.

Package root: `com.reladynamo.demo.crm`. Compiled with `maven.compiler.release=11`
(Java 11 language + API + class-file 55) on the installed JDK 21.

## Domain

A sales CRM with 46 entities across three Reladomo temporal flavours:

| Flavour | Reladomo shape | Used for |
|---|---|---|
| BITEMPORAL | `businessDate` (from/thru) + `processingDate` (in/out) | Facts that can be corrected retroactively |
| AUDIT | `processingDate` only | Immutable activity (calls, emails, payments) |
| PLAIN | no `AsOfAttribute` | Reference data (industry, product, pipeline stage) |

Infinity sentinel: `9999-12-01 23:59:00.000` UTC (`InfinityTimestamp`).

Seed data is **exactly 100 logical records**, inserted and mutated at fixed UTC
processing times. There is no `System.currentTimeMillis()` in data setup.
`MithraTransaction.setProcessingStartTime` freezes knowledge time so reruns are
bit-identical.

## How to run

JDK 21 is the build host. No JDK 11 runtime is required for this demo.

```bash
cd crm-bitemporal-demo
mvn -q clean test          # Reladomo codegen + 14 demonstration tests
mvn -q exec:java           # print the seven as-of tables (after a compile/test)
```

`exec:java` needs compiled classes. After a clean tree use:

```bash
mvn -q compile exec:java
```

Code generation is bound to `generate-sources` via `maven-antrun-plugin` +
`reladomogen` 18.1.0 (not the unpublished `reladomo-mithra-plugin`).

## What each demonstration proves

Every demonstration is also a JUnit 5 + AssertJ test in
`RequiredDemonstrationsTest`. The printed tables are the bitemporal rectangles:
same business date, two processing dates, different facts.

### 1. Retroactive address correction

Acme moved on **business date 2025-03-01**; operations only learned on
**processing date 2025-06-15**. Querying business date 2025-04-01 from
2025-04-02 still shows Market St; querying the same business date from today
shows Mission St.

```
asOfBusiness | asOfProcessing             | line1          | city          | postal
-------------+----------------------------+----------------+---------------+-------
2025-04-01   | 2025-04-02 (as known then) | 100 Market St  | San Francisco | 94103
2025-04-01   | 2025-10-01 (as known now)  | 200 Mission St | San Francisco | 94105
```

### 2. Territory reassignment effective in the past

West (Alice) was restated to East (Bob) effective 2025-02-01, recorded 2025-08-01.
Commission owner follows the assignment visible in that rectangle.

```
asOfBusiness | asOfProcessing             | territoryId | repId | commissionOwner
-------------+----------------------------+-------------+-------+----------------
2025-04-01   | 2025-04-02 (as known then) | 1           | 1     | Alice
2025-04-01   | 2025-10-01 (as known now)  | 2           | 2     | Bob
```

### 3. Opportunity amount restated after close

Closed-won 2025-05-15 at $100,000; restated to $85,000 on 2025-08-01. An audit
report as-of 2025-06-02 still reproduces the originally reported pipeline.

```
asOfBusiness | asOfProcessing            | stage      | amount    | probability
-------------+---------------------------+------------+-----------+------------
2025-06-01   | 2025-06-02 (audit report) | CLOSED_WON | 100000.00 | 100
2025-06-01   | 2025-10-01 (as known now) | CLOSED_WON | 85000.00  | 100
```

### 4. Consent withdrawn retroactively (GDPR)

Outreach was sent 2025-04-01 14:00. Consent was later withdrawn effective
2025-03-15 (recorded 2025-07-01). As known then the send was lawful; as known
now consent is absent for that same business date.

```
asOfBusiness | asOfProcessing             | granted | lawfulBasis | outreachLawful
-------------+----------------------------+---------+-------------+---------------
2025-04-01   | 2025-04-02 (as known then) | true    | consent     | YES
2025-04-01   | 2025-10-01 (as known now)  | false   | withdrawn   | NO
```

### 5. Terminate / expire

`Subscription.terminate()` at business 2025-08-15 and `SalesRep.terminate()` at
business 2025-08-31. Current-knowledge queries see the objects before those
dates and nothing after.

```
entity         | asOfBusiness | asOfProcessing | present | detail
---------------+--------------+----------------+---------+----------------------
Subscription   | 2025-08-01   | 2025-10-01     | YES     | mrr=10000.00
Subscription   | 2025-08-16   | 2025-10-01     | NO      |
SalesRep Carol | 2025-08-01   | 2025-10-01     | YES     | carol@reladynamo.test
SalesRep Carol | 2025-09-01   | 2025-10-01     | NO      |
```

### 6. `updateUntil` / `incrementUntil` on PriceBookEntry

Base $100. `setUnitPriceUntil(80, 2025-07-01)` for `[2025-04-01, 2025-07-01)`.
`incrementUnitPriceUntil(5, 2025-08-01)` for `[2025-07-01, 2025-08-01)`.
Outside those exclusive windows the price is unchanged.

```
asOfBusiness | asOfProcessing | unitPrice | window
-------------+----------------+-----------+-------------------------
2025-02-01   | 2025-10-01     | 100.00    | base
2025-05-01   | 2025-10-01     | 80.00     | promo updateUntil
2025-07-15   | 2025-10-01     | 105.00    | surcharge incrementUntil
2025-09-01   | 2025-10-01     | 100.00    | after bounded windows
```

### 7. Full history reconstruction for Customer Acme

Edge-point query on both axes (`businessDate.equalsEdgePoint()` and
`processingDate.equalsEdgePoint()`) returns every stored rectangle: insert,
legal-name change, revenue restatement.

```
inZ                 | outZ                | businessFrom        | businessThru        | name             | annualRevenue | status
--------------------+---------------------+---------------------+---------------------+------------------+---------------+-------
2025-01-02 09:00:00 | 2025-03-10 09:00:00 | 2025-01-01 00:00:00 | 2025-03-11 09:00:00 | Acme Corp        | 50000000.00   | ACTIVE
2025-03-10 09:00:00 | 9999-12-01 23:59:00 | 2025-01-01 00:00:00 | 2025-03-01 00:00:00 | Acme Corp        | 50000000.00   | ACTIVE
2025-03-10 09:00:00 | 2025-05-01 09:00:00 | 2025-03-01 00:00:00 | 2025-05-02 09:00:00 | Acme Corporation | 50000000.00   | ACTIVE
2025-05-01 09:00:00 | 9999-12-01 23:59:00 | 2025-03-01 00:00:00 | 2025-05-01 00:00:00 | Acme Corporation | 50000000.00   | ACTIVE
2025-05-01 09:00:00 | 9999-12-01 23:59:00 | 2025-05-01 00:00:00 | 9999-12-01 23:59:00 | Acme Corporation | 62000000.00   | ACTIVE
Rectangle count: 5
```

## Layout

```
crm-bitemporal-demo/
  pom.xml
  src/main/resources/reladomo/models/   MithraObject XML (46 entities)
  src/main/resources/reladomo/ReladomoRuntimeConfig.xml
  src/main/resources/h2/schema.sql      temporal from/thru and in/out columns
  src/main/java/.../CrmBitemporalDemo.java
  src/test/java/.../RequiredDemonstrationsTest.java
  src/test/resources/reladomo/TestReladomoRuntimeConfig.xml
```

Tests use `MithraTestResource` + `ConnectionManagerForTests`. The demo `main()`
uses `H2ConnectionManager` and `schema.sql` so the handwritten DDL is exercised.

## Versions

| Piece | Version |
|---|---|
| Reladomo / reladomogen / reladomo-test-util | 18.1.0 |
| H2 | 2.1.210 (Java 11-safe; 2.3.x is Java 21 bytecode) |
| JUnit | 5.11.4 |
| AssertJ | 3.27.0 |
| Compiler release | 11 |
