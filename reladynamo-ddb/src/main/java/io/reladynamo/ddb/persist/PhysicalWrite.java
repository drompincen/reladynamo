package io.reladynamo.ddb.persist;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Immutable physical action. No temporal interpretation belongs here. */
public final class PhysicalWrite {

    private final String table;
    private final Map<String, AttributeValue> key;
    private final Map<String, AttributeValue> image;
    private final boolean delete;
    private final Condition condition;

    /**
     * R-02 seam: the expression tests the ORIGINAL durable image. For a subsequent action on an
     * already staged key, stagedTest must test that action's precondition against the staged image
     * (an empty map means absent). The coordinator retains the FIRST durable predicate.
     *
     * <p>R-02 constructs the expression and the staged predicate from the same condition family
     * ({@code attribute_not_exists} for insert, expected-state equality for update/delete). Do not
     * duplicate that logic here.
     */
    public static final class Condition {
        private final String expression;
        private final Map<String, String> names;
        private final Map<String, AttributeValue> values;
        private final Predicate<Map<String, AttributeValue>> stagedTest;

        public Condition(String expression, Map<String, String> names, Map<String, AttributeValue> values,
                         Predicate<Map<String, AttributeValue>> stagedTest) {
            this.expression = Objects.requireNonNull(expression, "expression");
            this.names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.stagedTest = Objects.requireNonNull(stagedTest, "stagedTest");
        }
    }

    private PhysicalWrite(String table, Map<String, AttributeValue> key, Map<String, AttributeValue> image,
                          boolean delete, Condition condition) {
        this.table = Objects.requireNonNull(table, "table");
        this.key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
        this.image = Collections.unmodifiableMap(new LinkedHashMap<>(image));
        this.delete = delete;
        this.condition = condition;
    }

    public static PhysicalWrite put(String table, Map<String, AttributeValue> key,
                                    Map<String, AttributeValue> item, Condition condition) {
        return new PhysicalWrite(table, key, item, false, condition);
    }

    /** Supply the complete old image for transaction size accounting, including physical keys. */
    public static PhysicalWrite delete(String table, Map<String, AttributeValue> key,
                                       Map<String, AttributeValue> oldImage, Condition condition) {
        return new PhysicalWrite(table, key, oldImage, true, condition);
    }

    Object identity() {
        return Arrays.asList(table, key);
    }

    String table() {
        return table;
    }

    Map<String, AttributeValue> itemKey() {
        return key;
    }

    boolean isDelete() {
        return delete;
    }

    /** Insert family: R-02 {@code attribute_not_exists(pk)}. */
    boolean isInsertNotExistsCondition() {
        return condition != null && "attribute_not_exists(pk)".equals(condition.expression);
    }

    /** Update/delete family: R-02 expected-prior-state, which always starts with existence. */
    boolean isExpectedPriorStateCondition() {
        return condition != null
                && condition.expression != null
                && condition.expression.startsWith("attribute_exists(pk)");
    }

    PhysicalWrite then(PhysicalWrite next) {
        if (next.condition != null && !next.condition.stagedTest.test(delete ? Collections.emptyMap() : image)) {
            throw new DynamoDbTransactionException("RELADYNAMO-TXN-004", "staged item condition failed");
        }
        // All later predicates were checked locally. Only the first predicate describes the store.
        return new PhysicalWrite(table, key, next.delete ? image : next.image, next.delete, condition);
    }

    TransactWriteItem transactionItem() {
        if (delete) {
            Delete.Builder b = Delete.builder().tableName(table).key(key);
            applyCondition(b, condition);
            return TransactWriteItem.builder().delete(b.build()).build();
        }
        Put.Builder b = Put.builder().tableName(table).item(image);
        applyCondition(b, condition);
        return TransactWriteItem.builder().put(b.build()).build();
    }

    void writeImmediately(DynamoDbClient client) {
        if (delete) {
            DeleteItemRequest.Builder b = DeleteItemRequest.builder().tableName(table).key(key);
            applyCondition(b, condition);
            client.deleteItem(b.build());
        } else {
            PutItemRequest.Builder b = PutItemRequest.builder().tableName(table).item(image);
            applyCondition(b, condition);
            client.putItem(b.build());
        }
    }

    long itemBytes() {
        return mapBytes(image);
    }

    private static void applyCondition(Put.Builder b, Condition condition) {
        if (condition == null) {
            return;
        }
        b.conditionExpression(condition.expression);
        if (!condition.names.isEmpty()) {
            b.expressionAttributeNames(condition.names);
        }
        if (!condition.values.isEmpty()) {
            b.expressionAttributeValues(condition.values);
        }
    }

    private static void applyCondition(Delete.Builder b, Condition condition) {
        if (condition == null) {
            return;
        }
        b.conditionExpression(condition.expression);
        if (!condition.names.isEmpty()) {
            b.expressionAttributeNames(condition.names);
        }
        if (!condition.values.isEmpty()) {
            b.expressionAttributeValues(condition.values);
        }
    }

    private static void applyCondition(PutItemRequest.Builder b, Condition condition) {
        if (condition == null) {
            return;
        }
        b.conditionExpression(condition.expression);
        if (!condition.names.isEmpty()) {
            b.expressionAttributeNames(condition.names);
        }
        if (!condition.values.isEmpty()) {
            b.expressionAttributeValues(condition.values);
        }
    }

    private static void applyCondition(DeleteItemRequest.Builder b, Condition condition) {
        if (condition == null) {
            return;
        }
        b.conditionExpression(condition.expression);
        if (!condition.names.isEmpty()) {
            b.expressionAttributeNames(condition.names);
        }
        if (!condition.values.isEmpty()) {
            b.expressionAttributeValues(condition.values);
        }
    }

    private static long utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long mapBytes(Map<String, AttributeValue> map) {
        long size = 0;
        for (Map.Entry<String, AttributeValue> e : map.entrySet()) {
            size += utf8(e.getKey()) + valueBytes(e.getValue());
        }
        return size;
    }

    private static long valueBytes(AttributeValue a) {
        if (a.s() != null) {
            return utf8(a.s());
        }
        if (a.n() != null) {
            return utf8(a.n()) + 1; // conservative upper bound on Dynamo's packed decimal
        }
        if (a.b() != null) {
            return a.b().asByteArray().length;
        }
        if (a.hasM()) {
            return 3 + a.m().size() + mapBytes(a.m());
        }
        if (a.hasL()) {
            long n = 3 + a.l().size();
            for (AttributeValue v : a.l()) {
                n += valueBytes(v);
            }
            return n;
        }
        if (a.hasSs()) {
            long n = 0;
            for (String v : a.ss()) {
                n += utf8(v);
            }
            return n;
        }
        if (a.hasNs()) {
            long n = 0;
            for (String v : a.ns()) {
                n += utf8(v) + 1;
            }
            return n;
        }
        if (a.hasBs()) {
            long n = 0;
            for (SdkBytes v : a.bs()) {
                n += v.asByteArray().length;
            }
            return n;
        }
        return 1;
    }
}
