package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.OrderMode;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.ReladynamoUnplannableOperationException;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-08: executor must sort, apply limits after dedup+order, and refuse unbounded in-memory
 * accumulation rather than return a plausible wrong top-N.
 */
class QueryPlanExecutorOrderSemanticsTest {

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
    void descending_top_one_returns_the_max_even_when_it_lives_in_the_last_partition() {
        EntityMapping mapping = ExecFixtures.customerMapping("TopOneLastPartition");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbClient client = ddb.client();
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(1L, "alpha", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(2L, "middle", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(3L, "omega", "OPEN"));

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(partitionQuery(mapping, 1L));
        children.add(partitionQuery(mapping, 2L));
        children.add(partitionQuery(mapping, 3L));

        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY_FAN_OUT)
                .fanOut(children)
                .orderMode(OrderMode.IN_MEMORY)
                .rowComparator(nameDescending())
                .rowcount(1)
                .build();

        List<Map<String, Object>> rows = new QueryPlanExecutor(client, codec).execute(plan);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name"))
                .as("top-1 name DESC must be omega (last partition), not alpha from the first child")
                .isEqualTo("omega");
    }

    @Test
    void multiple_sort_columns_order_the_union() {
        EntityMapping mapping = ExecFixtures.customerMapping("MultiColOrder");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbClient client = ddb.client();
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(1L, "b", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(2L, "a", "CLOSED"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(3L, "c", "OPEN"));

        QueryPlan plan = fanOutAll(mapping, 1L, 2L, 3L)
                .orderMode(OrderMode.IN_MEMORY)
                .rowComparator(statusAscThenNameAsc())
                .build();

        List<Map<String, Object>> rows = new QueryPlanExecutor(client, codec).execute(plan);

        assertThat(names(rows)).containsExactly("a", "b", "c");
        assertThat(statuses(rows)).containsExactly("CLOSED", "OPEN", "OPEN");
    }

    @Test
    void mixed_asc_desc_orders_each_column_independently() {
        EntityMapping mapping = ExecFixtures.customerMapping("MixedDirOrder");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbClient client = ddb.client();
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(1L, "b", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(2L, "a", "CLOSED"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(3L, "c", "OPEN"));

        QueryPlan plan = fanOutAll(mapping, 1L, 2L, 3L)
                .orderMode(OrderMode.IN_MEMORY)
                .rowComparator(statusDescThenNameAsc())
                .build();

        List<Map<String, Object>> rows = new QueryPlanExecutor(client, codec).execute(plan);

        assertThat(names(rows)).containsExactly("b", "c", "a");
    }

    @Test
    void overlapping_or_branches_return_one_logical_row_and_count_one() {
        EntityMapping mapping = ExecFixtures.customerMapping("OrUnionDedup");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbClient client = ddb.client();
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(7L, "Ada", "OPEN"));

        // Mixed GET_ITEM + QUERY so FanOutSelect cannot collapse; both children hit the same item.
        QueryPlan get = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.GET_ITEM)
                .keyCondition(KeyCondition.partitionAndSortEquals(
                        "pk", "sk",
                        ExecFixtures.encodedPk(mapping, 7L),
                        DefaultKeyStrategy.NON_DATED_SORT_KEY))
                .build();
        QueryPlan query = partitionQuery(mapping, 7L);
        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(get);
        children.add(query);

        QueryPlan plan = ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY_FAN_OUT)
                .fanOut(children)
                .build();

        QueryPlanExecutor executor = new QueryPlanExecutor(client, codec);
        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows)
                .as("overlapping OR of the same physical item is one logical row")
                .hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("Ada");
        assertThat(rows.size())
                .as("count() is execute().size(); overlapping branches must not inflate it")
                .isEqualTo(1);
    }

    @Test
    void limit_applies_after_ordering() {
        EntityMapping mapping = ExecFixtures.customerMapping("LimitAfterOrder");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbClient client = ddb.client();
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(1L, "alpha", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(2L, "middle", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(3L, "omega", "OPEN"));

        QueryPlan plan = fanOutAll(mapping, 1L, 2L, 3L)
                .orderMode(OrderMode.IN_MEMORY)
                .rowComparator(nameDescending())
                .rowcount(2)
                .build();

        List<Map<String, Object>> rows = new QueryPlanExecutor(client, codec).execute(plan);

        assertThat(names(rows)).containsExactly("omega", "middle");
    }

    @Test
    void in_memory_sort_that_would_exceed_the_row_ceiling_is_plan_007() {
        EntityMapping mapping = ExecFixtures.customerMapping("OrderCeiling");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbClient client = ddb.client();
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(1L, "alpha", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(2L, "middle", "OPEN"));
        ExecFixtures.putCustomer(client, mapping, codec, ExecFixtures.customer(3L, "omega", "OPEN"));

        QueryPlan plan = fanOutAll(mapping, 1L, 2L, 3L)
                .orderMode(OrderMode.IN_MEMORY)
                .rowComparator(nameDescending())
                .rowcount(1)
                .inMemoryRowCeiling(2)
                .build();

        assertThatThrownBy(() -> new QueryPlanExecutor(client, codec).execute(plan))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-007")
                .hasMessageContaining("inMemoryRowCeiling=2");
    }

    private static QueryPlan partitionQuery(EntityMapping mapping, long id) {
        return ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", ExecFixtures.encodedPk(mapping, id)))
                .build();
    }

    private static QueryPlan.Builder fanOutAll(EntityMapping mapping, long a, long b, long c) {
        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(partitionQuery(mapping, a));
        children.add(partitionQuery(mapping, b));
        children.add(partitionQuery(mapping, c));
        return ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY_FAN_OUT)
                .fanOut(children);
    }

    private static Comparator<Map<String, Object>> nameDescending() {
        return new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                return String.valueOf(right.get("name")).compareTo(String.valueOf(left.get("name")));
            }
        };
    }

    private static Comparator<Map<String, Object>> statusAscThenNameAsc() {
        return new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int status = String.valueOf(left.get("status")).compareTo(String.valueOf(right.get("status")));
                if (status != 0) {
                    return status;
                }
                return String.valueOf(left.get("name")).compareTo(String.valueOf(right.get("name")));
            }
        };
    }

    private static Comparator<Map<String, Object>> statusDescThenNameAsc() {
        return new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int status = String.valueOf(right.get("status")).compareTo(String.valueOf(left.get("status")));
                if (status != 0) {
                    return status;
                }
                return String.valueOf(left.get("name")).compareTo(String.valueOf(right.get("name")));
            }
        };
    }

    private static List<String> names(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(String.valueOf(rows.get(i).get("name")));
        }
        return out;
    }

    private static List<String> statuses(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(String.valueOf(rows.get(i).get("status")));
        }
        return out;
    }
}
