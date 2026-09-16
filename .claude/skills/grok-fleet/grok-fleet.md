---
name: grok-fleet
description: Fan out work to grok CLI sub-agents in parallel from WSL, with filesystem-based progress, monitoring, and stop control — combined with Claude sub-agents
user-invocable: true
---

# Grok Fleet

You orchestrate **grok CLI sub-agents** alongside Claude's own sub-agents. Grok agents are real
`grok.exe` processes (the CLI, **not** the API — no key, it uses the user's `grok login` session),
driven entirely through files: `task.md` in, `status.json` / `PROGRESS.md` / `result.json` / `output/` out.

## Prerequisite — always run first

```bash
bash scripts/grok-fleet.sh doctor --live
```

Exit 0 means grok is reachable and the project layout works. If `win_visible` fails, the project is
on a WSL-native path and **grok cannot see it** — grok.exe is a Windows process, so the project must
live under `/mnt/<drive>/…`. Say so plainly and stop; no fan-out is possible.

## Routing — which engine gets the work

**Give it to Claude sub-agents when the work needs:**
- Repo context, project memory, hooks, or conventions
- Coherent edits spanning multiple files
- Final integration, merging, and adjudication
- Any write to the real working tree

**Give it to grok sub-agents when the work is:**
- Wide and independent — many units that don't touch each other
- Well-specified with a verifiable output (a marker, a schema, a file that must exist)
- Breadth research, per-file mechanical transforms, test generation, N-way exploration
- A **cross-model second opinion** on Claude's own output

**Hard rule:** never let both engines write the same files in the same phase. Grok writes only inside
its own `output/`; Claude reads those and integrates. This is what makes the fan-out safe.

## Running a fleet

```bash
# one agent
bash scripts/grok-fleet.sh spawn --run-id RUN --agent-id alpha --task-file /path/task.md

# structured verdict (cross-model review)
bash scripts/grok-fleet.sh spawn --run-id RUN --agent-id reviewer \
  --task-file review.md --schema verdict.schema.json

# watch (safe to call repeatedly)
bash scripts/grok-fleet.sh status --run-id RUN

# stop one, or everything
bash scripts/grok-fleet.sh stop --run-id RUN --agent-id alpha
bash scripts/grok-fleet.sh stop --all

# gather into one report (non-zero exit if any agent failed)
bash scripts/grok-fleet.sh collect --run-id RUN
```

For parallel fan-out, background each `spawn` and `wait`, respecting `GROK_MAX_PARALLEL` (default 4).

## Writing a good task.md

Each task must be self-contained — grok has **no** conversation context and cannot see Claude's memory.
Include: the goal, the exact output file expected, and a verifiable success marker. The fleet preamble
(auto-appended) already tells the agent to write `PROGRESS.md` checkpoints and stay inside its directory.

Verify outputs by content, not by exit code alone — the runner already downgrades an agent to `FAILED`
when it exits 0 but produced nothing.

## Monitoring

`streaming-json` carries **no tool-call events** — only token deltas and a final `end`. So progress
comes from two places:
- `PROGRESS.md` — checkpoints the agent writes itself (the real signal)
- `stream.jsonl` mtime — liveness; older than `--stall-secs` (180) marks the agent `STALLED`

## Budget

Roughly **$0.02–0.10 per agent** depending on turns. `GROK_BUDGET_USD` (default 5) caps a run;
per-agent cost is recorded in `status.json` and totalled by `status` and `collect`.

## Environment

| Var | Default | Purpose |
|---|---|---|
| `GROK_BIN` | auto-resolved | path to `grok.exe` |
| `GROK_MODEL` | `grok-4.5` | pinned model — validate with `grok models`; the name in `modelUsage` is **not** a selectable id |
| `GROK_MAX_PARALLEL` | 4 | concurrency gate |
| `GROK_BUDGET_USD` | 5 | run budget cap |
| `GROK_FLEET_ROOT` | `<project>/.claude/.grok-fleet` | control plane |

## Self-test

`bash scripts/grok-fleet.sh verify` runs six gates — feasibility, work_done, monitor, stop, combined,
control — and writes `reports/grok-verify.json`. The `combined` gate requires **you** (Claude) to read
the grok outputs and write `reports/grok-claude-merge.md` citing each agent's marker; that is
deliberate, since it proves both engines actually cooperated.

## The codex backend

`scripts/codex-fleet.sh` is the same fleet with a different runner, and takes the same
subcommands: `doctor | spawn | status | stop | collect | resume | clean`. The on-disk protocol is
identical, so `collect --run-id R --brief` reads the same either way.

```bash
bash scripts/codex-fleet.sh spawn --manifest <manifest.json>
bash scripts/codex-fleet.sh collect --run-id R --brief
```

Pick by what the machine has and what the work needs:

- **codex** — native binary, no Windows path translation, real activity events in `status`, and
  the sandbox is *enforced*: an agent can read the repository and cannot write outside its own
  directory. Accounting is in tokens.
- **grok** — Windows process driven from WSL, so the control plane must stay on `/mnt/<drive>`.
  Accounting is in USD, and there is a budget cap.

**Both are optional.** If a runner is not installed, or is turned off with `CODEX_DISABLE=1` /
`GROK_DISABLE=1`, `doctor` exits 0 reporting `available: false` and `spawn` refuses with
structured JSON and exit 3. Say nothing about it and do the work yourself.

Repository writes: codex agents write only inside their own directory by default. An agent that
must edit the repository needs `--write-repo`, which forces parallelism to 1 — never fan out
repository-writing agents.

## Choosing a runner — use the front door

Do not pick a backend by hand. `scripts/fleet.sh` decides from measured capability and says why:

```bash
bash scripts/fleet.sh route <kind>                       # backend + reason, as JSON
bash scripts/fleet.sh spawn --kind <kind> --manifest M   # routes, then delegates
bash scripts/fleet.sh collect --run-id R --brief         # finds the run in either control plane
```

`<kind>` is one of `research|web|social`, `bulk|breadth|sweep`,
`audit|review|analysis|code|author|implement|refactor|test`, or anything else for the default.

The one hard capability difference: **codex's sandbox has no network** — verified, DNS resolution
fails inside it. So web or X research can only be grok. Everything repository-grounded prefers
codex, whose sandbox *enforces* that an agent cannot write outside its own directory. Availability
always overrides preference, and with neither runner installed the command refuses with exit 3 —
at which point do the work yourself and say nothing about it.
