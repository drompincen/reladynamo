package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.FilterExpression;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.temporal.TemporalEncoder;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-03: GetItem has no FilterExpression, so a GET_ITEM plan that still carries a payload or
 * as-of filter must evaluate that filter locally. These tests write through {@link ItemCodec}
 * and read through {@link QueryPlanExecutor} — the same path {@code find}/{@code count} use.
 */
class GetItemFilterTest {

    private static LocalDynamoDb ddb;

    @BeforeAll
    static void startLocal() {
        ddb = LocalDynamoDb.start();
    }

    @AfterAll
    static void stopLocal() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_return_nothing_and_count_zero_when_point_get_payload_filter_fails() {
        EntityMapping mapping = ExecFixtures.customerMapping("GetItemFilterFail");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(7L, "Ada", "CLOSED"));

        QueryPlan plan = pointGet(mapping, 7L, statusEquals("ACTIVE"));

        assertThat(plan.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(plan.filterExpression()).isEqualTo("#status = :st");

        ExecFixtures.CountingClient counting = new ExecFixtures.CountingClient(ddb.client());
        QueryPlanExecutor executor = new QueryPlanExecutor(counting.client, codec);
        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows)
                .as("id=7 AND status=ACTIVE must not return a CLOSED row")
                .isEmpty();
        assertThat(executor.execute(plan).size())
                .as("count() is execute(plan).size() on this path")
                .isZero();
        assertThat(counting.reads.get())
                .as("must keep the single-RCU GetItem, not degrade to Query/Scan")
                .isGreaterThanOrEqualTo(1);
        assertThat(executor.lastExplain().kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(executor.lastExplain().actualItemsReturned()).isZero();
    }

    @Test
    void should_return_the_row_when_point_get_payload_filter_passes() {
        EntityMapping mapping = ExecFixtures.customerMapping("GetItemFilterPass");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(7L, "Ada", "ACTIVE"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        QueryPlan plan = pointGet(mapping, 7L, statusEquals("ACTIVE"));

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo(7L);
        assertThat(rows.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(executor.lastExplain().kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(executor.execute(plan).size()).isEqualTo(1);
    }

    @Test
    void should_return_nothing_when_exact_dated_rectangle_fails_as_of_containment() {
        EntityMapping mapping = datedMapping("GetItemAsOfFail");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);

        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 6, 1);
        Timestamp inZ = utc(2026, 1, 1);
        Timestamp outZ = utc(9999, 12, 1);
        Timestamp asOfOutside = utc(2026, 7, 1);

        putDated(mapping, codec, 42, "OPEN", from, thru, inZ, outZ);

        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("id", Integer.valueOf(42));
        String pk = keys.partitionKey(mapping, pkValues);
        String sk = keys.sortKey(mapping, inZ, from);

        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.GET_ITEM)
                .fastPath(QueryPlan.FAST_PATH_POINT_GET)
                .keyCondition(KeyCondition.partitionAndSortEquals("pk", "sk", pk, sk))
                .filterExpression(asOfContainment(asOfOutside))
                .build();

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(plan.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(rows)
                .as("exact rectangle [Jan 1, June 1) must not match as-of July 1")
                .isEmpty();
        assertThat(executor.execute(plan).size()).isZero();
        assertThat(executor.lastExplain().kind()).isEqualTo(PlanKind.GET_ITEM);
    }

    private static QueryPlan pointGet(EntityMapping mapping, long id, FilterExpression filter) {
        return ExecFixtures.basePlan(mapping)
                .kind(PlanKind.GET_ITEM)
                .fastPath(QueryPlan.FAST_PATH_POINT_GET)
                .keyCondition(KeyCondition.partitionAndSortEquals(
                        "pk", "sk",
                        ExecFixtures.encodedPk(mapping, id),
                        DefaultKeyStrategy.NON_DATED_SORT_KEY))
                .filterExpression(filter)
                .build();
    }

    private static FilterExpression statusEquals(String status) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#status", "status");
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":st", ExpressionValue.s(status));
        return new FilterExpression("#status = :st", names, values);
    }

    private static FilterExpression asOfContainment(Timestamp asOf) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#FROM_Z", "FROM_Z");
        names.put("#THRU_Z", "THRU_Z");
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":asof", ExpressionValue.s(TemporalEncoder.encode(asOf)));
        return new FilterExpression("#FROM_Z <= :asof AND #THRU_Z > :asof", names, values);
    }

    private static EntityMapping datedMapping(String tableName) {
        List<AttributeMapping> attributes = new ArrayList<AttributeMapping>();
        attributes.add(new AttributeMapping("id", "id", "int", true, false));
        attributes.add(new AttributeMapping("status", "status", "String", false, false));
        attributes.add(new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false));
        attributes.add(new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false));
        attributes.add(new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false));
        attributes.add(new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false));
        return new EntityMapping("DatedStatus", tableName,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, utc(9999, 12, 1)),
                attributes);
    }

    private static void putDated(EntityMapping mapping, ItemCodec codec, int id, String status,
                                 Timestamp from, Timestamp thru, Timestamp inZ, Timestamp outZ) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Integer.valueOf(id));
        row.put("status", status);
        row.put("businessDateFrom", from);
        row.put("businessDateTo", thru);
        row.put("processingDateFrom", inZ);
        row.put("processingDateTo", outZ);
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("id", Integer.valueOf(id));
        String pk = keys.partitionKey(mapping, pkValues);
        String sk = keys.sortKey(mapping, inZ, from);
        Map<String, AttributeValue> item = new LinkedHashMap<String, AttributeValue>(codec.encode(row));
        item.put("pk", AttributeValue.builder().s(pk).build());
        item.put("sk", AttributeValue.builder().s(sk).build());
        ddb.client().putItem(PutItemRequest.builder().tableName(mapping.tableName()).item(item).build());
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
