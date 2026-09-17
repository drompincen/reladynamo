#!/bin/bash
# drom-flow — resume support for the grok fleet.
# Sourced by grok-fleet.sh. Provides: drain (detached runner), checkpoint (compact
# resume record), resume (reconcile + re-dispatch).
#
# Premise: Claude tokens are finite, grok's are not. Claude is therefore the
# interruptible component — in-flight grok work must be able to finish without it,
# and a cold Claude session must resume from a tiny amount of state.

RESUME_MAX_BYTES="${RESUME_MAX_BYTES:-2048}"

# --- drain: run a manifest to completion detached from this shell ---------------
# Survives the Claude session ending, so a fan-out is never stranded.
cmd_drain() {
  local mf=""
  while [[ $# -gt 0 ]]; do case $1 in --manifest) mf="$2"; shift 2 ;; *) shift ;; esac; done
  [[ -f "$mf" ]] || die "drain needs --manifest <file>"
  local run_id; run_id="$(python3 -c "import json;print(json.load(open('$mf'))['run_id'])")"
  mkdir -p "$FLEET_ROOT/$run_id"
  local logf="$FLEET_ROOT/$run_id/drain.log"
  nohup setsid bash "$REPO_ROOT/scripts/grok-fleet.sh" spawn --manifest "$mf" \
        > "$logf" 2>&1 < /dev/null &
  disown 2>/dev/null
  echo "{\"run_id\":\"$run_id\",\"detached\":true,\"log\":\"$logf\"}"
  log "drain: run $run_id detached — it will finish without Claude"
}

# --- checkpoint: the entire cost of resuming --------------------------------------
cmd_checkpoint() {
  local run_id="" goal="" plan="" chapter=""
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --goal) goal="$2"; shift 2 ;;
    --plan) plan="$2"; shift 2 ;;
    --chapter) chapter="$2"; shift 2 ;;
    *) shift ;;
  esac; done
  [[ -n "$run_id" ]] || die "checkpoint needs --run-id"
  local rd="$FLEET_ROOT/$run_id"; mkdir -p "$rd"
  [[ -n "$goal"    ]] && echo "$goal"    > "$rd/.goal"
  [[ -n "$plan"    ]] && echo "$plan"    > "$rd/.plan"
  [[ -n "$chapter" ]] && echo "$chapter" > "$rd/.chapter"

  python3 - "$rd" "$RESUME_MAX_BYTES" <<'PY'
import json,os,sys
rd,cap=sys.argv[1],int(sys.argv[2])
def rd_file(n,d=''):
    p=os.path.join(rd,n)
    return open(p).read().strip() if os.path.exists(p) else d
base=os.path.join(rd,'agents')
done=[];pend=[];run=[]
if os.path.isdir(base):
    for a in sorted(os.listdir(base)):
        try: st=json.load(open(os.path.join(base,a,'status.json')))['state']
        except Exception: st='UNKNOWN'
        (done if st=='DONE' else run if st=='RUNNING' else pend).append(f"{a}:{st}")
L=[]
L.append(f"# RESUME — {os.path.basename(rd)}")
g=rd_file('.goal');  L.append(f"goal: {g}") if g else None
p=rd_file('.plan');  L.append(f"plan: {p}") if p else None
c=rd_file('.chapter');L.append(f"chapter: {c}") if c else None
L.append(f"done({len(done)}): {', '.join(done) or '-'}")
L.append(f"running({len(run)}): {', '.join(run) or '-'}")
L.append(f"pending({len(pend)}): {', '.join(pend) or '-'}")
L.append(f"next: bash scripts/grok-fleet.sh resume --run-id {os.path.basename(rd)}")
L.append("note: DONE units are never re-run. Read outputs only for FAILED units.")
out='\n'.join(L)+'\n'
if len(out.encode())>cap:                      # hard cap — resuming must stay cheap
    out=out.encode()[:cap-20].decode('utf-8','ignore')+"\n...(truncated)\n"
tmp=os.path.join(rd,'.RESUME.tmp')
open(tmp,'w').write(out); os.replace(tmp,os.path.join(rd,'RESUME.md'))
print(out,end='')
PY
}

# --- resume: reconcile reality, re-dispatch only what is genuinely incomplete -----
cmd_resume() {
  local run_id=""
  while [[ $# -gt 0 ]]; do case $1 in --run-id) run_id="$2"; shift 2 ;; *) shift ;; esac; done
  [[ -n "$run_id" ]] || die "resume needs --run-id"
  local rd="$FLEET_ROOT/$run_id" base="$FLEET_ROOT/$run_id/agents"
  [[ -d "$base" ]] || die "no such run: $run_id"

  # Reconcile: trust on-disk results over the recorded state. An agent whose process
  # is gone but whose result is complete really did finish; one with neither is
  # INTERRUPTED and must be redone.
  local recovered=0 interrupted=0
  for d in "$base"/*; do [[ -d "$d" ]] || continue
    local s; s="$(get_state "$d")"
    [[ "$s" == RUNNING ]] || continue
    local pid alive=0
    pid="$(python3 -c "import json;print(json.load(open('$d/pid'))['wsl_pid'])" 2>/dev/null)"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && alive=1
    (( alive )) && continue
    if [[ -s "$d/result.json" ]] && [[ -n "$(ls -A "$d/output" 2>/dev/null)" ]]; then
      local c; c="$(python3 -c "import json;print(json.load(open('$d/result.json')).get('total_cost_usd',0))" 2>/dev/null || echo 0)"
      set_status "$d" DONE "\"recovered\":true,\"cost_usd\":${c:-0}"; recovered=$(( recovered + 1 ))
    else
      set_status "$d" INTERRUPTED "\"reason\":\"process gone, no result\""; interrupted=$(( interrupted + 1 ))
    fi
  done
  log "resume: recovered=$recovered interrupted=$interrupted"

  # Re-dispatch. spawn is idempotent — DONE agents are skipped, never re-billed.
  local mf="$rd/run.json"
  if [[ -f "$mf" ]]; then
    cmd_spawn_manifest "$mf"
  else
    log "resume: no stored manifest; nothing to re-dispatch automatically"
  fi
  cmd_checkpoint --run-id "$run_id" >/dev/null
  cmd_collect --run-id "$run_id" --brief
}
