# Bug Fix Workflow

## Step 1: Investigate (parallel)
Spawn parallel agents in ONE message:
- **Agent A**: Reproduce the bug — find failing test case or steps to trigger
- **Agent B**: Search codebase — grep for related patterns, read recent git log for the area
- **Agent C** (if JavaDucker available): `javaducker_search` for error messages and related code paths, `javaducker_explain` on suspect files

Wait for both. Combine findings.

## Step 2: Locate root cause
Trace from symptoms to root cause using investigation results. Read the code path involved.

## Step 3: Fix
Make the minimal change that addresses the root cause, not the symptom.

## Step 4: Verify (parallel)
Run in ONE message:
- Add a regression test that fails without the fix and passes with it
- Run the full test suite

## Step 5: Document
Update `context/MEMORY.md` with what was found and fixed.

## If fix doesn't work → closed loop
If the test still fails after the fix:
1. Re-analyze — was the root cause wrong?
2. Try a different fix
3. Re-run tests
4. Max 3 attempts before escalating to the user
