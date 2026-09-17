#!/bin/bash
# Re-dispatch an astra (codex gpt-6-astra) run once the usage limit resets.
#
# codex reports its reset time in the error text ("try again at 2:53 PM") and nothing exposes a
# live quota meter, so this polls rather than predicts: try, and if the failure is a usage limit,
# wait and try again. Any other failure is a real failure and stops the loop — retrying a broken
# task forever is how a fleet burns a night producing nothing.
#
#   astra-retry.sh <manifest> <run_id> <agent_id> [interval_secs] [max_tries]
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MF="$1"; RUN="$2"; AGENT="$3"; INT="${4:-900}"; MAX="${5:-24}"
ST="$REPO_ROOT/.claude/.fleet/$RUN/agents/$AGENT/status.json"
EV="$REPO_ROOT/.claude/.fleet/$RUN/agents/$AGENT/events.jsonl"

for i in $(seq 1 "$MAX"); do
  echo "[astra-retry] attempt $i/$MAX $(date -Is)"
  CODEX_MODEL=gpt-6-astra CODEX_AGENT_TIMEOUT=7200 \
    bash "$REPO_ROOT/scripts/codex-fleet.sh" spawn --manifest "$MF" >/dev/null 2>&1

  state="$(python3 -c "import json;print(json.load(open('$ST'))['state'])" 2>/dev/null || echo UNKNOWN)"
  if [[ "$state" != "FAILED" ]]; then
    echo "[astra-retry] state=$state — done"; exit 0
  fi
  if ! grep -q "usage limit" "$EV" 2>/dev/null; then
    echo "[astra-retry] FAILED for a reason other than the usage limit — not retrying"; exit 1
  fi
  echo "[astra-retry] usage limit still in force; sleeping ${INT}s"
  sleep "$INT"
done
echo "[astra-retry] gave up after $MAX attempts"; exit 1
