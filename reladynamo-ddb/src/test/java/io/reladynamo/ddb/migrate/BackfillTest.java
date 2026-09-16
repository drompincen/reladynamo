package io.reladynamo.ddb.migrate;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backfill is the step where a migration silently goes wrong: a partial copy leaves DynamoDB looking
 * populated and being incomplete, and nothing about a successful-looking run says otherwise. So the
 * tool verifies by reading back and diffing, and reports what it actually wrote rather than what it
 * was asked to write.
 */
class BackfillTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static DynamoDbWriter writer;
    private static ItemCodec codec;

    @BeforeAll
    static void setUp() {
        ddb = LocalDynamoDb.start();
        mapping = new EntityMapping("com.acme.Balance", "backfill_balance",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, ts(9999, 12, 1)),
                Arrays.asList(
                        new AttributeMapping("balanceId", "balanceId", "int", true, false),
                        new AttributeMapping("quantity", "quantity", "double", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
        codec = new ItemCodec(mapping);
        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        createTable(mapping.tableName());
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    private static void createTable(String tableName) {
        ddb.client().createTable(b -> b.tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
    }

    private static Backfill backfill() {
        return new Backfill(ddb.client(), mapping, codec, new DefaultKeyStrategy(), writer);
    }

    @Test
    void copies_every_version_and_verifies_by_reading_back() {
        List<Map<String, Object>> source = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            source.add(row(i, i * 1.5, ts(2026, 1, 1)));
        }
        BackfillResult r = backfill().run(source);

        assertThat(r.rowsRead()).isEqualTo(40);
        assertThat(r.rowsWritten()).isEqualTo(40);
        assertThat(r.verified()).isTrue();
        assertThat(r.divergences()).isEmpty();
    }

    @Test
    void preserves_all_four_temporal_boundaries_through_the_copy() {
        Map<String, Object> r1 = row(100, 10.0, ts(2026, 1, 1));
        r1.put("businessDateTo", ts(2026, 6, 1));
        BackfillResult r = backfill().run(Arrays.asList(r1));

        assertThat(r.verified()).isTrue();
        Map<String, Object> back = backfill().readBack(100).get(0);
        assertThat(back.get("businessDateFrom")).isEqualTo(ts(2026, 1, 1));
        assertThat(back.get("businessDateTo")).isEqualTo(ts(2026, 6, 1));
        assertThat(back.get("processingDateTo")).isEqualTo(ts(9999, 12, 1));
    }

    @Test
    void is_idempotent_so_a_resumed_migration_does_not_double_write() {
        List<Map<String, Object>> source = Arrays.asList(row(200, 5.0, ts(2026, 1, 1)));
        backfill().run(source);
        BackfillResult second = backfill().run(source);

        assertThat(second.verified()).isTrue();
        assertThat(backfill().readBack(200)).hasSize(1);
    }

    @Test
    void reports_an_empty_source_rather_than_claiming_success() {
        BackfillResult r = backfill().run(new ArrayList<>());
        assertThat(r.rowsRead()).isZero();
        assertThat(r.verified()).isFalse();
        assertThat(r.summary()).containsIgnoringCase("empty");
    }

    @Test
    void refuses_a_row_missing_a_temporal_boundary() {
        Map<String, Object> bad = row(300, 1.0, ts(2026, 1, 1));
        bad.remove("businessDateFrom");
        assertThatThrownBy(() -> backfill().run(Arrays.asList(bad)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> row(int id, double qty, Timestamp from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balanceId", id);
        m.put("quantity", qty);
        m.put("businessDateFrom", from);
        m.put("businessDateTo", ts(9999, 12, 1));
        m.put("processingDateFrom", from);
        m.put("processingDateTo", ts(9999, 12, 1));
        return m;
    }

    private static Timestamp ts(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
