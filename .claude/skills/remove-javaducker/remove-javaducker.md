---
name: remove-javaducker
description: Remove JavaDucker companion tool configuration from this project
user-invocable: true
---

# Remove JavaDucker

You are removing the JavaDucker companion tool integration from this project.

## Removal Process

1. **Stop watch** — if the `javaducker_watch` MCP tool is available, call it with `action: "stop"` to stop any active file watchers. Ignore errors if the server is not running.

1.5. **Check if shared** — read `.claude/.state/javaducker.conf` and check if the `JAVADUCKER_DB` path points outside the current project directory. If so, this is a shared instance:
   - Do NOT stop the server (other projects may be using it)
   - Only remove the local config file and MCP registration
   - Skip deleting `.claude/.javaducker/` if it does not exist locally (shared instances store data in the parent project)

2. **Remove config** — delete `.claude/.state/javaducker.conf` if it exists.

3. **Remove project-local data** — delete `.claude/.javaducker/` if it exists. This directory contains the per-project DuckDB database and intake folder created by the auto-start lifecycle.

4. **Clean MCP registration** — read `.mcp.json` in the project root:
   - If it contains only the `javaducker` entry, delete the entire `.mcp.json` file
   - If it contains other MCP servers too, remove only the `javaducker` key from `mcpServers` and write the file back

5. **Confirm removal** — print what was removed:
   ```
   JavaDucker removed:
     Deleted: .claude/.state/javaducker.conf
     Deleted: .claude/.javaducker/ (project-local data)
     Cleaned: .mcp.json (javaducker entry removed)
   ```
   Only list items that actually existed and were removed.

## What is preserved

- The JavaDucker installation itself (at its own root directory) is untouched
- drom-flow hooks and skills gracefully degrade — they check for the config file and skip JavaDucker features when it's absent

## To re-add later

Run `/add-javaducker` with the JavaDucker root path.
