# car-classifier-demo

Standalone Reladomo 18.1.0 + H2 2.1.210 demo of a **bitemporal decision table**.
The cars never change. The rules do.

A 1985 Toyota MR2 coupe is `COOL` when classified as of 2012, `EIGHTIES_COOL`
as of 2016, and `RETRO` as of today — because the rule set changed, not the car.

Package root: `com.reladynamo.demo.classifier`. Compiled with
`maven.compiler.release=11` (Java 11 language + API + class-file 55) on the
installed JDK 21.

This is the H2 reference implementation for Reladynamo demo 3. The same object
model is later expected to persist to DynamoDB and return identical as-of results.

## Domain

Five entities, three Reladomo temporal flavours:

| Entity | Flavour | Reladomo shape | Role |
|---|---|---|---|
| `Car` | PLAIN | no `AsOfAttribute` | The thing being classified. Never dated. |
| `ResultLabel` | PLAIN | no `AsOfAttribute` | Lookup: `COOL`, `RETRO`, `CLASSIC`, … |
| `ClassificationRule` | **BITEMPORAL** | `businessDate` + `processingDate` | Decision-table row. Valid-from is a past business date. |
| `RuleCriterion` | **BITEMPORAL** | `businessDate` + `processingDate` | One predicate on a car attribute. All must match. |
| `ClassificationResult` | AUDIT | `processingDate` only | What we said, when we said it. |

Infinity sentinel: Reladomo `DefaultInfinityTimestamp` (`9999-12-01 23:59:00.000`).

A rule matches when **all** of its criteria match. Highest `priority` wins;
ties break to the lowest `ruleId`.

`Classifier.classify(Car car, Timestamp asOfDate)` loads the rules that are
business-valid at `asOfDate` under **current processing knowledge**, evaluates
them, writes an audit row, and returns the winning label.

## Seeded decision table

Each rule is inserted with its stated **business-valid-from** date so the rule
set genuinely differs by as-of date:

| Rule | Business-valid from | Priority | Criteria | Label |
|---|---|---|---|---|
| R1 | 2010-01-01 | 40 | year BETWEEN 1980 AND 1989 | `COOL` |
| R2 | 2015-01-01 | 70 | year BETWEEN 1980 AND 1989 AND bodyStyle EQ COUPE | `EIGHTIES_COOL` |
| R3 | 2020-01-01 | 80 | year BETWEEN 1980 AND 1989 | `RETRO` (supersedes R1 by priority) |
| R4 | 2010-01-01 | 50 | year LT 1975 | `CLASSIC` |
| R5 | 2022-01-01 | 90 | year LT 1960 | `VINTAGE` |
| R6 | 2010-01-01 | 100 | wheelCount NE 4 | `EXOTIC` |
| R7 | 2018-01-01 | 60 | year GT 2015 | `MODERN` |

Ten cars are seeded, including the spec's 1985 Toyota MR2 coupe.

## How to run

JDK 21 is the build host. No JDK 11 runtime is required for this demo.

```bash
cd car-classifier-demo
mvn -q clean test          # Reladomo codegen + 9 demonstration tests
mvn -q exec:java           # print the side-by-side table (after a compile/test)
```

`exec:java` needs compiled classes. After a clean tree use:

```bash
mvn -q compile exec:java
```

Code generation is bound to `generate-sources` via `maven-antrun-plugin` +
`reladomogen` 18.1.0 (not the unpublished `reladomo-mithra-plugin`).

Pinned versions: Reladomo `18.1.0`, H2 `2.1.210`, JUnit 5.11.4, AssertJ 3.27.0.

## What each demonstration proves

Every demonstration is also a JUnit 5 + AssertJ test in `ClassifierTest`.
The printed tables are the same facts the tests assert.

### 1. `classify(car, asOfDate)` — same car, different answers

The 1985 Toyota MR2 coupe never changes. Re-running classification at four
business dates walks the rule table as it existed then (current knowledge):

```
The point, in one car: 1985 Toyota MR2 coupe
  as of 2012-06-01  ->  COOL   (only R1 exists yet)
  as of 2016-06-01  ->  EIGHTIES_COOL   (R2 is more specific)
  as of 2026-09-12  ->  RETRO   (R3 supersedes; tastes moved on)
```

Test: `should_progress_mr2_from_cool_through_eighties_cool_to_retro`.

### 2. Side-by-side table of ten cars

Same ten cars, four as-of dates, current processing knowledge. One glance:

```
Same 10 cars, four as-of dates (current processing knowledge)
--------------------------------------------------------------------------------------------------
Car                                2012-06-01      2016-06-01      2021-06-01      2026-09-12
--------------------------------------------------------------------------------------------------
1985 Toyota MR2 coupe              COOL            EIGHTIES_COOL   RETRO           RETRO
1985 Toyota Corolla sedan          COOL            COOL            RETRO           RETRO
1972 Volkswagen Beetle sedan       CLASSIC         CLASSIC         CLASSIC         CLASSIC
1957 Chevrolet Bel Air coupe       CLASSIC         CLASSIC         CLASSIC         VINTAGE
2018 Tesla Model 3 sedan           UNCLASSIFIED    UNCLASSIFIED    MODERN          MODERN
2005 Honda Civic sedan             UNCLASSIFIED    UNCLASSIFIED    UNCLASSIFIED    UNCLASSIFIED
1985 Reliant Robin sedan           EXOTIC          EXOTIC          EXOTIC          EXOTIC
1994 Mazda MX-5 coupe              UNCLASSIFIED    UNCLASSIFIED    UNCLASSIFIED    UNCLASSIFIED
1965 Ford Mustang coupe            CLASSIC         CLASSIC         CLASSIC         CLASSIC
2020 Porsche 911 coupe             UNCLASSIFIED    UNCLASSIFIED    MODERN          MODERN
--------------------------------------------------------------------------------------------------
```

Read across a row: the car is constant. Read down a column: that date's rule set.
The 1985 Corolla sedan stays `COOL` in 2016 because R2 requires `COUPE`. The
three-wheeler is always `EXOTIC` (R6, highest priority). The 1957 Bel Air becomes
`VINTAGE` only after R5 exists (2022).

Test: `should_classify_every_seeded_car_as_of_2012_2016_2021_and_today`.

### 3. Retroactive rule correction (the bitemporal payoff)

R8 was recorded as business-valid from 2010: year 1990–1999 → `CLASSIC`. That
label was **wrong when written**. We classified the 1994 Mazda MX-5 as of
2012-06-01 and stored the `ClassificationResult` audit row. Later we correct R8
to `ECONOMY` (a processing-date correction; business-valid-from is still
2010-01-01). The car did not change.

Re-running the *past* classification now yields a different answer than the
audit row recorded at the time:

```
  what we said then:                               CLASSIC  (audit row #44, evaluated as of 2012-06-01)
  what we now think we should have said then:      ECONOMY  (re-run classify(miata, 2012-06-01) after the correction)

  R8 as of business 2012 / processing 2026-09-12 11:00 : CLASSIC
  R8 as of business 2012 / processing now              : ECONOMY
```

That is the whole point of bitemporality: *"what would we have said then"*
versus *"what do we now think we should have said then"*.

Test: `should_diverge_from_audit_after_processing_date_correction`.

### 4. Rule expiry

`ClassificationRule.cascadeTerminate()` at business date 2014-01-01 ends R1.
The 1985 Corolla sedan is still `COOL` as of 2013 (R1 still valid) and
`UNCLASSIFIED` as of 2014 (R1 gone, R2/R3 not yet born). The MR2 is still
`EIGHTIES_COOL` as of 2016 because R2 is independent of R1.

```
1985 Toyota Corolla sedan uses R1 (1980-89 -> COOL) until we terminate R1 on 2014-01-01.
  as of 2013-06-01 (R1 still valid) : COOL
  as of 2014-06-01 (R1 terminated)  : UNCLASSIFIED
```

Test: `should_stop_using_r1_after_it_is_terminated`.

## Layout

```
car-classifier-demo/
  pom.xml
  README.md
  src/main/java/com/reladynamo/demo/classifier/
    CarClassifierDemo.java     # one-screen main()
    Classifier.java            # classify(Car, Timestamp)
    DemoSeed.java              # exact rule table + 10 cars
    domain/                    # hand-written Reladomo subclasses
    util/                      # H2 connection, runtime, dated transactions
  src/main/resources/
    h2/schema.sql              # H2 DDL (plain / bitemporal / audit)
    reladomo/models/           # 5 MithraObject XML + class list
    reladomo/config/ReladomoRuntimeConfig.xml
  src/test/java/.../ClassifierTest.java
```

Generated Reladomo sources land in `target/generated-sources/reladomo` and are
not checked in.
