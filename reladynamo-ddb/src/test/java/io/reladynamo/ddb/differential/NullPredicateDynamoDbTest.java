package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.core.plan.eval.QueryPlanInterpreter;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-05: {@code isNull}/{@code isNotNull} against DynamoDB Local, not only the in-memory interpreter.
 *
 * <p>Four wire states on nullable {@code DiffBalance.note}: codec-written explicit NULL,
 * genuinely missing attribute (schema evolution), empty string, and a populated value.
 *
 * <p>Policy: Reladomo {@code isNull()} matches explicit NULL <em>and</em> missing (both decode
 * to Java {@code null}). Empty string is not null. Reladomo 18.1.0 has no {@code NotOperation};
 * {@code isNotNull()} is the negation.
 */
class NullPredicateDynamoDbTest {

    private static final int ID_EXPLICIT_NULL = 9401;
    private static final int ID_MISSING = 9402;
    private static final int ID_EMPTY = 9403;
    private static final int ID_POPULATED = 9404;

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static PhysicalDesign design;
    private static ItemCodec codec;
    private static QueryPlanner planner;
    private static QueryPlanExecutor executor;
    private static Timestamp from;
    private static Timestamp thru;
    private static Timestamp inZ;
    private static Timestamp asOf;
    private static Timestamp infinity;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        mapping = new MithraObjectXmlParser()
                .parse(DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
        codec = new ItemCodec(mapping);
        DifferentialSupport.createPkSkTable(ddb, mapping.tableName());
        planner = new QueryPlanner();
        design = PhysicalDesign.builder(mapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance())
                .build();
        executor = new QueryPlanExecutor(ddb.client(), codec);
        from = utc(2026, 1, 1);
        thru = utc(2026, 12, 1);
        inZ = utc(2026, 1, 2);
        asOf = utc(2026, 6, 1);
        infinity = DiffBalanceFinder.processingDate().getInfinityDate();
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @BeforeEach
    void writeFourWireStates() {
        putRow(ID_EXPLICIT_NULL, null, false);
        putRow(ID_MISSING, null, true);
        putRow(ID_EMPTY, "", false);
        putRow(ID_POPULATED, "hello", false);
    }

    @Test
    void should_return_explicit_null_and_missing_from_dynamodb_when_isNull() {
        QueryPlan plan = planScan(DiffBalanceFinder.note().isNull());

        assertThat(plan.kind()).isEqualTo(PlanKind.SCAN);
        assertThat(plan.filterExpression())
                .as("planner must send DynamoDB a NULL-type check, not only attribute_not_exists: %s",
                        plan.filterExpression())
                .contains("attribute_type");

        List<Integer> ids = ids(executor.execute(plan));

        assertThat(ids)
                .as("isNull must include codec-written explicit NULL and schema-evolution missing; "
                        + "filter=%s", plan.filterExpression())
                .containsExactlyInAnyOrder(
                        Integer.valueOf(ID_EXPLICIT_NULL), Integer.valueOf(ID_MISSING));
    }

    @Test
    void should_return_empty_string_and_populated_from_dynamodb_when_isNotNull() {
        QueryPlan plan = planScan(DiffBalanceFinder.note().isNotNull());

        assertThat(plan.kind()).isEqualTo(PlanKind.SCAN);
        assertThat(plan.filterExpression())
                .as("isNotNull must exclude explicit NULL, not only attribute_exists: %s",
                        plan.filterExpression())
                .contains("attribute_type")
                .contains("NOT");

        List<Integer> ids = ids(executor.execute(plan));

        assertThat(ids)
                .as("isNotNull must exclude stored NULL and missing; include empty string; filter=%s",
                        plan.filterExpression())
                .containsExactlyInAnyOrder(
                        Integer.valueOf(ID_EMPTY), Integer.valueOf(ID_POPULATED));
    }

    @Test
    void should_put_isNull_translation_on_getitem_plan_and_match_wire_items() {
        QueryPlan isNull = planGet(ID_EXPLICIT_NULL, DiffBalanceFinder.note().isNull());
        assertThat(isNull.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(isNull.filterExpression())
                .as("GET_ITEM must carry the isNull translation, not only as-of: %s",
                        isNull.toAssertableString())
                .contains("attribute_not_exists")
                .contains("attribute_type");

        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        assertThat(interp.accepts(isNull, wireItem(ID_EXPLICIT_NULL))).isTrue();
        assertThat(interp.accepts(planGet(ID_MISSING, DiffBalanceFinder.note().isNull()),
                wireItem(ID_MISSING))).isTrue();
        assertThat(interp.accepts(planGet(ID_EMPTY, DiffBalanceFinder.note().isNull()),
                wireItem(ID_EMPTY))).isFalse();
        assertThat(interp.accepts(planGet(ID_POPULATED, DiffBalanceFinder.note().isNull()),
                wireItem(ID_POPULATED))).isFalse();
    }

    @Test
    void should_put_isNotNull_translation_on_getitem_plan_and_match_wire_items() {
        QueryPlan isNotNull = planGet(ID_POPULATED, DiffBalanceFinder.note().isNotNull());
        assertThat(isNotNull.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(isNotNull.filterExpression())
                .contains("attribute_exists")
                .contains("attribute_type")
                .contains("NOT");

        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        assertThat(interp.accepts(planGet(ID_EXPLICIT_NULL, DiffBalanceFinder.note().isNotNull()),
                wireItem(ID_EXPLICIT_NULL))).isFalse();
        assertThat(interp.accepts(planGet(ID_MISSING, DiffBalanceFinder.note().isNotNull()),
                wireItem(ID_MISSING))).isFalse();
        assertThat(interp.accepts(planGet(ID_EMPTY, DiffBalanceFinder.note().isNotNull()),
                wireItem(ID_EMPTY))).isTrue();
        assertThat(interp.accepts(isNotNull, wireItem(ID_POPULATED))).isTrue();
    }

    @Test
    void should_agree_with_interpreter_on_scan_results() {
        QueryPlan isNull = planScan(DiffBalanceFinder.note().isNull());
        QueryPlan isNotNull = planScan(DiffBalanceFinder.note().isNotNull());
        QueryPlanInterpreter interp = new QueryPlanInterpreter();

        List<Map<String, AttributeValue>> wire = ddb.client()
                .scan(ScanRequest.builder().tableName(mapping.tableName()).build())
                .items();

        List<Integer> ddbNull = ids(executor.execute(isNull));
        List<Integer> ddbNotNull = ids(executor.execute(isNotNull));

        List<Integer> interpNull = new ArrayList<Integer>();
        List<Integer> interpNotNull = new ArrayList<Integer>();
        for (int i = 0; i < wire.size(); i++) {
            Map<String, AttributeValue> item = wire.get(i);
            Map<String, ExpressionValue> asExpr = fromSdkItem(item);
            int id = Integer.parseInt(item.get("BALANCE_ID").n());
            if (interp.accepts(isNull, asExpr)) {
                interpNull.add(Integer.valueOf(id));
            }
            if (interp.accepts(isNotNull, asExpr)) {
                interpNotNull.add(Integer.valueOf(id));
            }
        }

        assertThat(interpNull)
                .as("interpreter must not repeat the inverted-null bug the DynamoDB expression had")
                .containsExactlyInAnyOrderElementsOf(ddbNull);
        assertThat(interpNotNull).containsExactlyInAnyOrderElementsOf(ddbNotNull);
    }

    @Test
    void should_pin_that_not_equals_null_does_not_match_string_values() {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#NOTE", "NOTE");
        Map<String, AttributeValue> values = new LinkedHashMap<String, AttributeValue>();
        values.put(":n", AttributeValue.builder().nul(true).build());

        ScanResponse eqNull = ddb.client().scan(ScanRequest.builder()
                .tableName(mapping.tableName())
                .filterExpression("#NOTE = :n")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build());
        assertThat(idsFromWire(eqNull.items()))
                .as("DynamoDB #attr = :null matches only the explicit NULL item")
                .containsExactly(Integer.valueOf(ID_EXPLICIT_NULL));

        ScanResponse neNull = ddb.client().scan(ScanRequest.builder()
                .tableName(mapping.tableName())
                .filterExpression("#NOTE <> :n")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build());
        assertThat(idsFromWire(neNull.items()))
                .as("DynamoDB Local: #attr <> :null matched %s. AWS documents type-strict "
                        + "false for mismatched types, so isNotNull uses attribute_type rather "
                        + "than relying on <>.", idsFromWire(neNull.items()))
                .contains(Integer.valueOf(ID_EMPTY), Integer.valueOf(ID_POPULATED))
                .doesNotContain(Integer.valueOf(ID_EXPLICIT_NULL));
    }

    private QueryPlan planScan(com.gs.fw.finder.Operation noteOp) {
        Operation op = (Operation) noteOp
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(infinity));
        PlannerConfig config = PlannerConfig.builder()
                .allowTableScan(true)
                .parallelScanSegments(1)
                .build();
        return planner.plan(new AnalyzedOperation(op), design, config);
    }

    private QueryPlan planGet(int id, com.gs.fw.finder.Operation noteOp) {
        Operation op = DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDateFrom().eq(from))
                .and(DiffBalanceFinder.processingDateFrom().eq(inZ))
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(infinity))
                .and(noteOp);
        return planner.plan(new AnalyzedOperation(op), design, PlannerConfig.defaults());
    }

    private void putRow(int id, String note, boolean removeNote) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("balanceId", Integer.valueOf(id));
        row.put("quantity", Double.valueOf(1.0d));
        row.put("label", "OPEN");
        row.put("note", note);
        row.put("businessDateFrom", from);
        row.put("businessDateTo", thru);
        row.put("processingDateFrom", inZ);
        row.put("processingDateTo", infinity);
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("balanceId", Integer.valueOf(id));
        String pk = keys.partitionKey(mapping, pkValues);
        String sk = keys.sortKey(mapping, inZ, from);
        Map<String, AttributeValue> item = new LinkedHashMap<String, AttributeValue>(codec.encode(row));
        item.put("pk", AttributeValue.builder().s(pk).build());
        item.put("sk", AttributeValue.builder().s(sk).build());
        if (removeNote) {
            item.remove("NOTE");
        }
        ddb.client().putItem(PutItemRequest.builder().tableName(mapping.tableName()).item(item).build());
    }

    private Map<String, ExpressionValue> wireItem(int id) {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("balanceId", Integer.valueOf(id));
        Map<String, AttributeValue> key = new LinkedHashMap<String, AttributeValue>();
        key.put("pk", AttributeValue.builder().s(keys.partitionKey(mapping, pkValues)).build());
        key.put("sk", AttributeValue.builder().s(keys.sortKey(mapping, inZ, from)).build());
        Map<String, AttributeValue> item = ddb.client()
                .getItem(b -> b.tableName(mapping.tableName()).key(key))
                .item();
        return fromSdkItem(item);
    }

    private static List<Integer> ids(List<Map<String, Object>> rows) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            Object id = rows.get(i).get("balanceId");
            out.add(Integer.valueOf(((Number) id).intValue()));
        }
        Collections.sort(out);
        return out;
    }

    private static List<Integer> idsFromWire(List<Map<String, AttributeValue>> items) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < items.size(); i++) {
            out.add(Integer.valueOf(Integer.parseInt(items.get(i).get("BALANCE_ID").n())));
        }
        Collections.sort(out);
        return out;
    }

    private static Map<String, ExpressionValue> fromSdkItem(Map<String, AttributeValue> item) {
        Map<String, ExpressionValue> out = new LinkedHashMap<String, ExpressionValue>();
        for (Map.Entry<String, AttributeValue> e : item.entrySet()) {
            out.put(e.getKey(), fromSdk(e.getValue()));
        }
        return out;
    }

    private static ExpressionValue fromSdk(AttributeValue value) {
        if (value == null || Boolean.TRUE.equals(value.nul())) {
            return ExpressionValue.nul();
        }
        if (value.s() != null) {
            return ExpressionValue.s(value.s());
        }
        if (value.n() != null) {
            return ExpressionValue.n(value.n());
        }
        if (value.bool() != null) {
            return ExpressionValue.bool(value.bool().booleanValue());
        }
        if (value.b() != null) {
            SdkBytes b = value.b();
            return ExpressionValue.b(b.asByteArray());
        }
        return ExpressionValue.nul();
    }

    private static Timestamp utc(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(0);
        return t;
    }
}
