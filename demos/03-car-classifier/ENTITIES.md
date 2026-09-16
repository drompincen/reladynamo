# Demo 3 — Car classifier, bitemporal decision table · entity model

**JDK 11** (matching Demo 1's floor). 5 entities. Deliberately tiny.

Package root: `com.reladynamo.demo.classifier`

## The idea

A decision table that classifies cars. **The rules themselves are bitemporal.** What counted as
"cool" in 2015 counts as "retro" in 2026 — not because the car changed, but because the *rule*
changed. Classify the same unchanged car as of different dates and get different answers.

This is the clearest possible demonstration of why bitemporal matters, and it fits on one screen.

---

## Entities

| # | Entity | Flavour | Key attributes |
|---|---|---|---|
| 1 | `Car` | PLAIN | carId PK, year int, make, model, wheelCount int, colorCode, bodyStyle, engineCode |
| 2 | `ClassificationRule` | **BITEMPORAL** | ruleId PK, ruleName, priority int, resultLabel, isActive boolean |
| 3 | `RuleCriterion` | **BITEMPORAL** | criterionId PK, ruleId, attributeName, operator, valueText, valueNumericLow int nullable, valueNumericHigh int nullable |
| 4 | `ClassificationResult` | AUDIT | resultId PK, carId, ruleId, resultLabel, evaluatedAsOfDate, evaluatedTime |
| 5 | `ResultLabel` | PLAIN | labelCode PK (String), displayName, description |

`operator` ∈ `EQ`, `NE`, `BETWEEN`, `IN`, `LT`, `GT`.
`attributeName` ∈ `year`, `make`, `model`, `wheelCount`, `colorCode`, `bodyStyle`, `engineCode`.
A rule matches when **all** its criteria match. Highest `priority` wins; ties broken by lowest `ruleId`.

Labels: `COOL`, `RETRO`, `CLASSIC`, `EIGHTIES_COOL`, `VINTAGE`, `MODERN`, `ECONOMY`, `EXOTIC`, `UNCLASSIFIED`.

---

## The decision table over time (this is the demo)

Rules are inserted with **business dates in the past**, so the rule set differs by as-of date:

| Rule | Business-valid from | Criteria | Label |
|---|---|---|---|
| R1 | 2010-01-01 | year BETWEEN 1980 AND 1989 | `COOL` |
| R2 | 2015-01-01 | year BETWEEN 1980 AND 1989 AND bodyStyle EQ COUPE | `EIGHTIES_COOL` |
| R3 | **2020-01-01** | year BETWEEN 1980 AND 1989 | `RETRO` (supersedes R1 by priority) |
| R4 | 2010-01-01 | year LT 1975 | `CLASSIC` |
| R5 | 2022-01-01 | year LT 1960 | `VINTAGE` |
| R6 | 2010-01-01 | wheelCount NE 4 | `EXOTIC` |
| R7 | 2018-01-01 | year GT 2015 | `MODERN` |

**A 1985 Toyota MR2 coupe classifies as:**

| Evaluated as of | Result | Why |
|---|---|---|
| 2012-06-01 | `COOL` | only R1 exists yet |
| 2016-06-01 | `EIGHTIES_COOL` | R2 now exists and is more specific |
| **2026-09-12 (today)** | `RETRO` | R3 supersedes; tastes moved on |

## Required demonstrations

1. **`classify(car, asOfDate)`** — one method, one date parameter, visibly different answers.
2. A printed **side-by-side table**: the same 8-10 cars classified as of 2012, 2016, 2021 and today.
3. **Retroactive rule correction** — fix a rule that was *wrong when written* (processing-date
   correction), and show that re-running a past classification now yields a different answer than the
   `ClassificationResult` audit row recorded at the time. This is the bitemporal payoff: *"what would
   we have said then"* versus *"what do we now think we should have said then"*.
4. **Rule expiry** — terminate R1 and show classifications after that date stop using it.
5. A one-screen `main()` a reviewer can read end to end.
