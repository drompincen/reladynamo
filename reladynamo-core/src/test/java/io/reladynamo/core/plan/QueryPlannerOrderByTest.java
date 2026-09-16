package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import io.reladynamo.core.plan.fixture.PlanCustomerFinder;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.reladynamo.core.plan.PlanFixtures.plan;
import static io.reladynamo.core.plan.PlanFixtures.planCustomer;
import static io.reladynamo.core.plan.PlanFixtures.planRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-08: OrderBy must become a real comparator, not a {@code toString()} substring mode.
 */
class QueryPlannerOrderByTest {

    static {
        PlanReladomoBoot.ensure();
    }

    @Test
    void payload_order_by_attaches_an_in_memory_comparator_not_native_sk() {
        OrderBy orderBy = PlanCustomerFinder.id().descendingOrderBy();
        QueryPlan plan = plan(PlanFixtures.customerById(9), orderBy, planCustomer());

        assertThat(plan.orderMode())
                .as("payload id is not the DynamoDB sort key")
                .isEqualTo(OrderMode.IN_MEMORY);
        assertThat(plan.rowComparator())
                .as("plan must carry a comparator derived from OrderBy.getAttribute(), not toString()")
                .isNotNull();

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(customerRow(1, "OPEN"));
        rows.add(customerRow(9, "OPEN"));
        rows.add(customerRow(3, "OPEN"));
        Collections.sort(rows, plan.rowComparator());

        assertThat(rows.get(0).get("id"))
                .as("descending top of {1,9,3} is 9; toString-mode plans cannot sort")
                .isEqualTo(Integer.valueOf(9));
        assertThat(rows.get(1).get("id")).isEqualTo(Integer.valueOf(3));
        assertThat(rows.get(2).get("id")).isEqualTo(Integer.valueOf(1));
    }

    @Test
    void multiple_sort_columns_compare_in_declared_order() {
        OrderBy orderBy = PlanCustomerFinder.status().ascendingOrderBy()
                .and(PlanCustomerFinder.id().descendingOrderBy());
        QueryPlan plan = plan(PlanFixtures.customerById(1), orderBy, planCustomer());

        assertThat(plan.orderMode()).isEqualTo(OrderMode.IN_MEMORY);
        assertThat(plan.rowComparator()).isNotNull();

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(customerRow(3, "OPEN"));
        rows.add(customerRow(2, "CLOSED"));
        rows.add(customerRow(1, "OPEN"));
        Collections.sort(rows, plan.rowComparator());

        assertThat(ids(rows)).containsExactly(
                Integer.valueOf(2), Integer.valueOf(3), Integer.valueOf(1));
    }

    @Test
    void mixed_asc_desc_uses_each_column_direction() {
        OrderBy orderBy = PlanCustomerFinder.status().descendingOrderBy()
                .and(PlanCustomerFinder.id().ascendingOrderBy());
        QueryPlan plan = plan(PlanFixtures.customerById(1), orderBy, planCustomer());

        assertThat(plan.rowComparator()).isNotNull();

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(customerRow(3, "OPEN"));
        rows.add(customerRow(2, "CLOSED"));
        rows.add(customerRow(1, "OPEN"));
        Collections.sort(rows, plan.rowComparator());

        assertThat(ids(rows)).containsExactly(
                Integer.valueOf(1), Integer.valueOf(3), Integer.valueOf(2));
    }

    @Test
    void exact_sort_key_chain_is_native_sk_forward() {
        java.sql.Timestamp asOf = PlanFixtures.utc(2026, java.util.Calendar.JUNE, 1);
        OrderBy orderBy = PlanRuleFinder.processingDateFrom().ascendingOrderBy()
                .and(PlanRuleFinder.businessDateFrom().ascendingOrderBy());
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), orderBy, planRule());

        assertThat(plan.orderMode()).isEqualTo(OrderMode.NATIVE_SK);
        assertThat(plan.scanIndexForward()).isTrue();
        assertThat(plan.rowComparator())
                .as("native SK still carries the comparator so a fan-out merge can reuse it")
                .isNotNull();
    }

    @Test
    void exact_sort_key_chain_descending_is_native_sk_reverse() {
        java.sql.Timestamp asOf = PlanFixtures.utc(2026, java.util.Calendar.JUNE, 1);
        OrderBy orderBy = PlanRuleFinder.processingDateFrom().descendingOrderBy()
                .and(PlanRuleFinder.businessDateFrom().descendingOrderBy());
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), orderBy, planRule());

        assertThat(plan.orderMode()).isEqualTo(OrderMode.NATIVE_SK);
        assertThat(plan.scanIndexForward()).isFalse();
    }

    @Test
    void fan_out_cannot_use_native_sk_because_order_is_per_partition() {
        java.sql.Timestamp asOf = PlanFixtures.utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanFixtures.ruleCurrent(1, asOf)
                .or(PlanFixtures.ruleCurrent(2, asOf));
        OrderBy orderBy = PlanRuleFinder.processingDateFrom().ascendingOrderBy()
                .and(PlanRuleFinder.businessDateFrom().ascendingOrderBy());
        QueryPlan plan = plan(op, orderBy, planRule());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.orderMode())
                .as("SK order is not global across partitions; merge must sort in memory")
                .isEqualTo(OrderMode.IN_MEMORY);
        assertThat(plan.rowComparator()).isNotNull();
    }

    @Test
    void in_memory_order_clears_dynamo_limit_so_rowcount_cannot_clip_before_sort() {
        OrderBy orderBy = PlanCustomerFinder.id().descendingOrderBy();
        QueryPlan plan = plan(PlanFixtures.customerById(9), orderBy, planCustomer(),
                PlannerConfig.builder().inMemoryRowCeiling(50_000).build(),
                1);

        assertThat(plan.orderMode()).isEqualTo(OrderMode.IN_MEMORY);
        assertThat(plan.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(plan.dynamoLimit())
                .as("Limit=rowcount before an in-memory sort drops the real top-N")
                .isNull();
        assertThat(plan.rowcount()).isEqualTo(1);
        assertThat(plan.inMemoryRowCeiling()).isEqualTo(50_000);
    }

    @Test
    void payload_order_with_zero_in_memory_ceiling_is_plan_009() {
        PlannerConfig config = PlannerConfig.builder().inMemoryRowCeiling(0).build();
        OrderBy orderBy = PlanCustomerFinder.status().ascendingOrderBy();

        assertThatThrownBy(() -> plan(PlanFixtures.customerById(9), orderBy, planCustomer(), config, 0))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-009");
    }

    private static Map<String, Object> customerRow(int id, String status) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Integer.valueOf(id));
        row.put("status", status);
        row.put("email", "c" + id + "@example.test");
        return row;
    }

    private static List<Integer> ids(List<Map<String, Object>> rows) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            out.add((Integer) rows.get(i).get("id"));
        }
        return out;
    }
}
