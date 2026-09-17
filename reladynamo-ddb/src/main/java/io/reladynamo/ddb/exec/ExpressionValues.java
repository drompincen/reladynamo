package io.reladynamo.ddb.exec;

import io.reladynamo.core.plan.ExpressionValue;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts planner {@link ExpressionValue}s (no AWS types) into SDK {@link AttributeValue}s
 * and the reverse, so a GetItem response can be evaluated by
 * {@link io.reladynamo.core.plan.eval.QueryPlanInterpreter}.
 */
final class ExpressionValues {

    private ExpressionValues() {
    }

    static Map<String, AttributeValue> toSdk(Map<String, ExpressionValue> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, AttributeValue> out = new LinkedHashMap<String, AttributeValue>(values.size() * 2);
        for (Map.Entry<String, ExpressionValue> e : values.entrySet()) {
            out.put(e.getKey(), toSdk(e.getValue()));
        }
        return out;
    }

    static AttributeValue toSdk(ExpressionValue value) {
        if (value == null) {
            return AttributeValue.builder().nul(true).build();
        }
        switch (value.kind()) {
            case S:
                return AttributeValue.builder().s(value.s()).build();
            case N:
                return AttributeValue.builder().n(value.n()).build();
            case B:
                return AttributeValue.builder().b(SdkBytes.fromByteArray(value.b())).build();
            case BOOL:
                return AttributeValue.builder().bool(value.bool()).build();
            case NULL:
                return AttributeValue.builder().nul(true).build();
            default:
                throw new IllegalArgumentException("unsupported ExpressionValue kind " + value.kind());
        }
    }

    /**
     * Wire item (DynamoDB attribute names) → interpreter item. GetItem has no FilterExpression,
     * so the executor evaluates the plan filter against this map.
     */
    static Map<String, ExpressionValue> fromItem(Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, ExpressionValue> out = new LinkedHashMap<String, ExpressionValue>(item.size() * 2);
        for (Map.Entry<String, AttributeValue> e : item.entrySet()) {
            out.put(e.getKey(), fromSdk(e.getValue()));
        }
        return out;
    }

    static ExpressionValue fromSdk(AttributeValue value) {
        if (value == null || Boolean.TRUE.equals(value.nul())) {
            return ExpressionValue.nul();
        }
        if (value.s() != null) {
            return ExpressionValue.s(value.s());
        }
        if (value.n() != null) {
            return ExpressionValue.n(value.n());
        }
        if (value.bool() != null) {
            return ExpressionValue.bool(value.bool().booleanValue());
        }
        if (value.b() != null) {
            return ExpressionValue.b(value.b().asByteArray());
        }
        return ExpressionValue.nul();
    }
}
