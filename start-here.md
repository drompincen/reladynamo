# Start Here

This repository contains **no executable scripts** — only Java, text and configuration. Every script
is stored as `<name>.sh.txt` next to where it runs, and the runnable `.sh` files are generated locally
(and gitignored). This keeps ZIP downloads and clones clean behind corporate proxies and policies.

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

That is a single-file Java program — no compilation step, no bash or Python needed to run it. It finds
every `*.sh.txt`, writes the script beside it with LF line endings, and marks it executable where the
file system supports it. It is idempotent: re-running it only rewrites scripts whose source changed.

| Source | Generates | Used by |
|---|---|---|
| `scripts/*.sh.txt` | `scripts/*.sh` | the closed-loop gate (`check.sh`, `spec-drift.sh`) and the agent-fleet tooling |
| `.claude/hooks/*.sh.txt` | `.claude/hooks/*.sh` | Claude Code hooks registered in `.claude/settings.json` |
| `.claude/skills/web-quality-audit/scripts/analyze.sh.txt` | `analyze.sh` | the `/web-quality-audit` skill |

**Without a JDK on the path** (bash only):

```bash
find . -name '*.sh.txt' -not -path '*/target/*' -not -path './.git/*' | while read -r f; do
  tr -d '\r' < "$f" > "${f%.txt}" && chmod +x "${f%.txt}"
done
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

The `.txt` file is the source of truth; the generated script is not tracked.

- **Edit the `.sh.txt`**, then run `java scripts/GenerateScripts.java` to regenerate.
- **Edited the `.sh` in place?** Run `java scripts/GenerateScripts.java --adopt` to copy it back
  over its `.txt` source before committing — otherwise the change never reaches git.
- **Adding a script:** create `<name>.sh.txt` and generate. Do not commit the `.sh`.
- **Check for drift:** `java scripts/GenerateScripts.java --check` exits non-zero if any script is
  missing or differs from its source.
