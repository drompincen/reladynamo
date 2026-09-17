package io.reladynamo.ddb.exec;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraDatedObjectFactory;
import com.gs.fw.common.mithra.MithraObjectFactory;
import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.attribute.TimestampAttribute;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.finder.asofop.AsOfEqOperation;
import io.reladynamo.core.bridge.MithraDataFactory;
import io.reladynamo.core.bridge.MithraDataPopulator;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.FilterExpression;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.OrderMode;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.ReladynamoResidualEvaluationException;
import io.reladynamo.core.plan.ReladynamoUnplannableOperationException;
import io.reladynamo.core.plan.ResidualPredicate;
import io.reladynamo.core.plan.TypedNumericResidual;
import io.reladynamo.core.plan.eval.QueryPlanInterpreter;
import io.reladynamo.ddb.codec.ItemCodec;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a {@link QueryPlan} into DynamoDB reads and decoded rows.
 *
 * <p>{@link PlanKind#EMPTY} issues no call. Query and scan pages are followed until
 * {@code LastEvaluatedKey} is exhausted. {@link PlanKind#QUERY_FAN_OUT} of same-shape
 * Query parts is one PartiQL {@code ExecuteStatement} with {@code IN} (chunked at 50) —
 * Reladomo's {@code IN} of parent ids is one find, and must not become one billed
 * {@code query} per value. Residual {@code Operation.matches} is evaluated against a
 * populated {@code MithraDataObject} (wrapped as the generated domain type), never the
 * decoded {@code Map}. A null {@code Boolean} is not a pass — it is a nullable return,
 * and unboxing it NPEs. Relationship residuals that cannot be evaluated below the seam
 * refuse with {@code RELADYNAMO-RESIDUAL-001} rather than silently matching false.
 *
 * <p>Order, top-N and OR-union (R-08): fan-out results are deduplicated by physical
 * identity ({@code pk}+{@code sk}). In-memory order materialises the unique matching
 * set, sorts with the plan comparator, then applies {@code rowcount}. DynamoDB
 * {@code Limit} and early {@code rowcount} stops are not used for in-memory order —
 * they would drop the real top-N. PartiQL {@code ExecuteStatement} carries the same
 * {@code pageSize} Limit as Query/Scan so {@code maxPages} is reachable inside one
 * IN-list, not only across IN-list chunks. Exceeding {@code inMemoryRowCeiling} is
 * {@code RELADYNAMO-PLAN-007}, not a plausible truncated answer.
 */
public final class QueryPlanExecutor {

    private static final QueryPlanInterpreter FILTER_INTERPRETER = new QueryPlanInterpreter();

    private final DynamoDbClient client;
    private final ItemCodec codec;
    private final Clock clock;
    private ExecutionExplain lastExplain;

    public QueryPlanExecutor(DynamoDbClient client, ItemCodec codec) {
        this(client, codec, Clock.systemUTC());
    }

    public QueryPlanExecutor(DynamoDbClient client, ItemCodec codec, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<Map<String, Object>> execute(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        long started = clock.millis();
        Stats stats = new Stats();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        run(plan, plan, rows, stats, seen);
        rows = finish(plan, rows);
        lastExplain = new ExecutionExplain(
                plan,
                plan.kind(),
                plan.indexName(),
                stats.examined,
                stats.dynamoReturned,
                rows.size(),
                stats.rcu,
                stats.pages,
                stats.requests,
                Math.max(0L, clock.millis() - started));
        return rows;
    }

    public ExecutionExplain lastExplain() {
        return lastExplain;
    }

    private void run(QueryPlan plan, QueryPlan root, List<Map<String, Object>> rows, Stats stats,
                     Set<String> seen) {
        PlanKind kind = plan.kind();
        if (kind == PlanKind.EMPTY) {
            return;
        }
        if (kind == PlanKind.QUERY_FAN_OUT) {
            if (executeFanOutSelect(plan, root, rows, stats, seen)) {
                return;
            }
            List<QueryPlan> children = plan.fanOut();
            for (int i = 0; i < children.size(); i++) {
                run(children.get(i), root, rows, stats, seen);
                if (filled(root, rows)) {
                    return;
                }
            }
            return;
        }
        if (kind == PlanKind.GET_ITEM) {
            getItem(plan, root, rows, stats, seen);
            return;
        }
        if (kind == PlanKind.SCAN) {
            scan(plan, root, rows, stats, seen);
            return;
        }
        query(plan, root, rows, stats, seen);
    }

    /**
     * @return true if the fan-out was executed as PartiQL {@code IN}; false to fall back
     *         to per-child Query/GetItem (mixed shapes, distinct sort keys).
     */
    private boolean executeFanOutSelect(QueryPlan plan, QueryPlan root, List<Map<String, Object>> rows,
                                        Stats stats, Set<String> seen) {
        int chunkSize = root.pageSize() > 0 ? root.pageSize() : FanOutSelect.IN_CHUNK;
        List<FanOutSelect> chunks = FanOutSelect.tryCollapse(plan.fanOut(), chunkSize);
        if (chunks.isEmpty()) {
            return false;
        }
        for (int c = 0; c < chunks.size(); c++) {
            if (c > 0) {
                refuseIfPageCapHit(root, stats, rows.size());
            }
            FanOutSelect select = chunks.get(c);
            String token = null;
            do {
                ExecuteStatementRequest.Builder b = ExecuteStatementRequest.builder()
                        .statement(select.statement)
                        .parameters(select.parameters)
                        .consistentRead(Boolean.valueOf(select.consistentRead))
                        .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
                Integer limit = requestLimit(plan, root);
                if (limit != null) {
                    b.limit(limit);
                }
                if (token != null && !token.isEmpty()) {
                    b.nextToken(token);
                }
                ExecuteStatementResponse response = client.executeStatement(b.build());
                stats.requests++;
                stats.pages++;
                stats.addRcu(response.consumedCapacity());
                List<Map<String, AttributeValue>> items = response.items();
                if (items != null) {
                    stats.examined += items.size();
                    stats.dynamoReturned += items.size();
                    for (int i = 0; i < items.size(); i++) {
                        acceptWithResidual(select.residual, plan, root, items.get(i), rows, seen);
                        if (filled(root, rows)) {
                            return true;
                        }
                    }
                }
                token = response.nextToken();
                if (token != null && !token.isEmpty()) {
                    refuseIfPageCapHit(root, stats, rows.size());
                }
            } while (token != null && !token.isEmpty());
        }
        return true;
    }

    private void acceptWithResidual(ResidualPredicate residual, QueryPlan executing, QueryPlan root,
                                    Map<String, AttributeValue> item,
                                    List<Map<String, Object>> rows, Set<String> seen) {
        if (item == null || item.isEmpty()) {
            return;
        }
        Map<String, Object> decoded = codec.decode(item);
        ResidualPredicate r = residual;
        if (r == null || r.isEmpty()) {
            r = executing.residual();
        }
        if (!passesResidual(r, decoded)) {
            return;
        }
        remember(root, item, decoded, rows, seen);
    }

    private void getItem(QueryPlan plan, QueryPlan root, List<Map<String, Object>> rows, Stats stats,
                         Set<String> seen) {
        GetItemRequest.Builder b = GetItemRequest.builder()
                .tableName(plan.tableName())
                .key(itemKey(plan))
                .consistentRead(Boolean.valueOf(plan.consistentRead()))
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
        GetItemResponse response = client.getItem(b.build());
        stats.requests++;
        stats.pages++;
        stats.addRcu(response.consumedCapacity());
        if (!response.hasItem() || response.item() == null || response.item().isEmpty()) {
            return;
        }
        stats.examined++;
        stats.dynamoReturned++;
        accept(plan, root, response.item(), rows, seen);
    }

    private void query(QueryPlan plan, QueryPlan root, List<Map<String, Object>> rows, Stats stats,
                       Set<String> seen) {
        Map<String, AttributeValue> startKey = null;
        while (true) {
            QueryRequest.Builder b = QueryRequest.builder()
                    .tableName(plan.tableName())
                    .keyConditionExpression(plan.keyConditionExpression())
                    .consistentRead(Boolean.valueOf(plan.consistentRead()))
                    .scanIndexForward(Boolean.valueOf(plan.scanIndexForward()))
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
            applyIndex(b, plan);
            applyFilter(b, plan);
            applyNamesValues(b, plan);
            applyRequestLimit(b, plan, root);
            if (startKey != null && !startKey.isEmpty()) {
                b.exclusiveStartKey(startKey);
            }
            QueryResponse response = client.query(b.build());
            stats.requests++;
            stats.pages++;
            stats.examined += n(response.scannedCount());
            stats.dynamoReturned += n(response.count());
            stats.addRcu(response.consumedCapacity());
            List<Map<String, AttributeValue>> items = response.items();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    accept(plan, root, items.get(i), rows, seen);
                    if (filled(root, rows)) {
                        return;
                    }
                }
            }
            if (!response.hasLastEvaluatedKey()) {
                return;
            }
            startKey = response.lastEvaluatedKey();
            if (startKey == null || startKey.isEmpty()) {
                return;
            }
            refuseIfPageCapHit(root, stats, rows.size());
        }
    }

    private void scan(QueryPlan plan, QueryPlan root, List<Map<String, Object>> rows, Stats stats,
                      Set<String> seen) {
        int segments = plan.segmentCount();
        if (segments <= 1) {
            scanSegment(plan, root, rows, stats, seen, null, null);
            return;
        }
        for (int segment = 0; segment < segments; segment++) {
            scanSegment(plan, root, rows, stats, seen, Integer.valueOf(segment), Integer.valueOf(segments));
            if (filled(root, rows)) {
                return;
            }
        }
    }

    private void scanSegment(QueryPlan plan, QueryPlan root, List<Map<String, Object>> rows, Stats stats,
                             Set<String> seen, Integer segment, Integer totalSegments) {
        Map<String, AttributeValue> startKey = null;
        while (true) {
            ScanRequest.Builder b = ScanRequest.builder()
                    .tableName(plan.tableName())
                    .consistentRead(Boolean.valueOf(plan.consistentRead()))
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
            if (!isPrimary(plan.indexName())) {
                b.indexName(plan.indexName());
            }
            if (plan.filterExpression() != null) {
                b.filterExpression(plan.filterExpression());
            }
            Map<String, String> names = plan.expressionAttributeNames();
            if (names != null && !names.isEmpty()) {
                b.expressionAttributeNames(names);
            }
            Map<String, AttributeValue> values = ExpressionValues.toSdk(plan.expressionAttributeValues());
            if (!values.isEmpty()) {
                b.expressionAttributeValues(values);
            }
            applyScanLimit(b, plan, root);
            if (segment != null && totalSegments != null) {
                b.segment(segment).totalSegments(totalSegments);
            }
            if (startKey != null && !startKey.isEmpty()) {
                b.exclusiveStartKey(startKey);
            }
            ScanResponse response = client.scan(b.build());
            stats.requests++;
            stats.pages++;
            stats.examined += n(response.scannedCount());
            stats.dynamoReturned += n(response.count());
            stats.addRcu(response.consumedCapacity());
            List<Map<String, AttributeValue>> items = response.items();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    accept(plan, root, items.get(i), rows, seen);
                    if (filled(root, rows)) {
                        return;
                    }
                }
            }
            if (!response.hasLastEvaluatedKey()) {
                return;
            }
            startKey = response.lastEvaluatedKey();
            if (startKey == null || startKey.isEmpty()) {
                return;
            }
            refuseIfPageCapHit(root, stats, rows.size());
        }
    }

    private void accept(QueryPlan executing, QueryPlan root, Map<String, AttributeValue> item,
                        List<Map<String, Object>> rows, Set<String> seen) {
        if (item == null || item.isEmpty()) {
            return;
        }
        // GetItem has no FilterExpression parameter. Query/Scan already applied the filter
        // server-side; only a point read still carries a filter that DynamoDB never saw.
        if (executing.kind() == PlanKind.GET_ITEM && !passesFilter(executing, item)) {
            return;
        }
        Map<String, Object> decoded = codec.decode(item);
        if (!passesResidual(executing.residual(), decoded)) {
            return;
        }
        remember(root, item, decoded, rows, seen);
    }

    /**
     * Local evaluation of {@link QueryPlan#filter()} against the wire item. Reuses
     * {@link QueryPlanInterpreter} so GET_ITEM payload and as-of predicates match Query/Scan.
     */

    private static void remember(QueryPlan root, Map<String, AttributeValue> item,
                                 Map<String, Object> decoded, List<Map<String, Object>> rows,
                                 Set<String> seen) {
        String identity = physicalIdentity(item);
        if (seen.contains(identity)) {
            return;
        }
        int ceiling = root.inMemoryRowCeiling();
        if (ceiling > 0 && rows.size() >= ceiling) {
            throw plan007(root, rows.size() + 1);
        }
        seen.add(identity);
        rows.add(decoded);
    }

    /**
     * Local evaluation of {@link QueryPlan#filter()} against the wire item. Reuses
     * {@link QueryPlanInterpreter} so GET_ITEM payload and as-of predicates match Query/Scan.
     */
    private static boolean passesFilter(QueryPlan plan, Map<String, AttributeValue> item) {
        FilterExpression filter = plan.filter();
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        QueryPlan filterOnly = QueryPlan.builder()
                .className(plan.className())
                .tableName(plan.tableName())
                .kind(PlanKind.GET_ITEM)
                .filterExpression(filter)
                .build();
        return FILTER_INTERPRETER.accepts(filterOnly, ExpressionValues.fromItem(item));
    }

    /**
     * Filter semantics: {@code Operation.matches} returns a nullable {@code Boolean}.
     * {@code null} is not a pass. Do not unbox.
     *
     * <p>Generated Reladomo attributes operate on domain / data types, not an arbitrary
     * {@code Map}. The candidate is a populated {@link MithraDataObject} wrapped as the
     * generated business object. Mapped / relationship residuals cannot be answered from
     * a decoded row alone (they need related objects in cache) and are refused with
     * {@code RELADYNAMO-RESIDUAL-001}. Test doubles without a portal still receive the
     * decoded map so in-process residual fixtures keep working.
     */
    static boolean passesResidual(ResidualPredicate residual, Map<String, Object> decoded) {
        if (residual == null || residual.isEmpty()) {
            return true;
        }
        Operation op = residual.operation();
        if (op.zContainsMappedOperation()) {
            throw new ReladynamoResidualEvaluationException(
                    "RELADYNAMO-RESIDUAL-001: residual relationship predicate cannot be evaluated "
                            + "below the seam (mapped / exists / not-exists requires relationship "
                            + "resolution; a cold cache would silently drop matching rows). Operation: "
                            + residual.dump());
        }
        Object candidate = residualCandidate(op, decoded);
        if (TypedNumericResidual.applies(op)) {
            return TypedNumericResidual.evaluate(op, decoded, candidate);
        }
        Boolean result = op.matches(candidate);
        return ResidualPredicate.isPass(result);
    }

    /**
     * Build the object representation generated attributes expect. Portal-less operations
     * (test doubles) keep the decoded map.
     */
    static Object residualCandidate(Operation op, Map<String, Object> decoded) {
        MithraObjectPortal portal = op.getResultObjectPortal();
        if (portal == null) {
            return decoded;
        }
        RelatedFinder finder = portal.getFinder();
        if (finder == null) {
            return decoded;
        }
        MithraDataObject data = MithraDataFactory.newData(finder);
        MithraDataPopulator.populate(finder, data, decoded);
        AsOfAttribute[] asOf = finder.getAsOfAttributes();
        if (asOf == null || asOf.length == 0) {
            MithraObjectFactory factory = portal.getMithraObjectFactory();
            if (factory != null) {
                return factory.createObject(data);
            }
            return portal.getCache().getObjectFromDataWithoutCaching(data);
        }
        Timestamp[] dates = asOfDatesForResidual(asOf, op, decoded);
        MithraDatedObjectFactory datedFactory = portal.getMithraDatedObjectFactory();
        if (datedFactory != null) {
            return datedFactory.createObject(data, dates);
        }
        return portal.getCache().getObjectFromDataWithoutCaching(data, dates);
    }

    private static Timestamp[] asOfDatesForResidual(AsOfAttribute[] asOf, Operation op,
                                                    Map<String, Object> decoded) {
        Timestamp[] dates = new Timestamp[asOf.length];
        for (int i = 0; i < asOf.length; i++) {
            Timestamp t = asOfOf(asOf[i], op, decoded);
            if (t == null) {
                throw new ReladynamoResidualEvaluationException(
                        "RELADYNAMO-RESIDUAL-001: cannot materialise a residual candidate without "
                                + "an as-of for '" + asOf[i].getAttributeName()
                                + "'. Guessing a date would evaluate the wrong version. Operation: "
                                + op);
            }
            dates[i] = t;
        }
        return dates;
    }

    private static Timestamp asOfOf(AsOfAttribute axis, Operation op, Map<String, Object> decoded) {
        Operation axisOp = op.zGetAsOfOp(axis);
        if (axisOp instanceof AsOfEqOperation) {
            return ((AsOfEqOperation) axisOp).getParameter();
        }
        TimestampAttribute fromAttr = axis.getFromAttribute();
        if (fromAttr != null) {
            Object v = decoded.get(fromAttr.getAttributeName());
            if (v instanceof Timestamp) {
                return (Timestamp) v;
            }
        }
        Object from = decoded.get(axis.getAttributeName() + "From");
        if (from instanceof Timestamp) {
            return (Timestamp) from;
        }
        return null;
    }

    private static List<Map<String, Object>> finish(QueryPlan plan, List<Map<String, Object>> rows) {
        if (plan.orderMode() == OrderMode.IN_MEMORY) {
            Comparator<Map<String, Object>> comparator = plan.rowComparator();
            if (comparator == null) {
                throw new ReladynamoUnplannableOperationException(
                        "RELADYNAMO-PLAN-009: OrderBy is not native sort-key order and in-memory sort "
                                + "is disabled (no comparator) for " + plan.className() + ".");
            }
            Collections.sort(rows, comparator);
        }
        int rowcount = plan.rowcount();
        if (rowcount > 0 && rows.size() > rowcount) {
            return new ArrayList<Map<String, Object>>(rows.subList(0, rowcount));
        }
        return rows;
    }

    private static boolean requiresFullMaterialization(QueryPlan root) {
        return root.orderMode() == OrderMode.IN_MEMORY;
    }

    private static boolean filled(QueryPlan root, List<Map<String, Object>> rows) {
        if (requiresFullMaterialization(root)) {
            return false;
        }
        int rowcount = root.rowcount();
        return rowcount > 0 && rows.size() >= rowcount;
    }

    static String physicalIdentity(Map<String, AttributeValue> item) {
        return stringAttr(item, PhysicalDesign.PK_ATTR) + "\u0000" + stringAttr(item, PhysicalDesign.SK_ATTR);
    }

    private static String stringAttr(Map<String, AttributeValue> item, String name) {
        if (item == null) {
            return "";
        }
        AttributeValue value = item.get(name);
        if (value == null || value.s() == null) {
            return "";
        }
        return value.s();
    }

    private static ReladynamoUnplannableOperationException plan007(QueryPlan plan, int n) {
        int ceiling = plan.inMemoryRowCeiling();
        return new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-007: In-memory materialisation for " + plan.className()
                        + " would materialise " + n + " rows (0 bytes), exceeding inMemoryRowCeiling="
                        + ceiling + " or inMemoryByteCeiling=0.");
    }

    private static void refuseIfPageCapHit(QueryPlan plan, Stats stats, int rowsSoFar) {
        if (plan.maxPages() > 0 && stats.pages >= plan.maxPages()) {
            throw new PageLimitExceededException(
                    plan.tableName(), stats.pages, plan.maxPages(), rowsSoFar);
        }
    }

    /**
     * DynamoDB {@code Limit} is items evaluated, not items matching a filter. Never substitute
     * Reladomo {@code rowcount} when a filter/residual is present — that is {@link QueryPlan#dynamoLimit()}.
     * A positive {@link QueryPlan#pageSize()} pages the request so {@code maxPages} is enforceable.
     *
     * <p>R-08: {@code dynamoLimit} (rowcount) is suppressed when {@code orderMode == IN_MEMORY}
     * so a later sort can see every matching row. {@code pageSize} is still applied: it bounds
     * request size, not the result. Hitting {@code maxPages} before the ordered set is complete
     * is {@code PLAN-006}, not a truncated top-N.
     */
    private static Integer requestLimit(QueryPlan plan, QueryPlan root) {
        if (root.orderMode() != OrderMode.IN_MEMORY && plan.dynamoLimit() != null) {
            return plan.dynamoLimit();
        }
        int pageSize = plan.pageSize() > 0 ? plan.pageSize() : root.pageSize();
        if (pageSize > 0) {
            return Integer.valueOf(pageSize);
        }
        return null;
    }

    private static void applyRequestLimit(QueryRequest.Builder b, QueryPlan plan, QueryPlan root) {
        Integer limit = requestLimit(plan, root);
        if (limit != null) {
            b.limit(limit);
        }
    }

    private static void applyScanLimit(ScanRequest.Builder b, QueryPlan plan, QueryPlan root) {
        Integer limit = requestLimit(plan, root);
        if (limit != null) {
            b.limit(limit);
        }
    }

    private static void applyIndex(QueryRequest.Builder b, QueryPlan plan) {
        if (!isPrimary(plan.indexName())) {
            b.indexName(plan.indexName());
        }
    }

    private static void applyFilter(QueryRequest.Builder b, QueryPlan plan) {
        if (plan.filterExpression() != null) {
            b.filterExpression(plan.filterExpression());
        }
    }

    private static void applyNamesValues(QueryRequest.Builder b, QueryPlan plan) {
        Map<String, String> names = plan.expressionAttributeNames();
        if (names != null && !names.isEmpty()) {
            b.expressionAttributeNames(names);
        }
        Map<String, AttributeValue> values = ExpressionValues.toSdk(plan.expressionAttributeValues());
        if (!values.isEmpty()) {
            b.expressionAttributeValues(values);
        }
    }

    private static boolean isPrimary(String indexName) {
        return indexName == null
                || indexName.isEmpty()
                || PhysicalDesign.PRIMARY_INDEX.equals(indexName);
    }

    private static Map<String, AttributeValue> itemKey(QueryPlan plan) {
        KeyCondition key = plan.keyCondition();
        if (key == null) {
            throw new IllegalArgumentException("GET_ITEM plan is missing a key condition");
        }
        String pkName = resolveName(plan, "#pk", PhysicalDesign.PK_ATTR);
        String skName = resolveName(plan, "#sk", PhysicalDesign.SK_ATTR);
        String pk = key.encodedPartitionKey();
        String sk = key.encodedSortKey();
        if (pk == null) {
            ExpressionValuePkSk extracted = fromValues(plan);
            pk = extracted.pk;
            if (sk == null) {
                sk = extracted.sk;
            }
        }
        if (pk == null) {
            throw new IllegalArgumentException("GET_ITEM plan is missing an encoded partition key");
        }
        if (sk == null) {
            sk = DefaultKeyStrategy.NON_DATED_SORT_KEY;
        }
        Map<String, AttributeValue> out = new LinkedHashMap<String, AttributeValue>(4);
        out.put(pkName, AttributeValue.builder().s(pk).build());
        out.put(skName, AttributeValue.builder().s(sk).build());
        return out;
    }

    private static String resolveName(QueryPlan plan, String placeholder, String fallback) {
        Map<String, String> names = plan.expressionAttributeNames();
        if (names != null && names.containsKey(placeholder)) {
            return names.get(placeholder);
        }
        return fallback;
    }

    private static ExpressionValuePkSk fromValues(QueryPlan plan) {
        Map<String, io.reladynamo.core.plan.ExpressionValue> values = plan.expressionAttributeValues();
        String pk = null;
        String sk = null;
        if (values != null) {
            io.reladynamo.core.plan.ExpressionValue pkVal = values.get(":pk");
            if (pkVal != null && pkVal.s() != null) {
                pk = pkVal.s();
            }
            io.reladynamo.core.plan.ExpressionValue skVal = values.get(":sk");
            if (skVal != null && skVal.s() != null) {
                sk = skVal.s();
            }
        }
        return new ExpressionValuePkSk(pk, sk);
    }

    private static int n(Integer v) {
        return v == null ? 0 : v.intValue();
    }

    private static final class ExpressionValuePkSk {
        final String pk;
        final String sk;

        ExpressionValuePkSk(String pk, String sk) {
            this.pk = pk;
            this.sk = sk;
        }
    }

    private static final class Stats {
        int examined;
        int dynamoReturned;
        int pages;
        int requests;
        double rcu;

        void addRcu(ConsumedCapacity capacity) {
            if (capacity == null || capacity.capacityUnits() == null) {
                return;
            }
            rcu += capacity.capacityUnits().doubleValue();
        }
    }
}
