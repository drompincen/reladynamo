package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.FilterExpression;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.PartitionKeyEncoder;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.temporal.TemporalEncoder;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-10: the planner's sparse-current path is a real Query on the GSI's own attributes,
 * and "as of now" examines far fewer items than a base-table range over history.
 */
class SparseCurrentGsiQueryTest {

    private static final int HISTORY_VERSIONS = 24;

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
    void should_return_the_current_row_from_the_sparse_gsi_attribute_names() {
        EntityMapping mapping = ruleMapping("query_sparse_current");
        GsiSpec gsi = GsiSpec.sparseCurrent("gsi_current", Collections.singletonList("ruleId"));
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbWriter writer = writerFor(mapping, gsi, codec);
        Timestamp businessFrom = ts(2020, 1, 1);
        seedHistory(writer, 11, businessFrom, HISTORY_VERSIONS);

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        List<Map<String, Object>> rows = executor.execute(sparsePlan(mapping, gsi, 11, businessFrom));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("ruleName")).isEqualTo("current");
        assertThat(rows.get(0).get("ruleId")).isEqualTo(Integer.valueOf(11));
        ExecutionExplain explain = executor.lastExplain();
        assertThat(explain.indexName()).isEqualTo("gsi_current");
        assertThat(explain.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(explain.requestCount()).isEqualTo(1);
        assertThat(explain.actualItemsExamined()).isEqualTo(1);
    }

    @Test
    void should_examine_fewer_items_for_as_of_now_with_sparse_current_gsi_than_without() {
        Timestamp businessFrom = ts(2020, 1, 1);

        EntityMapping baseMapping = ruleMapping("perf_asof_base");
        ItemCodec baseCodec = new ItemCodec(baseMapping);
        DynamoDbWriter baseWriter = writerFor(baseMapping, null, baseCodec);
        seedHistory(baseWriter, 42, businessFrom, HISTORY_VERSIONS);
        ExecFixtures.CountingClient baseCounting = new ExecFixtures.CountingClient(ddb.client());
        QueryPlanExecutor baseExecutor = new QueryPlanExecutor(baseCounting.client, baseCodec);
        List<Map<String, Object>> baseRows = baseExecutor.execute(basePlan(baseMapping, 42, businessFrom));
        ExecutionExplain baseExplain = baseExecutor.lastExplain();

        EntityMapping gsiMapping = ruleMapping("perf_asof_gsi");
        GsiSpec gsi = GsiSpec.sparseCurrent("gsi_current", Collections.singletonList("ruleId"));
        ItemCodec gsiCodec = new ItemCodec(gsiMapping);
        DynamoDbWriter gsiWriter = writerFor(gsiMapping, gsi, gsiCodec);
        seedHistory(gsiWriter, 42, businessFrom, HISTORY_VERSIONS);
        ExecFixtures.CountingClient gsiCounting = new ExecFixtures.CountingClient(ddb.client());
        QueryPlanExecutor gsiExecutor = new QueryPlanExecutor(gsiCounting.client, gsiCodec);
        List<Map<String, Object>> gsiRows = gsiExecutor.execute(sparsePlan(gsiMapping, gsi, 42, businessFrom));
        ExecutionExplain gsiExplain = gsiExecutor.lastExplain();

        int totalVersions = HISTORY_VERSIONS + 1;
        assertThat(baseRows).hasSize(1);
        assertThat(gsiRows).hasSize(1);
        assertThat(baseRows.get(0).get("ruleName")).isEqualTo("current");
        assertThat(gsiRows.get(0).get("ruleName")).isEqualTo("current");

        assertThat(baseCounting.reads.get()).isEqualTo(1);
        assertThat(gsiCounting.reads.get()).isEqualTo(1);
        assertThat(baseExplain.requestCount()).isEqualTo(1);
        assertThat(gsiExplain.requestCount()).isEqualTo(1);

        assertThat(baseExplain.actualItemsExamined())
                .as("base-table as-of-now must examine every historical version in the partition")
                .isEqualTo(totalVersions);
        assertThat(gsiExplain.actualItemsExamined())
                .as("sparse-current GSI must examine only the live row")
                .isEqualTo(1);
        assertThat(gsiExplain.actualItemsExamined())
                .as("the measured win: GSI examined %s items vs base %s",
                        Integer.valueOf(gsiExplain.actualItemsExamined()),
                        Integer.valueOf(baseExplain.actualItemsExamined()))
                .isLessThan(baseExplain.actualItemsExamined());
        assertThat(gsiExplain.indexName()).isEqualTo("gsi_current");
        assertThat(baseExplain.indexName()).isEqualTo("PRIMARY");
    }

    private static DynamoDbWriter writerFor(EntityMapping mapping, GsiSpec gsi, ItemCodec codec) {
        PhysicalDesign.Builder design = PhysicalDesign.builder(mapping).infinity(infinity());
        if (gsi != null) {
            design.addGsi(gsi);
        }
        new TableCreator(ddb.client()).create(design.build());
        List<GsiSpec> gsis = gsi == null
                ? Collections.<GsiSpec>emptyList()
                : Collections.singletonList(gsi);
        return new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy(), gsis);
    }

    private static void seedHistory(DynamoDbWriter writer, int ruleId, Timestamp businessFrom,
                                    int closedVersions) {
        for (int i = 0; i < closedVersions; i++) {
            Timestamp processingFrom = ts(2020, 1, 1 + i);
            Timestamp processingTo = ts(2020, 1, 2 + i);
            writer.insert(ruleRow(ruleId, "hist-" + i, businessFrom, processingFrom, processingTo));
        }
        Timestamp currentFrom = ts(2020, 1, 1 + closedVersions);
        writer.insert(ruleRow(ruleId, "current", businessFrom, currentFrom, infinity()));
    }

    private static QueryPlan basePlan(EntityMapping mapping, int ruleId, Timestamp businessFrom) {
        String encodedPk = new DefaultKeyStrategy().partitionKey(mapping, pk(ruleId));
        Timestamp asOf = ts(2026, 6, 1);
        return QueryPlan.builder()
                .className(mapping.className())
                .tableName(mapping.tableName())
                .indexName(PhysicalDesign.PRIMARY_INDEX)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", encodedPk))
                .filterExpression(currentAsOfFilter(asOf, true))
                .consistentRead(true)
                .fastPath(QueryPlan.FAST_PATH_CURRENT_ASOF)
                .estimatedItemsExamined(PlannerConfig.DEFAULT_ESTIMATED_VERSIONS_PER_KEY)
                .estimatedItemsReturned(1)
                .build();
    }

    private static QueryPlan sparsePlan(EntityMapping mapping, GsiSpec gsi, int ruleId,
                                        Timestamp businessFrom) {
        String encodedPk = new DefaultKeyStrategy().partitionKey(mapping, pk(ruleId));
        Timestamp asOf = ts(2026, 6, 1);
        return QueryPlan.builder()
                .className(mapping.className())
                .tableName(mapping.tableName())
                .indexName(gsi.name())
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionAndBetween(
                        gsi.partitionKeyAttributeName(),
                        gsi.sortKeyAttributeName(),
                        encodedPk,
                        "v1#B#",
                        PartitionKeyEncoder.currentBusinessSk(asOf)))
                .filterExpression(currentAsOfFilter(asOf, false))
                .consistentRead(false)
                .fastPath(QueryPlan.FAST_PATH_CURRENT_ASOF)
                .estimatedItemsExamined(1)
                .estimatedItemsReturned(1)
                .build();
    }

    private static FilterExpression currentAsOfFilter(Timestamp asOf, boolean includeProcessingInfinity) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        names.put("#thru", "THRU_Z");
        values.put(":b", ExpressionValue.s(TemporalEncoder.encode(asOf)));
        String expr;
        if (includeProcessingInfinity) {
            names.put("#out", "OUT_Z");
            names.put("#from", "FROM_Z");
            values.put(":inf", ExpressionValue.s(TemporalEncoder.encode(infinity())));
            expr = "#out = :inf AND #from <= :b AND #thru > :b";
        } else {
            expr = "#thru > :b";
        }
        return new FilterExpression(expr, names, values);
    }

    private static EntityMapping ruleMapping(String table) {
        return new EntityMapping("com.acme.PlanRule", table,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, infinity()),
                java.util.Arrays.asList(
                        new AttributeMapping("ruleId", "ruleId", "int", true, false),
                        new AttributeMapping("ruleName", "ruleName", "String", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
    }

    private static Map<String, Object> ruleRow(int id, String name, Timestamp businessFrom,
                                               Timestamp processingFrom, Timestamp processingTo) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("ruleId", Integer.valueOf(id));
        row.put("ruleName", name);
        row.put("businessDateFrom", businessFrom);
        row.put("businessDateTo", infinity());
        row.put("processingDateFrom", processingFrom);
        row.put("processingDateTo", processingTo);
        return row;
    }

    private static Map<String, Object> pk(int ruleId) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("ruleId", Integer.valueOf(ruleId));
        return values;
    }

    private static Timestamp ts(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(0);
        return t;
    }

    private static Timestamp infinity() {
        return ts(9999, 12, 1);
    }
}
