package io.reladynamo.ddb.persist;

import com.gs.fw.common.mithra.MithraUniqueIndexViolationException;
import com.gs.fw.common.mithra.behavior.txparticipation.MithraOptimisticLockException;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-02: inserts, updates and deletes must carry concurrency conditions. Two writer
 * instances sharing one DynamoDB Local table stand in for independent JVMs; there is
 * no Reladomo cache on this path, so a warm cache cannot mask a lost update.
 */
class WriterConcurrencyTest {

    private static final String TABLE = "plan_rule_concurrency";

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static ItemCodec codec;
    private static DefaultKeyStrategy keys;
    private static DynamoDbWriter writerA;
    private static DynamoDbWriter writerB;

    @BeforeAll
    static void setUp() {
        ddb = LocalDynamoDb.start();
        mapping = new EntityMapping("com.acme.PlanRule", TABLE,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, infinity()),
                Arrays.asList(
                        new AttributeMapping("ruleId", "ruleId", "int", true, false),
                        new AttributeMapping("ruleName", "ruleName", "String", false, false),
                        new AttributeMapping("priority", "priority", "int", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
        codec = new ItemCodec(mapping);
        keys = new DefaultKeyStrategy();

        ddb.client().createTable(b -> b.tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

        writerA = new DynamoDbWriter(ddb.client(), mapping, codec, keys);
        writerB = new DynamoDbWriter(ddb.client(), mapping, codec, keys);
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_reject_duplicate_insert_when_second_client_has_cold_cache() {
        Map<String, Object> first = row(1, "alpha", 10, ts(2026, 6, 1));
        Map<String, Object> duplicate = row(1, "beta", 99, ts(2026, 6, 1));

        writerA.insert(first);

        assertThatThrownBy(() -> writerB.insert(duplicate))
                .isInstanceOf(MithraUniqueIndexViolationException.class);

        assertThat(storedName(first)).isEqualTo("alpha");
    }

    @Test
    void should_preserve_first_writer_when_two_clients_update_the_same_row() {
        Map<String, Object> original = row(2, "shared", 10, ts(2026, 6, 1));
        writerA.insert(original);

        Map<String, Object> fromA = copy(original);
        fromA.put("priority", 20);
        Map<String, Object> fromB = copy(original);
        fromB.put("priority", 30);

        writerA.update(fromA, original);

        assertThatThrownBy(() -> writerB.update(fromB, original))
                .isInstanceOf(MithraOptimisticLockException.class);

        assertThat(storedPriority(original)).isEqualTo("20");
    }

    @Test
    void should_reject_stale_delete_when_item_was_already_removed() {
        Map<String, Object> original = row(3, "gone", 1, ts(2026, 6, 1));
        writerA.insert(original);
        writerA.delete(original);

        assertThatThrownBy(() -> writerB.delete(original))
                .isInstanceOf(MithraOptimisticLockException.class);
    }

    @Test
    void should_reject_stale_delete_when_item_was_concurrently_updated() {
        Map<String, Object> original = row(4, "keep", 10, ts(2026, 6, 1));
        writerA.insert(original);

        Map<String, Object> updated = copy(original);
        updated.put("priority", 20);
        writerB.update(updated, original);

        assertThatThrownBy(() -> writerA.delete(original))
                .isInstanceOf(MithraOptimisticLockException.class);

        assertThat(storedPriority(original)).isEqualTo("20");
        assertThat(storedName(original)).isEqualTo("keep");
    }

    @Test
    void should_detect_overlapping_temporal_corrections_on_the_same_rectangle() {
        Map<String, Object> open = row(5, "open", 10, ts(2026, 1, 1));
        writerA.insert(open);

        Timestamp closeA = ts(2026, 3, 1);
        Timestamp closeB = ts(2026, 4, 1);

        Map<String, Object> closedByA = copy(open);
        closedByA.put("processingDateTo", closeA);
        Map<String, Object> closedByB = copy(open);
        closedByB.put("processingDateTo", closeB);

        writerA.update(closedByA, open);

        assertThatThrownBy(() -> writerB.update(closedByB, open))
                .isInstanceOf(MithraOptimisticLockException.class);

        Map<String, Object> stored = decode(open);
        assertThat(stored.get("processingDateTo")).isEqualTo(closeA);
        assertThat(stored.get("priority")).isEqualTo(Integer.valueOf(10));
    }

    @Test
    void should_succeed_on_retry_after_re_reading_expected_prior() {
        Map<String, Object> original = row(6, "retry", 10, ts(2026, 6, 1));
        writerA.insert(original);

        Map<String, Object> firstWrite = copy(original);
        firstWrite.put("priority", 20);
        writerB.update(firstWrite, original);

        Map<String, Object> stale = copy(original);
        stale.put("priority", 30);
        MithraOptimisticLockException failure = null;
        try {
            writerA.update(stale, original);
        } catch (MithraOptimisticLockException e) {
            failure = e;
        }
        assertThat(failure)
                .as("a stale expected-prior must fail so the caller can retry")
                .isNotNull();
        assertThat(failure.isRetriable())
                .as("Reladomo retries only when isRetriable() is true")
                .isTrue();

        Map<String, Object> freshPrior = decode(original);
        Map<String, Object> retry = copy(freshPrior);
        retry.put("priority", 30);
        writerA.update(retry, freshPrior);

        assertThat(storedPriority(original)).isEqualTo("30");
    }

    @Test
    void should_overwrite_when_upserting_existing_item() {
        Map<String, Object> first = row(7, "before", 1, ts(2026, 6, 1));
        Map<String, Object> replay = row(7, "after", 2, ts(2026, 6, 1));
        writerA.insert(first);
        writerB.upsert(replay);
        assertThat(storedName(first)).isEqualTo("after");
        assertThat(storedPriority(first)).isEqualTo("2");
    }

    // --- helpers ---------------------------------------------------------------------

    private static Map<String, Object> row(int id, String name, int priority, Timestamp from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", id);
        m.put("ruleName", name);
        m.put("priority", priority);
        m.put("businessDateFrom", from);
        m.put("businessDateTo", infinity());
        m.put("processingDateFrom", from);
        m.put("processingDateTo", infinity());
        return m;
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }

    private static String storedName(Map<String, Object> row) {
        return stored(row).get("ruleName").s();
    }

    private static String storedPriority(Map<String, Object> row) {
        return stored(row).get("priority").n();
    }

    private static Map<String, Object> decode(Map<String, Object> row) {
        return codec.decode(stored(row));
    }

    private static Map<String, AttributeValue> stored(Map<String, Object> row) {
        Map<String, AttributeValue> item = ddb.client().getItem(b -> b
                .tableName(TABLE)
                .key(keyOf(row))
                .consistentRead(true)).item();
        assertThat(item)
                .as("item for ruleId=%s must still exist", row.get("ruleId"))
                .isNotNull()
                .isNotEmpty();
        return item;
    }

    private static Map<String, AttributeValue> keyOf(Map<String, Object> row) {
        Map<String, Object> pkValues = new LinkedHashMap<>();
        pkValues.put("ruleId", row.get("ruleId"));
        String pk = keys.partitionKey(mapping, pkValues);
        String sk = keys.sortKey(mapping,
                (Timestamp) row.get("processingDateFrom"),
                (Timestamp) row.get("businessDateFrom"));
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put("pk", AttributeValue.builder().s(pk).build());
        key.put("sk", AttributeValue.builder().s(sk).build());
        return key;
    }

    private static Timestamp ts(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }

    private static Timestamp infinity() {
        return ts(9999, 12, 1);
    }
}
