# Third-party notices

Reladynamo is MIT licensed. It depends on the following third-party software, each under its own
licence. Nothing here is relicensed; these notices ship with any distribution.

## Reladomo — Apache License 2.0
Copyright Goldman Sachs.
<https://github.com/goldmansachs/reladomo> · <https://www.apache.org/licenses/LICENSE-2.0>

Reladomo is Apache-2.0, which requires that its NOTICE file be reproduced in distributions that
include it. Reladynamo links against Reladomo's published artifacts and does not modify them.

## AWS SDK for Java 2.x — Apache License 2.0
Copyright Amazon.com, Inc. or its affiliates.
<https://github.com/aws/aws-sdk-java-v2>

## DynamoDB Local — Amazon Software License (never distributed)
Copyright Amazon.com, Inc. or its affiliates.

The Amazon Software License is not OSI-approved and carries a field-of-use restriction, so this must
never be **distributed** with Reladynamo. Depending on it is fine; shipping it is not.

- `reladynamo-test-kit` declares it **`provided`** — `LocalDynamoDb` compiles against it, and
  `provided` is non-transitive, so a consumer of the test kit does not inherit it. Anyone wanting the
  in-process harness supplies the jar themselves.
- `reladynamo-ddb` declares it **`test`**.
- It appears in **no** module's compile or runtime scope, so it is in no published artifact's
  transitive closure.

Enforced, not merely intended: a `maven-enforcer` banned-dependencies rule fails the build if it ever
reaches compile or runtime scope, and `scripts/check.sh` reports a `licence-scope` gate. The same rule
covers `com.almworks.sqlite4java` (DynamoDB Local's native dependency).

## H2 Database Engine — MPL 2.0 / EPL 1.0 (test scope only)
<https://h2database.com>
The reference database in the differential test harness. `test` scope in every module, and covered by
the same enforcer rule.

## JUnit 5 — Eclipse Public License 2.0 · AssertJ — Apache 2.0 · SLF4J — MIT
Test and logging dependencies.

## jqwik — Eclipse Public License 2.0 (test scope only)
<https://jqwik.net>
Property-based generation for the codec fidelity tests and the adversarial planner fuzzer. `test` scope,
covered by the same enforcer rule.

## JMH — GPL-2.0 with Classpath Exception (`reladynamo-bench` only)
<https://openjdk.org/projects/code-tools/jmh/>
The microbenchmark harness behind `docs/PERFORMANCE.md`. It is confined to the `reladynamo-bench` module,
which is never published and is not on any consumer's classpath; no JMH code is distributed or linked by
`reladynamo-core` or `reladynamo-ddb`.
