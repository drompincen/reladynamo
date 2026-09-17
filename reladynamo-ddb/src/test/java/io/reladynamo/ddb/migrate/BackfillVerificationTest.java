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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-01 / M-02: a backfill that certifies a corrupted or composite-key copy as identical is worse
 * than a missing feature — it authorises a production cutover on a lie.
 */
class BackfillVerificationTest {

    private static LocalDynamoDb ddb;
    private static Fixture dated;
    private static Fixture nondated;
    private static Fixture composite;

    @BeforeAll
    static void setUp() {
        ddb = LocalDynamoDb.start();
        dated = fixture(new EntityMapping("com.acme.Balance", "bf_dated",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, ts(9999, 12, 1)),
                Arrays.asList(
                        new AttributeMapping("balanceId", "balanceId", "int", true, false),
                        new AttributeMapping("quantity", "quantity", "double", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false))));
        nondated = fixture(new EntityMapping("com.acme.Product", "bf_nondated",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("code", "code", "String", true, false),
                        new AttributeMapping("label", "label", "String", false, false),
                        new AttributeMapping("payload", "payload", "byte[]", false, true))));
        composite = fixture(new EntityMapping("com.acme.TenantAccount", "bf_composite",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, ts(9999, 12, 1)),
                Arrays.asList(
                        new AttributeMapping("tenantId", "tenantId", "String", true, false),
                        new AttributeMapping("accountId", "accountId", "String", true, false),
                        new AttributeMapping("quantity", "quantity", "double", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false))));
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    // --- M-01 dated -------------------------------------------------------------------------

    @Test
    void should_report_divergence_when_dated_payload_mutated_keeping_temporal_boundaries() {
        Map<String, Object> original = datedRow(1, 10.0, ts(2026, 1, 1));
        assertThat(dated.backfill.run(Collections.singletonList(original)).verified()).isTrue();

        dated.writer.upsert(datedRow(1, 99.0, ts(2026, 1, 1)));
        BackfillResult r = dated.backfill.verify(Collections.singletonList(original));

        assertThat(r.verified()).isFalse();
        assertThat(r.rowsWritten()).isLessThan(r.rowsRead());
        assertThat(joined(r)).contains("quantity");
    }

    @Test
    void should_report_divergence_when_one_dated_version_is_removed() {
        Map<String, Object> v1 = datedRow(2, 10.0, ts(2026, 1, 1));
        Map<String, Object> v2 = datedRow(2, 11.0, ts(2026, 6, 1));
        List<Map<String, Object>> source = Arrays.asList(v1, v2);
        assertThat(dated.backfill.run(source).verified()).isTrue();

        dated.writer.delete(v2);
        BackfillResult r = dated.backfill.verify(source);

        assertThat(r.verified()).isFalse();
        assertThat(joined(r)).containsIgnoringCase("missing");
    }

    @Test
    void should_report_divergence_when_an_extra_dated_version_is_injected() {
        Map<String, Object> original = datedRow(3, 10.0, ts(2026, 1, 1));
        assertThat(dated.backfill.run(Collections.singletonList(original)).verified()).isTrue();

        dated.writer.insert(datedRow(3, 10.0, ts(2026, 9, 1)));
        BackfillResult r = dated.backfill.verify(Collections.singletonList(original));

        assertThat(r.verified()).isFalse();
        assertThat(joined(r)).containsIgnoringCase("extra");
    }

    @Test
    void should_report_zero_divergences_when_dated_copy_is_identical() {
        Map<String, Object> original = datedRow(4, 10.0, ts(2026, 1, 1));
        BackfillResult r = dated.backfill.run(Collections.singletonList(original));
        assertThat(r.verified()).isTrue();
        assertThat(r.divergences()).isEmpty();
        assertThat(r.rowsWritten()).isEqualTo(1);
        assertThat(dated.backfill.verify(Collections.singletonList(original)).verified()).isTrue();
    }

    // --- M-01 non-dated ---------------------------------------------------------------------

    @Test
    void should_report_divergence_when_nondated_payload_mutated() {
        Map<String, Object> original = product("SKU-1", "alpha", new byte[]{1, 2, 3});
        assertThat(nondated.backfill.run(Collections.singletonList(original)).verified()).isTrue();

        nondated.writer.upsert(product("SKU-1", "CORRUPT", new byte[]{1, 2, 3}));
        BackfillResult r = nondated.backfill.verify(Collections.singletonList(original));

        assertThat(r.verified()).isFalse();
        assertThat(r.rowsWritten()).isLessThan(r.rowsRead());
        assertThat(joined(r)).contains("label");
    }

    @Test
    void should_report_divergence_when_nondated_row_is_removed() {
        Map<String, Object> original = product("SKU-2", "alpha", new byte[]{9});
        assertThat(nondated.backfill.run(Collections.singletonList(original)).verified()).isTrue();

        nondated.writer.delete(original);
        BackfillResult r = nondated.backfill.verify(Collections.singletonList(original));

        assertThat(r.verified()).isFalse();
        assertThat(joined(r)).containsIgnoringCase("missing");
    }

    @Test
    void should_report_divergence_when_extra_nondated_version_is_injected_into_partition() {
        Map<String, Object> original = product("SKU-3", "alpha", new byte[]{1});
        assertThat(nondated.backfill.run(Collections.singletonList(original)).verified()).isTrue();

        injectExtraSortKey(nondated, original, "v1#EXTRA");
        BackfillResult r = nondated.backfill.verify(Collections.singletonList(original));

        assertThat(r.verified()).isFalse();
        // Same logical PK, extra physical item: reported as EXTRA or as a duplicate identity
        // (non-dated identity is the PK, so two items in one partition collide).
        assertThat(joined(r).toLowerCase()).containsAnyOf("extra", "duplicate");
    }

    @Test
    void should_report_zero_divergences_when_nondated_copy_is_identical() {
        Map<String, Object> original = product("SKU-4", "alpha", new byte[]{1, 2, 3});
        BackfillResult r = nondated.backfill.run(Collections.singletonList(original));
        assertThat(r.verified()).isTrue();
        assertThat(r.divergences()).isEmpty();
        assertThat(r.rowsWritten()).isEqualTo(1);
    }

    // --- M-02 composite PK ------------------------------------------------------------------

    @Test
    void should_round_trip_a_composite_primary_key() {
        Map<String, Object> row = account("ten-A", "acct-1", 10.0, ts(2026, 1, 1));
        BackfillResult r = composite.backfill.run(Collections.singletonList(row));
        assertThat(r.verified()).isTrue();
        List<Map<String, Object>> back = composite.backfill.readBack(pk(row));
        assertThat(back).hasSize(1);
        assertThat(back.get(0).get("quantity")).isEqualTo(10.0);
        assertThat(back.get(0).get("tenantId")).isEqualTo("ten-A");
        assertThat(back.get(0).get("accountId")).isEqualTo("acct-1");
    }

    @Test
    void should_resume_a_composite_key_backfill_without_duplicating_or_losing_rows() {
        Map<String, Object> first = account("ten-B", "acct-1", 1.0, ts(2026, 1, 1));
        Map<String, Object> second = account("ten-B", "acct-2", 2.0, ts(2026, 1, 1));
        composite.writer.insert(first);

        BackfillResult r = composite.backfill.run(Arrays.asList(first, second));
        assertThat(r.verified()).isTrue();
        assertThat(r.rowsWritten()).isEqualTo(2);
        assertThat(composite.backfill.readBack(pk(first))).hasSize(1);
        assertThat(composite.backfill.readBack(pk(second))).hasSize(1);

        BackfillResult again = composite.backfill.run(Arrays.asList(first, second));
        assertThat(again.verified()).isTrue();
        assertThat(composite.backfill.readBack(pk(first))).hasSize(1);
    }

    @Test
    void should_report_divergence_when_binary_payload_mutated_using_independently_constructed_arrays() {
        byte[] originalBytes = new byte[] {1, 2, 3};
        byte[] equalCopy = new byte[] {1, 2, 3};
        byte[] mutated = new byte[] {1, 2, 4};
        assertThat(originalBytes).isNotSameAs(equalCopy);
        assertThat(originalBytes).isNotSameAs(mutated);

        Map<String, Object> original = product("SKU-BIN", "alpha", originalBytes);
        assertThat(nondated.backfill.run(Collections.singletonList(original)).verified()).isTrue();

        BackfillResult sameContent = nondated.backfill.verify(
                Collections.singletonList(product("SKU-BIN", "alpha", equalCopy)));
        assertThat(sameContent.verified())
                .as("independently constructed equal byte[] must compare by content")
                .isTrue();

        nondated.writer.upsert(product("SKU-BIN", "alpha", mutated));
        BackfillResult r = nondated.backfill.verify(Collections.singletonList(original));
        assertThat(r.verified()).isFalse();
        assertThat(joined(r)).contains("payload");
    }

    @Test
    void should_not_cross_match_two_tenants_sharing_an_account_id() {
        Map<String, Object> a = account("ten-X", "shared", 10.0, ts(2026, 1, 1));
        Map<String, Object> b = account("ten-Y", "shared", 99.0, ts(2026, 1, 1));
        BackfillResult r = composite.backfill.run(Arrays.asList(a, b));
        assertThat(r.verified()).isTrue();

        List<Map<String, Object>> fromX = composite.backfill.readBack(pk(a));
        List<Map<String, Object>> fromY = composite.backfill.readBack(pk(b));
        assertThat(fromX).hasSize(1);
        assertThat(fromY).hasSize(1);
        assertThat(fromX.get(0).get("quantity")).isEqualTo(10.0);
        assertThat(fromY.get(0).get("quantity")).isEqualTo(99.0);
        assertThat(fromX.get(0).get("tenantId")).isEqualTo("ten-X");
        assertThat(fromY.get(0).get("tenantId")).isEqualTo("ten-Y");
    }

    @Test
    void should_not_cross_match_two_accounts_sharing_a_tenant_id() {
        Map<String, Object> a = account("ten-Z", "acct-1", 10.0, ts(2026, 1, 1));
        Map<String, Object> b = account("ten-Z", "acct-2", 99.0, ts(2026, 1, 1));
        BackfillResult r = composite.backfill.run(Arrays.asList(a, b));
        assertThat(r.verified()).isTrue();

        List<Map<String, Object>> first = composite.backfill.readBack(pk(a));
        List<Map<String, Object>> second = composite.backfill.readBack(pk(b));
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0).get("quantity")).isEqualTo(10.0);
        assertThat(second.get(0).get("quantity")).isEqualTo(99.0);
        assertThat(first.get(0).get("accountId")).isEqualTo("acct-1");
        assertThat(second.get(0).get("accountId")).isEqualTo("acct-2");

        composite.writer.delete(b);
        BackfillResult missingSecond = composite.backfill.verify(Arrays.asList(a, b));
        assertThat(missingSecond.verified()).isFalse();
        assertThat(joined(missingSecond).toLowerCase()).contains("missing");
    }

    // --- helpers ----------------------------------------------------------------------------

    private static Fixture fixture(EntityMapping mapping) {
        ItemCodec codec = new ItemCodec(mapping);
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        DynamoDbWriter writer = new DynamoDbWriter(ddb.client(), mapping, codec, keys);
        ddb.client().createTable(b -> b.tableName(mapping.tableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
        Backfill backfill = new Backfill(ddb.client(), mapping, codec, keys, writer);
        return new Fixture(mapping, codec, writer, backfill, keys);
    }

    private static void injectExtraSortKey(Fixture f, Map<String, Object> row, String sk) {
        Map<String, AttributeValue> item = new LinkedHashMap<>(f.codec.encode(row));
        Map<String, Object> pkValues = pk(row);
        String partition = f.keys.partitionKey(f.mapping, pkValues);
        item.put("pk", AttributeValue.builder().s(partition).build());
        item.put("sk", AttributeValue.builder().s(sk).build());
        ddb.client().putItem(b -> b.tableName(f.mapping.tableName()).item(item));
    }

    private static Map<String, Object> pk(Map<String, Object> row) {
        Map<String, Object> pk = new LinkedHashMap<>();
        if (row.containsKey("tenantId")) {
            pk.put("tenantId", row.get("tenantId"));
            pk.put("accountId", row.get("accountId"));
        } else if (row.containsKey("balanceId")) {
            pk.put("balanceId", row.get("balanceId"));
        } else {
            pk.put("code", row.get("code"));
        }
        return pk;
    }

    private static Map<String, Object> datedRow(int id, double qty, Timestamp from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balanceId", Integer.valueOf(id));
        m.put("quantity", Double.valueOf(qty));
        m.put("businessDateFrom", from);
        m.put("businessDateTo", ts(9999, 12, 1));
        m.put("processingDateFrom", from);
        m.put("processingDateTo", ts(9999, 12, 1));
        return m;
    }

    private static Map<String, Object> product(String code, String label, byte[] payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("payload", payload);
        return m;
    }

    private static Map<String, Object> account(String tenant, String account, double qty, Timestamp from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenant);
        m.put("accountId", account);
        m.put("quantity", Double.valueOf(qty));
        m.put("businessDateFrom", from);
        m.put("businessDateTo", ts(9999, 12, 1));
        m.put("processingDateFrom", from);
        m.put("processingDateTo", ts(9999, 12, 1));
        return m;
    }

    private static String joined(BackfillResult r) {
        StringBuilder sb = new StringBuilder();
        for (String d : r.divergences()) {
            sb.append(d).append('\n');
        }
        return sb.toString();
    }

    private static Timestamp ts(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }

    private static final class Fixture {
        final EntityMapping mapping;
        final ItemCodec codec;
        final DynamoDbWriter writer;
        final Backfill backfill;
        final DefaultKeyStrategy keys;

        Fixture(EntityMapping mapping, ItemCodec codec, DynamoDbWriter writer,
                Backfill backfill, DefaultKeyStrategy keys) {
            this.mapping = mapping;
            this.codec = codec;
            this.writer = writer;
            this.backfill = backfill;
            this.keys = keys;
        }
    }
}
