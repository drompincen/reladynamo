# Closed-Loop Workflow

A repeating pipeline that runs checks, fixes issues, and re-checks until all pass or max iterations reached.

## Prerequisites

Before starting, you need:
- A **check command** — a script or test that produces a machine-readable report (JSON)
- A **pass condition** — what "done" looks like (e.g., 0 issues, all tests pass)
- A **max iterations** cap (default: 10)

## Loop Protocol

### 1. Capture baseline
```
Run the check command → parse results → record in context/MEMORY.md:
  Iteration: 0 (baseline)
  Total issues: N
  Pass rate: X/Y
  Issue breakdown by category
```

### 2. Analyze
- Read the report output (JSON, test results, screenshots, etc.)
- Categorize issues by type and fix approach
- Group into independent fix batches — each batch becomes a parallel agent

### 3. Fix (PARALLEL)
Spawn one Agent per independent fix category in a SINGLE message:
```
Agent 1: "Fix all [category-A] issues. Files: [list]. Read each file first."
Agent 2: "Fix all [category-B] issues. Files: [list]. Read each file first."
Agent 3: "Fix all [category-C] issues. Files: [list]. Read each file first."
```
All agents run with `run_in_background: true`. Wait for ALL to complete.

### 4. Review agent results
- Read ALL agent outputs before proceeding
- Check for conflicts (two agents editing the same file/region)
- If conflicts exist, resolve them before re-checking

### 5. Re-check
Run the check command again. Compare to previous iteration:
- **Improved** (fewer issues) → continue
- **Regression** (more issues) → revert immediately, log what failed, try different approach
- **Same count** but different issues → continue (progress is being made)
- **All pass** → go to Confirm

### 6. Log iteration
Append to `context/MEMORY.md`:
```
### Iteration N
- Pass: X/Y
- Issues: N (was M)
- Fixed: [categories]
- Regressed: [none | categories]
- Key changes: [summary]
```

### 7. Loop or exit
- Issues remain AND iteration < max → go to step 2
- All pass → go to Confirm
- Max iterations reached → stop, report remaining issues

### 8. Confirm
- Run check one final time to verify
- If visual/manual inspection is needed, do it now
- Copy confirmed outputs to final location
- Write final summary to `context/MEMORY.md`

### 9. JavaDucker hygiene (if available)
- Run `workflows/javaducker-hygiene.md` Phase 2 (enrich new artifacts) and Phase 3 (compact superseded artifacts from prior iterations)
- Record loop decisions with `javaducker_extract_decisions`

## Anti-patterns to avoid

- **Don't fix everything at once** — group by category, fix in parallel batches
- **Don't retry the same fix** — if it regressed, try a different approach
- **Don't skip the re-check** — always verify before continuing
- **Don't run agents sequentially** — if fixes are independent, they MUST be parallel
- **Don't keep going past max iterations** — diminishing returns, stop and report

## Prompting this workflow

Tell Claude:
```
Follow workflows/closed-loop.md.
Check command: [your command here]
Pass condition: [what success looks like]
Max iterations: [N]
```

Example:
```
Follow workflows/closed-loop.md.
Check command: npx playwright test tests/qa-audit.spec.js --reporter=json
Pass condition: 0 failures in the JSON report
Max iterations: 10
```
