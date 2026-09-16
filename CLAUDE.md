# drom-flow — Project Configuration

> **drom-flow** is active in this project. It provides workflows, parallel agent orchestration, closed-loop pipelines, persistent memory, and lifecycle hooks. The statusline shows drom-flow branding, git state, session time, edit/agent counts, and memory status.

## Behavioral Rules

- Do what has been asked; nothing more, nothing less
- NEVER create files unless absolutely necessary for the goal
- ALWAYS prefer editing an existing file to creating a new one
- NEVER proactively create documentation files unless explicitly requested
- NEVER save working files, tests, or docs to the root folder
- ALWAYS read a file before editing it
- Keep files under 500 lines
- NEVER commit secrets, credentials, or .env files

## File Organization

- Use `src/` for source code
- Use `tests/` for test files
- Use `docs/` for documentation
- Use `scripts/` for utility scripts and orchestration scripts
- Use `config/` for configuration files
- Use `drom-plans/` for execution plans (chapter-based, with progress tracking)

## Parallelism — ALWAYS parallel by default

- EVERY task must be analyzed for parallelism BEFORE execution
- Batch ALL related file reads in ONE message
- Batch ALL file edits in ONE message
- Batch ALL independent Bash commands in ONE message
- Spawn ALL independent Agent calls in ONE message with `run_in_background: true`
- After spawning background agents, STOP and wait for results — do NOT poll
- When a task has multiple independent fix targets, spawn one Agent per target in a single message
- When reviewing results from parallel agents, read ALL results before deciding next action
- Sequential steps run only when there is a true data dependency on a prior step

## Closed-Loop Execution

When a workflow specifies a loop (repeat-until-pass), follow this protocol:

1. **Read the workflow** to identify: steps, pass condition, max iterations, and what to capture per iteration
2. **Run the check/capture step** to establish baseline metrics
3. **Analyze results** — categorize issues, group by fix type
4. **Spawn parallel fix agents** — one Agent per independent issue category, ALL in one message
5. **Wait for all agents** — review ALL results together
6. **Re-run the check** — compare metrics to previous iteration
7. **Log iteration** — append to `context/MEMORY.md`: iteration number, pass/fail counts, key fixes, regressions
8. **Decide**:
   - All pass → exit loop, run final confirmation
   - Regression detected → revert, log what failed, try different approach
   - Issues remain and under max iterations → go to step 3
   - Max iterations reached → stop, report remaining issues
9. **On exit** — write final summary to `context/MEMORY.md`

### Regression handling
- If an iteration produces MORE issues than the previous one, it is a regression
- Revert the changes from that iteration immediately
- Log what was attempted and why it regressed
- Try a different fix approach in the next iteration
- Never repeat the same fix that caused a regression

## Security

- NEVER hardcode API keys, secrets, or credentials in source files
- NEVER commit .env files or any file containing secrets
- Always validate user input at system boundaries
- Always sanitize file paths to prevent directory traversal

## Memory Protocol

- At session start, read `context/MEMORY.md` for ongoing context
- Before session ends, update `context/MEMORY.md` with progress and findings
- Log important architectural decisions in `context/DECISIONS.md`
- Check `context/CONVENTIONS.md` for project-specific patterns before writing code
- During loops, append iteration results to `context/MEMORY.md` after each iteration

## JavaDucker Integration (optional)

JavaDucker is an optional companion (semantic code search + session memory over MCP).

**It is conditional by design: use it when it is there.** The session-start hook detects it and, when
present, points here — so projects without JavaDucker carry none of this in context.

- Detect: `.claude/.state/drom-flow`-managed config via `javaducker_available()` in
  `.claude/hooks/javaducker-check.sh`
- **Full 48-tool catalog and usage protocol: `.claude/docs/javaducker.md`** (loaded only when active)
- Set up with `/add-javaducker`, remove with `/remove-javaducker`

When JavaDucker is NOT configured, ignore it entirely — do not attempt its tools.

## Plan Protocol

- All plans are created in `drom-plans/` as markdown files with YAML frontmatter
- Plans are broken into **chapters** — each chapter is a logical phase of work with its own steps
- Chapter status tracks progress: `pending` → `in-progress` → `completed`
- At session start, the memory-sync hook checks for `status: in-progress` plans and surfaces them
- When resuming a plan, read the plan file, find the current chapter, and continue from the first unchecked step
- Update step checkboxes (`[ ]` → `[x]`) and chapter status as work progresses
- When all chapters are done, set the plan's frontmatter `status: completed`
- Use `/planner` to create new plans — it handles the format and file creation

## Orchestration Scripts

- Orchestration scripts live in `scripts/` and automate multi-step pipelines
- Scripts should be idempotent — safe to re-run from any iteration
- Scripts must accept `--iteration N` to resume from a specific point
- Scripts must write machine-readable output (JSON) for Claude to parse
- Scripts must exit with code 0 on success, non-zero on failure
- Use `scripts/orchestrate.sh` as the template for new orchestration scripts

## Workflows

When the task matches a common pattern, follow the corresponding workflow:

- Bug fixes: follow `workflows/bug-fix.md`
- New features: follow `workflows/new-feature.md`
- Refactoring: follow `workflows/refactor.md`
- Code reviews: follow `workflows/code-review.md`
- Closed-loop QA: follow `workflows/closed-loop.md`
- JavaDucker index maintenance: follow `workflows/javaducker-hygiene.md`

## Skills

Use these agent profiles when the task calls for a specialized role.

**Code & engineering:**

- `/planner` — Task decomposition, parallel execution planning
- `/implementer` — Writing production code
- `/reviewer` — Code review with severity ratings
- `/debugger` — Systematic bug investigation
- `/refactorer` — Safe code restructuring
- `/architect` — System design and architecture decisions
- `/orchestrator` — Design and run closed-loop pipelines
- `/ascii-architect` — Convert thoughts, architectures, and processes into ASCII art diagrams
- `/api-expert` — Contract-first REST API design and implementation (OpenAPI 3.1, Spring Boot, security, rate limiting)
- `/dynamodb-architect` — DynamoDB data modelling, key and index design, capacity and cost, and migration paths for changes that cannot be made in place
- `/enterprise-form-architect` — Long corporate data-entry forms in React: section architecture, render isolation at 100+ fields, validation timing, draft autosave, server error mapping, accessibility
- `/grok-fleet` — Fan out parallel grok CLI sub-agents with filesystem progress, monitoring, and stop control (combines with Claude sub-agents)
- `/df-research` — Deep research on the grok fleet: multi-perspective sweep, independence + contradiction audit, adversarial critics, cite-check gate
- `/add-javaducker` — Set up JavaDucker companion tool for semantic code search
- `/remove-javaducker` — Remove JavaDucker integration

**Web platform quality** (from `addyosmani/web-quality-skills`, MIT):

- `/web-quality-audit` — Comprehensive web quality audit (orchestrates the QA skills below)
- `/accessibility` — WCAG 2.2 audit and fixes (screen reader, keyboard navigation)
- `/seo` — Search engine visibility (meta, structured data, sitemap)
- `/performance` — Web performance optimization (load time, bundle size)
- `/core-web-vitals` — Optimize LCP, INP, CLS for page experience
- `/best-practices` — Modern web best practices (security, code quality, modernization)

**Product management**:

- `/discovery-process` — Run a full discovery cycle from problem hypothesis to validated solution
- `/problem-statement` — Write a user-centered problem statement (who, what, why, how it feels)
- `/jobs-to-be-done` — Uncover customer jobs, pains, and gains (JTBD framework)
- `/customer-journey-map` — Map customer experience across stages, touchpoints, emotions
- `/user-story-mapping` — Build a user story map (activities → steps → tasks → release slices)
- `/epic-breakdown-advisor` — Split epics into stories using Humanizing Work patterns
- `/user-story` — Write user stories (Mike Cohn format with Gherkin acceptance criteria)
- `/user-story-splitting` — Break large stories into deliverable slices
- `/prd-development` — Build structured PRDs (problem → users → solution → success criteria)
- `/roadmap-planning` — Strategic roadmap (prioritization, epics, sequencing)
- `/prioritization-advisor` — Choose the right prioritization framework (RICE, ICE, value/effort)

## Repository Intelligence

drom-flow keeps a deterministic structural map of this repository up to date automatically. The
**user** never installs, initialises, refreshes or maintains it, and must never be asked to.
Skills reach it through a documented internal command — the details live in the skill files, not
here.

For unfamiliar multi-file work, architecture analysis, debugging, impact analysis, refactoring
or review, prefer that structural map over broad source discovery, then verify what it tells you
against the actual source before acting. The `/planner`, `/architect`, `/debugger`, `/reviewer`,
`/refactorer`, `/implementer` and `/orchestrator` skills carry the details. For trivial work — a
typo, a formatting fix, a one-line config change — skip it and just make the edit. If it is
unavailable, fall back to ordinary search and read without comment.

## Token Economy

Claude tokens are the scarce resource; grok sub-agents are not. To minimize Claude usage:

- **Collapse turns** — one `spawn --manifest` for N units, never N separate spawns, and never poll in a loop
- **Stop authoring** — generate task prompts with `scripts/mk-task.sh` templates instead of writing them inline
- **Read verdicts, not artifacts** — `collect --run-id R --brief`; open an agent's `output/` only to diagnose a FAIL
- **Let grok read files** — pass paths, never paste file contents into context to describe them

Measured: turns −64%, Claude output tokens −65%, context bytes −97%, quality held.
Full guidance in `.claude/docs/token-economy.md`.

## Deep Research

For research questions, use `/df-research` — it runs on grok sub-agents, not Claude:

```bash
bash scripts/df-research.sh run "<question>" [--depth quick|deep]
```

Phases: decompose -> multi-perspective sweep (parallel, one unit uses X/Twitter search) ->
independence + contradiction audit -> draft -> adversarial critics (parallel) -> surgical patch ->
cite-check gate.

- **Read `reports/df-research-audit.json` and the report's Answer section — not the phase bodies.**
- The audit is a hard gate: >=20 distinct sources, every citation resolves, >=1 contradiction
  cluster, zero uncited claims, social sources labelled, and no unsupported or fabricated citations.
- If the audit fails, the report is not shippable. Say so plainly rather than presenting it anyway.
- X/social sources are signal, not evidence — they are tagged and counted separately.

Full guidance in `.claude/docs/df-research.md`.

## Updating drom-flow

**Prerequisite:** If the drom-flow source directory does not contain `init.sh` (e.g., after downloading a new ZIP), generate scripts first by running `claude "Read start-here.md and follow the setup instructions"` in the drom-flow directory.

When the user asks to update drom-flow (e.g., "update to latest drom-flow", "update drom-flow"):

1. **Find the drom-flow source** — read `.claude/.state/drom-flow.conf` to get `DROM_FLOW_HOME`
2. **Pull latest** — run `git -C "$DROM_FLOW_HOME" pull` to fetch the newest version
3. **Preview changes** — run `bash "$DROM_FLOW_HOME/init.sh.txt" --check .` and show the user what would change
4. **Apply the update** — run `bash "$DROM_FLOW_HOME/init.sh.txt" --update .`

```bash
# Read the saved drom-flow location
source .claude/.state/drom-flow.conf

# Pull latest
git -C "$DROM_FLOW_HOME" pull

# Check what would change (dry run)
bash "$DROM_FLOW_HOME/init.sh.txt" --check .

# Apply the update
bash "$DROM_FLOW_HOME/init.sh.txt" --update .
```

`--update` overwrites drom-flow managed files (hooks, skills, workflows, settings) but **never touches** project-specific files: `CLAUDE.md`, `context/MEMORY.md`, `context/DECISIONS.md`, `context/CONVENTIONS.md`, `scripts/orchestrate.sh`. Plans in `drom-plans/` and reports are also preserved.

## Uninstalling drom-flow

**Prerequisite:** If the drom-flow source directory does not contain `init.sh` (e.g., after downloading a new ZIP), generate scripts first by running `claude "Read start-here.md and follow the setup instructions"` in the drom-flow directory.

When the user asks to uninstall drom-flow:

1. **Find the drom-flow source** — read `.claude/.state/drom-flow.conf` to get `DROM_FLOW_HOME`
2. **Preview** — run `bash "$DROM_FLOW_HOME/init.sh.txt" --uninstall-check .`
3. **Uninstall** — run `bash "$DROM_FLOW_HOME/init.sh.txt" --uninstall .`

`--uninstall` removes all drom-flow managed files (hooks, skills, workflows, settings, VERSION) and cleans up empty directories and gitignore entries. It **never removes** user-owned files: `CLAUDE.md`, `context/MEMORY.md`, `context/DECISIONS.md`, `context/CONVENTIONS.md`, `scripts/orchestrate.sh`, or any plans in `drom-plans/`.
