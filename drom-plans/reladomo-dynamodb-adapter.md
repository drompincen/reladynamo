---
title: Reladynamo — a generic MIT-licensed Reladomo persistence adapter for DynamoDB
status: in-progress
created: 2026-09-12
updated: 2026-09-12
current_chapter: 1
---

# Plan: Reladynamo

Build a **generic, MIT-licensed Reladomo adapter that persists objects as JSON items in DynamoDB**,
preserving Reladomo's bitemporal semantics exactly. The adapter is driven by the project's existing
Reladomo XML — no per-entity hand-coding — and is proven correct by running one shared conformance
suite against **H2 (reference) and DynamoDB (adapter)** and demanding identical results.

Built by a **codex + grok multi-agent fleet** under TDD, on a **Java 11 baseline**.

---

## Findings from inspection (these decide the design)

Verified against `reladomo-18.1.0.jar` with `javap` before writing this plan.

**1. The seam is the persister SPI, not `DatabaseType`.**
`DatabaseType` (`H2DatabaseType`, `OracleDatabaseType`, …) is the *JDBC dialect* layer. Implementing
it would mean generating SQL for a database that has none. The correct seam is one level up:

```
com.gs.fw.common.mithra.portal.MithraObjectReader          (read side)
com.gs.fw.common.mithra.transaction.MithraObjectPersister  (write side, extends the reader)
com.gs.fw.common.mithra.transaction.MithraDatedObjectPersister
        + getForDateRange(MithraDataObject, Timestamp, Timestamp)
        + enrollDatedObject(MithraDatedTransactionalObject)
```

**2. Bitemporal logic lives ABOVE the persister — we inherit it, we do not reimplement it.**
`com.gs.fw.common.mithra.behavior.TemporalDirector` (`GenericBiTemporalDirector`,
`AuditOnlyTemporalDirector`, `GenericNonAuditedTemporalDirector`) owns `terminate`, `updateUntil`,
`incrementUntil`, `inactivateForArchiving`, `insertUntil`, `purge`. The director decomposes each of
these into **plain insert / update / delete of dated `MithraDataObject`s** that already carry their
`from`/`thru` and `in`/`out` timestamps, then calls down into the persister.

> This is the fact that makes the project tractable. We implement ~20 CRUD methods and get
> Reladomo's exact bitemporal behaviour for free. Any design that re-derives bitemporal semantics
> inside the DynamoDB layer is wrong and will diverge from H2.

**3. There is already a non-SQL reference implementation to model on.**
`com.gs.fw.common.mithra.portal.PureMithraObjectPersister` implements
`MithraObjectPersister` + `MithraDatedObjectPersister` + `MithraTuplePersister` with no JDBC at all
(Reladomo's "pure" in-memory objects). It is the structural template for `DynamoDbPersister`.

**4. Environment (verified on this machine)**

| Component | State |
|---|---|
| reladomo 16.2.0 / 17.0.0 / **18.1.0** | in `~/.m2` — 18.1.0 is Java 8 bytecode ✓ |
| **DynamoDBLocal 2.5.3** | in `~/.m2` — Java 9 bytecode ✓, runs in-process |
| h2 2.3.232 | **Java 21 bytecode ✗ — unusable at Java 11** |
| **h2 2.1.210** | Java 10 bytecode ✓ — **pin this one** |
| Docker | **MISSING** — hence DynamoDBLocal in-process, not testcontainers |
| jq | MISSING — irrelevant, fleet scripts use python3 (0 jq calls) |
| JDK 11 | **not installed** — needs a Maven toolchain before Chapter 7 can be verified |
| grok / codex CLI | `doctor: OK` for both; project is under `/mnt/c` so grok fan-out is viable |

---

## Agent assignment

**Routing rule, as of 2026-09-14: astra validates and prescribes; grok executes.**

The original rule was "codex for high-brainpower tasks, grok for high-token tasks" — reasoning density
to codex, volume to grok. `gpt-6-astra` becoming selectable sharpens it into something more useful
than a difficulty sort, because the two engines are now good at genuinely different *jobs* rather than
the same job at different levels:

| Role | Engine | Work |
|---|---|---|
| **Validate** | `gpt-6-astra` | Audit an implementation or a plan against its own claims. Is this triage right? Will this brief actually close this finding? What did the inspector miss? Challenge scope declarations hardest — declaring something out of scope is the cheapest way to make a board look green. |
| **Prescribe** | `gpt-6-astra` | Turn prose intentions into a specification precise enough that the executor makes **no judgement calls**: test class and method names, exact fixtures, exact values, exact assertions, and the specific failure each test must exhibit *before* the fix. |
| **Execute** | `grok-4.6` | Build what was prescribed, TDD, at volume and in parallel. Free on this account, so breadth costs nothing. |

Why this split beats the difficulty sort: the failures this project has actually suffered were not
failures of effort, they were failures of *specification*. A green board over twenty-one live defects,
a gate that read a declaration as a use, a test whose success branch asserted something the setup
already satisfied. Each was an executor doing exactly what it was told by a brief that had a hole in
it. Astra's leverage is in closing those holes before an executor reaches them, not in writing the
code more carefully afterwards.

The corollary matters as much: **astra writes no production code and no test code.** A prescription
that arrives as a diff invites integration instead of review, and review is the entire point. Its
deliverables are documents.

*(One exception in flight: the R-01 transaction coordinator was dispatched to astra as an
implementation before this rule was set. Its output is being taken as a prescription plus reference
implementation, which grok then hardens — not integrated as-is.)*

**Economics (this account): grok is effectively free — an unlimited plan — while codex is metered.**
That is not a tie-breaker, it is the dominant term. Push volume to grok aggressively and reserve codex
for work where being *exactly* right matters more than being thorough. Concretely:

- Every grok manifest sets **`"budget_usd": 0`** — the guard treats ≤ 0 as unlimited and still records
  spend, it just never halts a run. A non-zero cap here buys nothing and can kill a run mid-flight.
- Raise grok `max_parallel` freely; the only real limits are machine load and `grok.exe` process count.
- Default to grok first. Promote a task to codex only when a wrong API signature would be *silently*
  wrong — the persister, the query planner, portal binding, transaction semantics.
- Use grok for redundancy that would be wasteful if metered: run the same design question two or three
  ways and treat disagreement as signal. Free breadth is worth more than a single confident answer.
- Set `CODEX_TOKEN_CAP` before wide codex fan-outs; codex is where the meter actually runs.

### Engines and models (pinned)

| | Setting | Note |
|---|---|---|
| grok | **`grok-4.6`** — "SpaceXAI's latest frontier model" | The fleet's built-in default is `grok-4.5`; overridden in `.claude/settings.local.json`. Only `grok-4.6` and `grok-4.5` are selectable. The name in `modelUsage` (`grok-4.5-build`) is an internal resolved name and is **not** a valid `-m` value. |
| grok | `GROK_BUDGET_USD=0` | Unlimited plan — spend still recorded, never halts a run |
| grok | `GROK_AGENT_TIMEOUT=1800`, `GROK_STALL_SECS=300` | Defaults (600/180) are too tight for 900+ line design docs and produce false STALLED readings while an agent writes a long file |
| codex | **OPEN** — default model, effort not pinned | `gpt-5.6-sol` / `gpt-5.6-terra` support an `ultra` reasoning tier above `max`. Worth pinning for the persister, query planner and portal binding. `codex-fleet.sh:160` wires `CODEX_MODEL` → `-m` but has **no passthrough for reasoning effort** — needs a small `-c model_reasoning_effort=…` patch. |

Settings live in `.claude/settings.local.json`, which is deliberately **not** in drom-flow's `template/`,
so `init.sh --update` cannot overwrite them.

**Caveat on the budget guard**: spend is only recorded when an agent *finishes*, so a cap cannot stop a
run whose agents all launch at once (`max_parallel` ≥ agent count). It gates launches, not flight. With
`budget_usd: 0` this is moot, which is another reason to set it.

| Engine | Used for | Why |
|---|---|---|
| **codex** | *High brainpower.* The persister, the `Operation` → DDB query planner, portal binding, transaction-boundary semantics, anything where a wrong API signature is silently wrong | Native binary, exact API work, strong reasoning. `--write-repo` serialises repo writes so agents cannot corrupt each other |
| **grok** | *High token volume.* Exhaustive enumeration (every XML construct, every type mapping), broad design documents, edge-case mining, docs and HTML explainer drafts, adversarial review sweeps | Cheap parallel breadth. Tasks where the work is "cover everything" rather than "get this one thing exactly right" |
| **Claude** | Integration, wiring, reviewing every agent's output, final arbitration | Holds the whole context; verifies agent claims against the real jars before anything is trusted |

**Every fan-out task must carry its own context.** Neither engine sees this conversation. Each task file
embeds the architecture decisions above and points at the skill files on disk
(`.claude/skills/{reladomo-expert,ddb-expert,dynamodb-architect,java-expert,tdd}/*.md`) — grok.exe can
read them because the project is under `/mnt`.
| **skills** | `/reladomo-expert` `/ddb-expert` `/dynamodb-architect` `/java-expert` `/tdd` `/architect` `/reviewer` | Loaded per chapter so each agent carries the right expertise rather than re-deriving it |
| **Claude** | Integration, wiring, review of all agent output, final arbitration | Holds the whole context |

Fan-out contract is identical for both (`task.md` in → `status.json` / `result.json` / `output/` out),
so a unit of work can be routed to either engine.

---

## Closed loop — definition of done

**Check command:** `bash scripts/check.sh --iteration N` → `reports/check-N.json`.
Exit 0 only when every gate is PASS and none is PENDING. Runs on a 10-minute cron (job `5735d38c`).

**Exit criteria — all eight gates green. No gate may be waived.**

| Gate | Passes when |
|---|---|
| `adapter-build` | `mvn clean test` green across all adapter modules, **and test count > 0** |
| `demo-crm` | 46-entity bitemporal CRM builds and its 14 demonstrations pass |
| `demo-petstore` | 22-entity unitemporal pet store builds and its 10 pass |
| `demo-classifier` | 5-entity bitemporal rules build and its 9 pass |
| `java11-floor` | every adapter class is class-file major ≤ 55 |
| `licence-scope` | no ASL/MPL-licensed dependency in compile or runtime scope — the MIT claim, enforced |
| `spec-drift` | every error code the design documents exists in code or is explicitly waived, and every config option has a consumer or refuses non-defaults (findings 16-18) |
| **`differential-h2-vs-ddb`** | **PASSING (3 tests)** — insert, retroactive correction and terminate produce identical row sets on H2 and DynamoDB, all four temporal boundaries compared exactly. **Scope: storage/key/codec fidelity. Query-path equivalence is Chapter 7 and not yet covered.** |

The differential gate is the real one; the others are preconditions for it being meaningful. It
reports **PENDING**, never PASS, until `DynamoDbPersister` exists — "not implemented" is not "passing",
and a loop that scores its own unfinished work as green is worse than no loop.

**Eight green gates are necessary and not sufficient, and the 2026-09-14 inspection is the proof.**
The board read 8/0/0 while twenty-one source-confirmed defects were live, four of them producing
silently wrong query answers. The inspector's own sentence is the correction to make to this plan:

> The right review unit is an observable application behavior with an executable acceptance case, not
> a checked chapter or a raw test count.

So the exit criteria gain a ninth item that is not a script: **every acceptance case listed at the end
of `docs/INSPECTION-2026-09-14.md` has an executable test that was seen to fail first.** Thirteen
boxes; none ticked at the time of writing. Chapter 11 owns them.

**Iteration protocol** (`workflows/closed-loop.md`): baseline → categorise divergences → one agent per
independent category, in parallel → verify every agent's output against the real jars before
integrating → re-run check → log to `context/MEMORY.md`. More failures than the previous iteration is
a **regression**: revert immediately, log it, try a different approach. Never re-run a fix that
regressed.

**Standing rule:** agent output is not trusted until verified. Two real defects have already been
caught this way — a non-existent `ServerRunner` import, and a nullable-`Boolean` unboxing NPE in the
planner design. Both would have surfaced much later as confusing runtime failures.

---

## Chapter 1: Skills + spike — prove the seam before building on it
**Status:** **COMPLETE.** 5 skills authored and installed; both read and write seams proven in a
running JVM (`PortalBindingSpikeTest`, `WritePathSpikeTest`).
**Depends on:** none

drom-flow ships **no** reladomo, java or tdd skill. They are prerequisites, not nice-to-haves.
It *does* ship `/dynamodb-architect` (664 lines) — but that skill owns **data modelling** only, with
almost no SDK-runtime content, so `ddb-expert` is scoped to the Java SDK v2 client layer beneath it
and defers every modelling question to its sibling.

- [x] Verify persister SPI signatures with `javap` against the real jar
- [x] Confirm `PureMithraObjectPersister` as non-SQL precedent
- [x] Verify Java 11 bytecode compatibility of every dependency
- [ ] **codex ×4** — author `reladomo-expert`, `java-expert`, `tdd`, `ddb-expert` skills
- [ ] Review all four skills for accuracy; correct any invented API. Verify every Reladomo signature
      with `javap` and every AWS SDK v2 call against the real SDK — a skill that hallucinates an API
      is worse than no skill, because it is trusted
- [ ] Install into this project **and** upstream into `drom-flow/.claude/skills/` + `template/.claude/skills/`, registering in `SCRIPTS.md` `MANAGED_DIRS`, `CLAUDE.md`, `template/CLAUDE.md`, `README.md` (drom-flow's own `drom-plans/add-domain-skills.md` already specifies `/reladomo-expert` in Tier 1 — this closes it)
- [x] **Kill criterion CLEARED.** `javap` confirms `public void setMithraObjectReader(MithraObjectReader)`
      on `MithraAbstractObjectPortal` — the portal *can* be bound to a custom persister with no Reladomo
      fork. Architecture is viable.
- [x] **Walking-skeleton spike PASSES** (`reladynamo-spike`, 2 tests green). A real bitemporal
      `SpikeBalance` object, generated by `reladomogen`, running on H2: the portal was re-pointed at a
      reader we supply via `setMithraObjectReader`, and a `findMany().forceResolve()` **actually routed
      through it** (asserted on call count, so a bypass would fail the test). The seam is not cosmetic.
- [x] **Write path confirmed** (`WritePathSpikeTest`): a reader that also implements
      `MithraDatedObjectPersister`, installed via `setMithraObjectReader`, is what
      `getMithraObjectPersister()` returns. **One setter binds both halves** — the adapter needs a
      single seam, and writes cannot silently keep going to JDBC while reads come from DynamoDB.
- [ ] **Residual risk to close in the spike:** `mithraTuplePersister` is `private transient` with a getter
      but no setter (constructor-supplied), so after a reader swap the portal keeps the original JDBC
      tuple persister. Prove whether the dated transactional path ever touches it. If it does, take
      Fallback C (`*DatabaseObject` subclasses that are the Dynamo persister from construction) rather
      than reflecting into the private field.

## Chapter 2: Project skeleton, licence, Java 11 **compatibility floor**
**Status:** **COMPLETE.** Skeleton green; class-file ≤ 55 enforced by the gate; **110 core tests
verified running on a real Temurin JDK 11** — see `docs/JAVA11-VERIFICATION.md`, which also records
what that verification cannot cover (no `linux-aarch64` sqlite4java native, so the DynamoDB modules
are inferred rather than executed).
**Depends on:** 1

Java 11 is the **backwards-compatibility floor, not the target ceiling**. The artifact must *run* on
11 and on every LTS above it; development happens on a modern JDK.

- [x] Maven multi-module: `reladynamo-core`, `reladynamo-ddb`, `reladynamo-test-kit`, `reladynamo-spike` — `mvn clean install` green
- [x] `maven.compiler.release=11` — **verified**: compiled classes report class-file major version **55**
- [x] Maven **toolchains** to run the full suite on a real JDK 11 *and* 17 *and* 21 — `release=11`
      proves compilation, only execution proves compatibility. **No JDK 11 is installed today; install one.**
- [ ] `animal-sniffer` (or `japicmp`) gate: build fails if a Java 12+ API leaks into the public path
- [ ] Optional **multi-release JAR** for genuine wins on newer JDKs (e.g. virtual-thread-friendly
      batch I/O in `META-INF/versions/21`), with the 11 implementation always present as the fallback.
      Only if a benchmark justifies it — an MR-JAR is real complexity.
- [ ] **Dependency floor audit** (verified): pin **h2 2.1.210** — h2 2.3.232 is Java 21 bytecode and
      cannot load on 11. DynamoDBLocal 2.5.3 (Java 9) ✓, Reladomo 18.1.0 (Java 8) ✓, AWS SDK v2 ✓
- [x] `LICENSE` (MIT), `THIRD-PARTY-NOTICES.md` — incl. the DynamoDB Local **Amazon Software Licence** caveat: not OSI-approved, field-of-use restricted, must stay test-scope only
- [x] Enforcer: dependency convergence + banned transitive copyleft
- [x] CI matrix: **JDK 11, 17, 21** — green on all three is the release gate

## Chapter 3: XML-driven mapping — the "generic" in generic adapter
**Status:** **IMPLEMENTED AND GREEN** — parser, key strategy, validator, codec (95 core + 68 ddb tests).
Design: `docs/design/01-xml-to-ddb-mapping.md` (967 lines)
**Depends on:** 2

Design decided by the grok fan-out and jar-verified: **table-per-object** (not classic single-table —
Reladomo finders are per-object, so a shared table buys nothing and costs index clarity);
sort key `v1#P#<processingDateFrom>#B#<businessDateFrom>`; a separate `reladynamo.xml` rather than
extending `MithraRuntime`; startup validation with numbered `RELADYNAMO-CFG-NNN` error codes.

The adapter reads the same Reladomo XML the project already has and derives everything.

- [ ] Parse `*MithraObject.xml`: attributes, types, nullability, primary keys, `asOfAttribute`
      (`businessDate`, `processingDate`), relationships
- [ ] Derive the DynamoDB key schema from the object model, with an overridable strategy:
      - default PK: `<Object>#<pk1>#<pk2>…`
      - default SK: `<processingDateFrom ISO-8601>#<businessDateFrom ISO-8601>` (fixed-width, UTC,
        lexicographically sortable — infinity encoded as a max sentinel, never as `null`)
      - GSIs derived only from declared relationships and configured access patterns
- [x] JSON item codec **designed** — `docs/design/03-json-item-codec.md` (1309 lines). `MithraDataObject` ⇄ DynamoDB item. Attribute-name compression map, explicit
      null handling, `Timestamp` precision preserved to the millisecond, BigDecimal as `N` not `S`
- [ ] Item-size policy: 400 KB hard limit — detect, and either overflow-chain or fail loudly
- [ ] Config surface: a `reladynamo.xml` (or runtime-XML extension) binding an object to a table,
      key strategy, capacity mode and index set
- [ ] **grok fan-out:** independently derive a key design per access pattern; diff against ours

## Chapter 4: Operation → DynamoDB query translation (highest risk)
**Status:** **IMPLEMENTED AND GREEN** — 38 planner tests incl. an adversarial fuzzer.
Design: `docs/design/04-operation-to-ddb-query-planner.md` (1406 lines)
**Depends on:** 3

Key decisions, jar-verified:
- Plan on **`getAnalyzedOperation()`**, never `getOriginalOperation()` — the analyzed form carries the
  as-of predicates Reladomo injects; planning the original silently drops them.
- **`businessDate` as-of cannot be a native base-table SK range.** With a processing-major sort key
  (`v1#P#…#B#…`) the business component is a non-contiguous suffix. It becomes a filter, or a range on
  a sparse current-row GSI. Inventing a `SK=CURRENT` item is rejected — the director never writes that
  shape, and synthesising it re-derives bitemporal logic the engine already owns.
- OR across partition keys **fans out to N queries (cap 100), never degrades to a Scan**.
- Scan is **opt-in only**; otherwise the planner throws with a named exception.
- The planner is a **pure function** with no I/O — AWS lives in a separate `QueryPlanExecutor`, which
  is what makes it unit-testable without AWS.
- DynamoDB `Limit` applies **before** the filter, so it can never be wired straight to Reladomo's
  `rowcount` — a naive mapping returns wrong rows.
- **Gap found in review:** `Operation.matches()` returns a nullable `Boolean`; the design assumed a
  primitive. Unfixed, the residual filter NPEs intermittently. Rule recorded in the doc's reviewer notes.

`MithraObjectReader.find(AnalyzedOperation, OrderBy, …)` hands us Reladomo's `Operation` tree. DynamoDB
cannot answer arbitrary predicates.

- [ ] Walk the `Operation` tree; classify each node: **key condition** / **filter expression** /
      **residual in-memory filter**
- [ ] Planner picks Query over Scan whenever a PK equality is derivable; Scan requires explicit opt-in
      and emits a warning, because a silent Scan is how this kind of adapter dies in production
- [ ] `businessDate`/`processingDate` predicates → SK range conditions (`between`, `<=`, `begins_with`)
- [ ] Implement `count`, `findCursor`, pagination, `OrderBy` (native where SK order suffices,
      in-memory otherwise), `findAggregatedData`
- [ ] `refresh` / `refreshDatedObject` / `getForDateRange`
- [ ] **Explain plan**: every query logs chosen index, key condition, residual filter, items scanned
      vs returned
- [ ] **codex** implements; **grok** writes an adversarial operation generator that fuzzes
      `Operation` trees looking for ones the planner silently mistranslates

## Chapter 5: The persister — write path
**Status:** **COMPLETE.** Write path differentially verified. **Read path wired**: `find()` runs
plan → execute → decode → `MithraDataFactory` → `MithraDataPopulator` → `Cache.getObjectFromData`,
and `count()` executes for real. `find()` refuses to materialise a dated object without an as-of
equality rather than guessing a date.
Design: `docs/design/02-java-config-and-bootstrap.md` (1250 lines)
**Depends on:** 3

Implement `MithraDatedObjectPersister` modelled on `PureMithraObjectPersister`.

- [ ] `insert`, `update` (single + `List<AttributeUpdateWrapper>`), `delete`, `purge`
- [ ] `batchInsert`, `batchDelete`, `batchDeleteQuietly`, `batchPurge` → `BatchWriteItem` with
      25-item chunking, unprocessed-item retry, exponential backoff
- [ ] `batchUpdate(BatchUpdateOperation)`, `multiUpdate(MultiUpdateOperation)`
- [ ] `deleteUsingOperation`, `deleteBatchUsingOperation`, `findForMassDelete`,
      `prepareForMassDelete`, `prepareForMassPurge`
- [ ] `enrollDatedObject`, `getForDateRange`
- [ ] **Transactions — state the boundary honestly.** `TransactWriteItems` caps at 100 items and
      offers no interactive transaction. Map `setTxParticipationMode` and Reladomo's transaction
      scope onto it where it fits; **document precisely where it does not**, rather than pretending
      parity. Optimistic locking via conditional writes on the out-timestamp.
- [ ] Idempotency and retry safety for every write path

## Chapter 6: H2 vs DDB differential test harness
**Status:** **COMPLETE.** Gate flipped to PASS at iteration 25 and has since grown from 3 to **32
differential tests**. `TemporalRowSetDiffer` compares exactly, names MISSING vs EXTRA distinctly, and
flags an empty-vs-empty comparison rather than passing it silently.
**Depends on:** 2

The correctness oracle: **one suite, two backends, identical assertions.**

- [ ] `reladynamo-test-kit`: abstract JUnit 5 contract class, per-backend subclasses
      (`H2ContractTest`, `DynamoDbContractTest`) over the **same** Reladomo object model XML
- [ ] H2 backend: stock Reladomo JDBC + `MithraTestResource` — the reference, assumed correct
- [x] DDB backend harness: **DynamoDBLocal 2.5.3 in-process, working** (`LocalDynamoDb`, proven by test) — natives unpacked to `target/native-libs`, `sqlite4java.library.path` set by surefire
- [ ] Differential runner: execute the same operation script against both, diff the full resulting
      row set including all four temporal timestamps; any divergence fails
- [ ] Optional live-AWS profile, off by default
- [ ] Determinism: fixed clock, seeded data, no wall-clock `now()` in assertions

## Chapter 7: Bitemporal conformance suite
**Status:** **COMPLETE.** All three classes integrated and green — operation matrix (14 ops),
audit-only (13), edge cases (16). The differential gate covers **48 tests**, up from 3.
**11 findings** recorded in `docs/CONFORMANCE-FINDINGS.md`.
Every "failure" so far has been a wrong premise about Reladomo, corrected with evidence from the
generated code — **no comparison has been weakened to reach green.**
**Depends on:** 5, 6

Prove "the same bitemporal features" rather than asserting it.

- [ ] Per director: `GenericBiTemporalDirector`, `AuditOnlyTemporalDirector`,
      `GenericNonAuditedTemporalDirector`
- [ ] Per operation: `insert`, `insertUntil`, `insertWithIncrement`, `insertWithIncrementUntil`,
      `update`, `updateUntil`, `increment`, `incrementUntil`, `inPlaceUpdate`, `terminate`,
      `terminateUntil`, `purge`, `inactivateForArchiving`, `insertForRecovery`
- [ ] Edge cases: infinity handling, zero-length segments, out-of-order business dates, retroactive
      corrections, same-millisecond writes, chained terminate-then-reinsert, full history reconstruction
      (`as of` any past processing date)
- [ ] **grok fan-out** mines edge cases from Reladomo's own semantics independently, so our tests are
      not merely a mirror of our own assumptions
- [ ] **PIT mutation testing** on the persister — line coverage is not evidence here
- [ ] Gate: 100 % of the conformance suite passes identically on both backends

## Chapter 8: README and HTML explainers
**Status:** **COMPLETE (2026-09-17).** `README.md` is 470 lines and now carries Install, the key
design guide and Operations alongside what was already there. All five explainers are published, not
as Artifacts but as pages on the project's GitHub Pages site (`docs/*.html`), which is where a reader
of the repository will actually look for them; they are cross-linked from `README.md` and
`docs/index.html`. Every shell block in `README.md` runs verbatim in CI, by explicit marker, and the
job fails if a block is unmarked or if zero blocks are found.
**Depends on:** 7

Shipped documentation, not notes.

- [x] `README.md`: what it is, the bitemporal guarantee, MIT badge, 5-minute quickstart
- [x] **Install**: Maven coordinates, Java 11 baseline, dependency table — and the honest version of
      the Java claim: `reladynamo-core` is proven on a real JDK 11, the DynamoDB modules cannot even be
      compiled by a JDK 11 compiler because DynamoDB Local 2.5.3 is Java 17 bytecode
- [x] **Usage walkthrough**: take an existing Reladomo object model XML → add the Reladynamo runtime
      config → run against DynamoDB with zero changes to generated code or finder call sites.
      Corrected on 2026-09-17: it showed the three-argument write-only persister, whose every read
      refuses
- [x] Worked example — **deviation, deliberate**: the three demo projects in `demos/` are the worked
      examples, runnable against both H2 and DynamoDB Local. No `reladynamo-examples` module was
      created; a fourth example beside three demos would be a fourth thing to keep in step
- [x] **Key design guide**: default strategy, when to override (don't — the planner hard-codes the v1
      key format), GSI rules
- [x] **Limits, stated plainly**: no cross-entity transactions beyond `TransactWriteItems`' 100-item
      cap, no arbitrary-predicate queries without a Scan, 400 KB item ceiling, eventual-consistency
      semantics on GSIs
- [x] **Operations**: capacity planning, cost model, monitoring — stated as the absence it is, since
      there is no metrics hook and no logging — and the explain-plan output
- [x] `CONTRIBUTING.md`, `LICENSE`, `THIRD-PARTY-NOTICES.md` — all three verified present; jqwik and
      JMH added to the notices on 2026-09-17
- [x] Every README command executed verbatim in CI so the docs cannot rot

### HTML explainers (for Java developers)

Self-contained, dark/light-aware HTML pages on the GitHub Pages site — a Java developer who has never
seen DynamoDB should understand the adapter from these alone. Diagrams are inline SVG, not screenshots.

- [x] **"Where the adapter plugs in"** — `docs/bitemporal-seam.html`. The Reladomo call stack from
      `Finder` → portal → `TemporalDirector` → persister, showing which box we replace and which we
      inherit. This is the single most important diagram in the project.
- [x] **"Bitemporality in pictures"** — `docs/bitemporality-in-pictures.html`. businessDate vs
      processingDate on a 2-D grid, each operation stepped through as rectangle splits.
- [x] **"Your table on DynamoDB"** — `docs/table-on-dynamodb.html`. The `Balance` object from the
      README as relational rows and as DynamoDB items, PK/SK built component by component.
- [x] **"From SQL query to DynamoDB query"** — `docs/query-planner.html`. Predicate classification,
      why business date cannot be a range scan, the five plan kinds, the current-row fast path.
- [x] **"What you give up"** — `docs/what-you-give-up.html`. Transactions, arbitrary predicates,
      400 KB items, GSI consistency, each with its workaround.
- [x] Every explainer cross-linked from `README.md` and `docs/index.html`

**Every claim on the three new pages was verified against source in a second pass**, which is the only
reason this chapter is closed rather than merely written. That pass found a wrong `TransactWriteItems`
action count, an overstated "ten maximum-size items fill 4 MB" (it is 3.9 MB), citations that did not
say what was claimed, a diagram whose tokens had vanished in a CSS change, and a defect in the
README's own `Balance` example: it omitted `futureExpiringRowsExist`, which changes which rows
Reladomo produces. Two captions were downgraded from "asserted" to "traced through the director
source", because no test pins those boundaries coordinate-by-coordinate.

## Chapter 9: Three demo projects (`demos/`)
**Status:** **COMPLETE.** All three run against **both H2 and DynamoDB**, with their object model XML
**unmodified** — 45 tests total (was 33 on H2 alone). CRM 18, pet store 16, classifier 11.
**Depends on:** 7 for the DynamoDB phase; the H2 phase runs independently and comes FIRST

Three **standalone Maven projects** under `demos/`, each with its own `pom.xml`. Built **H2-first**:
the working H2 project is the reference implementation the DynamoDB layer is later diffed against.
Entity models are specified in `demos/*/ENTITIES.md` (authored here); Reladomo XML, schema, seed data
and demo code are built by grok.

Deliberately spanning both JDK targets and both temporal shapes, so the adapter is proven against the
real variation rather than one happy path.

### Demo 1 — `demos/01-crm-bitemporal` · JDK 11, ~44 entities, BITEMPORAL
A typical CRM: customers, contacts, calls, outreach, campaigns, pipeline, contracts, support, billing.

- [x] Entity model specified — `demos/01-crm-bitemporal/ENTITIES.md` (46 entities across 9 groups)
- [x] Reladomo object model XML for all entities, mixing **bitemporal / audit-only / plain** flavours
- [x] H2 runtime config, schema DDL, `reladomogen` codegen bound to `generate-sources`
- [x] **Exactly 100 seed records**, deterministic — verified in demo output
- [x] Seven required demonstrations, 14 tests green. GDPR case verified: same business date 2025-04-01, lawful YES as known 2025-04-02, NO as known 2025-10-01:
      retroactive address correction · past-dated territory reassignment + commission · opportunity
      restatement · **GDPR consent withdrawn retroactively** (lawful as known then, absent as known
      now) · terminate/expire subscription and rep · `updateUntil`/`incrementUntil` on a bounded price
      window · full history reconstruction for one customer
- [x] **Same project, same XML, DynamoDB backend** — `CrmDynamoDifferentialTest`, 4 tests incl. the GDPR consent case; object model digest identical before and after

### Demo 2 — `demos/02-petstore-unitemporal` · JDK 21, ~22 entities, UNITEMPORAL
Pet store: pets, breeds, kennels, vet visits, orders, stock, staff.

- [x] Entity model specified — `demos/02-petstore-unitemporal/ENTITIES.md`
- [x] **Exactly one `AsOfAttribute` per entity** — 10 tests green
- [ ] Modern Java 21 (records, pattern matching, text blocks) — a deliberate contrast with Demo 1
- [x] **The teaching point** is tested: `nonAuditedCorrectionDestroysThePriorValue()`
- [ ] Show that a unitemporal as-of query takes **one** date parameter, not two

### Demo 3 — `demos/03-car-classifier` · JDK 11, 5 entities, BITEMPORAL RULES
A decision table classifying cars — year, make, model, wheels, colour → `COOL`, `RETRO`, `CLASSIC`,
`EIGHTIES_COOL`, … **The rules are bitemporal, not the cars.**

- [x] Entity model + full rule table specified — `demos/03-car-classifier/ENTITIES.md`
- [x] `Classifier.classify(Car, Timestamp asOfDate)` — 9 tests green
- [x] **VERIFIED IN REAL OUTPUT** — 1985 Toyota MR2 coupe: `COOL` (2012) → `EIGHTIES_COOL` (2016) →
      `RETRO` (2021, 2026). The 1957 Bel Air also shifts `CLASSIC` → `VINTAGE` when R5 lands in 2022.
- [x] **RUNS AGAINST DYNAMODB** — `ClassifierDynamoDifferentialTest`, 11 tests total (the original 9
      still green). **All five object model XMLs unchanged**, verified by diff rather than taken on
      trust. This is the project's headline claim, demonstrated on a demo the adapter did not ship with.
- [ ] Side-by-side table of all cars at four as-of dates — one glance makes the point
- [ ] **Retroactive rule correction**: after fixing a rule that was wrong when written, re-running a
      past classification differs from the `ClassificationResult` audit row recorded at the time.
      Printed as "what we said then" vs "what we now think we should have said then".
- [ ] Rule expiry via `terminate`

**This demo is the best explainer in the project.** It fits on one screen and makes bitemporality
obvious to someone who has never heard the word. The Chapter 8 HTML explainer should be built on it.

### Gates for all three
- [x] `mvn clean test` passes — **independently re-run by Claude**, not taken on report: crm 14, petstore 10, classifier 9 = **33 tests, 0 failures**
- [x] No `BLOCKERS.md` needed — nothing was blocked
- [ ] README per demo with real printed output inline
- [ ] CI builds all three; Demo 1 and 3 at `release=11`, Demo 2 at `release=21`

## Chapter 10: Hardening and release
**Status:** in-progress. Done: security review (`docs/SECURITY-REVIEW.md`), **licence-scope enforcer
gate** (which caught a real compile-scope leak), CI matrix on JDK 11/17/21, Java 11 **runtime**
verification (`docs/JAVA11-VERIFICATION.md`), migration tooling + guide. performance measured (`docs/PERFORMANCE.md` — per-row work is ~0.1-1% of a round trip), and
`docs/RELEASE-READINESS.md` written: an honest accounting of what is proven versus what is not.
`japicmp` is deliberately deferred — it needs a published baseline that will not exist until 0.1.0,
and a plugin that always passes is worse than no check because it looks like one.
**Remaining before 0.1.0: contact with a real AWS endpoint, relationships/deep-fetch differentially,
finder-driven query-path comparison, a load test.** All operational, none architectural.
**Depends on:** 9

- [x] Performance: JMH on the codec and query planner; N+1 / deep-fetch behaviour measured
- [x] `/reviewer` + `/security-review` passes; grok adversarial review of the whole adapter
- [x] Verify Java 11 runtime on a real JDK 11 toolchain, not just `release=11`
- [ ] API stability review, `japicmp` baseline, semantic versioning
- [ ] Publish checklist; tag `0.1.0`

## Chapter 11: Close the 2026-09-14 inspection
**Status:** in-progress, dispatched 2026-09-14. **Depends on:** 5, 7, 10.

An independent inspector reviewed the whole repository and filed
`docs/INSPECTION-2026-09-14.md`: **21 source-confirmed findings**, six of them blockers, across two
tracks — migration tooling and runtime ORM parity. I re-verified all 21 against the source before
dispatching anything; every one was still present and every line reference was accurate. Disposition
per finding is tracked in `docs/INSPECTION-TRACKING.md`.

This chapter exists because the previous ten produced a board reading 8 green gates over a codebase
with four silently-wrong query paths and a backfill that certifies corrupted copies. That is not a
gate failure to patch over — it is the plan's central lesson, and the ninth exit criterion above is
the response.

**What the inspection changes structurally.** It was right that this plan "primarily plans an adapter"
and treats migration as a hardening narrative. Two things follow, and only the second is code:

- **Scope is now stated separately from progress.** `docs/SUPPORT-CONTRACT.md` divides the world into
  permanent DynamoDB boundaries (transaction caps, item size, GSI eventual consistency, no unique
  constraints), gaps that are simply not built (reverse migration, source extractor, CDC, ASE), the
  real mapping contract as opposed to the "unchanged arbitrary XML" claim, and the one GSI shape that
  actually has evidence. `MIGRATION.md`'s reverse-migration and transaction claims were retracted.
- **A first-class migration workstream**, which this plan lacked: source inventory and extraction,
  snapshot/CDC protocol, job state and checkpointing, validation manifests, cutover, reverse import.
  M-03 through M-06 are its backlog. Declared out of scope for 0.1.0 and named as such.

**Dispatched (five agents, 2026-09-14):**

| Agent | Engine | Findings |
|---|---|---|
| `insp/query` | grok-4.6 | R-03 point reads drop the plan filter · R-04 numbers emitted as `N` against a codec storing `B`/`S` · R-05 null predicates inverted, **and the interpreter oracle repeats the bug** · R-06 fan-out collapse compares expression text, not bindings |
| `insp/backfill` | grok-4.6 | M-01 verification compares only temporal boundaries, nothing at all for non-dated · M-02 first PK component only · M-08 differ infers identity from name heuristics and map order |
| `insp/limits` | grok-4.6 | R-08 no comparator, no sort, no fan-out dedup · R-09 `maxPages` never reaches the plan · R-13 size checked before keys appended, no reserved-name validation |
| `insp/writes` | grok-4.6 | R-02 unconditional writes across JVMs · R-07 residual evaluated against a raw `Map` · M-07 `TableCreator` checks ACTIVE, not *matching* |
| `insptx/tx` | **codex `gpt-6-astra`** | R-01 no transaction integration at all; `setTxParticipationMode` a no-op; writes durable on arrival |

R-01 went to codex because it is the one finding that is a design problem rather than a bug, and
`gpt-6-astra` became selectable the same day. Every task carries the standing rules verbatim: never
weaken a comparison to reach green, a divergence you cannot fix is the valuable outcome, Java 11
floor, verify every Reladomo API with `javap` first.

- [x] R-01 transaction coordinator — **closed**; astra designed and implemented it, grok composed it with R-02's conditions through `PhysicalWrite.Condition`. `DurableTransactionTest`, `DurableTransactionLocalTest`, `BoundDurableTransactionTest` green; finding 21 held at 5/5
- [x] R-02 conditional writes — **integrated**; two write families (ORM conditional vs migration upsert), and it **closed finding 21** with the strengthened assertions left intact
- [~] R-03/R-04/R-05/R-06 query correctness, each with a test seen to fail first — **R-03, R-05, R-06 integrated**; R-04 in flight (third attempt; both earlier deaths were the hardcoded 30-turn ceiling, not difficulty)
- [x] R-07 residual evaluated against a typed object — **integrated**; nullable `matches()` contract preserved
- [x] R-08/R-09/R-13 ordering, limits wired end to end, storage-contract validation — **all three integrated**; R-09 re-implemented against the current tree after its first solution proved unmergeable
- [x] M-01/M-02/M-08 backfill verification that can actually detect a corrupted copy — **integrated**, red-then-green in the agent's own log; new `MappedRowSetDiffer` keys identity on `EntityMapping.primaryKeyAttributes()`, compares every mapped attribute both directions, `Arrays.equals` for binary; 287 → 310 tests
- [x] Scope stated: `docs/SUPPORT-CONTRACT.md`; `MIGRATION.md` overstatements retracted; SPI refusal
      count corrected 21 → 20
- [x] Disposition tracked per finding: `docs/INSPECTION-TRACKING.md`
- [x] `spec-drift` strengthened after it failed to catch R-09 — it was reading a same-named getter
      *declaration* on `QueryPlan` as a *use* of `PlannerConfig.maxPages`. The stronger gate
      immediately found a second dead knob, `avgItemBytes`, read by nothing anywhere (finding 20).
      **Both left failing**: a red gate naming a real defect beats a green one that was never
      load-bearing.
- [ ] The thirteen acceptance cases at the end of the inspection, each with an executable test

---

## Execution protocol

1. Chapters 3, 4 and 5 fan out to **codex with `--write-repo`** (serialised — parallel repo writes
   corrupt each other) while **grok** runs research and adversarial tasks in parallel, sandboxed.
2. **TDD is mandatory** (`/tdd`): the H2 contract test is written and *observed failing* against the
   DDB backend before adapter code is written.
3. Closed loop per `workflows/closed-loop.md`: run the differential suite → categorise divergences →
   one agent per category → re-run → log to `context/MEMORY.md`. More failures than the previous
   iteration is a regression: revert immediately.
4. Architectural decisions land in `context/DECISIONS.md`.
5. Budget caps: `CODEX_TOKEN_CAP`, and `budget_usd` in every grok manifest.


### Chapter 11 status at iteration 120

**The eight-gate board is green again — 8 pass / 0 fail / 0 pending, `exit_condition_met: true`.**

That is the same reading the board gave on 2026-09-14 morning, over twenty-one live source-confirmed
defects. So it is worth saying plainly what is different now and what is not.

**What changed.** Fourteen findings closed, each with a test that was seen to fail first: M-01, M-02,
M-07, M-08, R-02, R-03, R-05, R-06, R-07, R-08, R-09, R-13, plus findings 20 and 21 found along the
way. Four of those produced silently wrong query answers, one certified corrupted migrations as
verified, and one left superseded bitemporal versions open. The differential gate's storage path went
56 → 76 tests; the adapter build 287 → 397.

**What has not changed: the ninth exit criterion is still unmet.** None of the thirteen acceptance
cases at the end of `docs/INSPECTION-2026-09-14.md` has an executable test yet. Those are the
observable-behaviour criteria — a failed transaction leaving no partial durable result, conflicts
across JVMs, limits bounding intermediate memory, every SPI path running with a cold cache and the
relational source disconnected. **Green gates plus unticked acceptance cases is exactly the
configuration that produced the inspection.**

Still open: **R-01** (astra's transaction coordinator — complete, jar-verified, parked until it can
compose with R-02's conditions), **R-04** (in flight), **M-04** (not started), and the findings
declared out of scope in `docs/SUPPORT-CONTRACT.md` (M-03, M-05, M-06, R-10, R-11, R-12).


### Chapter 11 status at 2026-09-15 06:30 MT

`mvn -B clean test` green: core **188**, test-kit **15**, ddb **298**, spike 3 — **504 tests**, up
from 287 when the inspection landed.

**Eighteen of twenty-one inspection findings closed**, plus findings 20-26 discovered while closing
them. What remains:

| Finding | State |
|---|---|
| R-11 | narrowed in `docs/SUPPORT-CONTRACT.md` Tier 3 — the mapping contract is stated rather than universal |
| R-12 | narrowed, and now **ordered**: finding 24 showed `refresh` is the first method a real application needs. Being implemented |
| M-03, M-05, M-06 | out of scope for 0.1.0, stated in the support contract |

**The ninth exit criterion is still 0 of 13.** No acceptance case from the end of
`docs/INSPECTION-2026-09-14.md` has an executable test yet. Astra's `finders` prescription — the
complex-finder matrix — is running now, and `acceptance` is queued behind it; those two are what
unblock the count.

**Topology decided and recorded** (`docs/TOPOLOGY-DECISION.md`): keep table-per-object. The deciding
fact is that `MithraObjectReader.find(...)` returns a single `CachedQuery` and
`Operation.getResultObjectPortal()` returns one portal, so a query reaching the adapter has exactly
one root type — single-table's heterogeneous-read benefit cannot be expressed at this seam. Verified
here with `javap` after astra declared its own gate blocked.
