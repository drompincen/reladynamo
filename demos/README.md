# Reladynamo demos

Three standalone Maven projects. Each runs against **H2** and, with the same object model XML, against
**DynamoDB** through the Reladynamo adapter — the H2 result is the oracle it is diffed against.

Entity models (`ENTITIES.md`) are the specification. The implementation under `project/` was built
from them and verified by re-running the build independently.

| Demo | Build JDK | Source level | Entities | Temporal shape | Tests |
|---|---|---|---|---|---|
| [`01-crm-bitemporal`](01-crm-bitemporal) | 17 | 11 | ~46 | Bitemporal + audit-only + plain | 18 |
| [`02-petstore-unitemporal`](02-petstore-unitemporal) | 21 | 21 | ~22 | Unitemporal (one `AsOfAttribute`) | 17 |
| [`03-car-classifier`](03-car-classifier) | 17 | 11 | 5 | Bitemporal **rules** | 13 |

Demos 01 and 03 keep `maven.compiler.release=11`, so their *sources* stay on the adapter's Java 11
floor; they are **built and tested on JDK 17** because every demo test goes through DynamoDB Local,
which is Java 17 bytecode. See [docs/JAVA11-VERIFICATION.md](../docs/JAVA11-VERIFICATION.md).

```bash
cd demos/03-car-classifier/project && mvn clean test
```

## Which one to read first

**`03-car-classifier`.** It fits on one screen and makes bitemporality obvious without jargon. A
decision table classifies cars; the *rules* are bitemporal, not the cars. The same unchanged 1985
Toyota MR2 row classifies differently depending on when you ask:

```
Car                                2012-06-01      2016-06-01      2021-06-01      2026-09-12
1985 Toyota MR2 coupe              COOL            EIGHTIES_COOL   RETRO           RETRO
1957 Chevrolet Bel Air coupe       CLASSIC         CLASSIC         CLASSIC         VINTAGE
```

Nothing about the cars changed. Taste did, and the rule table records when.

It then shows the second temporal axis: a rule that was *wrong when written* is corrected, and
re-running a past classification now disagrees with the audit row stored at the time —
*"what we said then"* versus *"what we now think we should have said then"*. That distinction is the
whole reason bitemporal exists.

## `01-crm-bitemporal`

A realistic CRM at scale, deliberately mixing all three temporal flavours in one runtime: bitemporal
for correctable facts, audit-only for activity that is never restated (a call's time is not revised,
only when we recorded it), plain for reference data.

The clearest demonstration is the GDPR one — one business date, two beliefs:

```
asOfBusiness | asOfProcessing             | granted | lawfulBasis | outreachLawful
2025-04-01   | 2025-04-02 (as known then) | true    | consent     | YES
2025-04-01   | 2025-10-01 (as known now)  | false   | withdrawn   | NO
```

An email sent on 2025-04-01 was lawful on the evidence available then, and unlawful on today's
record. Both are true. A unitemporal store can represent only one of them.

## `02-petstore-unitemporal`

The counterexample, and the reason it exists. One `AsOfAttribute` per entity.

Its most important test is `nonAuditedCorrectionDestroysThePriorValue()`. A non-audited correction
**destroys** the previous value — it is not recoverable. Engineers routinely assume Reladomo always
retains history; it does not, and that assumption causes real data loss. Read this demo next to the
CRM one, where the same correction preserves the prior belief, and the difference is unmissable.
