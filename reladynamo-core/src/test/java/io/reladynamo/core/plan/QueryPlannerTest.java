package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.All;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.plan.fixture.PlanCustomerFinder;
import io.reladynamo.core.plan.fixture.PlanPositionFinder;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import io.reladynamo.core.temporal.TemporalEncoder;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.reladynamo.core.plan.PlanFixtures.INFINITY;
import static io.reladynamo.core.plan.PlanFixtures.plan;
import static io.reladynamo.core.plan.PlanFixtures.planCustomer;
import static io.reladynamo.core.plan.PlanFixtures.planPosition;
import static io.reladynamo.core.plan.PlanFixtures.planRule;
import static io.reladynamo.core.plan.PlanFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryPlannerTest {

    static {
        PlanReladomoBoot.ensure();
    }

    @Test
    void should_use_getitem_when_nondated_pk_is_complete() {
        QueryPlan plan = plan(PlanFixtures.customerById(9), planCustomer());

        assertThat(plan.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(plan.indexName()).isEqualTo("PRIMARY");
        assertThat(plan.fastPath()).isEqualTo(QueryPlan.FAST_PATH_POINT_GET);
        assertThat(plan.keyConditionExpression()).contains("#pk = :pk");
        assertThat(plan.keyCondition().encodedSortKey()).isEqualTo("v1#ND");
        assertThat(plan.keyCondition().encodedPartitionKey()).isEqualTo("v1#PLANCUSTOMER#9");
    }

    @Test
    void should_use_current_asof_query_when_pk_and_infinity_processing() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), planRule());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.indexName()).isEqualTo("PRIMARY");
        assertThat(plan.fastPath()).isEqualTo(QueryPlan.FAST_PATH_CURRENT_ASOF);
        assertThat(plan.filterExpression()).contains("OUT_Z");
        assertThat(plan.estimatedItemsExamined()).isLessThanOrEqualTo(16);
        assertThat(plan.estimatedItemsReturned()).isEqualTo(1);
        assertThat(plan.consistentRead()).isTrue();
    }

    @Test
    void should_not_put_business_asof_in_base_sk_condition() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), planRule());

        String key = plan.keyConditionExpression();
        assertThat(key).doesNotContain("#B#");
        assertThat(key).doesNotContain("BETWEEN");
        assertThat(key).isEqualTo("#pk = :pk");
        assertThat(plan.kind()).isNotEqualTo(PlanKind.GET_ITEM);
    }

    @Test
    void should_use_current_gsi_range_when_sparse_gsi_configured() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        PlannerConfig config = PlannerConfig.builder()
                .estimatedVersionsPerKey(16)
                .allowGsi(true)
                .inTransaction(false)
                .build();
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf),
                PlanFixtures.planRuleWithCurrentGsi(), config);

        assertThat(plan.indexName()).isEqualTo("gsi_current");
        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.keyConditionExpression()).contains("BETWEEN");
        assertThat(plan.consistentRead()).isFalse();
        assertThat(plan.expressionAttributeNames().get("#pk"))
                .as("sparse-current GSI must bind GsiSpec.partitionKeyAttributeName, not the base table pk")
                .isEqualTo("gsi_ruleId");
        assertThat(plan.expressionAttributeNames().get("#sk"))
                .as("sparse-current GSI must bind GsiSpec.sortKeyAttributeName, not the base table sk")
                .isEqualTo("gsi_sk");
        assertThat(plan.estimatedItemsExamined())
                .as("history never enters the sparse-current index")
                .isEqualTo(1);
    }

    @Test
    void should_bind_composite_sparse_current_gsi_to_gsi_pk() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        PlannerConfig config = PlannerConfig.builder()
                .estimatedVersionsPerKey(16)
                .allowGsi(true)
                .inTransaction(false)
                .build();
        QueryPlan plan = plan(PlanFixtures.positionCurrent(7L, 3, asOf),
                PlanFixtures.planPositionWithCurrentGsi(), config);

        assertThat(plan.indexName()).isEqualTo("gsi_current");
        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.expressionAttributeNames().get("#pk")).isEqualTo("gsi_pk");
        assertThat(plan.expressionAttributeNames().get("#sk")).isEqualTo("gsi_sk");
        assertThat(plan.keyCondition().encodedPartitionKey()).isEqualTo("v1#PLANPOSITION#7#3");
        assertThat(plan.estimatedItemsExamined()).isEqualTo(1);
        assertThat(plan.consistentRead()).isFalse();
    }

    @Test
    void should_forbid_gsi_when_in_transaction() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        PlannerConfig config = PlannerConfig.builder()
                .estimatedVersionsPerKey(16)
                .allowGsi(true)
                .inTransaction(true)
                .build();
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf),
                PlanFixtures.planRuleWithCurrentGsi(), config);

        assertThat(plan.indexName()).isEqualTo("PRIMARY");
        assertThat(plan.consistentRead()).isTrue();
        assertThat(plan.fastPath()).isEqualTo(QueryPlan.FAST_PATH_CURRENT_ASOF);
    }

    @Test
    void should_fan_out_or_of_two_partition_keys() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanFixtures.ruleCurrent(1, asOf)
                .or(PlanFixtures.ruleCurrent(2, asOf));
        QueryPlan plan = plan(op, planRule());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.fanOut()).hasSize(2);
        assertThat(plan.kind()).isNotEqualTo(PlanKind.SCAN);
        assertThat(plan.fanOut().get(0).kind()).isNotEqualTo(PlanKind.SCAN);
        assertThat(plan.fanOut().get(1).kind()).isNotEqualTo(PlanKind.SCAN);
    }

    @Test
    void should_fan_out_in_of_partition_keys_at_the_cap() {
        IntHashSet ids = new IntHashSet();
        for (int i = 1; i <= 100; i++) {
            ids.add(i);
        }
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanRuleFinder.ruleId().in(ids)
                .and(PlanRuleFinder.businessDate().eq(asOf));
        QueryPlan plan = plan(op, planRule());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.fanOut()).hasSize(100);
    }

    @Test
    void should_reject_or_fan_out_past_limit() {
        IntHashSet ids = new IntHashSet();
        for (int i = 1; i <= 101; i++) {
            ids.add(i);
        }
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanRuleFinder.ruleId().in(ids)
                .and(PlanRuleFinder.businessDate().eq(asOf));

        assertThatThrownBy(() -> plan(op, planRule()))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-002")
                .hasMessageContaining("fan-out of 101")
                .hasMessageContaining("pkFanOutLimit=100");
    }

    @Test
    void should_throw_scan_required_when_all_and_scans_disabled() {
        Operation op = new All(PlanRuleFinder.ruleId());
        assertThatThrownBy(() -> plan(op, planRule()))
                .isInstanceOf(ReladynamoScanRequiredException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-001")
                .hasMessageContaining("Scan required for io.reladynamo.core.plan.fixture.PlanRule")
                .hasMessageContaining("on table PLAN_RULE")
                .hasMessageContaining("scans are disabled")
                .hasMessageContaining("allowTableScan(true)");
    }

    @Test
    void should_scan_when_all_and_scans_enabled() {
        Operation op = new All(PlanRuleFinder.ruleId());
        PlannerConfig config = PlannerConfig.builder().allowTableScan(true).build();
        QueryPlan plan = plan(op, planRule(), config);

        assertThat(plan.kind()).isEqualTo(PlanKind.SCAN);
        assertThat(plan.segmentCount()).isEqualTo(4);
        assertThat(plan.estimatedItemsExamined()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void should_encode_partition_key_in_the_decided_uppercase_grammar() {
        QueryPlan plan = plan(PlanFixtures.customerById(9), planCustomer());

        assertThat(plan.keyCondition().encodedPartitionKey()).isEqualTo("v1#PLANCUSTOMER#9");
        assertThat(plan.keyCondition().encodedSortKey()).isEqualTo("v1#ND");
    }

    @Test
    void should_treat_processing_infinity_as_to_equality_even_when_design_infinity_differs() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Timestamp unrelatedInfinity = utc(8888, java.util.Calendar.JANUARY, 1);
        PhysicalDesign mismatched = PhysicalDesign.builder(planRule().entity())
                .infinity(unrelatedInfinity)
                .businessToInclusive(false)
                .processingToInclusive(false)
                .build();

        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), mismatched);

        assertThat(plan.fastPath())
                .as("processing as-of infinity is the AsOfAttribute sentinel, not PhysicalDesign.infinity(): %s",
                        plan.toAssertableString())
                .isEqualTo(QueryPlan.FAST_PATH_CURRENT_ASOF);
        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.keyCondition().encodedSortKey()).isNull();
        String encodedReladomoInf = TemporalEncoder.encode(INFINITY);
        boolean filterBindsReladomoInfinity = false;
        for (ExpressionValue value : plan.expressionAttributeValues().values()) {
            if (value.s() != null && encodedReladomoInf.equals(value.s())) {
                filterBindsReladomoInfinity = true;
            }
        }
        assertThat(filterBindsReladomoInfinity)
                .as("filter values must bind Reladomo infinity %s, got %s",
                        encodedReladomoInf, plan.expressionAttributeValues())
                .isTrue();
        assertThat(plan.expressionAttributeNames().values()).contains("OUT_Z");
        assertThat(plan.expressionAttributeNames().values())
                .as("infinity as-of is TO equality, never IN_Z containment: %s", plan.toAssertableString())
                .doesNotContain("IN_Z");
    }

    @Test
    void should_encode_asof_infinity_as_to_column_equality() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), planRule());

        assertThat(plan.filterExpression()).contains("OUT_Z");
        assertThat(plan.filterExpression()).contains("=");
        boolean hasOutEq = plan.filterExpression().contains("OUT_Z")
                && plan.filterExpression().matches(".*#n[0-9]+ = :v[0-9]+.*");
        assertThat(hasOutEq || plan.filterExpression().contains("=")).isTrue();
        assertThat(plan.expressionAttributeNames().values()).contains("OUT_Z");
    }

    @Test
    void should_encode_asof_half_open_containment() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.businessDate().eq(asOf))
                .and(PlanRuleFinder.processingDate().eq(asOf));
        QueryPlan plan = plan(op, planRule());

        String filter = plan.filterExpression();
        assertThat(filter).contains("<=");
        assertThat(filter).contains(">");
        assertThat(plan.expressionAttributeNames().values()).contains("FROM_Z", "THRU_Z");
    }

    @Test
    void should_not_set_dynamo_limit_when_filter_present() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), planRule(),
                PlannerConfig.defaults(), 10, PlanningPurpose.FIND);

        assertThat(plan.filterExpression()).isNotBlank();
        assertThat(plan.dynamoLimit()).isNull();
    }

    @Test
    void should_set_dynamo_limit_only_on_pure_key_get() {
        QueryPlan plan = plan(PlanFixtures.customerById(9), planCustomer(),
                PlannerConfig.defaults(), 5, PlanningPurpose.FIND);
        assertThat(plan.filterExpression()).isNull();
        assertThat(plan.residual().isEmpty()).isTrue();
        assertThat(plan.dynamoLimit()).isEqualTo(5);
    }

    @Test
    void should_plan_payload_filter_on_keyed_query() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanFixtures.ruleCurrent(42, asOf)
                .and(PlanRuleFinder.active().eq(true));
        QueryPlan plan = plan(op, planRule());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.filterExpression()).contains("IS_ACTIVE");
        assertThat(plan.dynamoLimit()).isNull();
    }

    @Test
    void should_leave_ends_with_as_residual() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanFixtures.ruleCurrent(42, asOf)
                .and(PlanRuleFinder.ruleName().endsWith(" cop"));
        QueryPlan plan = plan(op, planRule());

        assertThat(plan.residual().isEmpty()).isFalse();
        assertThat(plan.residualOperationDump().toLowerCase()).contains("end");
    }

    @Test
    void should_plan_compound_pk_position_fast_path() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        QueryPlan plan = plan(PlanFixtures.positionCurrent(42L, 7, asOf), planPosition());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.fastPath()).isEqualTo(QueryPlan.FAST_PATH_CURRENT_ASOF);
        assertThat(plan.keyCondition().encodedPartitionKey()).isEqualTo("v1#PLANPOSITION#42#7");
        assertThat(plan.keyConditionExpression()).doesNotContain("#B#");
    }

    @Test
    void should_fan_out_or_of_compound_partition_keys() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation left = PlanFixtures.positionCurrent(1L, 10, asOf);
        Operation right = PlanFixtures.positionCurrent(2L, 20, asOf);
        QueryPlan plan = plan((Operation) left.or(right), planPosition());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.fanOut()).hasSize(2);
    }

    @Test
    void should_require_allow_scan_delete_for_mass_delete_without_pk() {
        Operation op = new All(PlanRuleFinder.ruleId());
        PlannerConfig config = PlannerConfig.builder().allowTableScan(true).allowScanDelete(false).build();
        assertThatThrownBy(() -> plan(op, planRule(), config, 0, PlanningPurpose.DELETE))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-008");
    }

    @Test
    void should_reject_compute_function() {
        Operation op = PlanFixtures.customerById(1);
        assertThatThrownBy(() -> plan(op, planCustomer(), PlannerConfig.defaults(), 0,
                PlanningPurpose.COMPUTE_FUNCTION))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-010");
    }

    @Test
    void should_plan_on_analyzed_operation_not_original() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation original = (Operation) PlanRuleFinder.ruleId().eq(7)
                .and(PlanRuleFinder.businessDate().eq(asOf));
        AnalyzedOperation analyzed = new AnalyzedOperation(original);
        assertThat(analyzed.getAnalyzedOperation()).isNotSameAs(analyzed.getOriginalOperation())
                .as("Reladomo injects processingDate = infinity on the analyzed form");

        QueryPlan fromAnalyzed = new QueryPlanner().plan(analyzed, planRule());
        assertThat(fromAnalyzed.fastPath()).isEqualTo(QueryPlan.FAST_PATH_CURRENT_ASOF);
        assertThat(fromAnalyzed.filterExpression()).contains("OUT_Z");
    }

    @Test
    void should_use_email_gsi_when_pk_unbound() {
        Operation op = (Operation) PlanCustomerFinder.email().eq("a@b");
        QueryPlan plan = plan(op, planCustomer());
        assertThat(plan.indexName()).isEqualTo("gsi_email");
        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.consistentRead()).isFalse();
    }

    @Test
    void should_refuse_email_gsi_in_transaction_without_base_pk() {
        Operation op = (Operation) PlanCustomerFinder.email().eq("a@b");
        PlannerConfig tx = PlannerConfig.builder().inTransaction(true).allowGsi(true).build();
        assertThatThrownBy(() -> plan(op, planCustomer(), tx))
                .isInstanceOf(ReladynamoScanRequiredException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-001");
    }

    @Test
    void should_fan_out_in_against_gsi_partition_key() {
        Set<String> emails = new HashSet<String>();
        emails.add("a@b");
        emails.add("c@d");
        emails.add("e@f");
        Operation op = (Operation) PlanCustomerFinder.email().in(emails);
        QueryPlan plan = plan(op, planCustomer());

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.fanOut()).hasSize(3);
        assertThat(plan.kind()).isNotEqualTo(PlanKind.SCAN);
        for (int i = 0; i < plan.fanOut().size(); i++) {
            QueryPlan part = plan.fanOut().get(i);
            assertThat(part.indexName()).isEqualTo("gsi_email");
            assertThat(part.kind()).isEqualTo(PlanKind.QUERY);
            assertThat(part.kind()).isNotEqualTo(PlanKind.SCAN);
            assertThat(part.consistentRead()).isFalse();
        }
    }

    @Test
    void should_refuse_payload_in_when_no_gsi() {
        Set<String> statuses = new HashSet<String>();
        statuses.add("OPEN");
        statuses.add("CLOSED");
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanPositionFinder.status().in(statuses)
                .and(PlanPositionFinder.businessDate().eq(asOf));
        assertThatThrownBy(() -> plan(op, planPosition()))
                .isInstanceOf(ReladynamoScanRequiredException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-001")
                .hasMessageContaining("scans are disabled");
    }

    @Test
    void should_fan_out_fk_in_on_dated_entity_with_asof_filter() {
        PhysicalDesign design = PhysicalDesign.builder(planPosition().entity())
                .infinity(INFINITY)
                .addGsi(GsiSpec.foreignKey("gsi_status", "status"))
                .build();
        Set<String> statuses = new HashSet<String>();
        statuses.add("OPEN");
        statuses.add("CLOSED");
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanPositionFinder.status().in(statuses)
                .and(PlanPositionFinder.businessDate().eq(asOf));
        QueryPlan plan = plan(op, design);

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.fanOut()).hasSize(2);
        assertThat(plan.kind()).isNotEqualTo(PlanKind.SCAN);
        QueryPlan part = plan.fanOut().get(0);
        assertThat(part.indexName()).isEqualTo("gsi_status");
        assertThat(part.keyCondition().encodedPartitionKey()).startsWith("v1#GSI#STATUS#");
        assertThat(part.filterExpression()).contains("OUT_Z");
        assertThat(part.consistentRead()).isFalse();
    }

    @Test
    void should_reject_gsi_in_past_fan_out_limit() {
        Set<String> emails = new HashSet<String>();
        for (int i = 0; i < 101; i++) {
            emails.add("user" + i + "@ex.com");
        }
        Operation op = (Operation) PlanCustomerFinder.email().in(emails);
        assertThatThrownBy(() -> plan(op, planCustomer()))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-002")
                .hasMessageContaining("pkFanOutLimit=100");
    }

    @Test
    void should_self_check_reladomo_layouts() {
        Operation and = (Operation) PlanRuleFinder.ruleId().eq(1).and(PlanRuleFinder.active().eq(true));
        Operation or = (Operation) PlanRuleFinder.ruleId().eq(1).or(PlanRuleFinder.ruleId().eq(2));
        io.reladynamo.core.plan.reladomo.ReladomoOperationAccess.selfCheck(and, or, and);
    }

    @Test
    void should_use_getitem_when_dated_from_columns_are_equality_bound() {
        Timestamp from = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.businessDateFrom().eq(from))
                .and(PlanRuleFinder.processingDateFrom().eq(from));
        QueryPlan plan = plan(op, planRule());

        assertThat(plan.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(plan.keyCondition().encodedSortKey()).startsWith("v1#P#");
        assertThat(plan.keyCondition().encodedSortKey()).contains("#B#");
        assertThat(plan.fastPath()).isEqualTo(QueryPlan.FAST_PATH_POINT_GET);
    }

    @Test
    void explain_plan_mirrors_static_query_plan() {
        QueryPlan plan = plan(PlanFixtures.customerById(9), planCustomer());
        ExplainPlan explain = plan.explain();
        assertThat(explain.kind()).isEqualTo(plan.kind());
        assertThat(explain.fastPath()).isEqualTo(plan.fastPath());
        assertThat(explain.estimatedItemsExamined()).isEqualTo(plan.estimatedItemsExamined());
    }

    @Test
    void should_return_empty_when_pk_equality_contradicts_in() {
        IntHashSet ids = new IntHashSet();
        ids.add(2);
        ids.add(3);
        Operation op = (Operation) PlanRuleFinder.ruleId().in(ids)
                .and(PlanRuleFinder.ruleId().eq(1));
        QueryPlan planned = plan(op, planRule());
        assertThat(flatten(planned)).allMatch(p -> p.kind() == PlanKind.EMPTY
                || !acceptsRuleId(p, 1));
        assertThat(acceptsAny(planned, 1)).isFalse();
    }

    @Test
    void should_keep_in_when_pk_equality_is_inside_the_set() {
        IntHashSet ids = new IntHashSet();
        ids.add(1);
        ids.add(2);
        Operation op = (Operation) PlanRuleFinder.ruleId().in(ids)
                .and(PlanRuleFinder.ruleId().eq(1));
        QueryPlan planned = plan(op, planRule());
        assertThat(acceptsAny(planned, 1)).isTrue();
        assertThat(acceptsAny(planned, 2)).isFalse();
    }

    @Test
    void should_return_empty_when_two_pk_equalities_disagree() {
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.ruleId().eq(2));
        QueryPlan planned = plan(op, planRule());
        assertThat(acceptsAny(planned, 1)).isFalse();
        assertThat(acceptsAny(planned, 2)).isFalse();
    }

    @Test
    void should_return_empty_when_pk_equality_is_excluded_by_not_eq() {
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.ruleId().notEq(1));
        QueryPlan planned = plan(op, planRule());
        assertThat(acceptsAny(planned, 1)).isFalse();
    }

    @Test
    void should_keep_not_eq_when_it_does_not_contradict_pk_equality() {
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.ruleId().notEq(99));
        QueryPlan planned = plan(op, planRule());
        assertThat(acceptsAny(planned, 1)).isTrue();
        assertThat(acceptsAny(planned, 99)).isFalse();
    }

    @Test
    void should_return_empty_when_pk_in_is_entirely_not_in() {
        IntHashSet in = new IntHashSet();
        in.add(2);
        in.add(3);
        IntHashSet notIn = new IntHashSet();
        notIn.add(2);
        notIn.add(3);
        Operation op = (Operation) PlanRuleFinder.ruleId().in(in)
                .and(PlanRuleFinder.ruleId().notIn(notIn));
        QueryPlan planned = plan(op, planRule());
        assertThat(acceptsAny(planned, 2)).isFalse();
        assertThat(acceptsAny(planned, 3)).isFalse();
    }

    @Test
    void should_return_empty_when_pk_equality_misses_range() {
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.ruleId().greaterThan(5));
        QueryPlan planned = plan(op, planRule());
        assertThat(acceptsAny(planned, 1)).isFalse();
    }

    @Test
    void should_keep_range_when_pk_equality_satisfies_it() {
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(7)
                .and(PlanRuleFinder.ruleId().greaterThan(5));
        QueryPlan planned = plan(op, planRule());
        assertThat(acceptsAny(planned, 7)).isTrue();
        assertThat(acceptsAny(planned, 5)).isFalse();
    }

    private static boolean acceptsAny(QueryPlan plan, int ruleId) {
        List<QueryPlan> parts = flatten(plan);
        for (int i = 0; i < parts.size(); i++) {
            if (acceptsRuleId(parts.get(i), ruleId)) {
                return true;
            }
        }
        return false;
    }

    private static List<QueryPlan> flatten(QueryPlan plan) {
        if (plan.kind() == PlanKind.QUERY_FAN_OUT) {
            return plan.fanOut();
        }
        return java.util.Collections.singletonList(plan);
    }

    private static boolean acceptsRuleId(QueryPlan plan, int ruleId) {
        if (plan.kind() == PlanKind.EMPTY) {
            return false;
        }
        java.sql.Timestamp from = utc(2026, java.util.Calendar.JANUARY, 1);
        java.sql.Timestamp thru = utc(2026, java.util.Calendar.DECEMBER, 1);
        io.reladynamo.core.plan.fixture.PlanRuleData data =
                new io.reladynamo.core.plan.fixture.PlanRuleData();
        data.setRuleId(ruleId);
        data.setRuleName("r" + ruleId);
        data.setPriority(ruleId);
        data.setResultLabel("KEEP");
        data.setActive(true);
        data.setBusinessDateFrom(from);
        data.setBusinessDateTo(thru);
        data.setProcessingDateFrom(from);
        data.setProcessingDateTo(INFINITY);
        return new io.reladynamo.core.plan.eval.QueryPlanInterpreter()
                .accepts(plan, AdversarialOperationGeneratorTest.itemOf(data), data);
    }
}
