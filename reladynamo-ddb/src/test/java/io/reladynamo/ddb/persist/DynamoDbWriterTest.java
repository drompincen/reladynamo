package io.reladynamo.ddb.persist;

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
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write path against real DynamoDB (in-process Local), not a mock. Mocks would confirm the calls
 * we intended to make; only a real store confirms the item that actually lands, which is what the
 * differential gate will compare.
 */
class DynamoDbWriterTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static DynamoDbWriter writer;
    private static final String TABLE = "plan_rule";

    @BeforeAll
    static void setUp() {
        ddb = LocalDynamoDb.start();
        mapping = new EntityMapping("com.acme.PlanRule", TABLE,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, infinity()),
                Arrays.asList(
                        new AttributeMapping("ruleId", "ruleId", "int", true, false),
                        new AttributeMapping("ruleName", "ruleName", "String", false, false),
                        new AttributeMapping("priority", "priority", "int", false, false),
                        // The temporal boundaries are stored columns, exactly as MithraObjectXmlParser
                        // now emits them for a bitemporal object.
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));

        ddb.client().createTable(b -> b.tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

        writer = new DynamoDbWriter(ddb.client(), mapping, new ItemCodec(mapping),
                new DefaultKeyStrategy());
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void insert_writes_an_item_addressable_by_its_derived_keys() {
        writer.insert(row(1, "alpha", 10, ts(2026, 6, 1)));

        List<Map<String, AttributeValue>> items = scanAll();
        assertThat(items).hasSize(1);
        Map<String, AttributeValue> item = items.get(0);
        assertThat(item.get("pk").s()).isEqualTo("v1#PLANRULE#1");
        assertThat(item.get("sk").s()).startsWith("v1#P#");
        assertThat(item.get("ruleName").s()).isEqualTo("alpha");
    }

    @Test
    void two_versions_of_one_key_coexist_because_the_sort_key_separates_them() {
        // This is the whole reason the sort key carries both temporal axes: correcting a row must
        // add a version, not overwrite one. If these collapsed to a single item, every bitemporal
        // guarantee would be lost at the storage layer.
        writer.insert(row(2, "v1", 1, ts(2026, 1, 1)));
        writer.insert(row(2, "v2", 2, ts(2026, 7, 1)));

        long forKey2 = scanAll().stream()
                .filter(i -> "v1#PLANRULE#2".equals(i.get("pk").s()))
                .count();
        assertThat(forKey2).isEqualTo(2);
    }

    @Test
    void delete_removes_exactly_the_addressed_version() {
        Map<String, Object> a = row(3, "keep", 1, ts(2026, 1, 1));
        Map<String, Object> b = row(3, "drop", 2, ts(2026, 7, 1));
        writer.insert(a);
        writer.insert(b);

        writer.delete(b);

        List<Map<String, AttributeValue>> left = new ArrayList<>();
        for (Map<String, AttributeValue> i : scanAll()) {
            if ("v1#PLANRULE#3".equals(i.get("pk").s())) {
                left.add(i);
            }
        }
        assertThat(left).hasSize(1);
        assertThat(left.get(0).get("ruleName").s()).isEqualTo("keep");
    }

    @Test
    void batch_insert_writes_every_row_including_past_the_25_item_limit() {
        // 60 rows spans three BatchWriteItem calls. If chunking or the unprocessed-items retry were
        // wrong, this is where writes would silently go missing.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 100; i < 160; i++) {
            rows.add(row(i, "bulk" + i, i, ts(2026, 6, 1)));
        }
        writer.batchInsert(rows);

        long written = scanAll().stream()
                .filter(i -> i.get("pk").s().startsWith("v1#PLANRULE#1")
                        && i.get("ruleName").s().startsWith("bulk"))
                .count();
        assertThat(written).isEqualTo(60);
    }

    // --- helpers ---------------------------------------------------------------------
    private static List<Map<String, AttributeValue>> scanAll() {
        ScanResponse r = ddb.client().scan(b -> b.tableName(TABLE));
        return new ArrayList<>(r.items());
    }

    private static Map<String, Object> row(int id, String name, int priority, Timestamp businessFrom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", id);
        m.put("ruleName", name);
        m.put("priority", priority);
        m.put("businessDateFrom", businessFrom);
        m.put("businessDateTo", infinity());
        m.put("processingDateFrom", businessFrom);
        m.put("processingDateTo", infinity());
        return m;
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
