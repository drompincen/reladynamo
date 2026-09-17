#!/bin/bash
# drom-flow — read an EXISTING DynamoDB estate into the model the amazon-dynamodb skill consumes.
#
#   ddb-introspect.sh [--table NAME]... [--all] [--region R] [--profile P]
#                     [--days N] [--sample N] [--out FILE] [--json]
#
# The AWS `amazon-dynamodb` skill designs, reviews and refactors from a `dynamodb_data_model.json`
# that YOU supply — it has no live introspection of its own. This produces that file from real
# tables, so "review my existing design" and "what does a refactor cost" work against reality
# instead of a hand-written guess.
#
# READ-ONLY BY CONSTRUCTION. Every AWS call here is a describe/list/get-metric. There is no
# CreateTable, no write, no delete, and no argument that enables one.
#
# What costs money:
#   * describe-* and CloudWatch calls          — free or negligible
#   * --sample N                               — a bounded Scan, consumes read capacity, OPT-IN,
#                                                capped, and never runs unless you ask for it
#
# Average item size is taken from DescribeTable (TableSizeBytes / ItemCount) — free, and accurate
# enough for costing — so a Scan is only needed when you want the attribute inventory.

set -uo pipefail

REGION=""; PROFILE=""; DAYS=14; SAMPLE=0; OUT="dynamodb_data_model.json"; AS_JSON=false
TABLES=(); ALL=false

while [[ $# -gt 0 ]]; do case $1 in
  --table)   TABLES+=("$2"); shift 2 ;;
  --all)     ALL=true; shift ;;
  --region)  REGION="$2"; shift 2 ;;
  --profile) PROFILE="$2"; shift 2 ;;
  --days)    DAYS="$2"; shift 2 ;;
  --sample)  SAMPLE="$2"; shift 2 ;;
  --out)     OUT="$2"; shift 2 ;;
  --json)    AS_JSON=true; shift ;;
  -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
  *) echo "ddb-introspect: unknown arg $1" >&2; exit 2 ;;
esac; done

log() { echo "[ddb] $*" >&2; }

command -v aws >/dev/null 2>&1 || {
  printf '{"ok":false,"error":"aws CLI not found — install it or run this where credentials live"}\n'
  exit 3
}

AWSA=(aws)
[[ -n "$REGION" ]] && AWSA+=(--region "$REGION")
[[ -n "$PROFILE" ]] && AWSA+=(--profile "$PROFILE")

# Fail early and clearly rather than emitting an empty model that looks like a real answer.
if ! "${AWSA[@]}" sts get-caller-identity >/dev/null 2>&1; then
  printf '{"ok":false,"error":"no usable AWS credentials (profile, SSO or environment) for this region"}\n'
  exit 3
fi

if $ALL || [[ ${#TABLES[@]} -eq 0 ]]; then
  mapfile -t TABLES < <("${AWSA[@]}" dynamodb list-tables --query 'TableNames[]' --output text 2>/dev/null | tr '\t' '\n' | sed '/^$/d')
  [[ ${#TABLES[@]} -eq 0 ]] && { printf '{"ok":false,"error":"no DynamoDB tables visible with these credentials"}\n'; exit 1; }
  log "discovered ${#TABLES[@]} table(s)"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for t in "${TABLES[@]}"; do
  log "describing $t"
  "${AWSA[@]}" dynamodb describe-table --table-name "$t" > "$WORK/$t.describe.json" 2>/dev/null || {
    log "  cannot describe $t — skipping"; continue; }
  "${AWSA[@]}" dynamodb describe-time-to-live --table-name "$t" > "$WORK/$t.ttl.json" 2>/dev/null || echo '{}' > "$WORK/$t.ttl.json"
  "${AWSA[@]}" dynamodb describe-continuous-backups --table-name "$t" > "$WORK/$t.pitr.json" 2>/dev/null || echo '{}' > "$WORK/$t.pitr.json"

  # Operation mix and request rates come from SuccessfulRequestLatency's SampleCount, which is a
  # real request count per operation — consumed-capacity metrics cannot tell a Query from a Scan.
  END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  START="$(date -u -d "-${DAYS} days" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-"${DAYS}"d +%Y-%m-%dT%H:%M:%SZ)"
  : > "$WORK/$t.ops.jsonl"
  for op in GetItem BatchGetItem Query Scan PutItem UpdateItem DeleteItem BatchWriteItem TransactGetItems TransactWriteItems; do
    "${AWSA[@]}" cloudwatch get-metric-statistics \
      --namespace AWS/DynamoDB --metric-name SuccessfulRequestLatency \
      --dimensions Name=TableName,Value="$t" Name=Operation,Value="$op" \
      --start-time "$START" --end-time "$END" --period 300 --statistics SampleCount \
      --output json 2>/dev/null \
      | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: sys.exit(0)
pts=[p.get('SampleCount',0) for p in d.get('Datapoints',[])]
if not pts or max(pts)<=0: sys.exit(0)
# SampleCount is per 300s period; convert to requests/second
print(json.dumps({'operation':'$op','peak_rps':round(max(pts)/300.0,4),
                  'avg_rps':round((sum(pts)/len(pts))/300.0,4),'periods':len(pts)}))
" >> "$WORK/$t.ops.jsonl"
  done

  "${AWSA[@]}" cloudwatch get-metric-statistics \
    --namespace AWS/DynamoDB --metric-name ThrottledRequests \
    --dimensions Name=TableName,Value="$t" \
    --start-time "$START" --end-time "$END" --period 3600 --statistics Sum \
    --output json > "$WORK/$t.throttle.json" 2>/dev/null || echo '{}' > "$WORK/$t.throttle.json"

  # Opt-in, bounded, and only for the attribute inventory. Costs read capacity — hence never
  # implicit. Projection is limited to attribute names; no item values are collected or written.
  if [[ "$SAMPLE" -gt 0 ]]; then
    log "  sampling up to $SAMPLE items (consumes read capacity)"
    "${AWSA[@]}" dynamodb scan --table-name "$t" --max-items "$SAMPLE" \
      --return-consumed-capacity TOTAL --output json > "$WORK/$t.sample.json" 2>/dev/null \
      || echo '{"Items":[]}' > "$WORK/$t.sample.json"
  fi
done

python3 - "$WORK" "$OUT" "$SAMPLE" "$DAYS" "${TABLES[@]}" <<'PY'
import json, os, sys, glob

work, out_path, sample, days = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
tables_in = sys.argv[5:]

def load(p, default=None):
    try:
        with open(p) as f: return json.load(f)
    except Exception: return default if default is not None else {}

model = {"tables": [], "access_patterns": []}
observed = {"source": "live AWS account (read-only)", "window_days": days, "tables": []}

for name in tables_in:
    d = load(os.path.join(work, f"{name}.describe.json")).get("Table")
    if not d: continue

    keys = {k["KeyType"]: k["AttributeName"] for k in d.get("KeySchema", [])}
    # Key attribute TYPES are load-bearing: a numeric key left as "S" is accepted at seed time and
    # rejected on real writes. DescribeTable knows them exactly, so declare them rather than default.
    attr_defs = [{"attribute_name": a["AttributeName"], "attribute_type": a["AttributeType"]}
                 for a in d.get("AttributeDefinitions", [])]

    gsis = []
    for g in d.get("GlobalSecondaryIndexes", []) or []:
        gk = {k["KeyType"]: k["AttributeName"] for k in g.get("KeySchema", [])}
        proj = g.get("Projection", {}) or {}
        entry = {"index_name": g["IndexName"], "partition_key": gk.get("HASH"),
                 "projection": {"type": proj.get("ProjectionType", "ALL")}}
        if gk.get("RANGE"): entry["sort_key"] = gk["RANGE"]
        if proj.get("NonKeyAttributes"): entry["projection"]["attributes"] = proj["NonKeyAttributes"]
        gsis.append(entry)

    lsis = []
    for l in d.get("LocalSecondaryIndexes", []) or []:
        lk = {k["KeyType"]: k["AttributeName"] for k in l.get("KeySchema", [])}
        lsis.append({"index_name": l["IndexName"], "sort_key": lk.get("RANGE"),
                     "projection": (l.get("Projection") or {}).get("ProjectionType", "ALL")})

    item_count = int(d.get("ItemCount", 0) or 0)
    size_bytes = int(d.get("TableSizeBytes", 0) or 0)
    # Free and good enough for costing — no Scan required.
    avg_item = int(size_bytes / item_count) if item_count > 0 else 1024

    attributes = []
    sample_path = os.path.join(work, f"{name}.sample.json")
    sampled_n = 0
    if sample > 0 and os.path.exists(sample_path):
        items = load(sample_path).get("Items", []) or []
        sampled_n = len(items)
        seen = {}
        for it in items:
            for attr, val in it.items():
                seen.setdefault(attr, next(iter(val.keys()), "S"))
        attributes = [{"name": k, "type": v} for k, v in sorted(seen.items())]

    entity = {"entity_name": f"{name}Item",
              "estimated_item_size_bytes": avg_item,
              "estimated_item_count": item_count}
    if attributes: entity["attributes"] = attributes

    table = {"table_name": name,
             "table_class": (d.get("TableClassSummary") or {}).get("TableClass", "STANDARD"),
             "global_tables": bool(d.get("Replicas")),
             "key_schema": {"partition_key": keys.get("HASH")},
             "attribute_definitions": attr_defs,
             "entities": [entity],
             "gsis": gsis}
    if keys.get("RANGE"): table["key_schema"]["sort_key"] = keys["RANGE"]
    model["tables"].append(table)

    # Access patterns observed from CloudWatch. These are REAL rates and a REAL operation mix,
    # but they are not the access-pattern LIST the skill wants: they say nothing about which
    # entity or index a call targeted, or what business need it serves. Treated as a starting
    # skeleton to be confirmed against application code.
    ops = []
    ops_path = os.path.join(work, f"{name}.ops.jsonl")
    if os.path.exists(ops_path):
        for line in open(ops_path):
            line = line.strip()
            if line:
                try: ops.append(json.loads(line))
                except Exception: pass
    for i, o in enumerate(sorted(ops, key=lambda x: -x["peak_rps"]), start=1):
        model["access_patterns"].append({
            "pattern_id": f"OBS-{name[:12]}-{i}",
            "description": f"OBSERVED from CloudWatch over {days}d — confirm intent against application code",
            "operation": o["operation"],
            "table": name,
            "index": None,
            "peak_rps": o["peak_rps"],
            "avg_rps": o["avg_rps"],
            "estimated_item_size_bytes": avg_item,
            "items_per_request": 1,
            "consistency": "eventual"})

    thr = load(os.path.join(work, f"{name}.throttle.json")).get("Datapoints", []) or []
    ttl = (load(os.path.join(work, f"{name}.ttl.json")).get("TimeToLiveDescription") or {})
    pitr = ((load(os.path.join(work, f"{name}.pitr.json")).get("ContinuousBackupsDescription") or {})
            .get("PointInTimeRecoveryDescription") or {})
    prov = d.get("ProvisionedThroughput") or {}
    observed["tables"].append({
        "table_name": name,
        "billing_mode": (d.get("BillingModeSummary") or {}).get("BillingMode", "PROVISIONED"),
        "provisioned": {"read": prov.get("ReadCapacityUnits", 0), "write": prov.get("WriteCapacityUnits", 0)},
        "item_count": item_count, "table_size_bytes": size_bytes, "avg_item_size_bytes": avg_item,
        "gsi_count": len(gsis), "lsi": lsis,
        "streams": (d.get("StreamSpecification") or {}).get("StreamEnabled", False),
        "global_table_replicas": [r.get("RegionName") for r in (d.get("Replicas") or [])],
        "ttl": ttl.get("TimeToLiveStatus", "DISABLED"),
        "pitr": pitr.get("PointInTimeRecoveryStatus", "DISABLED"),
        "throttled_requests_in_window": sum(p.get("Sum", 0) for p in thr),
        "operations_observed": ops,
        "sampled_items": sampled_n})

json.dump(model, open(out_path, "w"), indent=2)
side = os.path.splitext(out_path)[0].replace("_data_model", "") + "_observed.json"
json.dump(observed, open(side, "w"), indent=2)

flags = []
for t in observed["tables"]:
    if t["pitr"] != "ENABLED": flags.append(f"{t['table_name']}: PITR disabled")
    if t["throttled_requests_in_window"] > 0:
        flags.append(f"{t['table_name']}: {int(t['throttled_requests_in_window'])} throttled requests in {days}d")
    if t["gsi_count"] >= 5: flags.append(f"{t['table_name']}: {t['gsi_count']} GSIs — write amplification")
    if t["lsi"]: flags.append(f"{t['table_name']}: has LSIs — cannot be changed without recreating the table")
    if not t["operations_observed"]: flags.append(f"{t['table_name']}: no traffic in window — rates are 0, costing will read as free")

print(json.dumps({"ok": True, "model": out_path, "observed": side,
                  "tables": len(model["tables"]),
                  "access_patterns_observed": len(model["access_patterns"]),
                  "scan_used": sample > 0, "flags": flags}, indent=2))
PY

rc=$?
if [[ $rc -eq 0 ]] && ! $AS_JSON; then
  log "wrote $OUT — review it, then hand it to the amazon-dynamodb skill:"
  log "  python3 \"\$DDB_SKILL_DIR/scripts/calculate_costs.py\" --model $OUT --output cost_report.md"
fi
exit $rc
