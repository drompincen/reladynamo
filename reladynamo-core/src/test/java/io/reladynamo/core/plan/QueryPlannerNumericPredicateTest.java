package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.plan.fixture.PlanPositionFinder;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import org.eclipse.collections.impl.set.mutable.primitive.DoubleHashSet;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Map;

import static io.reladynamo.core.plan.PlanFixtures.INFINITY;
import static io.reladynamo.core.plan.PlanFixtures.plan;
import static io.reladynamo.core.plan.PlanFixtures.planPosition;
import static io.reladynamo.core.plan.PlanFixtures.planRule;
import static io.reladynamo.core.plan.PlanFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-04: Double/Float/BigDecimal must not be planned as DynamoDB {@code N}. IEEE-754 bytes and
 * decimal strings are not ordered the same way Reladomo compares Java values, so these
 * predicates become typed residuals evaluated after decode.
 */
class QueryPlannerNumericPredicateTest {

    static {
        PlanReladomoBoot.ensure();
    }

    @Test
    void should_leave_double_equality_as_residual_instead_of_dynamo_n() {
        QueryPlan plan = plan(quantityEq(1.5d), planPosition());

        assertNoNumericWireBinding(plan);
        assertThat(plan.residual().isEmpty()).isFalse();
        assertThat(plan.residualOperationDump().toLowerCase()).contains("quantity");
        assertThat(plan.dynamoLimit())
                .as("a residual filter cannot use DynamoDB Limit or the adapter stops short")
                .isNull();
        assertThat(plan.estimatedItemsExamined())
                .as("dropping the wire filter must not claim DDB already discarded versions: %s",
                        plan.toAssertableString())
                .isEqualTo(PlannerConfig.DEFAULT_ESTIMATED_VERSIONS_PER_KEY);
    }

    @Test
    void should_leave_each_double_range_operator_as_residual() {
        assertRangeIsResidual(PlanPositionFinder.quantity().greaterThan(-2.0d));
        assertRangeIsResidual(PlanPositionFinder.quantity().greaterThanEquals(-2.0d));
        assertRangeIsResidual(PlanPositionFinder.quantity().lessThan(8.0d));
        assertRangeIsResidual(PlanPositionFinder.quantity().lessThanEquals(8.0d));
    }

    @Test
    void should_leave_double_between_as_residual() {
        assertRangeIsResidual(PlanPositionFinder.quantity().greaterThanEquals(-4.0d)
                .and(PlanPositionFinder.quantity().lessThanEquals(1.5d)));
    }

    @Test
    void should_leave_double_in_as_residual() {
        DoubleHashSet set = new DoubleHashSet();
        set.add(-0.0d);
        set.add(1.5d);
        set.add(Double.NEGATIVE_INFINITY);
        QueryPlan plan = plan(withCurrentKey(PlanPositionFinder.quantity().in(set)), planPosition());

        assertNoNumericWireBinding(plan);
        assertThat(plan.residual().isEmpty()).isFalse();
        assertThat(plan.residualOperationDump().toLowerCase()).contains("quantity");
    }

    @Test
    void should_still_emit_integer_payload_as_dynamo_n() {
        Timestamp asOf = utc(2026, Calendar.JUNE, 1);
        QueryPlan plan = plan(PlanRuleFinder.ruleId().eq(42)
                .and(PlanRuleFinder.businessDate().eq(asOf))
                .and(PlanRuleFinder.priority().eq(7)), planRule());

        assertThat(plan.filterExpression().toUpperCase()).contains("PRIORITY");
        boolean foundN = false;
        for (Map.Entry<String, ExpressionValue> e : plan.expressionAttributeValues().entrySet()) {
            if (e.getValue().kind() == ExpressionValue.Kind.N && "7".equals(e.getValue().n())) {
                foundN = true;
            }
        }
        assertThat(foundN)
                .as("int/long/short stay DynamoDB N; only Double/Float/BigDecimal are residual. plan=%s values=%s",
                        plan.toAssertableString(), plan.expressionAttributeValues())
                .isTrue();
    }

    @Test
    void should_refuse_double_partition_key_component() {
        EntityMapping entity = new EntityMapping(
                "io.reladynamo.core.plan.fixture.PlanPosition",
                "PLAN_POSITION",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, INFINITY),
                Arrays.asList(
                        new AttributeMapping("quantity", "QUANTITY", "double", true, false),
                        new AttributeMapping("status", "STATUS", "String", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)
                ));
        PhysicalDesign design = PhysicalDesign.builder(entity).infinity(INFINITY).build();
        Timestamp asOf = utc(2026, Calendar.JUNE, 1);
        Operation op = PlanPositionFinder.quantity().eq(1.5d)
                .and(PlanPositionFinder.businessDate().eq(asOf));

        assertThatThrownBy(() -> plan(op, design))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-012");
    }

    private static void assertRangeIsResidual(Operation range) {
        QueryPlan plan = plan(withCurrentKey(range), planPosition());
        assertNoNumericWireBinding(plan);
        assertThat(plan.residual().isEmpty()).isFalse();
        assertThat(plan.residualOperationDump().toLowerCase()).contains("quantity");
    }

    private static Operation quantityEq(double quantity) {
        return withCurrentKey(PlanPositionFinder.quantity().eq(quantity));
    }

    private static Operation withCurrentKey(Operation quantityPredicate) {
        Timestamp asOf = utc(2026, Calendar.JUNE, 1);
        return PlanPositionFinder.accountId().eq(1L)
                .and(PlanPositionFinder.productId().eq(2))
                .and(PlanPositionFinder.businessDate().eq(asOf))
                .and(quantityPredicate);
    }

    private static void assertNoNumericWireBinding(QueryPlan plan) {
        String filter = plan.filterExpression();
        if (filter != null) {
            assertThat(filter.toUpperCase())
                    .as("double predicates must not become a DynamoDB filter; filter=%s values=%s",
                            filter, plan.expressionAttributeValues())
                    .doesNotContain("QUANTITY");
        }
        for (Map.Entry<String, ExpressionValue> e : plan.expressionAttributeValues().entrySet()) {
            assertThat(e.getValue().kind())
                    .as("Double/Float/BigDecimal must never be bound as N (%s=%s)",
                            e.getKey(), e.getValue())
                    .isNotEqualTo(ExpressionValue.Kind.N);
        }
    }
}
