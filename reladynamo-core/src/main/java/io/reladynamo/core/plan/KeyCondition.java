package io.reladynamo.core.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * DynamoDB KeyConditionExpression plus the encoded PK/SK values that produced it.
 */
public final class KeyCondition {

    private final String expression;
    private final Map<String, String> names;
    private final Map<String, ExpressionValue> values;
    private final String encodedPartitionKey;
    private final String encodedSortKey;

    public KeyCondition(String expression, Map<String, String> names,
                        Map<String, ExpressionValue> values,
                        String encodedPartitionKey, String encodedSortKey) {
        this.expression = expression;
        this.names = copyNames(names);
        this.values = copyValues(values);
        this.encodedPartitionKey = encodedPartitionKey;
        this.encodedSortKey = encodedSortKey;
    }

    public static KeyCondition partitionEquals(String pkAttr, String encodedPk) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#pk", pkAttr);
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":pk", ExpressionValue.s(encodedPk));
        return new KeyCondition("#pk = :pk", names, values, encodedPk, null);
    }

    public static KeyCondition partitionAndSortEquals(String pkAttr, String skAttr,
                                                      String encodedPk, String encodedSk) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#pk", pkAttr);
        names.put("#sk", skAttr);
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":pk", ExpressionValue.s(encodedPk));
        values.put(":sk", ExpressionValue.s(encodedSk));
        return new KeyCondition("#pk = :pk AND #sk = :sk", names, values, encodedPk, encodedSk);
    }

    public static KeyCondition partitionAndBeginsWith(String pkAttr, String skAttr,
                                                      String encodedPk, String skPrefix) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#pk", pkAttr);
        names.put("#sk", skAttr);
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":pk", ExpressionValue.s(encodedPk));
        values.put(":skprefix", ExpressionValue.s(skPrefix));
        return new KeyCondition("#pk = :pk AND begins_with(#sk, :skprefix)", names, values,
                encodedPk, null);
    }

    public static KeyCondition partitionAndBetween(String pkAttr, String skAttr,
                                                   String encodedPk, String skLo, String skHi) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#pk", pkAttr);
        names.put("#sk", skAttr);
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(":pk", ExpressionValue.s(encodedPk));
        values.put(":sklo", ExpressionValue.s(skLo));
        values.put(":skhi", ExpressionValue.s(skHi));
        return new KeyCondition("#pk = :pk AND #sk BETWEEN :sklo AND :skhi", names, values,
                encodedPk, null);
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

    public String encodedPartitionKey() {
        return encodedPartitionKey;
    }

    public String encodedSortKey() {
        return encodedSortKey;
    }

    public boolean isEmpty() {
        return expression == null || expression.isEmpty();
    }

    static Map<String, String> copyNames(Map<String, String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(names));
    }

    static Map<String, ExpressionValue> copyValues(Map<String, ExpressionValue> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, ExpressionValue>(values));
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
        if (!(o instanceof KeyCondition)) {
            return false;
        }
        KeyCondition that = (KeyCondition) o;
        return Objects.equals(expression, that.expression)
                && Objects.equals(encodedPartitionKey, that.encodedPartitionKey)
                && Objects.equals(encodedSortKey, that.encodedSortKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expression, encodedPartitionKey, encodedSortKey);
    }
}
