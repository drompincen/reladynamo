# Security review — 2026-09-13

Reviewed against the adapter's actual attack surface rather than a generic checklist. This library
parses project-supplied XML, builds DynamoDB expressions from caller predicates, and holds AWS
credentials — three things worth checking and one thing worth checking twice.

## 1. XML External Entity (XXE) — MITIGATED, and tested

`MithraObjectXmlParser` reads object model XML that, in a real deployment, may be supplied by a
build pipeline rather than hand-audited. A permissive parser there reads arbitrary local files.

Hardening in place (`MithraObjectXmlParser`):

```java
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
```

**Tested, not just configured**: `should_reject_external_entities_when_xml_declares_a_doctype` feeds a
real payload declaring `<!ENTITY xxe SYSTEM "file:///etc/passwd">` and asserts the parse is refused.
Disabling doctypes outright is the strongest option here and costs nothing — Reladomo object models
have no legitimate need for a DTD.

## 2. Expression injection — NOT POSSIBLE by construction

The DynamoDB analogue of SQL injection is string-building a `KeyConditionExpression` or
`FilterExpression` from caller-supplied values.

Verified absent: no call site concatenates a value into an expression string. Every value is bound
through `ExpressionAttributeValues` and every attribute name through `ExpressionAttributeNames`
(the latter is mandatory anyway, because DynamoDB reserved words would otherwise break ordinary
queries — a case where the safe path and the working path coincide).

Values reach DynamoDB as typed `AttributeValue` objects, never as text spliced into a query.

## 3. Credentials — none in source

No access keys, secrets or passwords in any main source. The single credential literal is:

```java
AwsBasicCredentials.create("local", "local")   // LocalDynamoDb, test harness
```

DynamoDB Local requires *some* credential pair and ignores its content; this is the conventional
dummy and reaches no real endpoint — `LocalDynamoDb` always overrides the endpoint to
`http://127.0.0.1:<port>`.

Production clients resolve credentials through the AWS default provider chain. The library never
accepts, stores or logs a credential.

## 4. Licence contamination — MITIGATED, and gated

Not a vulnerability, but a real distribution risk that was **actually present**: `reladynamo-test-kit`
declared DynamoDB Local (Amazon Software Licence — not OSI-approved, field-of-use restricted) and H2
(MPL/EPL) at compile scope, so any consumer inherited them transitively.

Fixed, and now enforced by a `maven-enforcer` banned-dependencies rule plus a `licence-scope` gate in
`scripts/check.sh`. The enforcer immediately caught a second leak that eye inspection had missed
(`reladomo-test-util` pulling H2 transitively), which is the argument for the rule existing at all.

## 5. Denial of service through unbounded queries — MITIGATED by design

An adapter that silently falls back to `Scan` turns a cheap query into a full-table read. The planner
**refuses** rather than degrading: a predicate with no derivable partition key throws
`ReladynamoScanRequiredException` unless Scan is explicitly enabled in configuration. Partition-key
fan-out is capped (default 100) and pagination is bounded by `PlannerConfig.maxPages`.

**Corrected 2026-09-14 (finding 16).** This section previously claimed pagination was bounded when
the executor read `maxPages` from nothing and followed `LastEvaluatedKey` without limit — a documented
mitigation that did not exist. It is now enforced, and enforcement **throws** rather than truncating:
a silently clipped result is indistinguishable from a small table, which would be a wrong answer
wearing the appearance of a right one. `maxPages` unset means unbounded, so existing behaviour is
unchanged unless a caller asks for a bound.

## 6. Multi-tenancy — `sourceAttribute` is refused, not ignored (finding 18)

Reladomo expresses multi-tenancy with `sourceAttribute`, which routes objects to different physical
databases. The adapter has no equivalent: the value is not in the partition key and the planner cannot
constrain it.

Until finding 18 this was **silently ignored** — a model declaring one parsed cleanly, every source's
rows shared a table with no discriminator, and a query for one tenant would have returned all of them.
No test could have caught it, because every fixture and all three demos are single-source.

The parser now refuses such a model with `RELADYNAMO-CFG-012`. **Refusal is the correct outcome**:
silent tenant merging is discovered by the wrong person.

## Not reviewed

- The generated Reladomo code and Reladomo itself — upstream, Apache-2.0.
- Transport security: the AWS SDK's defaults apply; the library does not configure TLS.
- Authorisation: the adapter performs no access control. IAM policy on the table is the boundary.
