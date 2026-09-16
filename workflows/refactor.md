# Refactoring Workflow

## Step 1: Assess (parallel)
Spawn parallel agents in ONE message:
- **Agent A**: Identify refactoring targets — duplication, complexity, unclear naming, tight coupling
- **Agent B**: Run existing tests to establish passing baseline, note coverage gaps
- **Agent C** (if JavaDucker available): `javaducker_dependents` on target files to discover all callers and importers before restructuring

Wait for both.

## Step 2: Plan
Group refactoring targets into independent batches. Each batch should be:
- Independently testable
- Non-conflicting with other batches (different files or non-overlapping regions)

## Step 3: Refactor (parallel)
Spawn one Agent per independent batch in ONE message:
- Each agent makes one type of structural change
- Each agent runs tests after their changes
- All agents run with `run_in_background: true`

Wait for all. Check for conflicts between agents.

## Step 4: Verify (closed loop)
Run full test suite. If failures:
1. Identify which batch broke tests
2. Fix or revert that batch only
3. Re-run tests
4. Max 3 iterations

## Step 5: Clean up
Remove dead code, update imports, delete unused files.
