package io.reladynamo.core.plan;

import java.util.Map;
import java.util.Objects;

/**
 * DynamoDB FilterExpression. Applied after the key condition; does not reduce RCU.
 */
public final class FilterExpression {

    private final String expression;
    private final Map<String, String> names;
    private final Map<String, ExpressionValue> values;

    public FilterExpression(String expression, Map<String, String> names,
                            Map<String, ExpressionValue> values) {
        this.expression = expression;
        this.names = KeyCondition.copyNames(names);
        this.values = KeyCondition.copyValues(values);
    }

    public static FilterExpression empty() {
        return new FilterExpression(null, null, null);
    }

    public String expression() {
        return expression;
    }

    public Map<String, String> names() {
        return names;
    }

    public Map<String, ExpressionValue> values() {
        return values;
    }

    public boolean isEmpty() {
        return expression == null || expression.isEmpty();
    }

    @Override
    public String toString() {
        return expression == null ? "" : expression;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FilterExpression)) {
            return false;
        }
        FilterExpression that = (FilterExpression) o;
        return Objects.equals(expression, that.expression);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(expression);
    }
}
