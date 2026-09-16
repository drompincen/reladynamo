package io.reladynamo.ddb.persist;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PartitionKeyEncoder;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.temporal.TemporalEncoder;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

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
 * R-10: sparse-current GSI keys are stamped only on current rows, on the GSI's own
 * attribute names, and removed when a correction closes the rectangle.
 */
class SparseCurrentGsiStampTest {

    private static LocalDynamoDb ddb;

    @BeforeAll
    static void startLocal() {
        ddb = LocalDynamoDb.start();
    }

    @AfterAll
    static void stopLocal() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_stamp_sparse_current_keys_only_when_processing_thru_is_infinity() {
        EntityMapping mapping = ruleMapping("stamp_current_only");
        GsiSpec gsi = GsiSpec.sparseCurrent("gsi_current", Collections.singletonList("ruleId"));
        DynamoDbWriter writer = writerFor(mapping, gsi);

        Map<String, Object> current = ruleRow(1, "live", ts(2026, 1, 1), ts(2026, 1, 1), infinity());
        Map<String, Object> closed = ruleRow(1, "old", ts(2026, 1, 1), ts(2025, 12, 1), ts(2026, 1, 1));
        writer.insert(current);
        writer.insert(closed);

        Map<String, AttributeValue> currentItem = get(mapping, current);
        Map<String, AttributeValue> closedItem = get(mapping, closed);

        String expectedPk = new DefaultKeyStrategy().partitionKey(mapping, pk(1));
        String expectedSk = PartitionKeyEncoder.currentBusinessSk(ts(2026, 1, 1));
        assertThat(gsi.partitionKeyAttributeName()).isEqualTo("gsi_ruleId");
        assertThat(gsi.sortKeyAttributeName()).isEqualTo("gsi_sk");
        assertThat(currentItem)
                .as("current rows must carry the sparse-current GSI attributes GsiSpec declares")
                .containsKeys("gsi_ruleId", "gsi_sk");
        assertThat(currentItem.get("gsi_ruleId").s()).isEqualTo(expectedPk);
        assertThat(currentItem.get("gsi_sk").s()).isEqualTo(expectedSk);
        assertThat(currentItem.get("gsi_ruleId").s()).isEqualTo(currentItem.get("pk").s());
        assertThat(closedItem.containsKey("gsi_ruleId"))
                .as("closed processing rectangles must omit sparse-current keys")
                .isFalse();
        assertThat(closedItem.containsKey("gsi_sk")).isFalse();
    }

    @Test
    void should_remove_sparse_current_keys_when_a_correction_closes_the_rectangle() {
        EntityMapping mapping = ruleMapping("stamp_remove_on_close");
        GsiSpec gsi = GsiSpec.sparseCurrent("gsi_current", Collections.singletonList("ruleId"));
        DynamoDbWriter writer = writerFor(mapping, gsi);

        Timestamp opened = ts(2026, 1, 1);
        Timestamp closedAt = ts(2026, 6, 1);
        Map<String, Object> current = ruleRow(7, "v1", opened, opened, infinity());
        writer.insert(current);

        Map<String, AttributeValue> before = get(mapping, current);
        assertThat(before)
                .as("the open rectangle must be in the sparse-current index before the correction")
                .containsKeys("gsi_ruleId", "gsi_sk");

        Map<String, Object> closed = ruleRow(7, "v1", opened, opened, closedAt);
        writer.update(closed, current);

        Map<String, AttributeValue> after = get(mapping, closed);
        assertThat(after.containsKey("gsi_ruleId"))
                .as("PutItem of a closed rectangle must drop sparse keys so the GSI forgets the row")
                .isFalse();
        assertThat(after.containsKey("gsi_sk")).isFalse();
        assertThat(after.get("OUT_Z").s()).isEqualTo(TemporalEncoder.encode(closedAt));

        QueryResponse gsiAfterClose = queryCurrentGsi(mapping, gsi, 7);
        assertThat(gsiAfterClose.items())
                .as("a closed rectangle must not remain in the sparse-current index")
                .isEmpty();

        Map<String, Object> replacement = ruleRow(7, "v2", opened, closedAt, infinity());
        writer.insert(replacement);

        QueryResponse gsiAfterInsert = queryCurrentGsi(mapping, gsi, 7);
        assertThat(gsiAfterInsert.items()).hasSize(1);
        assertThat(gsiAfterInsert.items().get(0).get("ruleName").s()).isEqualTo("v2");
        assertThat(gsiAfterInsert.items().get(0).get("gsi_sk").s())
                .isEqualTo(PartitionKeyEncoder.currentBusinessSk(opened));
    }

    @Test
    void should_stamp_composite_sparse_current_keys_identical_to_the_base_partition_key() {
        EntityMapping mapping = positionMapping("stamp_composite_current");
        GsiSpec gsi = GsiSpec.sparseCurrent("gsi_current", Arrays.asList("accountId", "productId"));
        DynamoDbWriter writer = writerFor(mapping, gsi);

        Map<String, Object> row = positionRow(9L, 4, "OPEN", ts(2026, 3, 1), infinity());
        writer.insert(row);

        Map<String, AttributeValue> item = get(mapping, row);
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("accountId", Long.valueOf(9L));
        pkValues.put("productId", Integer.valueOf(4));
        String expectedPk = new DefaultKeyStrategy().partitionKey(mapping, pkValues);

        assertThat(gsi.partitionKeyAttributeName()).isEqualTo("gsi_pk");
        assertThat(item)
                .as("composite sparse-current keys use gsi_pk / gsi_sk, not the base table names")
                .containsKeys("gsi_pk", "gsi_sk");
        assertThat(item.get("gsi_pk").s()).isEqualTo(expectedPk);
        assertThat(item.get("gsi_pk").s()).isEqualTo(item.get("pk").s());
        assertThat(item.get("gsi_sk").s()).isEqualTo(PartitionKeyEncoder.currentBusinessSk(ts(2026, 3, 1)));
    }

    @Test
    void should_stamp_composite_lookup_gsi_keys_with_key_component_encoding() {
        EntityMapping mapping = lookupMapping("stamp_composite_lookup");
        GsiSpec gsi = new GsiSpec("byTenantStatus", Arrays.asList("tenantId", "status"),
                null, false, GsiSpec.Projection.ALL, null);
        DynamoDbWriter writer = writerFor(mapping, gsi);

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Long.valueOf(3L));
        row.put("tenantId", "acme");
        row.put("status", "OPEN");
        writer.insert(row);

        Map<String, AttributeValue> item = get(mapping, row);
        assertThat(gsi.partitionKeyAttributeName()).isEqualTo("gsi_pk");
        assertThat(item)
                .as("composite lookup GSI keys must be stamped, not skipped because pkNames.size() != 1")
                .containsKey("gsi_pk");
        assertThat(item.get("gsi_pk").s()).isEqualTo("v1#GSI#TENANTID#acme#STATUS#OPEN");
    }

    private static DynamoDbWriter writerFor(EntityMapping mapping, GsiSpec gsi) {
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();
        new TableCreator(ddb.client()).create(design);
        return new DynamoDbWriter(ddb.client(), mapping, new ItemCodec(mapping),
                new DefaultKeyStrategy(), Collections.singletonList(gsi));
    }

    private static Map<String, AttributeValue> get(EntityMapping mapping, Map<String, Object> row) {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        List<AttributeMapping> pks = mapping.primaryKeyAttributes();
        for (int i = 0; i < pks.size(); i++) {
            String javaName = pks.get(i).javaName();
            pkValues.put(javaName, row.get(javaName));
        }
        Timestamp processingFrom = asTimestamp(row.get("processingDateFrom"));
        Timestamp businessFrom = asTimestamp(row.get("businessDateFrom"));
        Map<String, AttributeValue> key = new LinkedHashMap<String, AttributeValue>();
        key.put("pk", AttributeValue.builder().s(keys.partitionKey(mapping, pkValues)).build());
        key.put("sk", AttributeValue.builder().s(keys.sortKey(mapping, processingFrom, businessFrom)).build());
        GetItemResponse response = ddb.client().getItem(b -> b.tableName(mapping.tableName()).key(key));
        assertThat(response.hasItem()).isTrue();
        return response.item();
    }

    private static QueryResponse queryCurrentGsi(EntityMapping mapping, GsiSpec gsi, int ruleId) {
        String encodedPk = new DefaultKeyStrategy().partitionKey(mapping, pk(ruleId));
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#gsipk", gsi.partitionKeyAttributeName());
        Map<String, AttributeValue> values = new LinkedHashMap<String, AttributeValue>();
        values.put(":pk", AttributeValue.builder().s(encodedPk).build());
        return ddb.client().query(b -> b.tableName(mapping.tableName())
                .indexName(gsi.name())
                .keyConditionExpression("#gsipk = :pk")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values));
    }

    private static EntityMapping ruleMapping(String table) {
        return new EntityMapping("com.acme.PlanRule", table,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, infinity()),
                Arrays.asList(
                        new AttributeMapping("ruleId", "ruleId", "int", true, false),
                        new AttributeMapping("ruleName", "ruleName", "String", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
    }

    private static EntityMapping positionMapping(String table) {
        return new EntityMapping("com.acme.PlanPosition", table,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, infinity()),
                Arrays.asList(
                        new AttributeMapping("accountId", "accountId", "long", true, false),
                        new AttributeMapping("productId", "productId", "int", true, false),
                        new AttributeMapping("status", "status", "String", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
    }

    private static EntityMapping lookupMapping(String table) {
        return new EntityMapping("com.acme.Lookup", table, TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("id", "id", "long", true, false),
                        new AttributeMapping("tenantId", "tenantId", "String", false, false),
                        new AttributeMapping("status", "status", "String", false, false)));
    }

    private static Map<String, Object> ruleRow(int id, String name, Timestamp businessFrom,
                                               Timestamp processingFrom, Timestamp processingTo) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("ruleId", Integer.valueOf(id));
        row.put("ruleName", name);
        row.put("businessDateFrom", businessFrom);
        row.put("businessDateTo", infinity());
        row.put("processingDateFrom", processingFrom);
        row.put("processingDateTo", processingTo);
        return row;
    }

    private static Map<String, Object> positionRow(long accountId, int productId, String status,
                                                   Timestamp from, Timestamp processingTo) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("accountId", Long.valueOf(accountId));
        row.put("productId", Integer.valueOf(productId));
        row.put("status", status);
        row.put("businessDateFrom", from);
        row.put("businessDateTo", infinity());
        row.put("processingDateFrom", from);
        row.put("processingDateTo", processingTo);
        return row;
    }

    private static Map<String, Object> pk(int ruleId) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("ruleId", Integer.valueOf(ruleId));
        return values;
    }

    private static Timestamp asTimestamp(Object value) {
        return value instanceof Timestamp ? (Timestamp) value : null;
    }

    private static Timestamp ts(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(0);
        return t;
    }

    private static Timestamp infinity() {
        return ts(9999, 12, 1);
    }
}
