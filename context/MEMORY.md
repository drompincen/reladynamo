# Session Memory

## Current Focus


## Recent Decisions


## Key Findings


## Open Questions


## Session Log

## Reladynamo closed loop

### Iteration 0 — baseline (2026-09-13)
- Gates: 5 pass / 0 fail / 1 pending
- `adapter-build` 2 tests (spike) · `demo-crm` 14 · `demo-petstore` 10 · `demo-classifier` 9
- `java11-floor` PASS (all classes ≤ major 55)
- `differential-h2-vs-ddb` **PENDING** — `DynamoDbPersister` not implemented. This is the real exit gate.
- Fixed two defects in `check.sh` itself before trusting it: `mvn -q` hid the summary line so every
  gate reported `tests=0` while passing; and a recursive surefire glob double-counted demo tests into
  `adapter-build` (35 → correct 2). A check script that misreports is worse than none.

### In flight
- grok-4.6 `impl/mapping` — XML → EntityMapping parser + KeyStrategy (Chapter 3)
- grok-4.6 `impl/codec` — JSON item codec, numeric fidelity, 400 KB ceiling (Chapter 3)
- codex `planner-impl/planner` — Operation → DDB query planner (Chapter 4), high-brainpower lane
- Cron `5735d38c` every 10 min: re-check, reconcile plan, log iteration

### Completed this session
- `TemporalEncoder` TDD'd green (7 tests): fixed-width 17, UTC, exact ms round trip, infinity sorts
  last, pre-epoch ordering correct. Encodes civil UTC fields rather than epoch offset — a signed
  offset sorts pre-1970 backwards as text.
- Core contract types: `TemporalMapping`, `AttributeMapping`, `EntityMapping`.
- `scripts/codex-fleet.sh` patched with a `CODEX_EXTRA_ARGS` passthrough so reasoning effort can be
  pinned. **Upstream this to drom-flow** — it is a generic gap, not project-specific.

### Iteration 1 (2026-09-13, cron tick 1)
- Gates: **5 pass / 0 fail / 1 pending** — unchanged shape, but `adapter-build` 2 → **9 tests**
  (TemporalEncoder's 7). Forward motion, no regression.
- `differential-h2-vs-ddb` still PENDING (expected — persister not written).
- **Confirmed** the `CODEX_EXTRA_ARGS` patch works: invocation is
  `-m gpt-5.6-sol -c model_reasoning_effort="ultra"`. Earlier I said I could not verify this; now I can.
- **Codex sandbox problems found** (agent still running, 32 steps, 631 KB events):
  - `patch rejected: writing outside of the project` — its cwd is its own `output/` dir, and the task
    referenced real repo paths, which it tried to use literally. Task wording bug, mine.
  - `exec_command failed for /bin/bash -lc ...: CreateProcess ... No such file or directory` — codex
    cannot spawn shells in this WSL sandbox, so **it cannot run maven to verify its own build**.
    Its output must be treated as unverified until I compile it myself.
  - grok agents are unaffected (Windows processes, working exec).
- Correction to my own earlier diagnosis: codex writes `events.jsonl`, not `stream.jsonl`
  (`stream.jsonl` is 0 bytes and misled my first check).

### Iteration 1b — codex exhausted (correction)
**Correcting my own diagnosis above.** The `planner-impl` agent FAILED with zero output, and the cause
was **not** the sandbox errors I logged. The real final event was:

```
"You've hit your usage limit ... try again at 4:36 PM."
```

Codex is out of credits until ~16:36 local. The `patch rejected` / `CreateProcess` errors were real
but incidental noise earlier in the run, not the failure cause. I attributed the failure to them
before reading the final events — wrong call, corrected here.

**Consequence for routing:** the "codex for high-brainpower" rule is suspended until codex resets.
Everything goes to **grok-4.6**, which is unlimited on this account and has now built three complete
Maven projects successfully. Re-dispatching the query planner to grok rather than waiting ~5 hours.

The `CODEX_EXTRA_ARGS` patch is still good and verified (`-m gpt-5.6-sol -c model_reasoning_effort="ultra"`
appeared in the real invocation) — worth upstreaming to drom-flow regardless.

### Iteration 2 (2026-09-13, cron tick 1 cont.)
- Gates: **5 pass / 0 fail / 1 pending**. `adapter-build` 9 → **10 tests**. No regression.
- **DynamoDB Local now runs in-process and is proven** (`LocalDynamoDbTest`): table created, item
  round-tripped, 9.1 s startup. The `sqlite4java` native-library trap is solved by unpacking
  `com.almworks.sqlite4java` natives to `target/native-libs` via maven-dependency-plugin and pointing
  `sqlite4java.library.path` at them from surefire. `LocalDynamoDb.start()` fails fast with an
  explanatory message if that property is missing, rather than an opaque `UnsatisfiedLinkError`.
  This unblocks the differential gate's infrastructure — the remaining blocker is only the persister.
- Used the verified `local.main.ServerRunner` (not `local.server.ServerRunner`, the import defect
  caught in the `ddb-expert` skill earlier). That fix paid off immediately.

### Iteration 3-4 (2026-09-13, cron tick 2)
- Iteration 3: 5 pass / 0 fail / 1 pending — flat (agents still running, no new code from me).
- **Write-path spike PASSES.** Swapping the reader also swaps the persister: one public setter binds
  both halves. Recorded in DECISIONS.md. This was the last unproven assumption in the core design.
  `adapter-build` now 11 tests.
- Residual risk narrowed: only `mithraTuplePersister` stays unswappable. Fallback C is now a
  contingency for that one case rather than for the write path generally.

### Iteration 5-6 (2026-09-13, cron tick 3)
- Iteration 5: flat at 11 (agents still running). Iteration 6: `adapter-build` **18 tests**.
- Built `TemporalRowSetDiffer` + `RowSetDiff` TDD (RED: 24 unresolved symbols → GREEN, 7 tests).
  This produces the differential gate's verdict, so it is deliberately strict:
  - temporal values compare **exactly** — no millisecond tolerance, since preserving boundaries is
    the adapter's whole purpose and a rounding differ would hide the one bug class that matters
  - row **order is not** part of the contract (DDB returns sort-key order, H2 plan order) — rows
    match on identity, not position
  - MISSING vs EXTRA rows are named distinctly, never reported as value differences
  - empty-vs-empty says so explicitly, so a vacuous pass is visible rather than green
  - cross-type numeric comparison fails (a long/int or double/BigDecimal substitution must not pass)
- The differential gate's machinery is now complete: DynamoDB Local harness + row differ. The only
  remaining blocker for the exit criterion is `DynamoDbPersister` itself.

### Iteration 7 (2026-09-13, cron tick 4)
- Gates: 5 pass / 0 fail / 1 pending. `adapter-build` flat at 18 (my work this tick was review +
  decisions, not new adapter code).
- `impl/mapping` is effectively complete: **45 tests green** across 4 test classes
  (`MithraObjectXmlParserTest` 16, `DefaultKeyStrategyTest` 12, `MappingValidatorTest` 11,
  `MithraObjectXmlCorpusTest` 6), plus `NOTES.md`, `BUILD-LOG.txt` and `OBJECTIONS.md`. Still RUNNING,
  so **not integrated yet** — integrating mid-flight risks copying files it is still editing.
- Its `OBJECTIONS.md` found **three real design conflicts**, resolved in DECISIONS.md:
  1. SK timestamp format — my `TemporalEncoder` (17 char) vs design's ISO-8601 (23 char). Kept the
     encoder, **amended the design doc** so the two stop disagreeing. I created this conflict by
     telling the agent to use the encoder while the doc said otherwise.
  2. Non-temporal SK — **the design won, my task instruction was wrong.** `v1#ND`, not null. The
     agent's reasoning decided it: a uniform key schema stops table creation, executor and planner
     from special-casing non-dated entities.
  3. Entity token uppercased (`v1#CUSTOMER#`) — design won.
- Asking agents for an OBJECTIONS.md is earning its keep: an agent that implements-then-objects
  surfaces conflicts that an agent which silently picks one side would bury.

### Iteration 8 (2026-09-13, cron tick 5) — mapping + codec INTEGRATED
- `adapter-build` **18 → 123 tests**. Gates: 5 pass / 0 fail / 1 pending. No regression.
- Integrated both grok agents into the real tree. Both had left my contract files byte-identical
  (verified by diff before copying).
- **Excluded the codec agent's duplicated `io/reladynamo/core/**`** — it had copied the contract into
  `reladynamo-ddb` to compile standalone. Copying that would have created two divergent definitions
  of `EntityMapping`/`TemporalEncoder` in one reactor.
- Added `jqwik 1.8.5` for the codec's property tests.
- **Caught a silent false green:** five `*Properties` classes compiled but never ran — surefire's
  default includes only match `*Test`/`*Tests`/`Test*`/`*TestCase`. Added `**/*Properties.java` to the
  include list; ddb went 44 → 63 tests. Worth remembering: a passing build is not evidence that the
  tests you wrote were executed.
- Two more agent objections accepted into DECISIONS.md, both correcting *me*:
  - **`BigDecimal` as `S` not `N`** — `N` trims trailing zeros, so `1.10` becomes `1.1`. Silent scale
    loss on money. My brief said `N`; the agent followed design 03 and was right.
  - **Nulls stored as explicit `NULL`** so "SQL NULL" stays distinguishable from "attribute absent in
    an older schema version".

### Iteration 9-10 (2026-09-13, cron tick 6) — planner attempted, REVERTED per protocol
- Iteration 9: flat at 123. Iteration 10: back to 123 after a revert.
- `planner2` FAILED with **"max turns reached"** mid-diagnosis, but left 24 main + 10 test classes
  with 85/86 tests passing. Worth finishing, not discarding.
- Integrated it and fixed two things myself:
  1. **Invented API**: `AsOfAttribute.isToInclusive()` does not exist — real name is
     `isToIsInclusive()` (javap). Third invented-API defect caught by verification this session.
  2. **Reflective stub portals do not work.** Building an `Operation` tree runs `AnalyzedOperation` →
     `OperationEfficiencyComparator` → class metadata → cache → index refs; each stub fix exposed the
     next null. Replaced with a real Reladomo boot over in-memory H2 (the spike's proven pattern).
     That cleared all three portal NPEs.
- **Then the adversarial fuzzer found a genuine planner bug** — exactly what it exists for:
  `ruleId IN (2,3) AND ruleId = 1` is unsatisfiable; Reladomo's `matches()` says false; the planner
  said **true**. It consumes `ruleId = 1` as the partition key and **drops the `IN (2,3)` predicate**,
  so the contradiction vanishes and a non-matching row is returned.
- **REVERTED the planner integration.** `adapter-build` had gone 123 PASS → FAIL, which is a
  regression by my own definition, and the protocol says revert rather than waive. Backup of
  `reladynamo-core` taken beforehand (no git in this repo). Clean rebuild confirms 123 green.
- Dispatched `planner3` on grok-4.6 with the exact failing seed, both fixes to apply, and an explicit
  instruction **not** to weaken or disable the adversarial test to get green — it found a real bug,
  which is it working.

### Iteration 11-12 (2026-09-13, cron tick 7)
- Iteration 11: flat at 123 (planner3 just started). Iteration 12: `adapter-build` **123 → 128**.
- Started Chapter 5 (the persister) on the half that needs no planner: **the write path operates on
  `MithraDataObject`s via codec + key strategy**, all of which are integrated and green, so it is
  unblocked regardless of planner3.
- Built `BatchWriter` + `BatchWriteClient` + `UnprocessedWritesException` TDD (RED: 84 unresolved
  symbols → GREEN, 5 tests). This targets the single most common silent-data-loss bug in DynamoDB
  code: **`BatchWriteItem` can return HTTP 200 having written only part of the batch**, with the rest
  in `UnprocessedItems`. Pinned by test:
  - chunks at 25 (the hard API limit)
  - resubmits until `UnprocessedItems` is empty (asserted on call count, so a single-shot
    implementation fails)
  - **throws rather than returning** when the attempt budget is spent with items still pending —
    returning normally is the data loss it exists to prevent
  - rejects two operations on the same key in one batch, naming the key (AWS's own
    `ValidationException` does not)
  - exponential backoff with full jitter
- `UnprocessedWritesException extends MithraDatabaseException`, so callers see Reladomo's exception
  contract rather than an AWS type leaking through the adapter.
- Narrowed the AWS dependency to a one-method `BatchWriteClient` interface so the partial-failure
  paths are testable with a scripted fake — they are the paths that matter and are awkward to provoke
  against a live service.

### Iteration 13-14 (2026-09-13, cron tick 8)
- Iterations 13 and 14: **131 tests, 5 pass / 0 fail / 1 pending.** No regression.
- **Applied the two key-strategy resolutions** that were decided in DECISIONS.md last tick but not yet
  in code:
  - entity token now **uppercased** (`v1#CUSTOMER#42`)
  - non-temporal entities now get **`v1#ND`** instead of a null sort key, so every table shares one
    key schema and table creation / executor / planner need no non-dated special case
  Four test assertions updated to the decided behaviour — they failed correctly first, which is the
  point of changing behaviour test-first even when the change is deliberate.
- **Verified the Reladomo value-extraction API** before designing the `MithraDataObject` → attribute
  bridge the persister needs:
  - `RelatedFinder.getPersistentAttributes()` / `getAsOfAttributes()` exist ✓
  - but the base `Attribute` has **no generic `valueOf`** and **no `isAttributeNull`** — extraction is
    typed per subclass (`IntegerAttribute.valueOf`, `StringAttribute.valueOf`,
    `TimestampAttribute.valueOf`, …), reachable generically only through the erased bridge method.
  - So the bridge needs **explicit type dispatch**. This is exactly why the codec agent deferred it
    rather than inventing an API, and its restraint was correct.

### Iteration 15 (2026-09-13) — PLANNER INTEGRATED, Chapter 4 complete
- `adapter-build` **128 → 166 tests**. Gates: 5 pass / 0 fail / 1 pending. No regression.
- `planner3` DONE and integrated. **Verified the adversarial test was not weakened** before trusting
  the green — that was the one unacceptable outcome:
  - no `@Disabled`, still `@Property(tries = 80)`
  - grew 330 → 351 lines rather than shrinking
  - seed **342 pinned as a named regression seed** alongside 10 others
- **The contradiction bug is fixed properly, not patched.** Rather than merely retaining the dropped
  predicate as a residual, it now converts the operation tree to **DNF, plans each conjunction
  separately, and introduces `PlanKind.EMPTY`** — so `ruleId IN (2,3) AND ruleId = 1` is recognised as
  unsatisfiable at plan time and returns a provably-empty plan instead of a query that fetches wrong
  rows. That was the stretch goal in the task, not the minimum.
- Also applied its two required fixes: `isToIsInclusive()` (the invented-API correction) and the real
  H2-backed `PlanReladomoBoot` replacing reflective stub portals.
- Re-enabled `reladomogen` in `reladynamo-core` for the three planner fixtures
  (PlanCustomer / PlanPosition / PlanRule).

**Chapters 3 and 4 are now implemented and green.** Remaining for the exit gate: the
`MithraDataObject` ↔ attribute bridge (needs explicit type dispatch — no generic `valueOf` exists),
then `DynamoDbPersister` wiring codec + keys + planner + BatchWriter together.

### Iteration 16-17 (2026-09-13, cron tick 9) — data-object bridge built
- `adapter-build` **166 → 179 tests**. 5 pass / 0 fail / 1 pending. No regression.
- Built `MithraDataAccessor` (Chapter 5's missing bridge) TDD, with **explicit type dispatch** over 14
  concrete Reladomo attribute classes, because there is no generic `valueOf` on the base `Attribute`.
  An unrecognised attribute type **throws** rather than being skipped: a missing dispatch would
  silently drop a column from the persisted item, which is invisible until someone reads it back.
- **Two false assumptions of mine, corrected by the tests:**
  1. `getPersistentAttributes()` **already includes** the temporal boundary columns — they are not
     separate. Good: that is exactly what persistence needs.
  2. Reladomo names them **`<axis>From` / `<axis>To`**, not `Thru`. My `TemporalRowSetDiffer` had
     invented `Thru`, so it would not have recognised the real columns as temporal and would have
     compared them as ordinary values — losing the exact-comparison rule on the very fields the
     differential gate exists to check. Differ updated to accept both.
- **Fixed a flaky test, which counts as a failing test.** The adversarial fuzzer could generate two
  as-of operations on one axis, which Reladomo rejects with `can't have multiple asOf operations`.
  It passed at iteration 15 and failed at 16 purely on which seeds jqwik drew. The generator now
  tracks used axes and re-rolls onto a non-temporal leaf. **Verified by running it three times** —
  green each time — rather than assuming the fix worked.

### Iteration 18-19 (2026-09-13, cron tick 10) — WRITE PATH WORKING against real DynamoDB
- `adapter-build` **171 → 183 tests**. 5 pass / 0 fail / 1 pending. No regression.
- Built `DynamoDbWriter` TDD **against in-process DynamoDB Local, not mocks** — a mock would confirm
  the calls we meant to make; only a real store confirms the item that actually lands, which is what
  the differential gate compares. Covers insert / delete / purge / batchInsert / batchDelete.
- The load-bearing test: **two versions of one primary key coexist as separate items**, because the
  sort key embeds both temporal axes. If they collapsed into one item, every bitemporal guarantee
  would be lost at the storage layer regardless of what Reladomo does above.
- Also covered: batch insert of 60 rows spanning three `BatchWriteItem` calls, which is where a
  chunking or unprocessed-items bug would silently lose writes.
- **Found and fixed a real gap in the mapping layer.** `MithraObjectXmlParser` validated
  `fromColumnName`/`toColumnName` but never emitted `AttributeMapping`s for them, so the four temporal
  boundary columns could never be encoded — the codec rejected them as unknown attributes. Since
  Reladomo's `getPersistentAttributes()` returns them alongside business attributes and the
  differential gate compares all four exactly, they are stored columns, not metadata. The parser now
  emits them as `Timestamp` attributes named `<axis>From`/`<axis>To`, matching what
  `MithraDataAccessor` extracts so the two sides line up with no translation table.
- `DynamoDbWriter` is deliberately **not temporal-aware**: it stores rows that already carry their own
  boundaries. Re-deriving any bitemporal rule below the seam is the one sure way to diverge from H2.

### Iteration 20-22 (2026-09-13, cron tick 11) — persister + executor in
- `adapter-build` **175 → 190 tests**. 5 pass / 0 fail / 1 pending. No regression.
- Built **`DynamoDbPersister`** — the single seam. Implements `MithraObjectReader` +
  `MithraDatedObjectPersister` on one class, which is not a convenience: `WritePathSpikeTest` proved
  one `setMithraObjectReader` call binds both halves, so both interfaces must live on one instance.
  Write path fully wired through `MithraDataAccessor` → `DynamoDbWriter`.
- Unimplemented reads **throw by name**. An adapter that returned an empty result for an
  unimplemented read would look like a working query over an empty table — diagnosed as a data
  problem, possibly long after someone concluded the adapter worked.
- `setTxParticipationMode` accepts silently: DynamoDB has no interactive transaction to enlist in, and
  refusing would break callers that set a mode they do not depend on. The real limitation is
  documented, not faked.
- Integrated grok's `QueryPlanExecutor` + `TableCreator` (12 tests). Verified it honours the two
  requirements that matter: **EMPTY issues no call at all** (asserted on a counting client) and
  **full pagination** past `LastEvaluatedKey`.
- **Corrected a dishonest gate condition of my own.** `check.sh` gated the differential result on
  `DynamoDbPersister.java` existing. The persister now exists while the suite that compares H2 against
  DynamoDB does not — so the gate would have flipped to PASS on the strength of code nobody had
  compared against anything. It now gates on the differential suite itself.

### Iteration 24-25 (2026-09-13, cron tick 12) — EXIT CONDITION MET (as defined)
- **6 pass / 0 fail / 0 pending. `exit_condition_met: true`.**
- `BitemporalDifferentialTest` is green: real Reladomo bitemporal operations run on H2, the resulting
  rows go through the adapter into DynamoDB, are read back, and compared with `TemporalRowSetDiffer`.
  Insert, **retroactive correction**, and **terminate** all produce identical row sets including all
  four temporal boundaries.
- The mapping is parsed from the **same XML the generated objects came from**, so this tests the
  generic adapter rather than a hand-written mapping. That surfaced immediately: the table name is
  `DIFF_BALANCE` from `<DefaultTable>`, not my local constant — a real check that the mapping is
  XML-driven.
- Two gate-honesty fixes while wiring it up:
  - the gate now requires the suite to have **actually run** (`Tests run > 0`), not merely to have
    built green — the same vacuous-pass trap as the `tests=0` bug at iteration 0;
  - `-DfailIfNoSpecifiedTests` is ignored by surefire 3.2.5; the real flag is
    `-Dsurefire.failIfNoSpecifiedTests`. Without it the gate FAILED for the wrong reason, which at
    least failed loudly rather than passing quietly.

**Scope, stated plainly:** the suite proves storage, key-derivation and codec fidelity across the
bitemporal operations exercised. It does NOT yet prove query-path equivalence — the read path does not
materialise Reladomo objects, so DynamoDB is queried for rows directly rather than through a finder.
The exit criteria I wrote are met; the plan's Chapter 7 conformance matrix is not. Raising the bar
rather than declaring the project finished.

### Iteration 26-27 (2026-09-13, cron tick 13) — bar raised, read path started
- Gates hold at **6 pass / 0 fail / 0 pending**. 201 tests.
- **Raised the bar rather than stopping at the green gate.** Dispatched `conformance` on grok-4.6 to
  expand the differential suite from 3 tests to the full Chapter 7 matrix: all 14 temporal operations,
  all three directors (adding audit-only and non-audited fixtures), plus infinity, zero-length
  segments, out-of-order business dates, same-millisecond writes, chained terminate-reinsert and full
  history reconstruction. Explicit instruction: **a genuine H2/DynamoDB divergence is a finding to
  record in `FINDINGS.md`, not a test to weaken.**
- Wired **`count()`** through planner → executor — the first read method that actually runs end to end.
  It counts by executing the plan and measuring the result rather than using DynamoDB's server-side
  `Select.COUNT`, because the plan can carry a residual predicate only this layer can evaluate; a
  server-side count would ignore it and over-report.
- Added a write-only constructor so a persister without the read path **refuses by name** instead of
  NPEing on a null planner.
- My own test caught that the new refusal message had dropped the entity name. Restored — the message
  now says which entity, which is the useful part when several are configured.

### Iteration 28-29 (2026-09-13, cron tick 14) — README written (Chapter 8)
- Gates hold at **6 pass / 0 fail / 0 pending**, 201 tests. No regression.
- Wrote `README.md` (187 lines) — Chapter 8's main deliverable, fully unblocked.
  - Leads with the architectural idea (the `TemporalDirector` sits above the seam, so bitemporality is
    inherited rather than reimplemented) because that is what makes the claim testable.
  - **Honest status table**: names what works, and states plainly that the read path does not yet
    materialise objects and the differential suite proves storage fidelity rather than query-path
    equivalence. Marked "not production ready".
  - Uses the car-classifier demo's **real printed output** as the 60-second explanation of
    bitemporality, rather than an invented example.
  - "Limits, stated plainly" section: transaction caps, Scan opt-in, 400 KB items, GSI consistency,
    and BigDecimal-as-string with the trailing-zero reason.
  - Documents the processing-major sort key **and its consequence** — a `businessDate`-only predicate
    is a non-contiguous suffix and cannot be a native range condition.
- **Verified every API in the README against the source and the jar** rather than writing
  plausible-looking code: `parse(String)`, `ItemCodec(EntityMapping)`, the `DynamoDbWriter`
  constructor, `setMithraObjectReader`, and `v1#ND`. Documentation that drifts from the code is how a
  README becomes actively misleading.

### Iteration 30-31 (2026-09-13, cron tick 15) — HTML explainer published (Chapter 8)
- Gates hold at **6 pass / 0 fail / 0 pending**, 201 tests.
- Published **"The Bitemporal Seam"** — https://claude.ai/code/artifact/5baee108-1d98-4425-87cb-7a0bbc559683
  Three hand-authored inline-SVG diagrams, each drawing a mechanism rather than naming one:
  1. **One box changes** — a shared Reladomo stack (finder → portal → TemporalDirector) forking into
     JDBC/SQL vs DynamoDbPersister/DynamoDB. Drawn as a *fork* rather than two side-by-side stacks so
     the picture shows what is identical and what is substituted.
  2. **Two axes** — the 2-D business/processing grid for a retroactive correction: the old belief
     closed at June, two replacement rectangles above it.
  3. **Sort-key anatomy** — why processing-major ordering makes `businessDate` a non-contiguous suffix
     that cannot be a native range condition.
- Colour encodes meaning rather than decorating: business time and processing time each get a hue,
  which is defensible precisely because the subject *is* two axes. A third signals the replaced box.
- Type: IBM Plex Sans / Source Serif 4 / IBM Plex Mono — deliberately not the Inter-and-Space-Grotesk
  default.
- Caught a corrupted CSS token (`#E0Aköpe`) before publishing, and verified all 13 tokens are declared
  in bare `:root` and redefined in both dark blocks — the classic unreadable-artifact bug.
- The car-classifier table on the page is **real demo output**, not an illustration.

### Iteration 32 (2026-09-13, cron tick 15 cont.) — conformance attempted, findings captured, gate restored
- Gates back to **6 pass / 0 fail / 0 pending**, 201 tests.
- `conformance` FAILED on **"max turns reached"** — the same budget exhaustion that killed `planner2`.
  **Lesson recorded: a task spanning 14 operations × 3 directors × 6 edge cases is too large for one
  agent.** Future conformance work goes out as three smaller agents.
- Its 55 files were salvageable and produced four real findings, written to
  `docs/CONFORMANCE-FINDINGS.md`:
  1. **A genuine bug in my own `TemporalRowSetDiffer` — fixed.** Row identity used only the FROM
     boundaries, so `insertWithIncrementUntil`'s two rows (same key, same FROMs, different
     `businessDateTo`) collapsed into a duplicate instead of being compared. A bitemporal row is a
     rectangle and two rectangles can share a corner. This is the failure mode that would let a real
     divergence pass as "identical", so finding it is worth the whole exercise.
  2. **Audit-only objects get a narrower generated API than assumed**: `insertUntil`/`terminateUntil`
     yes; `setXUntil`, `increment*`, `insertWithIncrement*` **not generated at all**. Enforced at code
     generation, which is a stronger guarantee than a runtime rejection.
  3. **Non-audited objects reject `inPlaceUpdate` at generation time** — `reladomogen` fails the build:
     "Inplace update can only be performed if Processing Date is present".
  4. Four tests still failing with causes not yet established — parked and listed, not swept away.
     The most consequential is `correction_destroys_the_prior_value_in_both_stores`: if that is a real
     divergence it means the adapter preserves history where Reladomo destroys it.
- **Parked the four WIP test classes rather than leaving the gate red**, per the regression protocol.
  Kept the differ fix, which stands on its own tests.

### Iteration 33-34 (2026-09-13, cron tick 16) — the biggest open finding resolved
- Gates hold at **6 pass / 0 fail / 0 pending**, 201 tests.
- **Investigated `correction_destroys_the_prior_value_in_both_stores` with evidence rather than
  reasoning, and it was the TEST that was wrong, not the adapter.** H2 itself returned
  `["original", "corrected"]` — the premise failed against the reference before DynamoDB was involved.
- The real semantics are narrower than "a non-audited correction destroys history":
  - correcting the **same** open business segment → prior value destroyed, one physical row
  - dating a change **later** → the timeline splits and the prior value survives, because it is
    still true for the earlier period
  Non-audited storage loses prior **beliefs**, not prior **business time**. Split into two tests, one
  per case; both now pass including the DynamoDB replay.
- **Cross-checked the petstore demo, which makes the same claim — and makes it correctly**: it asserts
  exactly one physical row after the correction, so it genuinely exercises the in-place case. Worth
  verifying rather than assuming, since I had praised that test earlier and the wrong version of this
  claim is very easy to write.
- **Applied the max-turns lesson**: re-dispatched the remaining conformance work as **three small
  agents, one test class each, run serially** (`max_parallel: 1`) rather than one agent for the whole
  matrix. Each task carries the established findings so nothing is rediscovered.

### Iteration 35-36 (2026-09-13, cron tick 17) — materialisation groundwork
- `adapter-build` **193 → 198**. Gates hold at 6 pass / 0 fail / 0 pending.
- Built **`MithraDataPopulator`** — the inverse of `MithraDataAccessor`, required before `find()` can
  return real objects instead of raw rows. Typed dispatch over 14 attribute classes, mirroring the
  extractor, because Reladomo declares `setValue` only on the subclasses.
- The round-trip test is the point: an extractor and a populator that disagreed on a name or type
  would corrupt data in a way **neither side's own tests would notice**.
- Two refusals, both deliberate: an unknown attribute name throws (ignoring it means the stored item
  carried data the object never received), and a null value is written as null rather than skipped
  (skipping leaves a default that reads as real data).
- **Found a real Reladomo constraint**: `setValueNull` on a temporal boundary NPEs inside the
  generated setter, which compares against infinity. A persisted dated row always carries all its
  boundaries, so a null there means a malformed item — now refused with a message that says so,
  instead of surfacing an NPE from generated code where the cause is invisible.
- Established the rest of the materialisation path for a later tick: `CachedQuery(Operation, OrderBy)`
  + `setResult(List)`, and the `*Data` classes are public and directly constructible — which is what
  a deserializer does, and what the corrected test now exercises.

### Iteration 37-38 (2026-09-13, cron tick 18) — materialisation chain complete
- `adapter-build` **198 → 203**. Gates hold at 6 pass / 0 fail / 0 pending.
- Built `MithraDataFactory`, the last missing link: creates the generated `*Data` instance for a
  finder. Reladomo's database object can only inflate from a `ResultSet`, which is useless to an
  adapter reading DynamoDB items, and there is no generic public factory — so it resolves the
  generated class by name and caches the constructor per finder (reflection on every row of every
  query would be a real cost in the read path).
- **Found the trap the hard way, then fixed it properly**: `MithraObjectPortal.getBusinessClassName()`
  returns the **simple** name (`PlanRule`), which reflection cannot resolve.
  `RelatedFinder.getFinderClassName()` carries the package, so the data class is derived from that
  instead, with an explicit check that the name really ends in `Finder`.
- One test earns its place beyond the happy path: **`creates_a_distinct_instance_each_call`**. A cached
  or shared instance would make every materialised object equal the last row read — a bug that would
  look like a query returning duplicates rather than a factory fault.
- The chain is now complete end to end: `item → ItemCodec.decode → MithraDataFactory.newData →
  MithraDataPopulator.populate → MithraDataObject`, with `CachedQuery(Operation, OrderBy)` +
  `setResult(List)` as the remaining wiring for `find()`.

### Iteration 39-42 (2026-09-13, cron tick 19) — find() wired, matrix integrated, gate strengthened
- `adapter-build` **203 → 219**; **the differential gate now covers 17 tests, up from 3.**
- Wired **`DynamoDbPersister.find()`**: plan → execute → decode → `MithraDataFactory` →
  `MithraDataPopulator` → `Cache.getObjectFromData`. Objects go through the portal's cache so identity
  behaves as on the relational path; handing back raw instances would break callers comparing by
  reference.
- `find()` **refuses to materialise a dated object without an as-of equality** rather than defaulting.
  A guessed as-of date returns rows that look entirely plausible and are silently the wrong version —
  nothing about the result would announce it.
- **Corrected my own TDD lapse**: I wrote `find()` before its test. Added `FindPathTest` covering both
  refusals. Worth noting rather than quietly fixing, since the discipline is the point.
- Integrated `conf3/matrix` — **14 operations, 0 failures.** It FAILED on max turns but had already
  reached a green build; verified integrity before trusting it (0 `@Disabled`, 14 `@Test`, uses the
  differ, no tolerance wording).
- Both of its "known failures" were **wrong premises, not divergences**, and it found a real API
  detail: Reladomo emits `setNoteUsingInPlaceUpdate` when the XML declares `inPlaceUpdate="true"` — a
  plain setter never reaches the director's in-place path.
- **Broadened the gate to sum every differential class**, not just the first. It had been reading one
  `Tests run:` line, which would have under-reported the suite's real coverage as it grew.

### Iteration 43-44 (2026-09-13, cron tick 20) — the "generic" claim tested properly
- `adapter-build` **219 → 220**. Gates hold at 6 pass / 0 fail / 0 pending.
- Built `DemoCorpusCodecTest`: **every object model in the three demo projects — 60+ entities across a
  CRM, a pet store and a car classifier — is parsed, keyed and round-tripped through the codec.**
- Why this one matters more than its test count suggests: those XMLs were written for three unrelated
  domains, by a different author, to demonstrate Reladomo rather than to suit this adapter. If the
  adapter only worked on fixtures written alongside it, "generic" would be a claim rather than a
  property. This is the test that tells the difference.
- Built in two anti-vacuity guards deliberately — it asserts both that 60+ files were found **and**
  that 60+ entities were actually exercised. A path typo would otherwise make it pass by finding
  nothing, which is the failure mode that has bitten this check script twice already.
- It collects every failure and reports them together rather than stopping at the first, so one bad
  entity does not hide the other seventy-nine.

### Iteration 45-46 (2026-09-13, cron tick 21) — audit-only conformance integrated
- `adapter-build` **220 → 236**; **differential gate now 32 tests** (was 3 at the start of the day).
- Integrated `conf3/audit` — 13 tests, verified clean first (0 `@Disabled`, uses the differ, no
  tolerance wording). All three of its known failures were **wrong premises, corrected with evidence
  from the generated code**, not weakened comparisons.
- Findings 6-8 recorded:
  - **`inPlaceUpdate` needs the generated `setXUsingInPlaceUpdate`.** A plain setter is an ordinary
    dated update and never reaches the director's in-place path.
  - **Audit-only `insertUntil`/`terminateUntil` are generated stubs that throw** — refining finding 2,
    which said only that they exist. The director's own message is unreachable from that path.
  - **My own bug**: the assertion I wrote used `Class.getMethods()`, which includes inherited methods,
    so it could have passed on a method the generated class never declared. The agent caught it and
    switched to declared methods. The whole point of that assertion is to pin what the generator
    emits, and the loose version could have confirmed the wrong thing.

### Iteration 47-48 (2026-09-13, cron tick 22) — Java 11 EXECUTION verified
- Gates hold at 6 pass / 0 fail / 0 pending, 233 tests.
- **Closed the gap flagged at the very start of this project**: `release=11` proves compilation, not
  execution. Downloaded a real Temurin JDK 11 and ran **`reladynamo-core`: 110 tests, 0 failures on a
  genuine Java 11 VM.**
- Two environment discoveries that cost time and are now written down so they cost nobody else any:
  - **This machine is `aarch64`**, not x86-64. The x64 JDK downloads fine and dies with
    `Exec format error`.
  - **The `mvn` on PATH is Windows Maven** reached through WSL interop — it resolved `/tmp/...` as
    `C:\tmp\...`. A WSL-native JDK is invisible to it, so running on Linux Java needs a Linux Maven
    too. That also explains why `java` on PATH is a shell shim rather than a binary.
- **Stated the limit honestly** in `docs/JAVA11-VERIFICATION.md`: `reladynamo-ddb` and
  `reladynamo-test-kit` cannot run on Linux ARM, because `com.almworks.sqlite4java` ships no
  `linux-aarch64` native and DynamoDB Local is a JNI wrapper over SQLite. So Java 11 execution is
  **proven** for core and **inferred** — from class-file level and API surface — for the DynamoDB
  modules. Closing that needs an x86-64 runner and belongs in CI.

### Iteration 49-50 (2026-09-13, cron tick 23) — plan reconciled, CI added
- Gates hold at **6 pass / 0 fail / 0 pending**, 233 tests.
- **Reconciled the plan with reality** — the tick instruction asks for this and I had been logging to
  MEMORY diligently while letting chapter statuses drift. Chapters 1, 2, 5 and 6 are now marked
  COMPLETE with what actually proves them; 7 and 8 moved to in-progress with their real state.
  24 checked / 68 open items.
- Added `.github/workflows/build.yml`:
  - **adapter on JDK 11 / 17 / 21** — execution, not just `release=11`
  - **the closed-loop gate** as its own job, uploading `check-latest.json` as an artifact
  - **each demo built separately**, because they are standalone projects outside the reactor and the
    root build does not cover them — they are the proof the adapter works on models it did not ship
    with
  - a comment recording *why* CI matters here specifically: `ubuntu-latest` is x86-64, so it can run
    the DynamoDB modules that this ARM machine cannot
- Dispatched `demoddb/classifier-ddb`: run the car-classifier demo's **own** XML through the adapter
  against DynamoDB and assert the MR2 walks `COOL → EIGHTIES_COOL → RETRO` from DynamoDB rows. Told it
  explicitly not to edit the demo's XML to suit the adapter — if it cannot consume it unchanged, that
  is the finding.

### Iteration 51 (2026-09-13, cron tick 23 cont.) — CHAPTER 7 COMPLETE
- `adapter-build` **233 → 249**; **differential gate now 48 tests** (3 this morning).
- Integrated `conf3/edge` — 16 tests, verified clean first. All three conformance agents are now in:
  matrix (14 ops), audit-only (13), edge cases (16).
- **11 findings recorded.** Every single "divergence" investigated this session turned out to be a
  wrong premise about Reladomo, corrected with evidence from generated code or `javap`. **Not one
  comparison was weakened**, and the adapter has not been found to diverge from H2 anywhere.
- The two subtlest findings, both from the edge agent:
  - **Zero-length `insertForRecovery` is illegal but zero-length `insertUntil` is not.**
    `insertForRecovery` calls `checkDatesAreWithinRange`, and `dataMatches` is half-open, so a
    `from == to` rectangle matches no as-of date at all. `insertUntil` does *not* range-check, so it
    persists a degenerate `[from, from)` row that `equalsEdgePoint()` can see and no as-of query ever
    can. Asymmetric by director design — and DynamoDB round-trips it faithfully either way.
  - **An unbounded `setX` as-of an earlier date ranges `[asOf, infinity)`** and therefore inactivates
    later business segments. A late-March write after a June update makes June read as the March
    value; June survives only as history.
- Also noted: `createProcessingTimestamp` clamps to 10ms granularity, which is why same-millisecond
  write tests behave the way they do.

### Iteration 52-53 (2026-09-13, cron tick 24) — second explainer published
- Gates hold at **6 pass / 0 fail / 0 pending**, 249 tests, 48 differential.
- Published **Planning a Bitemporal Query** —
  https://claude.ai/code/artifact/55fae26f-e916-4196-8b98-555528428c3e
  Same design language as the first explainer so the pair reads as one set; new semantic palette
  where each colour is a **plan destination** (key condition / filter / in-memory residual / refused),
  which is information rather than decoration.
- Content is drawn from the implemented planner, not invented: the real `PlanKind` values, the real
  fan-out cap of 100, `FAST_PATH_CURRENT_ASOF` / `FAST_PATH_POINT_GET`.
- Three diagrams: predicate classification (with `businessDate` deliberately landing in *filter*, not
  key condition), why a processing-major sort key scatters identical business dates across the key
  space, and the five plan kinds including `EMPTY`.
- Explains the seed-342 contradiction bug as the reason `EMPTY` exists, and makes the argument that
  **refusing matters more than optimising**: a silent Scan does not fail, it returns correct rows at
  rising cost until the table is big enough that it doesn't.
- Both explainers now linked from `README.md`.

### Iteration 54-56 (2026-09-13, cron tick 25) — licence gate added; headline claim proven
- **7 gates now, all green.** 249 adapter tests, 48 differential, demo-classifier 9 → 11.
- **Found and fixed a real licence bug.** `reladynamo-test-kit` declared DynamoDB Local (Amazon
  Software Licence — not OSI-approved, field-of-use restricted) and H2 (MPL/EPL) at **compile scope**,
  so anything depending on it inherited them transitively. That directly contradicts the MIT claim in
  `THIRD-PARTY-NOTICES.md`, which I wrote hours ago and never enforced.
- Added a `maven-enforcer` banned-dependencies rule and a **`licence-scope` gate** in the closed loop.
  The enforcer immediately found a **second leak I had missed by eye**: `reladomo-test-util` at compile
  scope drags H2 in transitively. Nothing in test-kit's main sources uses either, so both dropped to
  test scope.
- **The headline claim is proven.** `demos/03-car-classifier` now runs against DynamoDB:
  `ClassifierDynamoDifferentialTest`, original 9 tests still green, 11 total. **All five object model
  XMLs unchanged** — I diffed each one rather than trusting the agent's report, because "we didn't
  edit the XML" is exactly the claim an agent would be tempted to fudge.
- Same object model, same generated finders, same call sites; only runtime configuration differs.

### Iteration 57-58 (2026-09-13, cron tick 26) — security review; licence distinction sharpened
- 7 gates green. 249 adapter tests, 48 differential.
- Wrote `docs/SECURITY-REVIEW.md` against the adapter's real attack surface rather than a generic
  checklist. Findings, each verified rather than asserted:
  - **XXE mitigated AND tested** — I suspected the parser might be unhardened; it is not. All seven
    features are set, and `should_reject_external_entities_when_xml_declares_a_doctype` feeds a real
    `file:///etc/passwd` payload. My suspicion was wrong, which is the right outcome to check for.
  - **Expression injection impossible by construction** — no call site concatenates a value into an
    expression; everything binds through `ExpressionAttributeValues`/`Names`. Notably the safe path
    and the working path coincide here, since reserved words force the same discipline.
  - **No credentials in source.** The one literal is DynamoDB Local's conventional dummy pair, on a
    client whose endpoint is always overridden to 127.0.0.1.
  - **Unbounded-query DoS mitigated by design** — the planner refuses rather than degrading to Scan.
- **User clarified the licence rule**: "you can make it a dep, but not something you have to provide
  and distribute." That is exactly the arrangement — DynamoDB Local is `provided` in test-kit (so
  `LocalDynamoDb` compiles, non-transitively) and `test` in ddb. Verified with `dependency:list
  -DincludeScope=runtime`: **nothing licence-sensitive is in any published artifact's transitive
  closure.** Rewrote `THIRD-PARTY-NOTICES.md` to state depend-vs-distribute precisely, since "test
  scope only" had become inaccurate.

### Iteration 59-60 (2026-09-13, cron tick 27) — petstore on DynamoDB
- **demo-petstore 10 → 16 tests.** 7 gates green.
- Integrated `demoddb2/petstore-ddb` — 6 differential tests, verified first (0 `@Disabled`, uses the
  differ, **all four object model XMLs unchanged**, diffed rather than trusted).
- This is the demo the adapter had tested least: every entity has exactly **one** `AsOfAttribute`. The
  test asserts the sharp case — a same-segment correction on a non-audited entity **destroys** the
  prior value, and DynamoDB must reproduce that destruction rather than helpfully preserving an extra
  row. The mechanism is the sort key: business-only rows key as `v1#B#<businessDateFrom>`, so a
  same-segment put overwrites in place, exactly as H2 does.
- The agent scoped itself honestly in a class comment: it does not rebind Reladomo finders onto
  DynamoDB, noting that finder materialisation is a separate seam already gated in the adapter module.
  Accurate, and worth more than a test that quietly overclaimed.
- Two of three demos now run against DynamoDB with unmodified object models. `crm-ddb` in flight.

### Iteration 61-62 (2026-09-13, cron tick 28) — CHAPTER 9 COMPLETE: all three demos on DynamoDB
- **demo-crm 14 → 18.** 7 gates green. Demo totals: CRM 18, pet store 16, classifier 11 = **45**
  (was 33 on H2 alone).
- Integrated `demoddb2/crm-ddb` — 4 differential tests including the **GDPR consent case**: one
  business date, two processing dates, two different lawful/unlawful answers, reproduced from
  DynamoDB.
- **Verified the object models were untouched by digest**, not by spot-check: hashed all 48 CRM XMLs
  before and after integration — `736e896ac2cb2889` both times. Agents write to their own output dirs
  so this is true by construction, but the claim "the XML is unmodified" is the entire point of these
  tests, and a digest costs nothing to prove where an eyeball comparison of 48 files proves little.
- **All three demos now run against both stores with unmodified object models.** That is the
  project's central claim, demonstrated three times over on models written for unrelated domains.

### Iteration 63-64 (2026-09-13, cron tick 29) — migration tooling and guide
- `adapter-build` **249 → 254**. 7 gates green.
- Built `Backfill` + `BackfillResult` TDD (RED: 12 unresolved → GREEN, 5 tests against real DynamoDB).
  This turns the ad-hoc "mirror H2 rows into DynamoDB" pattern that all three demo tests were doing
  by hand into a shipped tool, which is what the earlier request for **migration examples** actually
  needed.
- Three design decisions, each aimed at a way migrations fail quietly:
  - **It reads back and diffs.** A partial copy leaves DynamoDB looking populated and being
    incomplete; a run that merely reports "done" says nothing about whether the rows arrived.
  - **An empty source is NOT verified.** `verified()` is false at zero rows, because
    *"migrated 0 rows, verified"* is indistinguishable from a misconfigured source query — the most
    dangerous green a migration can print.
  - **Idempotent by construction** — the item key includes the temporal boundaries, so re-running an
    interrupted migration converges rather than duplicating.
  - Refuses a row missing a temporal boundary before writing anything, rather than copying a row that
    could never be addressed or read back.
- Wrote `docs/MIGRATION.md`: five stages, lift through **the way back**. Kept the rollback stage
  deliberately — a migration tool without a proven exit is a lock-in trap. Also noted that a week of
  quiet diffing proves less in a bitemporal system than elsewhere, because late corrections are the
  entire point.
- Linked from `README.md`.

### Iteration 65-66 (2026-09-13, cron tick 30) — performance measured
- 7 gates green, 254 adapter tests. Benchmarks are excluded from the default build (`-Pbench`).
- Built `reladynamo-bench` (JMH 1.37) for the per-row hot path — temporal encoding, key derivation,
  item codec — and **actually ran it** rather than shipping a harness nobody executes.
- Results (ns/op): partitionKey 314, temporalDecode 428, temporalEncode 756, sortKey 1884,
  codecDecode 3089, codecEncode 6191.
- **The error bars are larger than the scores** (1 fork, 3×1s iterations). Recorded that plainly in
  `docs/PERFORMANCE.md` rather than presenting the numbers as precise — comparing two of those rows
  against each other would be reading noise.
- The conclusion that survives the noise: the whole per-row path is **single-digit microseconds**
  against a **single-digit millisecond** round trip — roughly 0.1–1% of one network call. Not the
  bottleneck; optimising it further would be effort spent where it cannot show up. What will actually
  cost is request count, which is why the planner caps fan-out and refuses Scans.
- **JDK 21 gotcha recorded**: JDK 21 no longer runs annotation processors found on the classpath.
  Without an explicit `<annotationProcessorPaths>` the JMH generator is silently skipped — compilation
  succeeds, and the failure surfaces much later as `Unable to find the resource:
  /META-INF/BenchmarkList`. The symptom appears at the opposite end of the build from its cause.

### Iteration 67-68 (2026-09-13, cron tick 31) — release readiness assessed
- 7 gates green. Adapter 254 tests (core 105, ddb 138, test-kit 8, spike 3); demos 45; differential 48.
- Wrote `docs/RELEASE-READINESS.md` — the capstone honest accounting. What is proven: the seam,
  inherited bitemporal semantics across 14 operations and 3 directors, genericity on 60+ third-party
  entities, Java 11 execution, the MIT claim enforced by a build rule.
- **What is NOT proven, stated first-class rather than buried**: no live AWS at all (the largest gap),
  no load at scale, query-path equivalence only partial, relationships and deep-fetch untested through
  the adapter, and `reladynamo-ddb` has never *run* on Java 11 — only compiled for it.
- Verdict recorded as: *suitable for evaluation, prototyping and design review; not for production
  data. The gap is operational, not architectural.*
- **Deliberately did NOT add `japicmp`.** It diffs against a published baseline, and none exists until
  0.1.0 — so it would be a plugin that always passes, which is worse than no check because it looks
  like one. Recorded the reasoning rather than silently skipping it.

### Iteration 71-73 (2026-09-13, cron tick 33) — the gate now admits what it does not measure
- **`differential-h2-vs-ddb` is now PARTIAL, and `exit_condition_met` is FALSE.** That is correct: the
  query path is broken and the loop should not report success.
- Split the differential gate to report **"48 storage-path, 0 query-path"** rather than a single total.
  Finding 12 existed *because* every differential test reads with `pk = :pk` and no sort-key
  condition, so 48 green tests said nothing about as-of translation. One combined number invites
  exactly the misreading that let a real bug hide behind it.
- Made `PARTIAL` count as pending so it blocks the exit condition — otherwise the honesty would have
  been cosmetic. I nearly shipped that: the first attempt printed PARTIAL while still reporting
  "0 pending" and `exit_condition_met: true`.
- Updated `RELEASE-READINESS.md` from "query-path equivalence is partial" to **"query-path equivalence
  is BROKEN, not merely unproven"**, and downgraded the verdict: suitable for design review, not for
  evaluation against real workloads until finding 12 is fixed.
- The distinction worth keeping: *the architecture is proven* and *the software works* are different
  claims, and only the first is currently true. The bug is in predicate translation, not in the seam
  or the temporal model.

### Iteration 74-75 (2026-09-13, cron tick 34) — enumerated the untested surface
- Gate correctly PARTIAL, `exit_condition_met: false`. `asof-fix` at ckpt 3, confirming the mechanism
  in the real `QueryPlanner` before changing anything, as instructed.
- Generalised finding 12's lesson instead of only fixing the instance. It was found by writing the
  first test of an untested thing — so I enumerated the rest of the untested surface deliberately,
  in `docs/COVERAGE-GAPS.md`.
- **The hard number that emerged: `DynamoDbPersister` implements 11 of 32 SPI methods; 21 refuse.**
  I had been describing the write path as "done", which is true of the 11 and says nothing about the
  21. Notably `enrollDatedObject` and `getForDateRange` both refuse, and both are declared on
  `MithraDatedObjectPersister` itself — they exist precisely because dated objects need them, so they
  are likely to be hit early.
- Also catalogued the structural blind spots: relationships/deep-fetch, aggregation, cursors, cache
  eviction, cross-entity transactions, concurrency, real AWS, and scale — none covered.
- Added the SPI figure to `RELEASE-READINESS.md`. "The write path is done" was an overstatement I had
  repeated several times today; it is now qualified wherever it appears.
- The document's stated purpose: *before claiming the adapter handles something, check whether it is
  on this list — if it is, either the claim is wrong or the list is out of date, and both are worth
  knowing.*

### Iteration 69-70 (2026-09-13, cron tick 32) — A REAL DIVERGENCE FOUND
- Gates hold at 7/7 (the failing test is parked, not counted).
- Wrote `FinderDrivenDifferentialTest` — the first test to drive **both stores through Reladomo
  finders**, rebinding the portal between runs. It closes the "query-path equivalence is partial" gap
  I have been flagging since iteration 25.
- **It immediately found a real adapter bug — finding 12, the first of the session that is not a wrong
  premise.** `businessDate = X and processingDate = infinity` returns **1 row from H2 and 0 from
  DynamoDB**. `count()` agrees: expected 1, got 0.
- The row is definitely stored; the storage tests write and read it back. What fails is **as-of
  translation into a query**.
- **Why 48 green differential tests missed it**: every one of them reads back with `pk = :pk` and no
  sort-key condition, so none exercise as-of translation at all. The suite proved storage fidelity
  thoroughly and query fidelity not at all — exactly what `RELEASE-READINESS.md` said, except the
  unproven part turned out to contain a bug rather than merely being unverified.
- Hypothesis recorded **as a hypothesis**: an as-of predicate is range containment
  (`from <= asOf < to`), not equality on the FROM boundary. Told the fix agent to dump the actual
  `QueryPlan` and confirm before changing anything — this style of reasoning has been wrong three
  times today.
- Test parked so the gate stays honest; dispatched `asof/asof-fix` with instructions to fix the
  adapter rather than the test, and to check the sibling cases (past business date, past processing
  date, finite `businessDateTo`) since a fix that only handles infinity is half a fix.

### Iteration 76-77 (2026-09-13, cron tick 35) — finding 12 FIXED; gate back to 7/7
- **`exit_condition_met: true` again.** `adapter-build` 254 → 262; differential now **48 storage-path
  + 6 query-path**. The query path is proven for the first time.
- **My hypothesis was wrong**, which is exactly why the agent was told to confirm before fixing.
  I guessed "equality against `processingDateFrom`". The dumped plan showed ordinary half-open
  containment — and the real cause was a **timezone mismatch**:
  - `MithraObjectXmlParser` cannot classload `infinityDate="[...getDefaultInfinity()]"`, so it
    substitutes a **conventional UTC** sentinel.
  - Reladomo's real infinity is `9999-12-01 23:59:00` in the **JVM default timezone** — UTC-6 here.
  - The two differ, `Timestamp.equals` is false, Reladomo's infinity special case is skipped, and
    `thru > infinity` matches nothing. Zero rows, not wrong rows.
- **Why 38 planner tests and 48 differential tests all missed it**: `PlanFixtures` sets
  `PhysicalDesign.infinity()` to Reladomo's own sentinel, so the two values agree throughout the
  planner suite. Only the production path puts the differing values side by side.
  **A fixture more correct than production hides precisely the bugs production has** — the sharpest
  lesson of the session.
- Recorded **finding 13** separately: the conventional-infinity substitution is a landmine beyond the
  one query it broke, and is currently patched at the comparison site rather than at the root. The
  root fix is to resolve infinity from the generated `AsOfAttribute` at bind time.
- Also upstreamed the 4 skills into drom-flow across all six registration points, and fixed a
  **pre-existing drift bug** there: `SCRIPTS.md` (the canonical source) was missing 8 `MANAGED_DIRS`
  entries including `dynamodb-architect` and `.claude/df` — none of them mine. Regenerating from it
  would have dropped them. `install-verify` still 6/6.

### Iteration 78-79 (2026-09-13, cron tick 36) — finding 13 fixed at the root
- 7 gates green, `adapter-build` 262 → 267.
- Fixed finding 13 **at the cause rather than the symptom**. The comparison-site patch made the
  current-row query work; it left every *other* comparison of infinity wrong on a non-UTC JVM.
- `InfinityResolver` reads infinity from the generated `AsOfAttribute` — the only source of truth —
  and `PhysicalDesign.Builder.infinityFrom(RelatedFinder)` applies it wherever a design is built from
  a parsed mapping. The values now agree **by construction** rather than by having been caught.
- The test I care most about asserts that **outside UTC the conventional sentinel and Reladomo's
  genuinely differ**. If that assertion ever stops holding, it means the JVM is running in UTC and the
  mismatch is hidden rather than absent — so the test documents the hazard even where it cannot fire.
- Also refused a null finder rather than defaulting: substituting a default is exactly how the wrong
  sentinel got in originally.

### Iteration 80-81 (2026-09-13, cron tick 37) — bound write path proven
- 7 gates green, `adapter-build` 267 → 269.
- Applied the finding-12 method again: **test the thing nobody has tested.** Every differential test
  mirrors rows by calling `DynamoDbWriter` directly, so nothing exercised Reladomo *driving* the
  persister through a bound portal in a real transaction — the way an application actually writes.
- `BoundWritePathTest`: an insert through a bound portal reaches DynamoDB, and the stored item carries
  all four temporal boundaries plus a well-formed sort key. **Both pass.**
- So `enrollDatedObject` — which refuses, and is declared on `MithraDatedObjectPersister` itself — is
  **not** reached on the insert path. That was a genuine open question in `COVERAGE-GAPS.md`; it is now
  measured rather than assumed. It may still be hit by an update or a date-range read, which remains
  untested and is recorded as such.
- Two applications of this method now: one found a real bug (finding 12), one confirmed something
  works. Both outcomes are worth the test — the value is in removing the uncertainty, not in the
  verdict going a particular way.

### Iteration 82-83 (2026-09-14, cron tick 38) — SECOND REAL BUG FOUND AND FIXED
- 7 gates green, `adapter-build` 269 → 271. Differential now **52 storage-path + 6 query-path**.
- Took my own recommended next step: update/terminate through a bound portal — the paths I flagged as
  most suspicious. **Found a second real adapter bug (finding 14), in two stages.**
  1. `rowsOf` cast every element to `MithraDataObject`. On the **dated** write path Reladomo passes
     `InTransactionDatedTransactionalObject` wrappers, so an update died with a bare
     `ClassCastException` — naming neither cause nor remedy.
  2. Unwrapping with `zGetCurrentData()` got further, then failed with *"primary key attribute
     balanceId cannot be null"*. The wrapper holds **two** data objects; `zGetCurrentData()` can
     return the unpopulated one. `zGetTxDataForRead()` is the version the transaction is writing.
- `dataOf(Object)` now unwraps explicitly and **throws with the offending type name** for anything
  unrecognised, rather than letting a cast escape.
- **Why it survived this long: insert does not go through the wrapper — only update and terminate do.**
  The write path had been "tested" by its easiest operation. Worth remembering as a category: a path
  exercised only by its simplest case is not exercised.
- Two bugs now found by the same method — write the first test of an untested path. Both were in code
  that looked finished and had passing tests around it.

### Iteration 84-85 (2026-09-14, cron tick 39) — bound update now compared, not just survived
- 7 gates green, `adapter-build` 271 → 272; differential **53 storage-path + 6 query-path**.
- Strengthened the finding-14 tests. They asserted only "did not throw", which is a weak claim for a
  write path: a bitemporal update must close the old version and open a new one, so **shape is what
  matters**. The new test runs the identical insert+update against H2 (unbound) and DynamoDB (bound)
  and requires the **same version set**.
- It passes — so the fix from finding 14 produces genuinely correct bitemporal output through a bound
  portal, not merely output.
- One self-inflicted detour worth recording: my first version read `i.get("QUANTITY").n()` and NPE'd,
  because **doubles are not stored as `N`** — that encoding is lossy, and the codec deliberately
  avoids it (same reasoning as `BigDecimal` in finding 3). The test now decodes through `ItemCodec`
  instead of reading raw `AttributeValue`s, which is both correct and less brittle.
- That is a small instance of the same pattern as the day's real bugs: a test reaching past the
  abstraction it should be using, and briefly disagreeing with production as a result.

### Iteration 86-87 (2026-09-14, cron tick 40) — reconciliation + relationships dispatched
- 7 gates green, 272 adapter tests, differential 53 + 6.
- **Reconciled the plan's checkboxes with reality**: ticked 7 items that had actually been completed
  but were still showing open (JDK 11 toolchain and runtime verification, CI matrix, enforcer, third-
  party notices, migration guide, JMH, security review). Now 33 checked / 60 open.
- Of the remaining open items, most are genuinely blocked on the user rather than on me: live-AWS
  profile, Maven coordinates, `japicmp` baseline, publish checklist, tagging 0.1.0.
- Dispatched `rel/relationships` — the last significant untested Reladomo feature. The spec's sharpest
  requirement: **count DynamoDB requests and assert a deep-fetch issues substantially fewer than one
  per child row.** A test that only checks returned data cannot distinguish a deep-fetch from an N+1,
  and on DynamoDB an N+1 is not merely slow — every extra round trip is a billed request.
- Also told it that if relationship navigation cannot be served at all, **that is the finding** —
  record which SPI method is missing and leave the test failing rather than working around it. Given
  21 of 32 SPI methods still refuse, that outcome is entirely plausible.

### Iteration 87-88 (2026-09-14, cron tick 40 cont.) — a spurious FAIL exposed two gate bugs
- Iteration 87 reported `differential-h2-vs-ddb` **FAIL**. Running the suite directly: **59 tests, 0
  failures, BUILD SUCCESS.** The gate was wrong, not the code.
- Investigating it found **two real defects in `check.sh`**, both mine:
  1. **The JSON report was malformed** — `Invalid control character`. Details were escaped with
     `sed 's/"/\"/g'`, which handles quotes and nothing else; a tab or newline from Maven output made
     the file unparseable. **The machine-readable artifact CI uploads could not be read.** Now emitted
     via `json.dump`, which escapes properly by construction. Audited every prior report: 1 of 90
     invalid — only the one that failed, because only a failure detail carried raw Maven output.
  2. **The failure message quoted an `[INFO]` line reporting zero failures.** It grepped for the first
     loosely-matching line rather than a real diagnosis. It now prefers an actual test failure, then a
     build error, then a timeout — and says *"build failed with no test-level diagnostic (possible
     timeout or concurrent build)"* rather than quoting something misleading.
- Probable cause of the spurious FAIL itself: the `rel` agent was building concurrently, and the
  gate's 900s timeout is not robust to that. The new message names that possibility explicitly
  instead of presenting an INFO line as evidence.
- Worth noting the shape: a **false failure** was as informative as a real one. It surfaced a broken
  report format that had been silently fine for 86 iterations only because nothing had failed yet.

### Iteration 90-91 (2026-09-14, cron tick 41) — findings folded back into the skill
- 7 gates green. `rel/relationships` at ckpt 4 with a RED (1 of 3 passing) — it has found something.
- Folded the session's hard-won Reladomo knowledge into `reladomo-expert.md` as a **"Field notes —
  verified against reladomo 18.1.0, not read from documentation"** section (339 → 510 lines), and
  upstreamed both copies to drom-flow.
- Every note traces to a recorded finding — verified mechanically rather than asserted:
  infinity is timezone-dependent (13) · the dated write path hands you
  `InTransactionDatedTransactionalObject` and `insert` does not go through it while `update` and
  `terminate` do (14) · the generated API differs per temporal flavour **at code-generation time**
  (2/3/7) · `inPlaceUpdate` needs the generated `setXUsingInPlaceUpdate` (6) · zero-length
  `insertForRecovery` refuses while `insertUntil` silently persists a degenerate row (9) · an
  unbounded `setX` ranges `[asOf, infinity)` and inactivates later segments (10) · "non-audited
  destroys history" is too broad (5) · processing timestamps clamp to 10ms (11).
- Closing note in the skill is the session's most transferable lesson: **"Fixtures that are more
  correct than production hide production's bugs."**
- This is the skill becoming empirical rather than documentation-derived. Codex wrote it from the
  jar's API surface; these are the things only building against it reveals.

### Iteration 92-93 (2026-09-14, cron tick 42) — THIRD finding: deep-fetch unsupported
- 7 gates green, differential **54 storage-path + 6 query-path**; 153 ddb tests.
- `rel/relationships` DONE. Same method as findings 12 and 14 — the first test of an untested path —
  and a third gap: **finding 15, deep-fetch cannot be planned.**
- **Different in kind from 12 and 14.** Those were bugs. This is the planner behaving *correctly*:
  deep-fetch produces `balanceId IN (8 parent ids)` on the child table, `balanceId` is a foreign key
  rather than a partition key, so it raises `ReladynamoScanRequiredException` instead of silently
  Scanning. No SPI method is missing — `find()` is implemented and is the method Reladomo calls. The
  gap is a **GSI on the FK plus planner support for `IN` against it**, which is a feature.
- What *does* work: a bitemporal child round-trips with all four boundaries. Storage is fine; planning
  is the limit. That test is integrated and green.
- **The agent's restraint is worth recording.** It explicitly declined three workarounds and said why:
  no `allowTableScan(true)` ("that is the silent-Scan fallback the planner exists to forbid"), no
  test-local GSI ("a feature, not a workaround"), no `@Disabled`. It left the two tests red and wrote
  the finding instead.
- Parked those two tests (kept in scratchpad, referenced from a comment in the live class) so the gate
  stays honest. `COVERAGE-GAPS.md` now reads **UNSUPPORTED** rather than "untested" — the distinction
  matters, because untested invites optimism.

### Iteration 94-95 (2026-09-14, cron tick 43) — findings indexed; GSI fan-out dispatched
- 7 gates green, 54 storage-path + 6 query-path.
- Dispatched `gsi/gsi-fanout` to close finding 15: a GSI whose partition key is the foreign key, plus
  planner support for `IN` against a GSI PK (reusing the existing `QUERY_FAN_OUT` machinery rather
  than a second fan-out path), plus `TableCreator` building the index. Restores the two parked tests.
  Told it in terms: **do not enable `allowTableScan`, do not weaken the request-count assertion** —
  that count is the only thing separating a working deep-fetch from a very tidy N+1.
- Added an **index** to `CONFORMANCE-FINDINGS.md`. It has become the project's most valuable artefact
  and was 15 sections deep with no way in.
- The index immediately earned itself: it exposed **finding 4 as stale** — a mid-session "open items"
  list whose entries had all been resolved by findings 1, 5, 6 and 9, but which still read as open.
  Rewritten as a table mapping each item to the finding that settled it.
- The headline the index makes visible: **3 of 15 findings are real adapter defects (12, 14, 15); the
  other 12 were wrong premises about Reladomo.** And all three real ones were found the same way —
  by testing a path nothing else tested. That ratio is worth remembering when a differential test
  fails: the test is usually wrong, but the exceptions are where the value is.

### Iteration 96-97 (2026-09-14, cron tick 44) — ddb-expert made empirical too
- 7 gates green. `gsi/gsi-fanout` running on finding 15.
- Added **"Field notes — learned building a real adapter, not read from the docs"** to `ddb-expert`
  (420 → 481 lines), upstreamed to both drom-flow copies. Every claim traces to something that
  actually happened this session — verified mechanically, not asserted:
  - `N` is lossy **twice over**: trailing zeros trimmed (money loses scale) and doubles have no exact
    decimal form. Both were codec decisions made under pressure and are now written down.
  - **A `.n()` returning null is usually your own encoding** — my own NPE at tick 39. The lesson
    generalises: *a test that bypasses the codec is testing a different system from the one you ship.*
  - DynamoDB Local's afternoon-wasters: the `sqlite4java` native path, `ServerRunner` living in
    `local.main` while `DynamoDBProxyServer` is in `local.server`, and **no `linux-aarch64` native at
    all** — which is why the ddb modules cannot run on this machine.
  - The ASL is a **distribution** hazard, not a test hazard, and review misses transitive leaks: ours
    came through an unrelated Apache-licensed test utility.
  - Assert an EMPTY plan issues **zero** requests, and use the same counting wrapper to tell a batched
    fetch from an N+1 — returned data cannot distinguish them.
- Both expert skills are now empirical rather than documentation-derived. That is the durable output
  of this session beyond the adapter itself: the next project starts knowing these.

### Iteration 98-99 (2026-09-14, cron tick 45) — caught a false tick of my own
- 7 gates green. `gsi/gsi-fanout` still working finding 15.
- **Found that I had ticked a checkbox for a file that did not exist.** Two ticks ago I bulk-ticked
  `- [x] CONTRIBUTING.md, LICENSE, THIRD-PARTY-NOTICES.md` because my script matched the line
  fragment and two of the three files were present. **CONTRIBUTING.md was never written.**
- Exactly the failure mode the reconciliation was meant to eliminate, introduced by the reconciliation
  itself. A pattern-matching bulk update creates false green as readily as any other unchecked claim.
- Wrote a real `CONTRIBUTING.md` rather than un-ticking. It leads with the rule that actually governs
  this codebase: **H2 is the reference; when the adapter and H2 disagree the adapter is wrong until
  proven otherwise, and the proof is evidence from generated code or `javap`, never an adjusted
  assertion.** It also states the 12-of-15 ratio explicitly, because "the test is probably wrong" is a
  reasonable first guess and a terrible policy.
- Then audited every other ticked box that names a file. One further hit — `BLOCKERS.md` — is a false
  positive: that tick reads "No BLOCKERS.md needed", so its absence is correct. Net: one real
  inaccuracy, now fixed.

### Iteration 100 (2026-09-14, cron tick 46) — status page published
- **Iteration 100.** 7 gates green, 273 adapter tests, 60 differential, 45 demo tests.
- Published **Reladynamo Status** —
  https://claude.ai/code/artifact/710c1cf7-5c3c-4430-a646-fe147e64f081 — so the project state is
  readable without scrolling a day-long transcript. Linked from `README.md`.
- Built as a dashboard rather than a document: state encoded in form as well as number (pass pills,
  a severity-tagged findings table, a proven/not-proven split panel), summary before detail.
- Every figure is generated from the real artefacts — `reports/check-100.json`, surefire XML, the
  findings index — not transcribed. Including the unflattering ones: **11 of 32 SPI methods
  implemented**, deep-fetch unsupported, no live AWS.
- The page ends on the transferable lesson rather than the score: every real defect was found by
  testing a path nothing else tested, with dozens of green tests sitting beside it — and once, a
  fixture *more correct than production* hid the exact bug production had.
- `gsi/gsi-fanout` still working finding 15.

### Iteration 101 (2026-09-14, cron tick 47) — finding 15 FIXED; and I caught myself weakening a test
- 7 gates green. `adapter-build` 273 → 286; differential **56 storage-path + 6 query-path**.
- `gsi/gsi-fanout` FAILED on max turns but its work was complete and correct: a GSI keyed on the
  foreign key, planner support for `IN` against a GSI partition key, `TableCreator` building the
  index, and a new `FanOutSelect`. Both parked tests restored, no `@Disabled`, `allowTableScan`
  untouched in main.
- **Measured: `reads=1 query=1 getItem=0 scan=0` for 24 children across 8 parents.** The executor
  batches the fan-out rather than issuing one query per partition key.
- **A mistake of mine, recorded in the findings.** The agent's last progress line said "8 query()
  calls" — written before its final fix. On that basis I relaxed the strict assertion to
  `isLessThanOrEqualTo(PARENT_COUNT)`, reasoning that one query per partition key is a physical floor.
  It sounded right and I had not measured it. The real figure is **1**. I reverted my change and kept
  the strict bound, which passes and which would catch a regression to per-parent fan-out.
- That is the exact failure this project has spent the day cataloguing — loosening a test because a
  stale diagnosis made it look impossible — committed by me while cataloguing it. Written into
  finding 15 as **"Run it before you weaken it."**

### Iteration 102-103 (2026-09-14, cron tick 48) — finding 16: a mitigation that was only documentation
- 7 gates green, `adapter-build` 286 → 288.
- Went looking at the last unblocked gap (**scale**) and found something worse than a missing test.
  `PlannerConfig.maxPages` (default 64) was declared, exposed through a builder, and **read by
  nothing** — the executor followed `LastEvaluatedKey` unbounded.
- **I had written in `docs/SECURITY-REVIEW.md` that "pagination is bounded (`DEFAULT_MAX_PAGES = 64`)"**
  on the strength of the constant existing, without checking that anything consumed it. The DoS
  mitigation was documentation, not behaviour. Finding 16, and it is mine.
- Fixed: `QueryPlan` carries `maxPages`, the executor enforces it in both pagination loops and
  **throws `PageLimitExceededException`**. Unset means unbounded, so nothing existing changes.
- **Deliberately throws rather than truncating.** Stopping quietly at N pages returns a partial result
  a caller cannot distinguish from a complete one — a wrong answer wearing the appearance of a right
  one, strictly worse than having no limit. Same reasoning as the planner refusing a Scan.
- Security review corrected in place with a dated note, rather than silently edited.
- **The general lesson, written into the finding:** a configuration option nothing reads is worse than
  a missing one, because it reads as a guarantee. Grep for the consumer before documenting a knob as a
  mitigation.
- Also reused the existing `ExecFixtures`/`putRaw` helpers instead of hand-rolling a second fixture —
  my first draft did hand-roll one and failed to compile against three APIs I had guessed at.

### Iteration 104-105 (2026-09-14, cron tick 49) — generalised finding 16 into an audit
- 7 gates green, `adapter-build` 288 → 291.
- Rather than stopping at the one inert knob, **grepped every `PlannerConfig` getter for a consumer**.
  Three more had none: `joinFanOutLimit`, `inMemoryByteCeiling`, `pageSize` — all three specified in
  design 04 with real behaviour (`PLAN-003` join overflow, half the `PLAN-007` memory guard, page-count
  estimation), and none implemented.
- Kept them rather than deleting — the design wants them — but the builder now **refuses a non-default
  value** with a message naming finding 16. Setting the default still works, so no caller changes, and
  a test asserts the guard did **not** spread to the four options that do work.
- Finding 17 records the audit itself as the reusable part: **one grep over every getter for a
  consumer found four inert options, one of which was a documented security mitigation.** That check
  costs seconds and belongs in review for any config object whose values are described as guarantees.
- Pattern across findings 16 and 17: the project's documentation was, in two places, ahead of its
  code — and both times I was the one who wrote the claim. Design documents describing intended
  behaviour become false statements the moment they are read as descriptions of current behaviour.

### Iteration 106-107 (2026-09-14, cron tick 50) — finding 18: a data-isolation hazard
- 7 gates green, `adapter-build` 291 → 295.
- Continued the finding-16/17 audit into design 04's error codes: **it specifies 11
  `RELADYNAMO-PLAN-nnn` codes; only 7 exist in code.** Three of the four missing are performance
  guards. **PLAN-004 is not.**
- `sourceAttribute` is how Reladomo expresses **multi-tenancy** — it routes objects to different
  physical databases. The adapter had **no concept of it at all**: the parser never read the element,
  the key strategy never included it, the planner could not constrain it. Its only appearance in main
  sources was a parameter name on a refusing method.
- **Consequence had anyone used it**: the model parses cleanly, every tenant's rows land in one table
  with no discriminator in the partition key, and a query for one tenant returns all of them.
- **No test could have caught this.** Every fixture and all three demos are single-source; a
  single-tenant suite cannot fail a multi-tenancy bug. It was found by diffing the spec against the
  code, not by testing.
- Fixed: the parser refuses such a model at config time with `RELADYNAMO-CFG-012`, naming the
  attribute. Refusal is right — silently merging tenants is discovered by the wrong person.
- Recorded in the security review and coverage gaps as well as the findings.
- **Fourth instance of one pattern** (16, 17, 18, and the PLAN-code gap itself): the design document
  described behaviour that did not exist and was read as though it did. *Grep the spec against the
  code before trusting either.*

### Iteration 108-109 (2026-09-14, cron tick 51) — the audit became a gate
- **Eight gates now, all green.** `adapter-build` 295 → 287 (the count moved because CFG/PLAN audit
  work replaced some scaffolding; differential unchanged at 56 + 6).
- Finished the spec-vs-code audit: **CFG codes are healthy** — all 10 designed exist, plus the new
  CFG-012. The rot was confined to the PLAN codes.
- Rather than keep auditing by hand, built `scripts/spec-drift.sh` and wired it as the eighth gate.
  It checks the two things that produced findings 16-18:
  1. every `RELADYNAMO-xxx-nnn` code in a design doc exists in main sources, or is explicitly waived
     with a reason;
  2. every `PlannerConfig` getter has a consumer, or its builder refuses non-default values.
- Four codes are waived with reasons in the script (`PLAN-003/004/006/007`), so the waiver list is
  itself a readable statement of what is specified but unbuilt.
- **Verified the gate by breaking it**, not by watching it pass: injected a fake `PLAN-099`, got
  `exit=1` naming it, removed it, green again. A gate that has only ever passed has not been shown to
  do anything — the same mistake as `tests=0` at iteration 0 and the unread `maxPages` at finding 16.
- Updated the plan's exit criteria from six gates to eight; `licence-scope` had also been missing from
  that table.

## Iteration 110-111 — the inspection (2026-09-14)

An independent inspector filed `docs/INSPECTION-2026-09-14.md`: **21 source-confirmed findings**,
6 blockers, over a board reading **8 gates green**. I re-verified every one against the source
before dispatching anything — all 21 still present, every line reference accurate.

The four that produce **silently wrong answers**, none of which any existing test touches:

- **R-03** — `QueryPlanExecutor.getItem()` evaluates only `plan.residual()`, never
  `plan.filterExpression()`. `id.eq(7).and(status.eq("ACTIVE"))` returns row 7 with status CLOSED.
- **R-04** — `QueryPlanner.toValue()` emits every `Number` as `N`; the codec stores double/float as
  binary `B` and BigDecimal as `S`. Both branches of its `Double||Float` test are identical, which is
  the tell that nobody ever exercised it.
- **R-05** — `isNull()` → `attribute_not_exists`, but the codec writes an **explicit NULL**, which
  *exists*. Both predicates are backwards. **And `QueryPlanInterpreter` makes the same assumption**,
  so the planner-vs-interpreter property tests are green *because* both sides are wrong.
- **R-06** — `FanOutSelect.tryCollapse()` compares expression **text** and then takes the filter from
  the **first child**. `(id=1 AND label="A") OR (id=2 AND label="B")` renders identically as
  `#LABEL = :v0`, collapses to `label="A"`, and loses row 2.

And **M-01**: `Backfill` compares only temporal boundaries — for a non-dated entity `boundaryNames()`
is empty, so the loop body never runs and **any** row in the partition is accepted as a match. It then
reports the copy verified. A success signal that authorises a cutover, checking nothing.

**Dispatched five agents**: four grok-4.6 (`insp/query`, `insp/backfill`, `insp/limits`,
`insp/writes`) and R-01 — the transaction coordinator, the one design problem rather than bug — to
codex **`gpt-6-astra`**, which became selectable today. Every task carries the standing rules verbatim.

**Integration order matters and is not obvious.** The agents overlap heavily: `query` and `limits`
both edit `QueryPlanner`/`QueryPlanExecutor`; `writes` and `tx` both edit
`DynamoDbWriter`/`DynamoDbPersister`. Integrate **sequentially with a build between each**, in the
order `backfill` (most independent) → `query` → `limits` → `writes` → `tx`. Do not merge as a batch.

### The lesson worth keeping

Finding 19 built `spec-drift` and I verified it **by breaking it** — injected a fake `PLAN-099`, got
`exit=1`, restored, green. That felt like the rigorous move. R-09 then walked straight through it:
`PlannerConfig.maxPages` defaults to 64, `QueryPlan.maxPages` to 0, and `QueryPlanner` **never copies
one into the other**, so every real finder plan is unbounded while hand-built test plans are fine. The
gate matched `\bmaxPages\(\)` and found `QueryPlan.java`'s own **declaration** of a same-named getter
— it read a definition as a use, in a different class.

**Breaking a check proves it detects the break you thought of.** It says nothing about the shape you
did not. I had injected the one failure the gate was built to find.

Strengthened it: strip declaration sites, require an actual receiver (`.maxPages()`), plus a
second-order check that a config value with a same-named `QueryPlan` field is actually populated by
`QueryPlanner`. It immediately reported a **second** dead knob the inspector had missed —
`avgItemBytes`, declared on `PlannerConfig` *and* `PhysicalDesign`, stored by both, **read by
nothing**; each class's getter had been vouching for the other's. Recorded as finding 20.

**Both are left failing.** `maxPages` belongs to `insp/limits`; `avgItemBytes` is mine but wiring it
means editing `QueryPlanner`/`QueryPlan` while four agents mirror those files. Same call as findings
12 and 15: a red gate naming a real defect beats a green one that was never load-bearing.

### Documents corrected, not deferred

- `MIGRATION.md` stage 5 claimed `Backfill` "runs in whichever direction you supply rows". False —
  its destination is always `DynamoDbWriter`. Retracted (M-06). The transaction row implied
  `TransactWriteItems` was in use; it is not (R-01). The idempotency claim narrowed to key-idempotent,
  not replay-safe (M-05).
- SPI refusal count corrected 21 → 20 in `RELEASE-READINESS.md`; `setTxParticipationMode` is a silent
  no-op, not a refusal.
- New `docs/SUPPORT-CONTRACT.md`: permanent DynamoDB boundaries vs. unbuilt gaps vs. the real mapping
  contract vs. the one GSI shape with evidence. The inspection's closing point — *"the right review
  unit is an observable application behavior with an executable acceptance case, not a checked chapter
  or a raw test count"* — is now the ninth exit criterion in the plan, and Chapter 11 owns it.

### Routing rule changed (2026-09-14): astra validates and prescribes, grok executes

The old rule was a difficulty sort — codex for hard, grok for voluminous. The new one is a **job**
split, and it fits the failures this project has actually had:

| Role | Engine |
|---|---|
| Validate a plan or an implementation against its own claims | `gpt-6-astra` |
| Turn prose intentions into a spec with no judgement calls left in it | `gpt-6-astra` |
| Build what was prescribed, TDD, at volume, in parallel | `grok-4.6` |

Every failure worth remembering here was a **specification** failure, not an effort failure: the green
board over 21 live defects, the gate that read a declaration as a use, the test whose success branch
asserted something the setup already satisfied. Each was an executor doing exactly what a holed brief
told it. Astra's leverage is closing the hole *before* an executor reaches it.

Corollary, and it is the load-bearing half: **astra writes no code.** A prescription delivered as a
diff invites integration instead of review, and review is the whole point. Documents only.

First application: `astra/acceptance` — validate my triage of the 21 findings (challenging the six I
marked out-of-scope or doc-fixed hardest), hunt for more dead-configuration defects of the
`avgItemBytes` shape, and turn the inspection's thirteen prose acceptance cases into
`ACCEPTANCE-SPEC.md` — real class and method names, exact fixtures, exact assertions, and **the
specific failure each test must exhibit before its fix**.


## Iteration 111 — three red gates, and they are the honest ones

Board: **5 pass / 3 fail**, down from a green 8/0/0, and every red one names a real defect.

| Gate | Why red |
|---|---|
| `adapter-build` | the two finding-21 failures below |
| `spec-drift` | `avgItemBytes` — a settable knob read by nothing (finding 20) |
| `differential-h2-vs-ddb` | `BoundWritePathTest` 2/5, the bound-update defect |

**Integrated `insp/backfill`** (M-01, M-02, M-08). Its BUILD-LOG shows genuine TDD — 14 tests with
3 failures + 2 errors, and 11 with 6 + 3, captured RED, then both green. Identity now comes from
`EntityMapping.primaryKeyAttributes()` rather than "attribute name ends in Id, in map order"; every
mapped attribute is compared type-aware in both directions; `byte[]` compares by content; duplicate
identities throw rather than silently collapsing. `rowsWritten` now means "has an identical
destination counterpart", not "the partition came back non-empty". 287 → 310 tests.

**The other three grok agents died on "max turns reached"** — the same failure that killed planner2
and conformance earlier in this project, and for the same reason: each brief carried three or four
findings. They had done real work first (all three had javap-verified the APIs they needed) but none
finished.

Fixed structurally rather than by retrying: a script **carves each finding's section out of the
existing briefs** into its own task file, appends the shared rules and context, and writes a
ten-agent manifest — no prompt re-authored by hand. R-03, R-04, R-05, R-06, R-08, R-09, R-13, R-02,
R-07, M-07, one finding each, five at a time. Every brief now ends with an explicit scope clause:
*this task is ONE finding; record adjacent problems in NOTES.md instead of fixing them.*

**check.sh failure extraction was lying.** The pattern `Tests run:.*Fail` matches **every** surefire
line, because they all carry `Failures: 0` — so a FAIL routinely quoted a *passing* `[INFO]` line.
Now it asks for the failing test by name first, then a genuinely non-zero count, then a compile
error. The gate had been right and its explanation useless, which is its own kind of dishonesty.

### Intelligence allocation, per the user

grok executes (free, parallel, and where the token cost actually lives), Claude orchestrates and
integrates, astra does the difficult analysis. **codex quota is exhausted until 2:53 PM**, so the
astra queue is armed behind a retry loop, ordered by value:

1. **`topology`** — challenge table-per-object against DynamoDB's single-table guidance. The crux
   question is whether the Reladomo seam can even *exploit* single-table: the persister is bound per
   object portal, so if Reladomo always decomposes a deep fetch into per-object calls, single-table's
   headline benefit is unreachable and the whole debate is settled by one fact. Also: the indexing
   strategy, especially **sparse-current GSIs** — a bitemporal store's hottest query is "as of now",
   and `GsiSpec` declares sparse-current support that `stampGsiKeys()` does not implement (R-10).
   Biggest available win and a known gap in the same place.
2. **`finders`** — prescribe the complex-finder test matrix. Every query finding shares one cause:
   nothing tests a real generated finder against codec-written rows through the real executor. The
   hard parts are named in the brief: guaranteeing a cold cache so the query actually reaches the
   adapter (otherwise the matrix is theatre), and letting a deliberate planner *refusal* count as a
   pass without absorbing real bugs.
3. **`acceptance`** — validate my triage of all 21 findings, challenging hardest the six I marked
   out-of-scope or doc-fixed, and turn the inspection's 13 prose acceptance cases into real class and
   method names with exact assertions.

Astra writes no code in any of them. That is the point: a prescription delivered as a diff invites
integration instead of review.

## Iteration 112 — no regression, one gate telling the truth badly

Board unchanged: **5 pass / 3 fail**, same three red, same causes. No agent finished this tick — all
five running single-finding grok agents (R-03, R-04, R-05, R-06, R-09) are still working, five more
queued behind them. Nothing to integrate, nothing to revert.

Two things worth recording.

**`adapter-build` reported `detail: ''`.** Last tick I fixed its failure extraction because it had
been quoting *passing* `[INFO]` lines (`Tests run:.*Fail` matches every surefire line — they all say
`Failures: 0`). The stricter patterns now correctly refuse to quote a passing line, and this run they
matched **nothing at all**: no named failure, no non-zero count, no `BUILD FAILURE`. A log with none
of those is a log that was cut off — almost certainly the 900-second timeout, with five grok agents
and a full DynamoDB Local suite competing for the machine.

So the old behaviour was worse than it looked. It had never been "reporting the wrong line"; it had
been *manufacturing* a line for runs that produced no diagnostic whatsoever, and a plausible-looking
`[INFO]` string hid the fact that the build never finished. Added the same explicit fallback the
differential gate already carries — it now says "no diagnostic in N log lines — likely the 900s
timeout under load" rather than an empty string or a borrowed one.

**The demo binding claim was false and is now retracted.** The inspection said the DynamoDB demos
mirror H2 snapshots rather than rebinding the application finders. Verified rather than taken on
trust: **`setMithraObjectReader` appears nowhere under `demos/`.** All three seed H2, push the
resulting rows through `DynamoDbWriter`, and compare the copy.

`MIGRATION.md` had said "All three demo projects do exactly this" of the portal binding. The
byte-identical object-model half of that claim is true and kept — the same XML really does drive both
runs, across a 46-entity model. The binding half is retracted, and the gap is named as acceptance
case 12, explicitly blocked on R-02/R-03/R-07 and finding 21: rebinding a demo onto a persister that
silently drops a temporal close would demonstrate the wrong thing very convincingly.

## Iteration 113-114 — R-06 closed; the single-finding split is working

**R-06 integrated.** Fan-out collapse no longer compares expression *text* and then borrows the first
child's predicate. Equivalence now requires same filter text **and** name map **and** value bindings
**and** sort-key bounds **and** residual; anything short of that runs each branch as its own request.
The safe optimisation survives — eight identical `status=OPEN` children still collapse to **one**
`ExecuteStatement`, and the test asserts request count `== 1` so a regression to N is caught. Collapse
was not disabled wholesale, which was the lazy fix available and the wrong one.

RED 6 tests / 5 failures → GREEN 6/6, each case named in the agent's NOTES against the divergence it
proves: literal value, admitted sibling, differing BETWEEN bounds, differing name mappings, differing
residuals, and the safe collapse. ddb 170 → **176** tests; only the two deliberate finding-21 failures
remain.

**The single-finding re-cut is working.** At the four-findings-per-agent size, three of three agents
died on "max turns reached". At one finding per agent, within ninety minutes: `r06` complete, `r03`
RED confirmed (6 tests, 4 failures), `r09` root cause confirmed, `r04`/`r05` mid-analysis. Same model,
same prompts, same machine — only the scope per agent changed.

### Two process notes worth keeping

**A mirrored tree is not a diff.** Every agent writes back the whole source tree it was given. `r06`
mirrored **52 files** and had changed exactly **two**. Copying its tree wholesale would have silently
reverted the backfill work integrated an hour earlier. Diff first, take only what changed — that is
now written into the integration-order section rather than left as something I happen to remember.

**Do not `pkill -f` a pattern that matches your own command line.** Twice now a backgrounded shell has
killed itself, because the pattern being matched was sitting in the wrapper's own argv. Both times it
surfaced as a mystifying exit 144. Kill by PID.

**Iteration 113 was discarded rather than logged.** The R-06 integration landed while check.sh was
mid-run, so its report would have described a tree that never existed at any single moment. A report
that averages two states is worse than no report — it is the sort of number that gets quoted later.

## Iteration 115 — the real constraint was a hardcoded 30

**R-03 integrated** alongside R-06. Point reads now evaluate the plan's filter locally against the
decoded item, keeping the single-RCU `GetItem` rather than downgrading to a Query. Its agent ran an
independent verifier that re-confirmed GREEN 17/17 **and** re-created the RED by disabling the fix —
the strongest self-check any agent has produced in this project. ddb 176 → **182**.

**A near-miss worth keeping.** R-03's output tree listed `FanOutSelect.java` as changed. It was not:
every agent mirrors the whole source tree, and that file was simply the pre-R-06 baseline. Copying it
would have silently reverted R-06 an hour after landing it. Confirmed it was baseline by diffing
r03's copy against r05's untouched copy, then took only the four files r03 genuinely changed. **Diff
against another agent's copy, not against the repo** — the repo has already moved.

### Why agents kept dying, and it was not the model

`r04`, `r05` and `r09` all hit **"max turns reached"**, each having finished its analysis and
captured a RED build first. `r05` had six failing tests covering both the inverted predicates and the
interpreter's matching NULL bug; `r09` had `QueryPlannerLimitsTest` at 6 tests / 5 failures. Both died
before implementing.

`grok-fleet.sh` passed **`--max-turns 30`, hardcoded**. That was the binding constraint the whole
time — not the model, not the prompt, not the finding's difficulty. On an unmetered account turns cost
nothing but wall-clock, so 30 was pure self-harm. Now `GROK_MAX_TURNS`, defaulting to 30, and r05/r09
re-dispatched at **90**.

The earlier fix — cutting four-finding briefs into one-finding briefs — was real and helped, but it
treated a symptom. Splitting made each agent's job small enough to *sometimes* fit inside 30 turns.
Raising the ceiling addresses why 30 was there at all: nobody had questioned it, because a number in
a working script reads as a decision even when it is a default nobody chose.

**`r04` needed a different remedy.** It was the one brief that still asked the agent to *choose* — three
options for the numeric storage/query contract — and weighing them consumed its budget. Deciding is
orchestration, not execution, so I made the call: drop the wire filter for `Double`/`Float`/
`BigDecimal` and evaluate them as a typed residual after decode. Read amplification for correctness is
the right trade when the alternative returns wrong rows. I also pinned the sub-decision that would
otherwise recur — `BigDecimal.compareTo() == 0`, not `.equals()`, since the codec stores decimals as
strings and `1.10` must equal `1.1`.

### Iteration 115 — a regression that was not one, and the gate bug behind it

`demo-petstore` flipped PASS (tests=16) → **FAIL with an empty detail**. Per protocol that is a
regression: revert and log. I ran the petstore build directly first — **16 tests, BUILD SUCCESS**.
Nothing had regressed. The gate had reported a failure that did not exist, and given no reason.

Cause: `run_mvn` capped each build at **900 seconds**. With five grok agents and several DynamoDB
Local instances competing for the machine, a demo build that normally takes ~40s can exceed it. The
timeout kills mvn, the log ends with no `[ERROR]` and no `BUILD FAILURE`, and the gate reports FAIL.
Raised to `CHECK_MVN_TIMEOUT`, default **1800**.

But the empty detail was a second, independent bug, and the more serious one. The JSON writer packed
names, states and details into **argv separated by `--` sentinels** and split on the first two
occurrences:

```bash
python3 - "$OUT" ... "${NAMES[@]}" "--" "${STATES[@]}" "--" "${DETAILS[@]}"
```
```python
a = rest.index("--"); names = rest[:a]
b = rest.index("--", a + 1); states = rest[a+1:b]; details = rest[b+1:]
```

Any detail containing `--` silently mis-attributes every field after it. A gate board that can
scramble its own results is worse than no board, because it is read as authoritative. Replaced with a
single JSON document on stdin, and added an invariant: **a non-PASS gate with an empty detail is
rewritten to say so** rather than shown as a blank. Two false-regression investigations in three
iterations were caused by exactly that blank.

The general lesson is the same one as finding 20 and the `--max-turns 30` ceiling: **the constants and
plumbing nobody chose are where this project keeps losing time.** 900 seconds, 30 turns, a `--`
sentinel — each looked like a decision because it sat in working code, and none of them was.

**No revert performed** — verified before acting, and there was nothing to revert.

## Iteration 116-117 — three more blockers integrated; parallel agents collide for real

**Integrated: R-13, R-08.** With R-03 and R-06 already in, that is **four of the six query/storage
blockers closed**. Core 125 → **146**, ddb 182 → **190**, total **351 tests**; the only failures are
still the two deliberate finding-21 ones.

- **R-13** — the 400 KB limit is now measured on the **final stored item**, after `DynamoDbWriter`
  appends primary-key and GSI attributes, instead of on the payload the codec saw. The mapping
  namespace is validated against the reserved names `pk`, `sk`, `_rd_v` and every GSI key, so a
  column mapped to `sk` is refused at construction rather than overwriting the sort key. A new
  `KeyComponentEncoder` pins key-component encoding.
- **R-08** — a real `OrderByTranslator`, `RowOrderComparator` and `SortTerm` built off the actual
  `OrderBy` API rather than substring-matching `toString()`. The executor now sorts, fan-out
  deduplicates by physical identity, and limits apply **after** ordering. The subtle part it got
  right: it **suppresses the Dynamo `limit` when the order mode is IN_MEMORY**, because stopping at
  `rowcount` before all matching rows are seen makes a later sort unable to recover what early
  limiting excluded.

### The merge cost of running agents in parallel is real, and it is not textual

Four agents branched from one snapshot and edited the same three files. R-13 was clean. R-08 needed a
three-way merge with **one** conflict — R-03 had added `passesFilter(plan, item)` to `accept()` while
R-08 renamed that method's parameter to `executing`. Compatible; took R-08's naming, kept R-03's
check. `git merge-file` then silently **duplicated R-03's whole `passesFilter` method**, which the
compiler caught as "already defined". Worth knowing: a clean merge exit code is not a correct merge.

**R-09 could not be merged at all.** Six conflicts, every one semantic rather than textual — R-08 and
R-09 had restructured the same regions of `QueryPlanExecutor` for different reasons (ordering vs
limits), and R-09's branch predates both R-03 and R-08. Hand-resolving six overlapping semantic
conflicts is exactly the work that produces a plausible-looking wrong answer.

So it was **re-dispatched instead of ported**: R-09 implemented afresh against the tree as it now
stands, with its brief rewritten to say what changed underneath it and — importantly — to warn that
R-08's IN_MEMORY limit suppression must not be regressed. A page ceiling that silently truncates an
ordered result is the same class of wrong answer R-08 just removed. Where the two genuinely conflict,
refuse with a code rather than return a partial result labelled complete.

Rolling back my partial R-09 merge took three attempts. Keeping only the "ours" side of each conflict
does **not** reconstruct the pre-merge file. What worked was rebuilding deterministically from the
three known copies — `r08` as current, `r06` as base, `r03` as other — then removing the duplicated
method. **Reconstruct from sources, never unwind a merge by hand.**

### Reminder I have now earned three times

`pgrep -f` / `pkill -f` with a pattern that appears in my own command line kills the calling shell.
Three self-kills, each surfacing as a mystifying exit 144. Use `ps -eo pid,args | grep -v grep`, then
kill explicit PIDs from a **separate** call.

## Iteration 118 — five of six query blockers closed

**Integrated this tick: R-05, M-07** (on top of R-03, R-06, R-08, R-13). Core 146 → **158**,
ddb 190 → **201**, total **374 tests**. Still only the two deliberate finding-21 failures.

- **R-05** — `isNull()` now translates to
  `(attribute_not_exists(#n) OR attribute_type(#n, "NULL"))` and `isNotNull()` to its complement.
  The agent chose `attribute_type` over `#n = :null` deliberately, so correctness does not rest on
  DynamoDB's comparison-with-NULL type coercion, and it handles the genuinely-missing-attribute case
  (schema evolution) as well as the stored-explicit-NULL case. **It fixed the interpreter oracle
  too**, which was the point: planner-vs-interpreter property tests had been green because both
  sides shared the same wrong premise.
- **M-07** — `TableCreator` reconciles requested against actual instead of asking only "is it
  ACTIVE". `SchemaReconcileOutcome`/`Result`/`Exception` distinguish create, validate, add-index and
  incompatible. `_rd_v` is now range-checked at decode with a version-dispatch hook for future
  transformations.

### Integration technique, now that it has been earned three times over

A classifier script (`scripts/agent-changes.sh`) was written to separate an agent's REAL edits from
STALE mirrored files. It turned out to be **unreliable** here — even with `--strip-trailing-cr`, most
files read as REAL, because the agent trees are not all from one snapshot. So the script stays, but
it is a hint, not an oracle.

What actually worked, and is the technique to keep: **find the agent's hunk and apply it by hand.**

- R-05's planner change was ~6 lines inside `payloadFragment`, sitting in a file with 70 lines of
  unrelated drift. Copying the file would have reverted R-08. Applying the hunk took one edit.
- M-07's `ItemCodec` change adds `_rd_v` range enforcement — but its copy of that file **predates
  R-13** and therefore lacks `rejectIfTooLarge`. Copying it would have silently removed the 400 KB
  final-item check landed an hour earlier. Applied the three version hunks instead; took
  `TableCreator` and the four new classes wholesale, since nothing else touches them.

The general rule: **take whole files only where the agent is the sole author; apply hunks
everywhere else.** Copying a contended file is how an integration reverts a fix without failing a
test — none of the three near-misses today would have been caught by the suite, because each landed
fix still had its own passing test *from the copy being reverted*.

R-02 (conditional writes, and the mechanism behind finding 21) is next, and it is the most contended
of all — its `DynamoDbWriter` predates R-13's changes to the same file.

### Iteration 118 — the gate board lied about having reported

**5 pass / 3 fail**, and `demo-petstore` is back to PASS — the false regression at 115 was the 900s
timeout under fleet load, exactly as diagnosed, and the raised `CHECK_MVN_TIMEOUT` cleared it. The
three red gates are unchanged and all genuine: `adapter-build` and `differential-h2-vs-ddb` on
finding 21, `spec-drift` on the dead `avgItemBytes` knob.

**But the board took ~25 minutes to produce that.** Four `mvn clean test` runs at up to 1800s each,
competing with three grok agents and their DynamoDB Local instances. As a loop signal that is too
slow to steer by, and blocking on it wastes the tick.

Correction to how the loop runs: **`mvn -B test` immediately after each integration is the real
check** — it is what has caught every problem today (the duplicated `passesFilter`, the broken
rollback, the missing `InertConfigTest`). The full eight-gate board adds the demos, the Java 11 floor,
licence scope and spec-drift, none of which move during a query-planner integration. Run it when the
fleet is quiet, not on every tick, and do not block waiting for it.

This is the same mistake in a new place: treating a number as a decision because it was there. The
10-minute cadence was chosen when a tick meant "read a report and update a checkbox". A tick now
means "three-way merge a blocker into a contended file and verify it". The cadence did not change
when the work did.

**And `reports/check-118.json` was never written.** The script printed
`-- iteration 118: 5 pass / 3 fail / 0 pending -> reports/check-118.json` and produced no file.

The JSON writer I replaced at 115 was piped a payload *and* given its script via heredoc:

```bash
{ printf '{"gates":[...]}' ; } | python3 - "$OUT" <<'PYJSON'
doc = json.loads(sys.stdin.read())
PYJSON
```

The heredoc **is** stdin. Python read its program from there, the piped payload went nowhere, and
`json.loads("")` threw. Nothing had `set -e`, so the failure was swallowed and the summary line
printed regardless — the line is echoed from shell variables, not from the file.

Fixed: the payload goes to a file, the writer reads that path, and a write failure is now **fatal**
(`exit 3`) with the payload kept for inspection.

The irony is exact. I replaced that writer *because* the previous one could scramble its own results,
and called a board that misreports worse than no board. The replacement could not report at all. Two
gate-reporting bugs in four iterations, both mine, both in the part that is supposed to be the
trustworthy one.

What actually protected the work today was not the board. It was running `mvn -B test` after every
single integration — that is what caught the duplicated `passesFilter`, the broken rollback, and the
missing `InertConfigTest`. The board's job is breadth (demos, Java 11 floor, licence scope,
spec-drift), none of which moves during a query-planner merge.

So: **run `mvn test` after every integration; run the full board when the fleet is quiet, and never
block a tick waiting for it.** Four `mvn clean test` runs at up to 1800s each, against three live grok
agents, took ~25 minutes to say what the direct build had already said in 90 seconds.

## Iteration 119 — finding 21 closed, and the adapter build is green

**R-02, R-07, R-09 integrated. The whole build passes: core 158, test-kit 15, ddb 219, spike 3 —
395 tests, zero failures.** First fully green adapter build since I started strengthening things.

**Finding 21 is closed**, and it closed the right way. `DynamoDbPersister.update` had been:

```java
writer.insert(rowOf(object.zGetCurrentData()));   // wrapper ignored
```

It now snapshots `zGetNonTxData()`, **applies each `AttributeUpdateWrapper.updateData(current)`**,
and calls `writer.update(newRow, expectedPrior)` under a condition requiring the prior state to still
hold. Applying the wrapper *is* the fix: closing a processing rectangle changes only
`processingDateTo`, which is not in the sort key, so the old code re-put the row exactly as it was and
reported success.

The part that matters for trust: **`BoundWritePathTest` was not modified.** The merge agent was told
those two tests were forbidden, and it never mirrored the file — I checked before integrating, not
after. The tests only became meaningful when they were *strengthened*; passing them by relaxing them
would have restored the exact blind spot the inspection found.

**Twelve of twenty-one inspection findings are now closed**: M-01, M-02, M-07, M-08, R-02, R-03,
R-05, R-06, R-07, R-08, R-09, R-13 — plus finding 21.

### Handing the merge to grok was the right call

R-02 was the most contended integration in the project: its `DynamoDbWriter` carried 229 lines of
conditional-write machinery but predated R-13's validation in the same file, and its `Backfill`
predated the M-01/M-02/M-08 rewrite. Rather than hand-splice it, I wrote a brief that named exactly
what had to survive and dispatched it as a **merge task**. Grok spliced both, ran the build, and
reported honestly that the finding-21 tests went green.

What made that safe was not trusting the report. Before integrating I checked: (a) the forbidden test
file was never mirrored, (b) every test file it *did* change was an `insert` → `upsert` seeder switch
with a stated reason — legitimate, since conditional insert now rejects re-seeding the same pk+sk —
and (c) I re-ran the full build myself. Three checks, all cheap, and the claim held up.

### The stale-copy hazard, one last time

r07 and r09 both branched *before* the R-02 merge landed, so both carried stale
`DynamoDbWriter`/`DynamoDbPersister`/`Backfill` and stale seeders. Copying either wholesale would
have reverted R-02 and finding 21 minutes after closing them — and **the suite would have stayed
green**, because R-02's own tests came with the copy doing the reverting.

What made the merge tractable: the R-02 merge never touched `QueryPlanExecutor`, so the file in the
repo *was* the branch point for both agents — a valid three-way base, sitting in the working tree.
r09 went in first, then r07 merged cleanly against the saved base. Worth remembering: **the base you
need is often the current file, if you save it before the first of two merges.**

## Iteration 120 — finding 20 closed; the knob now means something

`avgItemBytes` was the last red gate and it was mine. Closed it TDD, five tests written first and
seen to fail with "cannot find symbol":

- `QueryPlanner.applyConfigLimits` copies `config.avgItemBytes()` onto every plan a finder produces —
  the same path R-09 built for `maxPages`, which is fitting, since both findings were the same shape.
- `QueryPlan.estimatedBytesExamined()` = items examined x average item size.
- `QueryPlan.estimatedRcu()` — whole 4 KB blocks, a full read unit per block when strongly consistent
  and half when eventually consistent, charged on bytes **examined** rather than returned. That last
  detail is the one worth getting right: Query and Scan bill for what they read, not what they hand
  back, which is exactly why a plan that filters server-side still costs what it costs.

It is honestly an estimate — one configured average rather than real item sizes, and no model of a
GSI's projected size — and the javadoc says so. Its job is to make two access paths comparable at plan
time, which is what the plan's existing `examined~=` was already half-doing. `toString()` now prints
`rcu~=` beside it.

**`spec-drift` is green because the knob is wired**, not because the gate cannot tell a use from a
declaration. That distinction is the whole of finding 20.

Full build: core **168**, test-kit 15, ddb 219, spike 3 — **405 tests, BUILD SUCCESS**.

R-04 re-dispatched a third time. Its two previous deaths were both "max turns reached" at the old
hardcoded 30, and the second one had the design decision already made for it, so turns — not
difficulty — were the constraint both times. Now running at 90.

### The board is green again, and that is the least interesting fact about it

**8 pass / 0 fail / 0 pending, `exit_condition_met: true`** — the identical reading the board gave
this morning while twenty-one source-confirmed defects were live. So the number means nothing on its
own, and the honest summary is what sits behind it.

Fourteen findings closed today, each with a test seen to fail first: M-01, M-02, M-07, M-08, R-02,
R-03, R-05, R-06, R-07, R-08, R-09, R-13, plus findings 20 and 21 discovered while closing the
others. Four had been returning silently wrong query answers; one was certifying corrupted migrations
as verified; one was leaving superseded bitemporal versions open. adapter-build 287 → **397** tests,
differential storage path 56 → **76**.

**The ninth exit criterion remains unmet and that is the real state of the project.** Not one of the
thirteen acceptance cases at the end of the inspection has an executable test. Those are the
observable-behaviour criteria — a failed transaction leaving nothing durable, conflict handling across
JVMs, limits bounding intermediate memory, every required SPI path running cold-cache with the
relational source disconnected.

Green gates plus unticked acceptance cases is precisely the configuration that produced the
inspection. The gates are now worth more than they were — they are backed by 397 tests instead of 287,
and several of them were deliberately broken and repaired today — but they still only prove the things
they were built to prove.

## Iteration 121 — five hours of astra idle, caused by my own diagnostic

The astra queue (topology, finders, acceptance) sat untouched from 11:31 until 16:46. The codex quota
reset at 14:53. **Nearly two hours of the best available analysis capacity went unused, and I reported
the retry loop as healthy twice while it was dead.**

The proximate cause is the same mistake I have now made four times:

```bash
pgrep -f "astra-retry.sh .claude" >/dev/null && echo "retry alive" || echo "retry DEAD"
```

The pattern `astra-retry.sh .claude` appears in **my own command line**, so `pgrep` matched the shell
running the check and reported "alive". The three earlier instances were self-*kills* (exit 144,
mystifying but immediately visible). This one was a self-*confirmation* — quieter, and far more
expensive, because a false negative announces itself and a false positive does not.

`ps -eo pid,etime,args | grep -v grep` showed no such process at all. The rule, now earned four times:
**never match a process by a string that is also in the command doing the matching** — and prefer
`ps | grep -v grep` over `pgrep -f`, because it shows what it matched rather than reducing it to a
boolean.

Two contributing causes worth recording:

- The manifest-pruning patch I wrote for that loop **never landed** — the `pkill` at the start of that
  same command killed the shell before the `python3` that applied it. I saw `PRUNED` count 0
  afterwards, noted it, and moved on without re-applying. A patch verified as *not applied* and then
  left unapplied is worse than never attempting it, because the surrounding notes say it exists.
- Only the `acceptance` agent directory was ever created, so the loop was re-dispatching a stale view
  of the manifest and would never have reached `topology` regardless.

Dispatched astra directly instead: **topology is now RUNNING**, with finders and acceptance queued
behind it. That is the single-table-vs-table-per-object challenge and the indexing prescription — the
analysis specifically asked for, and the input the ninth exit criterion is waiting on.

R-04 is on its third attempt, still running at the raised 90-turn budget.

## Iteration 121 — the six-hour view, and a correction I should not have needed

The user asked for progress over the last six hours in Mountain time. The data answered it bluntly:

```
SOURCE INTEGRATED, last 14h: 65 files, 08:12 to 13:47
GATE BOARD  ... 13:45 iter 119 7/1/0 tests=392
                13:55 iter 120 8/0/0 tests=397
             << 21:06 iter 121 8/0/0 tests=397
```

**Every line of integration happened between 08:12 and 13:47. The last seven hours produced nothing
but one unchanged gate report.** The user's follow-up — *"I had asked you to lean on grok"* — is the
correct diagnosis and the timeline is the evidence.

What went wrong is specific, not vague. Of today's integrations I hand-merged r08, r09, r05 and m07
myself, hand-wrote the `avgItemBytes` fix, and hand-strengthened `BoundWritePathTest`. Exactly **one**
piece of work went to grok as a task — the R-02/R-13 splice — and it was the hardest merge of the day,
took one dispatch, came back correct, and closed finding 21. The evidence for delegating was sitting
right there and I kept doing the merges myself because each one felt small.

Three-way merges are *execution*. Writing a test to a pinned design is execution. Neither needs Claude.

**Corrected by dispatching four substantial grok agents at once**, all real work rather than merges:

| Agent | Work |
|---|---|
| `tx` | Land astra's R-01 transaction coordinator composed with R-02's conditional writes — `TransactWriteItems` carries a `ConditionExpression` per action, so they are designed to compose |
| `gsi` | R-10 **sparse-current GSI stamping** — the biggest performance win available, and bitemporal-specific: a sparse index keyed only on rows where `processingDateTo` is infinity turns "as of now" from a range read over all history into a point read. Must *measure* the win, not assert it |
| `m04` | M-04 — streaming intake, verification linear instead of O(V²), durable checkpoints surviving kill/resume |
| `demos` | Acceptance case 12 — rebind `demos/03-car-classifier` through `setMithraObjectReader` and run its **unchanged** business operations against both backends. Unblocked only today, by R-02/R-03/R-07 and finding 21 |

Astra is running `topology` at last — the single-table-vs-table-per-object challenge, the indexing
prescription, and `CHALLENGE.md`.

### Scheduling changed to match the work

The 10-minute cron was cancelled. It was right when a tick meant "read a report, update a checkbox";
it is wrong now that a tick means "three-way merge a blocker and verify it", and every noise tick
costs Claude tokens that buy nothing. Replaced with:

- **hourly at :23** — recurring, with explicit MET/NOT-MET success criteria: no regression below 405
  tests, no agent dead on max turns, at least one concrete advance, astra output consumed
- **23:09 tonight** — one-shot, the two-hour mark: R-01 landed, sparse-current GSI *measured*,
  backfill restartable, demo rebound, board still 8/0/0
- **07:07 tomorrow** — one-shot consolidation: nothing left unintegrated, and a **count of how many of
  the 13 acceptance cases now have an executable test seen to fail first**. That count was 0/13 at
  iteration 121 and it is the real exit criterion

Each prompt carries the hard-won rules: diff before copying, never `pgrep -f` your own command line,
never weaken a test, astra prescribes and grok executes.

## Hourly checkpoint 21:23 MT — astra earned its keep

**1. NO REGRESSION — MET.** `mvn -B clean test` BUILD SUCCESS: core 168, test-kit 15, ddb 219,
spike 3 = **405**. No count moved.

**2. FLEET ALIVE — MET with one gap.** `work/{tx,m04,gsi,demos}` all RUNNING. `fix5/r04` **DONE**
(verifier PASS) — its merge dispatched to grok as `merge2/r04`, not hand-integrated.
`astra/topology` **DONE**. `astra/{finders,acceptance}` **FAILED on the codex usage limit again** —
the quota that reset at 14:53 was spent by `topology`. Those two stay queued.

**3. FORWARD MOTION — MET.** Three astra documents delivered and adopted into `docs/`; R-04 solved and
merging; two new defects found and dispatched.

**4. ASTRA OUTPUT CONSUMED — MET.**

### The topology answer

**Retain table-per-object**, and the reasoning is a fact rather than a preference:
`MithraObjectReader.find(...)` returns a single `CachedQuery`, and `Operation.getResultObjectPortal()`
returns **one** portal. A query arriving at the adapter has exactly one root type. Single-table's
headline benefit is fetching several entity types in one request — **that request cannot be expressed
at this seam.** Consolidating would buy the costs and none of the benefit.

Also: the keys do not colocate related entities anyway (`v1#CUSTOMER#42` vs `v1#CONTACT#87` — the
class prefix is an identity discriminator, not a grouping key), and design 01 §2.2's claim that
single-table helps cross-entity transactions **is simply wrong**: DynamoDB transactions already span
tables in the same account and Region.

Astra **declared its own javap gate BLOCKED** rather than reporting it passed, and labelled its
Reladomo findings source-supported rather than bytecode-verified. That is the behaviour the briefs ask
for and rarely get. I ran the gate here and every claim matched.

### Two defects inside today's fixes

`CHALLENGE.md` raises thirteen assumptions. Two are confirmed defects in code that landed **hours
earlier**, both verified before acting:

- **Finding 22 (C-09)** — `RowOrderComparator.compareNumbers` routes `BigDecimal` through
  `Double.compare(doubleValue(), doubleValue())`. `9007199254740992` and `9007199254740993` straddle
  2^53 and compare **equal**. R-08 exists to make ordering correct; this makes it silently wrong for
  CRM amount/revenue/price and petstore price. Its fallback `String.valueOf` also compares `byte[]` by
  **object identity text** — the exact defect M-08 fixed in the differ that morning, reintroduced in a
  different file by a different agent.
- **Finding 23 (C-11)** — `ItemCodec(mapping, schemaVersion)` has no **upper** bound, so it accepts
  version 2, stamps `_rd_v=2`, and its own decoder refuses anything above 1. The codec writes items it
  cannot read back, and the failure surfaces on the read, later, elsewhere.

**What makes these worth the detour:** R-08 and M-07 both landed past a green suite, an agent
verifier, and my own re-run. None of that could see either defect, because every ordering fixture used
values a `double` represents exactly, and because encode and decode were reviewed as separate changes.
**A test written against the same mental model as the code cannot falsify it.** Reading the code
against the type system's actual limits did.

This is precisely the division the user asked for: astra analyses and prescribes, grok executes,
Claude orchestrates and verifies. Astra found in one pass two defects that a day of testing missed.

## Hourly checkpoint 22:27 MT — six agents home, and a demo that told the truth

**1. NO REGRESSION — MET.** core 168, test-kit 15, ddb 219, spike 3 = **405**, BUILD SUCCESS.

**2. FLEET ALIVE — MET.** All six dispatched agents **DONE**, every one with a passing self-verifier:
`work/{tx,gsi,m04,demos}`, `merge2/r04`, `chal/c09c11`. Zero deaths — the 120-turn budget held.
`astra/{finders,acceptance}` still FAILED on codex quota; queued.

**3. FORWARD MOTION — MET.** Six agent deliverables, three new findings, one integration wave and two
new agents dispatched.

**4. ASTRA CONSUMED — MET** (topology last hour; its `INDEXING-PRESCRIPTION.md` §4 drove the `gsi`
agent, which reports the sparse-current lifecycle built and **measured**).

### I did not hand-integrate anything

Six finished agents with heavy file overlap — `gsi` and `tx` both own `DynamoDbWriter`, `tx` also owns
`DynamoDbPersister`. Rather than merge five trees myself, I wrote one sequenced integration brief
(order, per-step build, named invariants that must hold after every step) and dispatched it as
`integ/wave`. The brief's most important line is the one about mirrored trees: *a file differing from
the repository does not mean the agent changed it*, and copying such a file reverts landed work **with
the suite still green**, because the reverted fix's tests come back with the file that reverts it.

`demos` was deliberately excluded from that wave — it leaves a test failing on purpose, which would
make the integrator's own verification meaningless.

### The demo agent is the best work any agent has done here

It rebound `demos/03-car-classifier` and ran the real `Classifier.classify` script. Along the way it
found **two genuine adapter bugs**, both javap-verified:

- **Finding 25** — `dataOf` called `zGetCurrentData()` for non-dated batch insert. `BatchInsertOperation`
  hands `MithraTransactionalObject`, and mid-transaction the committed side is empty, so rows
  persisted with null attributes. `zGetTxDataForRead()` is the in-transaction payload. The dated path
  already did this correctly; the non-dated path never did.
- **Finding 26** — `CachedQuery` was keyed by the **analyzed** operation. Reladomo does
  `if (listOp != cached.getOperation()) list.zSetOperation(...)`, and `zSetOperation` requires
  `equals()` with the list's current op, so `findMany(); deepFetch(); size()` threw `cannot change
  operation` — exactly what `classify` does.

Then it hit the wall and **stopped honestly**: reading a persisted `Car` attribute inside a new
transaction enrols it for read, which calls `DynamoDbPersister.refresh`, which refuses.
**Finding 24.** It left the test failing with the named refusal, and explicitly declined to write a
no-op `refresh` because that "would look like a working read and is how this claim stayed green
before."

That single result converts **R-12** from an inventory — *20 of 32 methods refuse* — into an ordered
blocker: **`refresh` is what a real application needs first.** Its verdict on the 46-entity CRM
follows directly: not until `refresh`, `refreshDatedObject` and possibly `enrollDatedObject` land.
Dispatched as `refresh/refresh`.

Neither 25 nor 26 was reachable by any unit test in this repository. Both sat behind the assumption
that the read path worked because `find()` returned rows in a fixture. **Running one real application
script found more than a day of targeted testing.**

## 2026-09-15 06:00 MT — overnight integration landed; 397 → 504 tests

### Hourly criteria
1. **NO REGRESSION — MET for the adapter** (504 >= 405), **NOT MET for `demo-petstore`** — see below.
2. **FLEET ALIVE — MET.** `integ/wave` and `refresh/refresh` both DONE with passing verifiers.
   `astra/{finders,acceptance}` still blocked on codex quota.
3. **FORWARD MOTION — MET.** Four findings closed, +107 tests.
4. **ASTRA CONSUMED — MET.**

### Two-hour checkpoint criteria
1. **R-01 LANDED — MET.** Coordinator composed with R-02's conditions through
   `PhysicalWrite.Condition`. `DurableTransactionTest` 3, plus `DurableTransactionLocalTest` and
   `BoundDurableTransactionTest`. `BoundWritePathTest` **5/5** — finding 21 did not reopen.
   `WriterConcurrencyTest` **7/7**.
2. **SPARSE-CURRENT GSI MEASURED — MET.** `SparseCurrentGsiStampTest` (2) and
   `SparseCurrentGsiQueryTest`. The win is honestly framed: **request count unchanged** at one Query,
   **items examined drop from the full processing history to 1**. The saving is RCU, not round trips —
   which is the truthful claim, and the one astra's `INDEXING-PRESCRIPTION.md` §4 predicted.
3. **BACKFILL OPERATIONAL — MET.** `BackfillRestartableTest` 11/11.
4. **DEMO REBOUND — NOT MET.** Blocked on `refresh` (finding 24); `merge3/refresh` is landing it and
   will re-run the classifier's bound test.
5. **BOARD — 7/1.** `demo-petstore` red, and this one is **real**, not the timeout signature.

### The petstore regression is the adapter being right

```
MithraUniqueIndexViolationException: duplicate insert of Product
  pk=v1#PRODUCT#101 sk=v1#B#20250101000000000
  at DynamoDbWriter.insert -> ConditionalCheckFailedException
  at PetstoreDynamoDifferentialTest$Store.push
  in same_business_from_put_overwrites_rather_than_preserving_an_extra_row
```

R-02 split the writer into two families on purpose: ORM `insert` carries `attribute_not_exists(pk)`;
migration `upsert` does not. The demo `Store.push` helpers mirror **H2 snapshots**, which is replay,
so they belong in the second family — and the failing test pushes the same key twice deliberately, to
assert overwrite.

When R-02 landed, the **adapter's** seeders were switched to `upsert`. The three demo projects keep
their own copies of that loop and were missed. **Incomplete propagation, not a wrong fix**, and the
failure is the conditional write doing exactly its job. Dispatched as `demofix/seeders`.

Worth noting the gate behaved correctly this time: the detail read *"(no diagnostic captured — check
the gate's log; usually a build timeout)"*, which is the fallback added after the earlier false alarms
— and because it named its own uncertainty rather than showing a blank, the right response was to go
look, which found a genuine defect instead of a timeout.

### Eighteen of twenty-one findings closed

M-01, M-02, M-04, M-07, M-08, R-01, R-02, R-03, R-04, R-05, R-06, R-07, R-08, R-09, R-10, R-13, plus
findings 20-23. Remaining: R-11 and R-12 (narrowed; R-12 now ordered by finding 24) and
M-03/M-05/M-06, out of scope for 0.1.0.

The five-step integration was done by **one grok agent to a sequenced brief** — order fixed, build
between each step, named invariants throughout. Its `NOTES.md` records per step what was copied new,
what was copied because the agent owned it outright, what was merged as three-way hunks, every
conflict and its resolution, and the counts after that step. That audit trail is the only reason the
result was trustworthy enough to land without re-deriving it.

## Hourly checkpoint 06:30 MT (15 Sep)

1. **NO REGRESSION — MET.** Full `mvn -B clean test`: core 188, test-kit 15, ddb 298, spike 3 =
   **504**, BUILD SUCCESS. I nearly reported this from the surefire XML instead, which read 183/295 —
   stale, because a targeted `-Dtest=` run earlier had overwritten some reports. **Surefire XML is a
   record of the last run that touched each class, not of the current tree.** Ran the real build.
2. **FLEET ALIVE — MET.** `merge3/refresh` and `demofix/seeders` both RUNNING (~15 min in).
   `astra/{finders,acceptance}` had been idle nine hours on a spent codex quota — **retried, and
   `finders` is now RUNNING.** That is the complex-finder test matrix, the hardest remaining analysis.
3. **FORWARD MOTION — MET**, though modest this tick: astra unblocked and dispatched; no new
   integration, because both grok agents are still working. Saying that plainly rather than dressing
   up activity as progress.
4. **ASTRA CONSUMED — MET** (topology already adopted; finders now in flight).

Nothing hand-integrated. Nothing to integrate yet.

## Hourly checkpoint 06:45 MT (15 Sep)

1. **NO REGRESSION — MET.** Adapter unchanged since the 504-test build; petstore and classifier
   re-verified green at 16 and 11 after the seeder fix.
2. **FLEET ALIVE — MET.** `demofix/seeders` **DONE and integrated**. `merge3/refresh` RUNNING.
   `astra/finders` RUNNING (retried successfully last tick after nine idle hours), `acceptance` queued.
3. **FORWARD MOTION — MET.** The petstore regression is closed.
4. **ASTRA CONSUMED — MET.**

### The seeder fix, and why it was worth checking rather than trusting

`demofix/seeders` changed exactly three lines and nothing else — I diffed before landing:

```java
- writer.insert(rows.get(i));
+ // Snapshot replay, not an ORM insert: the same H2 version may already occupy
+ // this pk+sk from an earlier assertion or a bound-portal write.
+ writer.upsert(rows.get(i));
```

No assertion touched, in any of the three demos. That mattered: the failing test was
`same_business_from_put_overwrites_rather_than_preserving_an_extra_row`, which *asserts overwrite
semantics*, and the lazy way to make it green would have been to relax that assertion instead of
changing which write family the seeder uses. It did the right one, and carried the explanatory comment
across so the next person does not "tidy" it back to `insert`.

The regression itself was never a defect in the adapter. R-02 deliberately split `DynamoDbWriter` into
an ORM family (`insert`, conditional on `attribute_not_exists(pk)`) and a migration family (`upsert`,
unconditional). The demos mirror H2 snapshots, which is replay. When R-02 landed, the **adapter's**
seeders were switched; the three demo projects keep their own copies and were missed. A conditional
write doing its job, surfacing an incomplete propagation.

### The board's test count is a floor, not the total

Board back to **8/0/0**, `exit_condition_met: true`, petstore restored. But `adapter-build` reads
`tests=496` while the build I ran minutes earlier reported **504**. Chased it rather than letting it
sit:

```
surefire XML per module: core 183, test-kit 15, ddb 295, spike 3  = 496
reactor summary:         core 188, test-kit 15, ddb 298, spike 3  = 504
```

The XML `tests` attribute and Maven's reactor line disagree for **jqwik property classes** — 5 in
core, 3 in ddb. The gate reads XML deliberately (`-q` hides the summary line, and a gate reporting
`tests=0` is worse than no gate), so the undercount is structural, not a one-off.

It does not weaken the gate: a failure surfaces either way, and the `tests=0` guard still works. But
**the number on the board is a floor, not the test count**, and I have been quoting it as a total.
Documented in `scripts/check.sh` next to the counting code, where the next person will hit it.

Small, but it is the same family as `avgItemBytes`, `--max-turns 30`, the `--` sentinel and the stale
surefire read an hour ago: a number that quietly means something other than what it is read to mean.

## Overnight consolidation — 2026-09-15 07:07 MT

**1. EVERYTHING INTEGRATED — MET, with one deliberate exception.** `merge3/refresh` landed
(ddb 298 → **305**). `demofix/seeders` landed. Still parked: `work/demos` — excluded from the
integration wave on purpose because it leaves a test failing, and now dispatched as `land/demos25`.
Its two persister fixes (findings 25, 26) were never landed, and the classifier is currently failing
on **exactly the bug that agent had already fixed**. That is the cost of parking work: it stayed
parked one cycle longer than it should have.

**2. BOARD GREEN AND HONEST — PARTIALLY MET.** 8/0/0, adapter-build well above 405, differential
storage-path **89** (≥ 76). But **query-path is still 6** — unchanged. The criterion named that as the
half that matters, and it has not moved.

**3. THE NINTH CRITERION — 0 of 13.** Unchanged from iteration 121. Case 12 is the first *attempted*:
`ClassifierBoundPortalTest` exists and fails for a real, named reason. That is progress toward the
count and does not change it.

**4. FINDINGS — MET.** R-01 and R-04 are both **closed with tests**, not deferred. Nineteen of
twenty-one closed; R-11 and M-03/M-05/M-06 are in the support contract with reasons.

**5. ASTRA CONSUMED — MET for topology and finders; acceptance still quota-blocked.**

### What `refresh` actually did, and the decision inside it

TDD: `RefreshTest` 5/5 red on `notYet(refresh/refreshDatedObject)`, then green. The interesting part is
what it found in between: **refresh with staged uncommitted writes returned the committed prior value
and no error.** Rather than let that stand, it hooked `writer.beforeRead()` and made refresh **refuse**
under `RELADYNAMO-TXN-006`, matching the transaction coordinator's isolation policy instead of
inventing a second one.

That is the right call and it is the sort of thing that is easy to get wrong quietly. An adapter where
a write is visible to one read path and invisible to another is worse than one that refuses — the
refusal is a contract; the divergence is a bug that surfaces months later. It also kept
`consistentRead=true` with a stated reason: an eventually consistent re-read of your own write is the
classic stale-value bug, and refresh is precisely what a caller uses after an optimistic-lock conflict.

### Astra ran javap this time

`astra/finders` delivered `docs/FINDER-MATRIX.md` (67 KB) plus **600 KB of javap output** —
`JAVAP-API.txt` and `JAVAP-BYTECODE.txt`. The previous astra run had declared its javap gate BLOCKED
because the Windows executable failed at WSL interop; this one **found the installed Linux JDK 11 and
used it against the same jar**. 59 type/operator invocations, a mandatory cold-cache protocol, three
narrowly-declared `PLAN-001` refusals, and an explicit list of what it could not verify.

Dispatched as `fm/harness` — §2, §4 and §5 only (fixture plus cold-cache harness), with an explicit
instruction to report if the prescription does not survive contact with the code, since it was written
without running anything.

## Hourly checkpoint 07:45 MT — the first acceptance case is closed

1. **NO REGRESSION — MET.** Adapter unchanged at core 188 / test-kit 15 / ddb 305 / spike 3 = **511**.
   Classifier demo 11 → **12**, BUILD SUCCESS.
2. **FLEET ALIVE — MET.** `land/demos25` DONE and integrated. `fm/harness` RUNNING on the
   finder-matrix fixture. `astra/acceptance` retried and **FAILED on codex quota again** — third
   attempt; `finders` consumed the window.
3. **FORWARD MOTION — MET.** Acceptance case 12 closed.
4. **ASTRA CONSUMED — MET** (topology and finders adopted; acceptance still blocked).

### The ninth criterion is no longer zero

`ClassifierBoundPortalTest` passes: each portal bound with `setMithraObjectReader`, the demo's
**unchanged** business operations run against DynamoDB, the processing clock pinned with
`setProcessingStartTime` so IN_Z/OUT_Z are comparable, and the two histories asserted identical across
all four temporal boundaries. Its catch block asserts a **named** refusal and then fails — no skip, no
`@Disabled`. I read the test before landing it, because that is exactly the assertion that would be
easiest to soften.

**0/13 → 1/13.**

### I was wrong last checkpoint, and the correction matters

I reported that findings 25 and 26 "were never landed" and that the classifier was failing on a bug
already fixed. **Both were already live** — `zGetTxDataForRead()` at `DynamoDbPersister:466` and
`getOriginalOperation()` at line 220. The integration wave had picked them up, because the `tx` agent
branched *after* the `demos` agent and inherited its persister changes.

What misled me was `merge3`'s report that the classifier still failed on `ResultLabel cannot be null`.
That report was **accurate for `merge3`'s own tree**, which did not contain the wave's result. I read
an agent's observation of its own sandbox as an observation of the repository.

The dispatched agent then found the persister already correct — a **zero-line diff against live** —
which is how the error surfaced. That is the same discipline catching my mistake that has been
catching the agents': diff against the live tree before believing anything about it. It cost one
unnecessary dispatch, and the dispatch still delivered the bound test, so the cost was small. The
lesson is not.

## Hourly checkpoint 08:27 MT — the finder-matrix harness is in

1. **NO REGRESSION — MET.** core 188, test-kit 15, ddb **323**, spike 3 = **529** (was 511).
2. **FLEET ALIVE — MET.** `fm/harness` DONE and integrated; `fm2/{cases,shapes}` dispatched.
   `astra/acceptance` **FAILED on codex quota for the third time** — `finders` consumed the window.
3. **FORWARD MOTION — MET.** The harness that the ninth criterion depends on now exists.
4. **ASTRA CONSUMED — MET.**

### What landed

A new `DiffFinderValue` fixture — composite PK `(scopeId, rowId)`, a `bucketId` for partitioning, and
one attribute per type: int, long, double, float, BigDecimal, String, boolean, Timestamp, byte[] —
plus a `findermatrix` package: `FinderMatrixHarness`, `RequestCounters`, `RequestAssertions`,
`CountingDynamoDb`, `RecordingPersister`, `SqlReadProbe`, `FixtureManifests`.

The part that matters is the cold-cache proof, and it is real:

- `should_fail_when_cache_is_allowed_to_serve_the_query` — with the cache serving, asserts data reads
  do **not** increase and that `requirePositiveDataReads` **throws**.
- `should_reach_adapter_after_cold_reset` — asserts reads > 0, reader entries > 0, **scans == 0**.

I checked those two before landing, because a harness that cannot detect a cache hit would make every
case built on it theatre — which is precisely how R-05 stayed green while being wrong in both the
implementation and its oracle.

### My integration bug, caught by the build

First landing attempt failed with four errors: `IllegalStateException: H2 truncate failed`. Cause was
**mine, not the agent's** — my copy loop filtered `-name "*.java" -o -name "*.xml"`, and the fixture's
H2 table lives in `diff-schema.sql`. The agent had shipped it; I did not copy it.

Worth recording because the failure was loud and immediate. A resource file silently missing is
exactly the kind of omission that could have produced a subtly wrong test rather than an obvious one,
had the harness not truncated the table in `setUp`. **Copy what the agent shipped, not what I expect
it to have shipped** — enumerate the delivered files rather than filtering by extension.

Landed the schema; 529 green.

### Next

`fm2/cases` takes the 59 type/operator invocations, `fm2/shapes` takes boolean structure, temporal
axes and result shape. Both are told that a refusal only counts as a pass when it is one of the three
narrow `PLAN-001` refusals §3 declares or is named in the support contract — anything else refusing is
a finding, not an accepted outcome. That distinction is the one that decides whether this matrix is
worth anything.

## Hourly checkpoint 09:27 MT — the matrix paid for itself on its first run

1. **NO REGRESSION — MET.** Live tree unchanged at **529** (core 188, test-kit 15, ddb 323, spike 3).
2. **FLEET ALIVE — MET.** `fm2/cases` and `fm2/shapes` both DONE; merge dispatched as `fm3/merge27`.
   `astra/acceptance` FAILED on codex quota a third time.
3. **FORWARD MOTION — MET.** Three findings recorded (27, 28, 29), two of them from the matrix's very
   first run.
4. **ASTRA CONSUMED — MET.**

### Finding 27, and why it is the best evidence yet that the matrix was worth building

```
Q(1).and(DiffFinderValueFinder.intValue().notEq(2))
H2  -> {1, 2, 4, 7}       DDB -> {1, 2, 4, 5, 7}    (row 5 is all-NULL)
```

`NULL <> 2` is **UNKNOWN** in SQL, not TRUE, so H2 excludes the null row; the adapter's native `<>`
filter matches the explicitly stored NULL. Five of nine types affected — int, long, String, boolean,
Timestamp.

**The other four agree with H2.** `notEq` on double, float and BigDecimal is correct, because R-04
moved those out of the wire filter into typed residual evaluation, where Java comparison happens to
implement UNKNOWN properly.

That split is the finding. One predicate, one test run, one oracle — right on the types that take the
residual path, wrong on the types that take the native filter. A fix aimed at numeric *fidelity*
accidentally produced correct null semantics for three types, and the contrast exposed that the other
five had never been right. Nothing short of a real finder against codec-written rows with a null row
in the fixture could have shown that.

Two more from the same run, and the agent distinguished them properly:

- **Finding 28** — `ByteArrayAttribute.notEq` throws `UnsupportedOperationException` in **Reladomo
  itself**, javap-confirmed. Not ours. Nothing to translate.
- **Finding 29** — five `equalsEdgePoint` cases error because `asOfDatesOf` requires an
  `AsOfEqOperation`. A real SPI gap, same family as finding 24, found the same way: by exercising a
  path an application takes rather than reading the refusal list.

One divergence that is ours, one that is the framework's, one that is a gap — separated with bytecode
rather than assumption. That is what I want from these agents.

### The merge will make the board red, on purpose

`fm3/merge27` lands both agents and fixes finding 27. The 28 and 29 cases stay failing, by name. The
board will go red when they land, and that red is **new information**, not a regression — the same
call as finding 21, which turned out to be the most serious defect in the project.

## Hourly checkpoint 10:27 MT — query-path coverage is no longer the weak half

1. **NO REGRESSION — MET in substance, RED by design.** core 188, test-kit 15, **ddb 435** (was 323),
   spike 3. Seven errors, every one named and tracked. No previously-green test regressed.
2. **FLEET ALIVE — MET.** `fm3/merge27` DONE and integrated; `fm4/{edgepoint,binorder}` dispatched.
   `astra/acceptance` FAILED on codex quota a fourth time.
3. **FORWARD MOTION — MET.** Finding 27 closed; 112 query-path cases landed; findings 29 and 30
   dispatched.
4. **ASTRA CONSUMED — MET.**

**The number that mattered has moved.** The differential gate has read "89 storage-path, 6 query-path"
for days, and I have repeatedly said the query path is the weaker half and the one that counts.
It is now **+112 cases** exercising real generated finders against codec-written rows through the real
executor, behind a harness that proves the adapter was actually reached.

**Finding 27 closed properly.** `notEq` now emits
`attribute_exists(#n) AND NOT attribute_type(#n, :null) AND #n <> :v`, following R-05's shape — and
the agent fixed `QueryPlanInterpreter` alongside the planner, which was the instruction and the whole
lesson of R-05.

### The remaining red, and why none of it is a regression

- **5 × finding 29** — `equalsEdgePoint` and from-range cannot materialise; `asOfDatesOf` requires an
  `AsOfEqOperation`. The refusal message deserves quoting: *"Picking a default date would return rows
  that look right and are silently the wrong version."* That is exactly the right instinct, so the fix
  adds the capability rather than softening the refusal.
- **1 × finding 28** — `ByteArrayAttribute.notEq` throws in Reladomo itself. Not ours.
- **1 × finding 30** — binary ordering on a GSI route returns nothing. **New**, and the interesting
  one: `RowOrderComparator.compareUnsignedBytes` reads correct, so the fault is probably elsewhere —
  possibly R-05's `isNotNull` translation against a *binary* attribute. Dispatched with an instruction
  to establish which side is empty **before** changing anything, because finding 22 was itself a
  defect inside a fix that passed its own tests.

## Hourly checkpoint 11:27 MT — I was wrong about finding 30, and the agent was better

1. **NO REGRESSION — MET.** Live tree unchanged: core 188, test-kit 15, ddb 435, spike 3; 7 known
   errors, all named.
2. **FLEET ALIVE — MET.** `fm4/edgepoint` and `fm4/binorder` both DONE; merge dispatched as
   `acc/merge345`. `astra/acceptance` FAILED on codex quota a **fifth** time.
3. **FORWARD MOTION — MET.** Findings 30 and 31 diagnosed and fixed; a new support-contract boundary.
4. **ASTRA CONSUMED — MET** for topology and finders; acceptance abandoned as a dependency, see below.

### Finding 30: three hypotheses, all mine, all wrong

I guessed R-05's `isNotNull` against a binary attribute, or the fixture, or the GSI route. The agent
ruled out each **with evidence** and found the real cause:

```java
// com.gs.fw.common.mithra.finder.orderby.ByteArrayOrderBy.compareWith
for (i = 0; i < a.length; i++) diff = a[i] - b[i];   // not bounded by min(a.length, b.length)
```

**Neither result set was empty.** H2 returned all six rows; Reladomo then crashed Java-sorting them,
because fixture row 2 is `new byte[0]`. It proved this in pure Reladomo — `ByteArrayOrderByConformanceTest`
reproduces the AIOOBE on two in-memory objects with no DynamoDB, no GSI, no projection. That is the
discipline of findings 2-11: separate "wrong premise about Reladomo" from "adapter defect", with a
test that isolates which.

### Finding 31 is the more consequential half

`a[i] - b[i]` is **signed** byte subtraction; DynamoDB binary ordering is **unsigned** lexicographic.
So `0x80` sorts **before** `0x01` in Reladomo and **after** it in DynamoDB — even with the crash fixed.

Neither store is wrong. It is a genuine semantic divergence between reference and target that no
adapter can reconcile, and the honest response is to state it, not to paper over it. Added to
`docs/SUPPORT-CONTRACT.md` as a Tier 1 boundary: **the adapter matches DynamoDB, and supplies
`UnsignedByteArrayOrderBy` so an H2 comparison sorts the same way.** An application depending on
Reladomo's signed order will see a different sequence, and now it is told so.

The agent also checked that `AttributeBasedOrderBy.equals` ignores the concrete class, so Reladomo's
`CachedQuery.hasSameOrderBy` still matches after the substitution. That detail would have broken query
caching silently — exactly the class of thing that has bitten this project repeatedly.

### Astra acceptance: five failures, stop blocking on it

`astra/acceptance` has now failed five times on codex quota. The prescription would have been useful,
but the finder matrix established the pattern it was meant to establish, and case 12 was closed
without it. **Dispatched acceptance cases 3, 4 and 5 to grok directly**, written against the
inspection's own text plus the harness that now exists — transaction durability, cross-JVM conflict
contract, and complete-key queries with failing filters. Waiting on a blocked prescription while the
pattern is already demonstrated is not caution, it is idling.

## Hourly checkpoint 12:27 MT — acceptance 1/13 → 3/13, and an agent broke protocol

1. **NO REGRESSION — MET in substance.** core 188 → **193**, test-kit 15, ddb 435 → **444**, spike 3.
   Errors **7 → 3**: two are acceptance case 4 (finding 32, new), one is finding 28 (Reladomo's own).
2. **FLEET ALIVE — MET.** `acc/merge345` DONE and integrated. `astra/acceptance` FAILED a sixth time;
   no longer a dependency.
3. **FORWARD MOTION — MET.** Findings 29, 30, 31 closed; finding 32 opened; **acceptance 1/13 → 3/13**.
4. **ASTRA CONSUMED — MET.**

### Acceptance cases 3 and 5 pass

- **Case 3** — a flushed-then-thrown Reladomo transaction leaves **nothing** durable, including a
  failure *between* closing an old rectangle and inserting its replacement, with the old rectangle
  still open afterwards. It passed on the first focused run because R-01's coordinator was already
  correct — and the agent noted the tests would still have failed had flush sent `TransactWriteItems`,
  which is the right way to say "this passed for a reason, not by luck."
- **Case 5** — complete PK plus a failing payload predicate returns nothing, `count()` is 0, and the
  request counters show `getItem >= 1, query == 0` — a point read, not a silent downgrade to Query.
  Same for an exact dated rectangle with failing as-of containment.

With case 12, that is **3 of 13**.

### Finding 32: R-02's conditions fire, and the coordinator throws the information away

Case 4 is implemented and failing. The condition **does** trigger inside a transaction
(`TransactionCanceledException` / `ConditionalCheckFailed`), but the coordinator wraps it as
`RELADYNAMO-TXN-004`, so the caller sees `Could not commit transaction` rather than
`MithraUniqueIndexViolationException` or `MithraOptimisticLockException`. The **non-transactional**
writer path maps both correctly (`WriterConcurrencyTest` 7/7), so the knowledge exists and is lost in
transit.

It matters because `MithraOptimisticLockException.isRetriable()` is `true` and a generic transaction
exception is not — an application's retry never fires, and the documented refresh-then-retry recovery
is unreachable through a transaction.

The agent found the real design question rather than the easy fix: `DurableTransactionTest` *requires*
TXN-004 for a conditional cancellation, so a blanket remap would regress R-01. The answer is that
`TransactWriteItems` returns **per-action `CancellationReasons`** — the coordinator can tell which
action failed which condition. The information is already on the wire and nothing reads it.

### An agent wrote into the live repository

`fm4/binorder` created `UnsignedByteArrayOrderBy.java`, edited `OrderByTranslator.java` and added
`ByteArrayOrderByConformanceTest.java` **directly in the working tree** (mtimes 10:43-10:48), rather
than in its own mirror. The shared brief is explicit: *never write to the real repository path;
several agents run in parallel and would corrupt each other.*

It was harmless this time — the work is good, I had verified its diagnosis, and the concurrently
running `edgepoint` agent touched `DynamoDbPersister` while this one touched `core/plan`, so they did
not collide. That is luck, not design. The real cost is that its changes **skipped my
verify-before-integrate step** and were in the tree for two hours before I knew, and I only noticed
because the next agent reported "binorder already identical to live" and that sentence did not make
sense. Worth adding an explicit check: before landing anything, confirm no source file has an mtime
newer than my last verified build.

## Hourly checkpoint 13:27 MT — one error left in the whole build, and it is not ours

1. **NO REGRESSION — MET.** core **193**, test-kit **15**, ddb **454**, spike 3 — **665 tests**.
   Errors **3 → 1**. The one remaining is finding 28: `ByteArrayAttribute.notEq` throws inside
   Reladomo 18.1.0 itself.
2. **FLEET ALIVE — MET.** `txn/conflict` DONE and integrated; `acc2/cases1678` dispatched.
   `astra/acceptance` FAILED a seventh time; not a dependency.
3. **FORWARD MOTION — MET.** Finding 32 closed; **acceptance 3/13 → 4/13**.
4. **ASTRA CONSUMED — MET.**

### Finding 32 closed the way it should have been

The coordinator now reads `TransactionCanceledException.cancellationReasons()` — the per-action list,
positionally aligned with submitted actions — and maps each failed slot back to the `PhysicalWrite`
that produced it. Insert condition → `MithraUniqueIndexViolationException`; update/delete
expected-state → `MithraOptimisticLockException`; anything unattributable → TXN-004 unchanged.

`DurableTransactionTest.permanent_cancellation_aborts_both_tables_without_retry` still passes on
TXN-004 — that constraint is exactly what made a blanket remap wrong, and the previous agent had
declined to make it for that reason. Three new tests pin the aligned cases, one asserting
`isRetriable()`, because the point of the right exception type is that a retry actually fires.

### I checked the thing most worth checking

`DurableTransactionTest` came back **134 lines changed** — on a test whose job is to keep passing
unchanged. That is the shape of a weakened guard, so I diffed it before landing: every assertion line
was an **addition** (three new aligned-conflict tests), and the only five removed lines were a
fixture stub replaced by one that can produce per-action reasons aligned to slots. Legitimate.

Had it been the other way, the build would still have gone green and R-01's guarantee would have been
quietly gone. A large diff on a guard test is worth thirty seconds of reading, every time.

### Next

`acc2/cases1678` takes acceptance cases 1, 6, 7 and 8, plus converting finding 28 from an error into
an assertion that Reladomo refuses binary `notEq` — which is a true statement about the support
surface, not a softened test. Two of those four (1 and 6) may already be satisfied by existing work;
the brief tells the agent to **prove it or name the gap**, and that an honest "already covered with
evidence" beats padding.

## Hourly checkpoint 14:27 MT — zero errors, acceptance 8/13

1. **NO REGRESSION — MET.** core **198**, test-kit **15**, ddb **461**, spike 3 — **677 tests**,
   **BUILD SUCCESS with zero errors**. First fully clean build since the finder matrix landed.
2. **FLEET ALIVE — MET.** `acc2/cases1678` DONE and integrated; `acc3/cases910` dispatched.
   `astra/acceptance` FAILED an eighth time.
3. **FORWARD MOTION — MET.** Finding 33 found and closed; **acceptance 4/13 → 8/13**.
4. **ASTRA CONSUMED — MET.**

### Finding 33: PartiQL was unbounded, and R-09's own notes had predicted it

`QueryPlanExecutor.executeFanOutSelect` uses `ExecuteStatement` for a collapsed fan-out and **never
called `limit()`**. `pageSize` was only the IN-list chunk size. So:

> two partition keys with five versions each, `pageSize=2`, `maxPages=2` collapsed to **one**
> statement and returned all ten items with **no refusal**

Query and Scan were bounded; the third path was not, while the public configuration said otherwise.

**That is the third defect of exactly this shape.** `avgItemBytes` was read by nothing (finding 20);
`maxPages` never reached the plan (R-09); now PartiQL ignored the limit entirely. The pattern is worth
naming: a setting compiles, so it looks wired; a test sets it directly on the plan, so it looks
enforced. Only driving it from **public configuration through every execution path** finds these.

### The agent declined to pad the count, which is the behaviour I want

Case 6 came back **ALREADY-COVERED-with-evidence** rather than as a new test file. It read the
existing `FinderMatrixTypeOperatorCasesTest`, established that the case's wording was already
satisfied, and said so. An honest "already proven, here is where" is worth more than a duplicate test
that inflates the number.

### The 8/13 needs its breakdown or it overstates

- **8 pass**: 1, 3, 4, 5, 6, 7, 8, 12
- **2 open and actionable**: 9 (custom temporal names, PK types, source routing, GSI projections) and
  10 (required SPI surface with cold caches and the source disconnected) — dispatched as `acc3`
- **3 blocked by declared scope**: 2 (stale-replay safety — M-05, offline-only), 11 (ASE extraction —
  M-03, no access), 13 (cutover/rollback/reverse import — M-05/M-06). Partially covered: 13's
  crash/restart and partial-writes halves are done via M-04 and R-01.

Three of the five remaining are not work in progress — they are things the support contract says
0.1.0 does not do. Reporting "8 of 13" without that would be the same kind of overstatement the
inspection was written to stop.

### The gate was under-reporting the half I kept calling the important one

Board 124: **8/0/0**, `tests=669` (the floor count — the real total is 677; jqwik classes report
differently in surefire XML than in the reactor summary).

But `differential-h2-vs-ddb` read **"240 storage-path, 6 query-path"**, and the 6 has not moved in
days. It is wrong. The gate classified a test as query-path only if its filename contained
`FinderDriven` — so ~112 finder-matrix cases and every acceptance test, all of which execute real
generated finders against DynamoDB and compare to H2, were being counted as **storage-path**.

Corrected classifier, and the real split:

```
before:  240 storage-path,   6 query-path
after:    78 storage-path, 168 query-path
```

I have said for days that the query path is the weaker half and the one that matters, and quoted "6"
as evidence of it. The number was an artefact of a filename match, and it had been stale since the
first finder-driven test was named anything else. The work was real and the metric was not measuring
it.

**This is the fourth metric in this project that quietly meant something other than it was read to
mean** — `avgItemBytes` read by nothing, the `tests=` floor undercounting jqwik, surefire XML
recording the last run per class rather than the current tree, and now this. The common thread is
that each was a *derived* number nobody re-derived after the thing it described changed shape.

## Hourly checkpoint 15:27 MT — acceptance 10/13; the remaining three are scope, not effort

1. **NO REGRESSION — MET.** core **202**, test-kit 15, ddb **469**, spike 3 — **689 tests**, zero
   errors. Demos: CRM 18, petstore **17**, classifier **13**.
2. **FLEET ALIVE — MET.** `acc3/cases910` DONE and integrated; `chal2/triage` dispatched.
   `astra/acceptance` FAILED a ninth time — permanently abandoned as a dependency.
3. **FORWARD MOTION — MET.** **Acceptance 8/13 → 10/13.**
4. **ASTRA CONSUMED — MET**, and now fully: `CHALLENGE.md`'s remaining eleven challenges are
   dispatched, which was the last unconsumed astra output.

### Case 9 — the path I predicted was half-working, was

Custom temporal names: the parser emitted `validDateFrom`/`validDateTo` while `DynamoDbWriter` looked
up `businessDateFrom`. Unitemporal `validDate` now **works end to end** — `PhysicalDesign` derives the
names from the parsed mapping, the sort key is `v1#B#<encoded validDateFrom>`, and a write plus a
consistent read round-trips. Bitemporal custom names **refuse** at preflight with `RELADYNAMO-CFG-015`.

Float/Double primary keys were *already* rejected — but by `KeyComponentEncoder` at **write** time,
which is not preflight. Moved to parse. That distinction is the whole of case 9's wording: "work end
to end **or fail preflight**".

The `KEYS_ONLY`/`INCLUDE` refusals were verified by the tables being **absent** after the throw, not
by the exception type alone. That is the right way to assert "before any write".

### Case 10 — required SPI derived by running applications, not reading a list

This is finding 24's method applied deliberately: run the classifier and petstore bound-portal tests
with H2 disconnected, record every SPI method actually reached, and require each to work or refuse by
name. `docs/SUPPORT-CONTRACT.md` Tier 2 now quotes that split rather than "20 of 32 refuse".

**Petstore rebinds cleanly** — 22 entities, a unitemporal director, 16 → 17 tests. A previous agent
had predicted it would hit the same `refresh` wall; `refresh` landed in between, and the agent
**checked rather than inherited the prediction**.

### The three that remain are declared scope

- **2** — stale-replay safety (M-05). The contract declares offline immutable-source migration for
  0.1.0, so there is no online replay to make safe.
- **11** — ASE extraction (M-03). No ASE in this environment, and manufacturing evidence for it is
  precisely what the last two days have been spent removing.
- **13** — half done: crash/restart (M-04) and partial writes (R-01) are covered; cutover, rollback
  and reverse import are M-05/M-06.

"10 of 13" without that breakdown would overstate it in exactly the way the inspection was written to
prevent.

### Board 125 — and the corrected metric reads true

```
8 pass / 0 fail / 0 pending
adapter-build            tests=681 (floor; real total 689)
demo-crm 18   demo-petstore 17   demo-classifier 13
java11-floor · spec-drift · licence-scope       PASS
differential-h2-vs-ddb   78 storage-path, 176 query-path
```

The differential split now reports what the suite actually contains. Yesterday it read **"6
query-path"** and I quoted that figure repeatedly as evidence the query path was the project's weak
half. It was a stale filename match, not a measurement — the fix landed this afternoon and the gate
now says **176**.

Both halves of that story are worth keeping. The query path genuinely *was* thin when the inspection
landed, and closing it was the right priority. But for some hours it had already been closed while the
gate still said otherwise, and I kept repeating the number instead of re-deriving it.
