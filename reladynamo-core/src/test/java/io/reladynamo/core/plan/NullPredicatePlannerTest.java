package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.plan.eval.QueryPlanInterpreter;
import io.reladynamo.core.plan.fixture.PlanCustomerFinder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-05: Reladomo {@code isNull()}/{@code isNotNull()} must not be translated as
 * {@code attribute_not_exists}/{@code attribute_exists}. The codec stores an explicit
 * DynamoDB NULL, which exists.
 */
class NullPredicatePlannerTest {

    static {
        PlanReladomoBoot.ensure();
    }

    @Test
    void should_translate_isNull_as_missing_or_explicit_null_type() {
        QueryPlan plan = planScan(PlanCustomerFinder.status().isNull());

        String filter = plan.filterExpression();
        assertThat(filter)
                .as("isNull must include a NULL-type check, not only attribute_not_exists: %s", filter)
                .contains("attribute_not_exists")
                .contains("attribute_type")
                .contains("OR");
        assertThat(filter)
                .as("bare attribute_not_exists excludes every stored NULL")
                .isNotEqualTo("attribute_not_exists(#STATUS)");
        assertThat(bindsNullType(plan))
                .as("must bind the DynamoDB type name NULL, got %s", plan.expressionAttributeValues())
                .isTrue();
    }

    @Test
    void should_translate_isNotNull_as_exists_and_not_null_type() {
        QueryPlan plan = planScan(PlanCustomerFinder.status().isNotNull());

        String filter = plan.filterExpression();
        assertThat(filter)
                .as("isNotNull must exclude explicit NULL, not only attribute_exists: %s", filter)
                .contains("attribute_exists")
                .contains("attribute_type")
                .contains("NOT");
        assertThat(filter)
                .as("bare attribute_exists includes every stored NULL")
                .isNotEqualTo("attribute_exists(#STATUS)");
        assertThat(bindsNullType(plan)).isTrue();
    }

    @Test
    void should_accept_explicit_null_and_missing_when_isNull() {
        QueryPlan plan = planScan(PlanCustomerFinder.status().isNull());
        QueryPlanInterpreter interp = new QueryPlanInterpreter();

        assertThat(interp.accepts(plan, item(ExpressionValue.nul()))).isTrue();
        assertThat(interp.accepts(plan, itemAbsent())).isTrue();
        assertThat(interp.accepts(plan, item(ExpressionValue.s("")))).isFalse();
        assertThat(interp.accepts(plan, item(ExpressionValue.s("ACTIVE")))).isFalse();
    }

    @Test
    void should_reject_explicit_null_and_missing_when_isNotNull() {
        QueryPlan plan = planScan(PlanCustomerFinder.status().isNotNull());
        QueryPlanInterpreter interp = new QueryPlanInterpreter();

        assertThat(interp.accepts(plan, item(ExpressionValue.nul()))).isFalse();
        assertThat(interp.accepts(plan, itemAbsent())).isFalse();
        assertThat(interp.accepts(plan, item(ExpressionValue.s("")))).isTrue();
        assertThat(interp.accepts(plan, item(ExpressionValue.s("ACTIVE")))).isTrue();
    }

    @Test
    void should_keep_isNull_as_payload_filter_on_point_get() {
        Operation op = (Operation) PlanCustomerFinder.id().eq(1)
                .and(PlanCustomerFinder.status().isNull());
        QueryPlan plan = PlanFixtures.plan(op, nullableStatusCustomer());

        assertThat(plan.kind()).isEqualTo(PlanKind.GET_ITEM);
        assertThat(plan.filterExpression())
                .contains("attribute_not_exists")
                .contains("attribute_type");
    }

    /**
     * Finding 27: native {@code <>} matches a stored DynamoDB NULL because the types
     * differ. Emit the R-05 isNotNull shape AND the inequality so SQL UNKNOWN is
     * excluded. A missing attribute (schema evolution) is Reladomo isNull, so notEq
     * is UNKNOWN there too.
     */
    @Test
    void should_translate_notEq_as_exists_and_not_null_type_and_inequality() {
        QueryPlan plan = planScan(PlanCustomerFinder.status().notEq("ACTIVE"));

        String filter = plan.filterExpression();
        assertThat(filter)
                .as("notEq must reuse the R-05 isNotNull shape, not emit bare <>: %s", filter)
                .contains("attribute_exists")
                .contains("attribute_type")
                .contains("NOT")
                .contains("<>");
        assertThat(filter.trim().startsWith("#") && !filter.contains("attribute_exists"))
                .as("bare #attr <> :v includes stored NULL; got %s", filter)
                .isFalse();
        assertThat(bindsNullType(plan))
                .as("must bind the DynamoDB type name NULL, got %s", plan.expressionAttributeValues())
                .isTrue();
    }

    @Test
    void should_reject_explicit_null_and_missing_when_notEq() {
        QueryPlan plan = planScan(PlanCustomerFinder.status().notEq("ACTIVE"));
        QueryPlanInterpreter interp = new QueryPlanInterpreter();

        assertThat(interp.accepts(plan, item(ExpressionValue.nul())))
                .as("SQL UNKNOWN: stored NULL <> 'ACTIVE' must not match. filter=%s",
                        plan.filterExpression())
                .isFalse();
        assertThat(interp.accepts(plan, itemAbsent()))
                .as("schema-evolution missing is Reladomo isNull, so notEq is UNKNOWN")
                .isFalse();
        assertThat(interp.accepts(plan, item(ExpressionValue.s("ACTIVE"))))
                .as("equal non-null value is not a notEq match")
                .isFalse();
        assertThat(interp.accepts(plan, item(ExpressionValue.s("CLOSED"))))
                .as("unequal non-null value is a notEq match")
                .isTrue();
        assertThat(interp.accepts(plan, item(ExpressionValue.s(""))))
                .as("empty string is not null and not equal to ACTIVE")
                .isTrue();
    }

    private static QueryPlan planScan(com.gs.fw.finder.Operation op) {
        PlannerConfig config = PlannerConfig.builder()
                .allowTableScan(true)
                .parallelScanSegments(1)
                .build();
        return PlanFixtures.plan((Operation) op, nullableStatusCustomer(), config);
    }

    /**
     * Same Java/item names as {@link PlanFixtures#planCustomer()}, but {@code status} is
     * nullable so the codec (and this planner test) can represent a stored NULL.
     */
    private static PhysicalDesign nullableStatusCustomer() {
        EntityMapping entity = new EntityMapping(
                "io.reladynamo.core.plan.fixture.PlanCustomer",
                "PLAN_CUSTOMER",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("id", "ID", "int", true, false),
                        new AttributeMapping("email", "EMAIL", "String", false, false),
                        new AttributeMapping("status", "STATUS", "String", false, true)
                ));
        return PhysicalDesign.builder(entity)
                .addGsi(GsiSpec.uniqueAttribute("gsi_email", "email"))
                .build();
    }

    private static boolean bindsNullType(QueryPlan plan) {
        for (ExpressionValue value : plan.expressionAttributeValues().values()) {
            if (value != null && value.kind() == ExpressionValue.Kind.S && "NULL".equals(value.s())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, ExpressionValue> item(ExpressionValue status) {
        Map<String, ExpressionValue> item = base();
        item.put("STATUS", status);
        return item;
    }

    private static Map<String, ExpressionValue> itemAbsent() {
        return base();
    }

    private static Map<String, ExpressionValue> base() {
        Map<String, ExpressionValue> item = new LinkedHashMap<String, ExpressionValue>();
        item.put("pk", ExpressionValue.s("v1#PLANCUSTOMER#1"));
        item.put("sk", ExpressionValue.s("v1#ND"));
        item.put("ID", ExpressionValue.n("1"));
        item.put("EMAIL", ExpressionValue.s("ada@example.test"));
        return item;
    }
}
