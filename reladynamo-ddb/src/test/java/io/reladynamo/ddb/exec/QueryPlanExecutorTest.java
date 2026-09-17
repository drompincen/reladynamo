package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.ResidualPredicate;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryPlanExecutorTest {

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
    void should_issue_zero_calls_and_return_empty_when_plan_is_empty() {
        EntityMapping mapping = ExecFixtures.customerMapping("EmptyPlanTable");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "Ada", "OPEN"));

        ExecFixtures.CountingClient counting = new ExecFixtures.CountingClient(ddb.client());
        QueryPlanExecutor executor = new QueryPlanExecutor(counting.client, codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.EMPTY)
                .estimatedItemsExamined(0)
                .estimatedItemsReturned(0)
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows).isEmpty();
        assertThat(counting.reads.get())
                .as("EMPTY must not touch DynamoDB at all")
                .isZero();
        ExecutionExplain explain = executor.lastExplain();
        assertThat(explain.kind()).isEqualTo(PlanKind.EMPTY);
        assertThat(explain.pageCount()).isZero();
        assertThat(explain.requestCount()).isZero();
        assertThat(explain.actualItemsExamined()).isZero();
        assertThat(explain.actualItemsReturned()).isZero();
    }

    @Test
    void should_round_trip_query_rows_written_via_item_codec() {
        EntityMapping mapping = ExecFixtures.customerMapping("RoundTrip");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbClient client = ddb.client();
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(9L, "Ada", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(9L, "should-not-match", "OPEN"));
        // same partition, different sort? non-dated SK is constant, so second put overwrites.
        // write a different partition that must not come back
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(8L, "Bob", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(9L, "Ada", "OPEN"));

        QueryPlanExecutor executor = new QueryPlanExecutor(client, codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", ExecFixtures.encodedPk(mapping, 9L)))
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo(9L);
        assertThat(rows.get(0).get("name")).isEqualTo("Ada");
        ExecutionExplain explain = executor.lastExplain();
        assertThat(explain.indexName()).isEqualTo("PRIMARY");
        assertThat(explain.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(explain.actualItemsReturned()).isEqualTo(1);
        assertThat(explain.pageCount()).isGreaterThanOrEqualTo(1);
        assertThat(explain.requestCount()).isGreaterThanOrEqualTo(1);
        assertThat(explain.consumedCapacityRcu()).isGreaterThanOrEqualTo(0.0d);
    }

    @Test
    void should_get_item_when_plan_is_point_get() {
        EntityMapping mapping = ExecFixtures.customerMapping("PointGet");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(4L, "Dora", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(5L, "Eli", "OPEN"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.GET_ITEM)
                .fastPath(QueryPlan.FAST_PATH_POINT_GET)
                .keyCondition(KeyCondition.partitionAndSortEquals(
                        "pk", "sk",
                        ExecFixtures.encodedPk(mapping, 4L),
                        DefaultKeyStrategy.NON_DATED_SORT_KEY))
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("Dora");
        assertThat(executor.lastExplain().kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(executor.lastExplain().actualItemsExamined()).isEqualTo(1);
    }

    @Test
    void should_return_every_row_when_query_paginates() {
        EntityMapping mapping = ExecFixtures.customerMapping("Paginate");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        // One partition, many sort keys: use dated? Non-dated SK is constant so one item per PK.
        // Force pagination by writing many partitions and scanning/querying with Limit=1
        // For QUERY, we need many items in ONE partition. Use unique sk via different encoded
        // sort keys written directly (still valid against the table schema).
        putRaw(mapping, codec, "v1#CUSTOMERPAGE#1", "v1#ND#00", 100L, "n0");
        putRaw(mapping, codec, "v1#CUSTOMERPAGE#1", "v1#ND#01", 101L, "n1");
        putRaw(mapping, codec, "v1#CUSTOMERPAGE#1", "v1#ND#02", 102L, "n2");
        putRaw(mapping, codec, "v1#CUSTOMERPAGE#1", "v1#ND#03", 103L, "n3");
        putRaw(mapping, codec, "v1#CUSTOMERPAGE#1", "v1#ND#04", 104L, "n4");

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", "v1#CUSTOMERPAGE#1"))
                .dynamoLimit(Integer.valueOf(1))
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows).hasSize(5);
        List<Object> names = new ArrayList<Object>();
        for (int i = 0; i < rows.size(); i++) {
            names.add(rows.get(i).get("name"));
        }
        assertThat(names).containsExactlyInAnyOrder("n0", "n1", "n2", "n3", "n4");
        assertThat(executor.lastExplain().pageCount())
                .as("Limit=1 over 5 items must follow LastEvaluatedKey")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void should_return_union_across_partition_keys_when_fan_out() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOut");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "Ada", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(2L, "Bob", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(3L, "Cyd", "OPEN"));

        QueryPlan child1 = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", ExecFixtures.encodedPk(mapping, 1L)))
                .build();
        QueryPlan child2 = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", ExecFixtures.encodedPk(mapping, 3L)))
                .build();
        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(child1);
        children.add(child2);

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY_FAN_OUT)
                .fanOut(children)
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        List<Object> names = new ArrayList<Object>();
        for (int i = 0; i < rows.size(); i++) {
            names.add(rows.get(i).get("name"));
        }
        assertThat(names).containsExactlyInAnyOrder("Ada", "Cyd");
        assertThat(names).doesNotContain("Bob");
        assertThat(executor.lastExplain().kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(executor.lastExplain().requestCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_batch_fan_out_into_fewer_round_trips_than_partitions() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOutBatch");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        int partitions = 8;
        for (int i = 0; i < partitions; i++) {
            ExecFixtures.putCustomer(ddb.client(), mapping, codec,
                    ExecFixtures.customer((long) (i + 1), "n" + i, "OPEN"));
        }

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        for (int i = 0; i < partitions; i++) {
            children.add(ExecFixtures.basePlan(mapping)
                    .kind(PlanKind.QUERY)
                    .keyCondition(KeyCondition.partitionEquals("pk",
                            ExecFixtures.encodedPk(mapping, (long) (i + 1))))
                    .build());
        }

        ExecFixtures.CountingClient counting = new ExecFixtures.CountingClient(ddb.client());
        QueryPlanExecutor executor = new QueryPlanExecutor(counting.client, codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY_FAN_OUT)
                .fanOut(children)
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        List<Object> names = new ArrayList<Object>();
        for (int i = 0; i < rows.size(); i++) {
            names.add(rows.get(i).get("name"));
        }
        assertThat(names).containsExactlyInAnyOrder("n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7");
        assertThat(counting.reads.get())
                .as("IN fan-out must not become one query/getItem/scan/executeStatement per partition")
                .isLessThan(partitions);
        assertThat(counting.reads.get())
                .as("the batch must still touch DynamoDB")
                .isGreaterThan(0);
        assertThat(executor.lastExplain().requestCount()).isLessThan(partitions);
    }

    @Test
    void should_apply_residual_and_treat_null_boolean_as_not_a_pass() {
        EntityMapping mapping = ExecFixtures.customerMapping("Residual");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "keep", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(2L, "drop", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(3L, "null-me", "OPEN"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.SCAN)
                .residual(new ResidualPredicate(ExecFixtures.residualNameOrNull("keep")))
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("keep");
    }

    @Test
    void should_execute_opt_in_scan_plan() {
        EntityMapping mapping = ExecFixtures.customerMapping("ScanOptIn");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "Ada", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(2L, "Bob", "CLOSED"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.SCAN)
                .segmentCount(1)
                .build();

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows).hasSize(2);
        assertThat(executor.lastExplain().kind()).isEqualTo(PlanKind.SCAN);
        assertThat(executor.lastExplain().actualItemsReturned()).isEqualTo(2);
        assertThat(executor.lastExplain().indexName()).isEqualTo("PRIMARY");
    }

    @Test
    void should_return_empty_when_get_item_misses() {
        EntityMapping mapping = ExecFixtures.customerMapping("GetMiss");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.GET_ITEM)
                .keyCondition(KeyCondition.partitionAndSortEquals(
                        "pk", "sk",
                        ExecFixtures.encodedPk(mapping, 99L),
                        DefaultKeyStrategy.NON_DATED_SORT_KEY))
                .build();

        assertThat(executor.execute(plan)).isEmpty();
        assertThat(executor.lastExplain().actualItemsReturned()).isZero();
    }

    private static void putRaw(EntityMapping mapping, ItemCodec codec,
                               String pk, String sk, long id, String name) {
        Map<String, Object> row = ExecFixtures.customer(id, name, "OPEN");
        java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item =
                new java.util.LinkedHashMap<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>(
                        codec.encode(row));
        item.put("pk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(pk).build());
        item.put("sk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(sk).build());
        ddb.client().putItem(b -> b.tableName(mapping.tableName()).item(item));
    }
    @Test
    void a_query_needing_more_pages_than_allowed_fails_rather_than_truncating() {
        // `PlannerConfig.maxPages` was declared, documented in the security review as a DoS
        // mitigation, and read by nothing (finding 16). Enforcing it by stopping quietly would be
        // worse than leaving it unenforced: a caller cannot tell a clipped result from a small
        // table, so a truncated read is a wrong answer that looks like a right one.
        EntityMapping mapping = ExecFixtures.customerMapping("PageCap");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        for (int i = 0; i < 12; i++) {
            putRaw(mapping, codec, "v1#CUSTOMERCAP#1", String.format("v1#ND#%02d", Integer.valueOf(i)),
                    200L + i, "c" + i);
        }
        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);

        QueryPlan unbounded = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", "v1#CUSTOMERCAP#1"))
                .dynamoLimit(Integer.valueOf(1))
                .build();
        assertThat(executor.execute(unbounded))
                .as("maxPages unset means unbounded — every row still comes back")
                .hasSize(12);

        QueryPlan capped = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", "v1#CUSTOMERCAP#1"))
                .dynamoLimit(Integer.valueOf(1))
                .maxPages(3)
                .build();
        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(capped))
                .isInstanceOf(PageLimitExceededException.class)
                .hasMessageContaining("page limit")
                .hasMessageContaining("row(s) so far");
    }

    @Test
    void a_page_limit_larger_than_the_result_does_not_fire() {
        EntityMapping mapping = ExecFixtures.customerMapping("PageCapOk");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        for (int i = 0; i < 4; i++) {
            putRaw(mapping, codec, "v1#CUSTOMERCAPOK#1", String.format("v1#ND#%02d", Integer.valueOf(i)),
                    300L + i, "d" + i);
        }
        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", "v1#CUSTOMERCAPOK#1"))
                .dynamoLimit(Integer.valueOf(1))
                .maxPages(50)
                .build();
        assertThat(new QueryPlanExecutor(ddb.client(), codec).execute(plan)).hasSize(4);
    }

}
