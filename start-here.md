# Start Here

This repository contains **no scripts** — only Java, markdown and configuration. Every script is stored
as a code block in a `script-instructions/*.md` file, under a `## <path>` heading naming where it runs,
and the runnable `.sh` files are generated locally (and gitignored). This keeps ZIP downloads and
clones clean behind corporate proxies and policies.

Using Claude Code? Run `claude "Read start-here.md and follow the setup instructions"` in this
directory and it will do steps 1–3 for you.

## Prerequisites

- **JDK 17+** to build and run the tests (DynamoDB Local, used in-process by the tests, needs Java 17).
  The adapter itself compiles to Java 11 bytecode and targets a Java 11 runtime.
- **Maven 3.9+**
- **bash** (Linux, macOS, WSL or Git Bash) to run the generated scripts

## 1. Generate the scripts

From the repository root:

```bash
java scripts/GenerateScripts.java
```

That is a single-file Java program — no compilation step, no bash or Python needed to run it. It reads
every `script-instructions/*.md`, writes each `## <path>` section's code block to that path with LF
line endings, and marks it executable where the file system supports it. It is idempotent: re-running
it only rewrites scripts whose source changed. Headings that leave the repository are refused.

| Instruction file | Generates | Used by |
|---|---|---|
| `gate-scripts.md` | `scripts/check.sh`, `spec-drift.sh`, `orchestrate.sh` | the closed-loop gate (CI runs `check.sh`) |
| `hook-scripts.md`, `indexing-hook-scripts.md` | `.claude/hooks/*.sh` | Claude Code hooks registered in `.claude/settings.json` |
| `grok-fleet-script.md`, `codex-fleet-script.md`, `fleet-scripts.md`, `audit-scripts.md`, `limit-scripts.md` | `scripts/*.sh` | the agent-fleet tooling |
| `research-scripts.md` | `scripts/df-research*.sh` | the `/df-research` pipeline |
| `skill-scripts.md` | `scripts/add-skill.sh`, `.claude/skills/web-quality-audit/scripts/analyze.sh` | skill install and `/web-quality-audit` |
| `ddb-scripts.md` | `scripts/ddb-introspect.sh` | DynamoDB table introspection |

**Without a JDK on the path**, copy each code block to the path in its heading by hand, or with awk:

```bash
awk '/^## / && !f { p = $2; next }
     /^````/ { if (f) { f = 0; close(p); p = "" } else if (p) { f = 1; system("mkdir -p \"$(dirname " p ")\""); printf "" > p } next }
     f { print > p }' script-instructions/*.md && find . -name '*.sh' -not -path './.git/*' -exec chmod +x {} +
```

## 2. Build

```bash
mvn clean install
```

`install`, not just `test`: the demos are standalone Maven projects that resolve the adapter from your
local repository, so they build against whatever was installed last.

## 3. Run the gate

```bash
bash scripts/check.sh
```

Eight gates — adapter build, three demos, Java 11 bytecode floor, spec drift, licence scope, and the
H2-vs-DynamoDB differential suite. The result is written to `reports/check-latest.json`.

## 4. Try a demo

```bash
cd demos/03-car-classifier/project && mvn clean test
```

Start with the car classifier: its *rules* are bitemporal, so the same unchanged car classifies
differently depending on the date you ask. See [`demos/README.md`](demos/README.md).

## Changing a script

The markdown is the source of truth; the generated script is not tracked.

- **Edit the code block** in `script-instructions/*.md`, then run `java scripts/GenerateScripts.java`.
- **Edited the `.sh` in place?** Run `java scripts/GenerateScripts.java --adopt` to copy it back
  into its code block before committing — otherwise the change never reaches git.
- **Adding a script:** add a `## <path>` heading and a fenced block (four backticks, so the script
  may itself contain triple backticks) to the fitting instruction file, keeping it under 500 lines,
  and generate. Do not commit the `.sh`.
- **Check for drift:** `java scripts/GenerateScripts.java --check` exits non-zero if any script is
  missing or differs from its source.
