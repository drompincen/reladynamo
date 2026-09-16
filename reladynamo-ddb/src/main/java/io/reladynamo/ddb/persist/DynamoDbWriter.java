package io.reladynamo.ddb.persist;

import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.MithraUniqueIndexViolationException;
import com.gs.fw.common.mithra.behavior.txparticipation.MithraOptimisticLockException;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.KeyStrategy;
import io.reladynamo.core.mapping.MappingValidator;
import io.reladynamo.core.mapping.TemporalAttributeNames;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PartitionKeyEncoder;
import io.reladynamo.ddb.codec.ExpressionNames;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.write.BatchWriteClient;
import io.reladynamo.ddb.write.BatchWriter;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The write half of the adapter: turns a flat attribute map (as produced by
 * {@code MithraDataAccessor}) into a DynamoDB item and persists it.
 *
 * <p>Deliberately <b>not</b> temporal-aware. Reladomo's {@code TemporalDirector} sits above the
 * persister and has already decided what rows should exist — this receives rows that carry their own
 * from/to boundaries and stores them verbatim. Re-deriving any bitemporal rule here would be the one
 * sure way to diverge from H2.
 *
 * <p>Each version is a distinct item because the sort key embeds both temporal axes, so a correction
 * adds a version rather than overwriting one.
 *
 * <p>ORM writes carry concurrency conditions. Migration writes use {@link #upsert} /
 * {@link #batchUpsert} and are unconditional: replaying a source row must converge. Callers choose
 * between those two families; they are not interchangeable.
 *
 * <p>When a Reladomo transaction is active, each action is submitted to
 * {@link DynamoDbTransactionCoordinator} as a {@link PhysicalWrite} that already carries the R-02
 * condition. The coordinator does not reconstruct conditions.
 *
 * <p>Java 11 baseline.
 */
public final class DynamoDbWriter {

    private static final String PK = "pk";
    private static final String SK = "sk";
    private static final String INSERT_CONDITION = "attribute_not_exists(pk)";
    private static final String NULL_TYPE_PLACEHOLDER = ":_nt";

    private final DynamoDbClient client;
    private final EntityMapping mapping;
    private final ItemCodec codec;
    private final KeyStrategy keys;
    private final List<GsiSpec> gsis;
    private final BatchWriter batchWriter;
    private MithraObjectPortal portal;

    public DynamoDbWriter(DynamoDbClient client, EntityMapping mapping, ItemCodec codec,
                          KeyStrategy keys) {
        this(client, mapping, codec, keys, 5, 50L, Collections.emptyList());
    }

    public DynamoDbWriter(DynamoDbClient client, EntityMapping mapping, ItemCodec codec,
                          KeyStrategy keys, List<GsiSpec> gsis) {
        this(client, mapping, codec, keys, 5, 50L, gsis);
    }

    public DynamoDbWriter(DynamoDbClient client, EntityMapping mapping, ItemCodec codec,
                          KeyStrategy keys, int maxBatchAttempts, long baseBackoffMillis) {
        this(client, mapping, codec, keys, maxBatchAttempts, baseBackoffMillis, Collections.emptyList());
    }

    public DynamoDbWriter(DynamoDbClient client, EntityMapping mapping, ItemCodec codec,
                          KeyStrategy keys, int maxBatchAttempts, long baseBackoffMillis,
                          List<GsiSpec> gsis) {
        if (client == null || mapping == null || codec == null || keys == null) {
            throw new IllegalArgumentException("client, mapping, codec and keys are all required");
        }
        List<GsiSpec> resolvedGsis = gsis == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<GsiSpec>(gsis));
        MappingValidator.validate(mapping, gsiStorageNames(resolvedGsis));
        this.client = client;
        this.mapping = mapping;
        this.codec = codec;
        this.keys = keys;
        this.gsis = resolvedGsis;
        BatchWriteClient bwc = new BatchWriteClient() {
            @Override
            public software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse batchWriteItem(
                    software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest request) {
                return client.batchWriteItem(request);
            }
        };
        this.batchWriter = new BatchWriter(bwc, mapping.tableName(), maxBatchAttempts, baseBackoffMillis);
    }

    void attachPortal(MithraObjectPortal portal) {
        this.portal = portal;
    }

    void beforeRead() {
        DynamoDbTransactionCoordinator.beforeRead(client);
    }

    /**
     * ORM insert. Fails with {@link MithraUniqueIndexViolationException} when {@code pk+sk} already
     * exists. Independent JVMs have no Reladomo cache to consult, so the condition is the only
     * duplicate detector.
     */
    public void insert(Map<String, Object> row) {
        Map<String, AttributeValue> item = toItem(row);
        try {
            submit(PhysicalWrite.put(mapping.tableName(), keyOf(row), item, insertCondition()));
        } catch (ConditionalCheckFailedException e) {
            throw duplicateInsert(item, e);
        }
    }

    /**
     * Strongly consistent re-read of the item addressed by {@code row}'s derived {@code pk+sk}.
     * Returns decoded attributes, or {@code null} if the item is gone — Reladomo's
     * "deleted underneath us" path.
     *
     * <p>Always consistent: an eventually consistent re-read after our own write is the
     * classic way to produce a confusing stale value, and refresh exists specifically
     * to learn the stored state after a conflict. Isolation against staged writes is
     * the coordinator's {@code beforeRead} policy, applied by the persister before
     * this GetItem.
     */
    public Map<String, Object> getConsistent(Map<String, Object> row) {
        if (row == null) {
            throw new IllegalArgumentException("row is required");
        }
        DynamoDbTransactionCoordinator.assertUsable(client);
        Map<String, AttributeValue> key = keyOf(row);
        GetItemResponse response = client.getItem(b -> b
                .tableName(mapping.tableName())
                .key(key)
                .consistentRead(true));
        if (!response.hasItem() || response.item() == null || response.item().isEmpty()) {
            return null;
        }
        return codec.decode(response.item());
    }

    /**
     * Unconditional overwrite used by migration. Distinct from {@link #insert}: Backfill
     * replaying a source row must converge, while the ORM path must detect duplicates.
     */
    public void upsert(Map<String, Object> row) {
        Map<String, AttributeValue> item = toItem(row);
        submit(PhysicalWrite.put(mapping.tableName(), keyOf(row), item, null));
    }

    /**
     * Replace an existing item only when every mapped attribute still matches
     * {@code expectedPrior}. A concurrent writer that changed payload or closed a temporal
     * rectangle causes {@link MithraOptimisticLockException} with {@code isRetriable() == true}.
     */
    public void update(Map<String, Object> newRow, Map<String, Object> expectedPrior) {
        if (newRow == null) {
            throw new IllegalArgumentException("newRow is required");
        }
        if (expectedPrior == null) {
            throw new IllegalArgumentException("expectedPrior is required");
        }
        Map<String, AttributeValue> newKey = keyOf(newRow);
        Map<String, AttributeValue> priorKey = keyOf(expectedPrior);
        if (!newKey.equals(priorKey)) {
            throw new IllegalArgumentException(
                    "update of " + mapping.className()
                            + " cannot change the item key; expected "
                            + describeKey(priorKey) + " but new row is " + describeKey(newKey));
        }
        Map<String, AttributeValue> item = toItem(newRow);
        try {
            submit(PhysicalWrite.put(mapping.tableName(), newKey, item, expectedStateCondition(expectedPrior)));
        } catch (ConditionalCheckFailedException e) {
            throw optimisticLock("update", newKey, e);
        }
    }

    /**
     * Delete only when the stored item still matches {@code row}. A missing item or a
     * concurrently updated payload fails with {@link MithraOptimisticLockException}.
     */
    public void delete(Map<String, Object> row) {
        Map<String, AttributeValue> key = keyOf(row);
        try {
            submit(PhysicalWrite.delete(mapping.tableName(), key, toItem(row), expectedStateCondition(row)));
        } catch (ConditionalCheckFailedException e) {
            throw optimisticLock("delete", key, e);
        }
    }

    /** Physical removal of a version. Reladomo calls this {@code purge}; at this layer it is a delete. */
    public void purge(Map<String, Object> row) {
        delete(row);
    }

    /** R-02 supplies its condition and staged precondition on the immutable action. */
    public void submit(PhysicalWrite action) {
        DynamoDbTransactionCoordinator.submit(client, action, portal);
    }

    /**
     * ORM batch insert. {@code BatchWriteItem} cannot carry conditions, so each row is a
     * conditional {@link #insert}. Use {@link #batchUpsert} for migration replay.
     */
    public void batchInsert(List<Map<String, Object>> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows is required");
        }
        DynamoDbTransactionCoordinator.assertUsable(client);
        for (int i = 0; i < rows.size(); i++) {
            insert(rows.get(i));
        }
    }

    /**
     * Unconditional batch overwrite used by {@code Backfill}. {@code BatchWriteItem} cannot
     * carry conditions; that is acceptable here because migration wants idempotent replay.
     * Inside a Reladomo transaction the rows are staged instead, so they commit with the rest
     * of the unit of work.
     */
    public void batchUpsert(List<Map<String, Object>> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows is required");
        }
        DynamoDbTransactionCoordinator.assertUsable(client);
        if (DynamoDbTransactionCoordinator.isActive()) {
            for (int i = 0; i < rows.size(); i++) {
                upsert(rows.get(i));
            }
            return;
        }
        List<WriteRequest> writes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            writes.add(WriteRequest.builder()
                    .putRequest(PutRequest.builder().item(toItem(row)).build())
                    .build());
        }
        batchWriter.writeAll(writes);
    }

    /**
     * ORM batch delete. Sequential so each row can carry an expected-state condition.
     */
    public void batchDelete(List<Map<String, Object>> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows is required");
        }
        DynamoDbTransactionCoordinator.assertUsable(client);
        for (int i = 0; i < rows.size(); i++) {
            delete(rows.get(i));
        }
    }

    // --- conditions ------------------------------------------------------------------

    /**
     * Existence plus equality of every mapped attribute (and {@code _rd_v}) in {@code expected}.
     * Nulls are checked with {@code attribute_type(..., NULL)} because DynamoDB {@code =} does
     * not compare against the NULL type.
     */
    private ExpectedState expectedState(Map<String, Object> expected) {
        Map<String, AttributeValue> encoded = codec.encode(expected);
        StringBuilder expr = new StringBuilder("attribute_exists(pk)");
        Map<String, String> names = new LinkedHashMap<String, String>();
        Map<String, AttributeValue> values = new LinkedHashMap<String, AttributeValue>();
        ExpressionNames expressionNames = codec.expressionNames();
        int i = 0;
        boolean anyNull = false;
        for (Map.Entry<String, AttributeValue> e : encoded.entrySet()) {
            String itemName = e.getKey();
            String namePh = expressionNames.placeholder(itemName);
            names.put(namePh, itemName);
            AttributeValue av = e.getValue();
            if (av == null || Boolean.TRUE.equals(av.nul())) {
                expr.append(" AND attribute_type(").append(namePh).append(',').append(NULL_TYPE_PLACEHOLDER).append(')');
                anyNull = true;
            } else {
                String valPh = ":c" + i;
                expr.append(" AND ").append(namePh).append('=').append(valPh);
                values.put(valPh, av);
                i++;
            }
        }
        if (anyNull) {
            values.put(NULL_TYPE_PLACEHOLDER, AttributeValue.builder().s("NULL").build());
        }
        return new ExpectedState(expr.toString(), names, values);
    }

    private PhysicalWrite.Condition insertCondition() {
        return new PhysicalWrite.Condition(
                INSERT_CONDITION,
                Collections.emptyMap(),
                Collections.emptyMap(),
                staged -> staged == null || staged.isEmpty());
    }

    private PhysicalWrite.Condition expectedStateCondition(Map<String, Object> expected) {
        ExpectedState state = expectedState(expected);
        Map<String, AttributeValue> encoded =
                Collections.unmodifiableMap(new LinkedHashMap<String, AttributeValue>(codec.encode(expected)));
        return new PhysicalWrite.Condition(
                state.expression,
                state.names,
                state.values,
                staged -> matchesExpected(staged, encoded));
    }

    private static boolean matchesExpected(Map<String, AttributeValue> staged,
                                           Map<String, AttributeValue> encoded) {
        if (staged == null || staged.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, AttributeValue> e : encoded.entrySet()) {
            AttributeValue actual = staged.get(e.getKey());
            AttributeValue expected = e.getValue();
            if (expected == null || Boolean.TRUE.equals(expected.nul())) {
                if (actual == null || !Boolean.TRUE.equals(actual.nul())) {
                    return false;
                }
            } else if (actual == null || !actual.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private MithraUniqueIndexViolationException duplicateInsert(
            Map<String, AttributeValue> item, ConditionalCheckFailedException cause) {
        return new MithraUniqueIndexViolationException(
                "duplicate insert of " + mapping.className() + " " + describeKey(item)
                        + ": an item with this key already exists",
                cause);
    }

    private MithraOptimisticLockException optimisticLock(
            String action, Map<String, AttributeValue> key, ConditionalCheckFailedException cause) {
        MithraOptimisticLockException ex = new MithraOptimisticLockException(
                "optimistic lock failed on " + action + " of " + mapping.className()
                        + " " + describeKey(key)
                        + "; the stored item did not match the expected prior state",
                cause);
        ex.setRetriable(true);
        return ex;
    }

    private static String describeKey(Map<String, AttributeValue> key) {
        return "pk=" + s(key.get(PK)) + " sk=" + s(key.get(SK));
    }

    private static String s(AttributeValue v) {
        return v == null ? "<null>" : v.s();
    }

    private static final class ExpectedState {
        private final String expression;
        private final Map<String, String> names;
        private final Map<String, AttributeValue> values;

        private ExpectedState(String expression, Map<String, String> names,
                              Map<String, AttributeValue> values) {
            this.expression = expression;
            this.names = names;
            this.values = values;
        }
    }

    // --- item construction -----------------------------------------------------------

    private Map<String, AttributeValue> toItem(Map<String, Object> row) {
        Map<String, AttributeValue> item = new LinkedHashMap<>(codec.encode(row));
        item.putAll(keyOf(row));
        stampGsiKeys(item, row);
        codec.rejectIfTooLarge(item, row);
        return item;
    }

    /**
     * GSI key attribute names occupy the same DynamoDB item namespace as mapped payload names.
     * A column mapped to {@code gsi_customerId} would be overwritten when the FK GSI is stamped.
     */
    private static List<String> gsiStorageNames(List<GsiSpec> gsis) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < gsis.size(); i++) {
            GsiSpec gsi = gsis.get(i);
            names.add(gsi.partitionKeyAttributeName());
            String sortKey = gsi.sortKeyAttributeName();
            if (sortKey != null && !sortKey.isEmpty()) {
                names.add(sortKey);
            }
        }
        return names;
    }

    /**
     * Writes encoded GSI partition keys so a Query on that index can find the item.
     * Null FK values are omitted (sparse). Sparse-current keys are stamped only while
     * {@code processingDateTo} is infinity; a later Put of a closed rectangle omits them
     * and DynamoDB drops the index entry. The GSI sort key, when it mirrors {@code sk},
     * is already on the item from {@link #keyOf}.
     */
    private void stampGsiKeys(Map<String, AttributeValue> item, Map<String, Object> row) {
        for (int i = 0; i < gsis.size(); i++) {
            GsiSpec gsi = gsis.get(i);
            if (gsi.sparseCurrent()) {
                stampSparseCurrent(item, row, gsi);
            } else {
                stampLookupGsi(item, row, gsi);
            }
        }
    }

    private void stampSparseCurrent(Map<String, AttributeValue> item, Map<String, Object> row,
                                    GsiSpec gsi) {
        if (!isCurrentProcessing(row)) {
            return;
        }
        String encodedPk = keys.partitionKey(mapping, primaryKeyValues(row));
        item.put(gsi.partitionKeyAttributeName(), AttributeValue.builder().s(encodedPk).build());
        String skAttr = gsi.sortKeyAttributeName();
        if (skAttr == null || !mapping.temporal().hasBusinessDate()) {
            return;
        }
        item.put(skAttr, AttributeValue.builder()
                .s(PartitionKeyEncoder.currentBusinessSk(businessFrom(row)))
                .build());
    }

    private void stampLookupGsi(Map<String, AttributeValue> item, Map<String, Object> row,
                                GsiSpec gsi) {
        List<String> pkNames = gsi.partitionKeyJavaNames();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pkNames.size(); i++) {
            String javaName = pkNames.get(i);
            Object value = row.get(javaName);
            if (value == null) {
                return;
            }
            values.put(javaName, value);
        }
        String encoded = encodeLookupGsiPk(gsi, values);
        item.put(gsi.partitionKeyAttributeName(), AttributeValue.builder().s(encoded).build());
    }

    private String encodeLookupGsiPk(GsiSpec gsi, Map<String, Object> values) {
        if (isEntityPrimaryKey(gsi.partitionKeyJavaNames())) {
            return keys.partitionKey(mapping, values);
        }
        return PartitionKeyEncoder.gsiPartitionKey(gsi.partitionKeyJavaNames(), values);
    }

    private boolean isEntityPrimaryKey(List<String> names) {
        List<AttributeMapping> pks = mapping.primaryKeyAttributes();
        if (pks.size() != names.size()) {
            return false;
        }
        for (int i = 0; i < pks.size(); i++) {
            if (!pks.get(i).javaName().equals(names.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sparse-current membership is processing-thru = infinity. Absence of the key attributes
     * is what keeps history out of the index; a status flag would not.
     */
    private boolean isCurrentProcessing(Map<String, Object> row) {
        if (!mapping.temporal().hasProcessingDate()) {
            return true;
        }
        Object to = row.get(TemporalAttributeNames.processingToJavaName(mapping));
        Timestamp inf = mapping.temporal().infinity();
        return to instanceof Timestamp && inf != null && inf.equals(to);
    }

    private Map<String, Object> primaryKeyValues(Map<String, Object> row) {
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        List<AttributeMapping> pks = mapping.primaryKeyAttributes();
        for (int i = 0; i < pks.size(); i++) {
            AttributeMapping a = pks.get(i);
            pkValues.put(a.javaName(), row.get(a.javaName()));
        }
        return pkValues;
    }

    private Map<String, AttributeValue> keyOf(Map<String, Object> row) {
        Map<String, Object> pkValues = primaryKeyValues(row);
        String pk = keys.partitionKey(mapping, pkValues);
        String sk = keys.sortKey(mapping, processingFrom(row), businessFrom(row));

        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put(PK, AttributeValue.builder().s(pk).build());
        key.put(SK, AttributeValue.builder().s(sk).build());
        return key;
    }

    private Timestamp businessFrom(Map<String, Object> row) {
        return mapping.temporal().hasBusinessDate()
                ? required(row, TemporalAttributeNames.businessFromJavaName(mapping))
                : null;
    }

    private Timestamp processingFrom(Map<String, Object> row) {
        return mapping.temporal().hasProcessingDate()
                ? required(row, TemporalAttributeNames.processingFromJavaName(mapping))
                : null;
    }

    /**
     * A dated row without its boundary is not a row we can address. Failing here names the problem;
     * defaulting to "now" would write an item under a key nobody can find again.
     */
    private Timestamp required(Map<String, Object> row, String name) {
        Object v = row.get(name);
        if (!(v instanceof Timestamp)) {
            throw new IllegalArgumentException(
                    "missing or non-Timestamp '" + name + "' for " + mapping.className()
                            + " (temporal flavour " + mapping.temporal().flavour() + "); "
                            + "the sort key cannot be derived without it");
        }
        return (Timestamp) v;
    }
}
