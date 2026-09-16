package io.reladynamo.core.plan.eval;

import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.FilterExpression;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-05: the planner-vs-interpreter oracle must match DynamoDB, not the inverted
 * {@code attribute_exists}/{@code attribute_not_exists} implementation that treated an
 * explicit NULL AttributeValue as missing.
 *
 * <p>DynamoDB: an attribute of type NULL <em>exists</em>. A missing attribute does not.
 * Reladomo {@code isNull()} matches both (they decode to Java {@code null}); that combined
 * predicate is a separate planner translation and is covered here too.
 */
class NullPredicateInterpreterTest {

    @Test
    void should_treat_explicit_null_as_existing() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        Map<String, ExpressionValue> item = itemWith(ExpressionValue.nul());

        assertThat(interp.accepts(scan("attribute_exists(#STATUS)"), item))
                .as("DynamoDB attribute_exists is true for an explicit NULL attribute")
                .isTrue();
        assertThat(interp.accepts(scan("attribute_not_exists(#STATUS)"), item))
                .as("DynamoDB attribute_not_exists is false for an explicit NULL attribute")
                .isFalse();
    }

    @Test
    void should_treat_absent_attribute_as_not_existing() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        Map<String, ExpressionValue> item = itemWithoutStatus();

        assertThat(interp.accepts(scan("attribute_exists(#STATUS)"), item)).isFalse();
        assertThat(interp.accepts(scan("attribute_not_exists(#STATUS)"), item)).isTrue();
    }

    @Test
    void should_treat_empty_string_as_existing_non_null() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        Map<String, ExpressionValue> item = itemWith(ExpressionValue.s(""));

        assertThat(interp.accepts(scan("attribute_exists(#STATUS)"), item)).isTrue();
        assertThat(interp.accepts(scan("attribute_not_exists(#STATUS)"), item)).isFalse();
        assertThat(interp.accepts(scan("attribute_type(#STATUS, :nt)", nullType()), item)).isFalse();
    }

    @Test
    void should_match_attribute_type_null_only_for_explicit_null() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        QueryPlan typeNull = scan("attribute_type(#STATUS, :nt)", nullType());

        assertThat(interp.accepts(typeNull, itemWith(ExpressionValue.nul()))).isTrue();
        assertThat(interp.accepts(typeNull, itemWithoutStatus())).isFalse();
        assertThat(interp.accepts(typeNull, itemWith(ExpressionValue.s("")))).isFalse();
        assertThat(interp.accepts(typeNull, itemWith(ExpressionValue.s("ACTIVE")))).isFalse();
    }

    @Test
    void should_match_isNull_translation_for_explicit_null_and_missing_only() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        QueryPlan isNull = scan(
                "(attribute_not_exists(#STATUS) OR attribute_type(#STATUS, :nt))",
                nullType());

        assertThat(interp.accepts(isNull, itemWith(ExpressionValue.nul())))
                .as("stored explicit NULL is Reladomo isNull")
                .isTrue();
        assertThat(interp.accepts(isNull, itemWithoutStatus()))
                .as("schema-evolution missing attribute is Reladomo isNull")
                .isTrue();
        assertThat(interp.accepts(isNull, itemWith(ExpressionValue.s(""))))
                .as("empty string is not null")
                .isFalse();
        assertThat(interp.accepts(isNull, itemWith(ExpressionValue.s("ACTIVE"))))
                .as("populated value is not null")
                .isFalse();
    }

    @Test
    void should_match_isNotNull_translation_for_empty_string_and_populated_only() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        QueryPlan isNotNull = scan(
                "(attribute_exists(#STATUS) AND NOT attribute_type(#STATUS, :nt))",
                nullType());

        assertThat(interp.accepts(isNotNull, itemWith(ExpressionValue.nul()))).isFalse();
        assertThat(interp.accepts(isNotNull, itemWithoutStatus())).isFalse();
        assertThat(interp.accepts(isNotNull, itemWith(ExpressionValue.s("")))).isTrue();
        assertThat(interp.accepts(isNotNull, itemWith(ExpressionValue.s("ACTIVE")))).isTrue();
    }

    @Test
    void should_treat_bare_not_equals_as_true_for_explicit_null() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":v", ExpressionValue.s("ACTIVE"));
        QueryPlan bare = scan("#STATUS <> :v", values);

        assertThat(interp.accepts(bare, itemWith(ExpressionValue.nul())))
                .as("DynamoDB <> is true for type-mismatched NULL; the planner must not emit this bare")
                .isTrue();
        assertThat(interp.accepts(bare, itemWithoutStatus()))
                .as("DynamoDB comparison against a missing attribute is false")
                .isFalse();
        assertThat(interp.accepts(bare, itemWith(ExpressionValue.s("ACTIVE")))).isFalse();
        assertThat(interp.accepts(bare, itemWith(ExpressionValue.s("CLOSED")))).isTrue();
    }

    @Test
    void should_match_notEq_translation_excluding_explicit_null_and_missing() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        Map<String, ExpressionValue> values = nullType();
        values.put(":v", ExpressionValue.s("ACTIVE"));
        QueryPlan notEq = scan(
                "(attribute_exists(#STATUS) AND NOT attribute_type(#STATUS, :nt) AND #STATUS <> :v)",
                values);

        assertThat(interp.accepts(notEq, itemWith(ExpressionValue.nul())))
                .as("stored explicit NULL is SQL UNKNOWN for notEq")
                .isFalse();
        assertThat(interp.accepts(notEq, itemWithoutStatus()))
                .as("schema-evolution missing is Reladomo isNull, so notEq is UNKNOWN")
                .isFalse();
        assertThat(interp.accepts(notEq, itemWith(ExpressionValue.s("ACTIVE")))).isFalse();
        assertThat(interp.accepts(notEq, itemWith(ExpressionValue.s("CLOSED")))).isTrue();
        assertThat(interp.accepts(notEq, itemWith(ExpressionValue.s("")))).isTrue();
    }

    @Test
    void should_treat_not_isNull_expression_as_isNotNull() {
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        QueryPlan notIsNull = scan(
                "NOT (attribute_not_exists(#STATUS) OR attribute_type(#STATUS, :nt))",
                nullType());
        QueryPlan isNotNull = scan(
                "(attribute_exists(#STATUS) AND NOT attribute_type(#STATUS, :nt))",
                nullType());

        java.util.List<Map<String, ExpressionValue>> items =
                java.util.Arrays.asList(
                        itemWith(ExpressionValue.nul()),
                        itemWithoutStatus(),
                        itemWith(ExpressionValue.s("")),
                        itemWith(ExpressionValue.s("ACTIVE")));
        for (int i = 0; i < items.size(); i++) {
            assertThat(interp.accepts(notIsNull, items.get(i)))
                    .as("NOT isNull must agree with isNotNull for item %s", Integer.valueOf(i))
                    .isEqualTo(interp.accepts(isNotNull, items.get(i)));
        }
    }

    private static QueryPlan scan(String expression) {
        return scan(expression, namesOnly());
    }

    private static QueryPlan scan(String expression, Map<String, ExpressionValue> values) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#STATUS", "STATUS");
        FilterExpression filter = new FilterExpression(expression, names, values);
        return QueryPlan.builder()
                .className("PlanCustomer")
                .tableName("PLAN_CUSTOMER")
                .kind(PlanKind.SCAN)
                .filterExpression(filter)
                .build();
    }

    private static Map<String, ExpressionValue> namesOnly() {
        return new LinkedHashMap<String, ExpressionValue>();
    }

    private static Map<String, ExpressionValue> nullType() {
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":nt", ExpressionValue.s("NULL"));
        return values;
    }

    private static Map<String, ExpressionValue> itemWith(ExpressionValue status) {
        Map<String, ExpressionValue> item = baseItem();
        item.put("STATUS", status);
        return item;
    }

    private static Map<String, ExpressionValue> itemWithoutStatus() {
        return baseItem();
    }

    private static Map<String, ExpressionValue> baseItem() {
        Map<String, ExpressionValue> item = new LinkedHashMap<String, ExpressionValue>();
        item.put("pk", ExpressionValue.s("v1#PLANCUSTOMER#1"));
        item.put("sk", ExpressionValue.s("v1#ND"));
        item.put("ID", ExpressionValue.n("1"));
        item.put("EMAIL", ExpressionValue.s("ada@example.test"));
        return item;
    }
}
