# New Feature Workflow

## Step 1: Understand (parallel)
Spawn parallel agents in ONE message:
- **Agent A**: Read requirements + check `context/MEMORY.md` and `context/CONVENTIONS.md`
- **Agent B**: Explore existing code — find related files, patterns, interfaces to extend
- **Agent C** (if JavaDucker available): `javaducker_search` for similar features and patterns, `javaducker_map` for project orientation

Wait for both. Combine into implementation plan.

## Step 2: Design
Identify affected files, interfaces, and data flow. Use `/architect` for complex designs.
Split implementation into independent pieces that can be built in parallel.

## Step 3: Implement (parallel)
Spawn one Agent per independent piece in ONE message:
- Each agent gets: specific files to create/edit, interfaces to implement, conventions to follow
- Each agent reads existing files before editing
- All agents run with `run_in_background: true`

Wait for all. Review for conflicts.

## Step 4: Test (parallel)
Run in ONE message:
- Write unit tests for new logic
- Write integration tests for boundaries
- Run full test suite

## Step 5: Review
Self-review against `/reviewer` checklist: correctness, security, readability.

## Step 6: Document
Update `context/MEMORY.md`. Add ADRs to `context/DECISIONS.md` if architectural.
