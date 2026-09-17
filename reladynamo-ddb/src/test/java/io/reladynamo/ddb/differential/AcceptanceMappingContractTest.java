package io.reladynamo.ddb.differential;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.mapping.ReladynamoConfigException;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.temporal.TemporalEncoder;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inspection acceptance case 9: custom temporal names, PK types, source routing, and
 * GSI projections either work end to end or fail preflight by name. A silent
 * half-working path is the finding this class exists to catch.
 *
 * <p>The {@code validDate} fixture is a real parsed model, not an inference from reading
 * the parser. The writer is the first thing that would have looked for the conventional
 * {@code businessDateFrom} name and either stored the row under an unfindable key or
 * thrown after table creation.
 */
class AcceptanceMappingContractTest {

    private static LocalDynamoDb ddb;

    @BeforeAll
    static void start() {
        ddb = LocalDynamoDb.start();
    }

    @AfterAll
    static void stop() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void custom_validDate_axis_round_trips_through_writer_or_refuses_before_any_write() {
        EntityMapping mapping = new MithraObjectXmlParser().parse(validDateXml("POLICY_VALIDDATE"));
        assertThat(mapping.attribute("validDateFrom").itemName()).isEqualTo("VALID_FROM");
        assertThat(mapping.attribute("validDateTo").itemName()).isEqualTo("VALID_TO");

        PhysicalDesign design = PhysicalDesign.builder(mapping).build();
        assertThat(design.businessFromJavaName())
                .as("PhysicalDesign must derive the from-java name from the parsed mapping, "
                        + "not keep the businessDateFrom default")
                .isEqualTo("validDateFrom");
        assertThat(design.businessFromItemName()).isEqualTo("VALID_FROM");
        assertThat(design.businessThruItemName()).isEqualTo("VALID_TO");
        assertThat(design.businessAsOfJavaName()).isEqualTo("validDate");

        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbWriter writer = new DynamoDbWriter(
                ddb.client(), mapping, codec, new DefaultKeyStrategy());

        Timestamp from = utc(2024, 3, 15);
        Timestamp infinity = mapping.temporal().infinity();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("policyId", Long.valueOf(7L));
        row.put("premium", Integer.valueOf(100));
        row.put("validDateFrom", from);
        row.put("validDateTo", infinity);

        writer.insert(row);

        String pk = new DefaultKeyStrategy().partitionKey(
                mapping, Collections.singletonMap("policyId", Long.valueOf(7L)));
        String sk = new DefaultKeyStrategy().sortKey(mapping, null, from);
        assertThat(sk).isEqualTo("v1#B#" + TemporalEncoder.encode(from));

        Map<String, AttributeValue> key = new LinkedHashMap<String, AttributeValue>();
        key.put("pk", AttributeValue.builder().s(pk).build());
        key.put("sk", AttributeValue.builder().s(sk).build());
        GetItemResponse got = ddb.client().getItem(b -> b
                .tableName(mapping.tableName())
                .key(key)
                .consistentRead(Boolean.TRUE));
        assertThat(got.hasItem())
                .as("item must be addressable by the sort key derived from validDateFrom, "
                        + "not from a missing businessDateFrom")
                .isTrue();
        assertThat(got.item().get("VALID_FROM")).isNotNull();
        assertThat(got.item().containsKey("FROM_Z"))
                .as("payload must use the mapped column name VALID_FROM, not the FROM_Z default")
                .isFalse();

        Map<String, Object> decoded = codec.decode(got.item());
        assertThat(decoded.get("policyId")).isEqualTo(Long.valueOf(7L));
        assertThat(decoded.get("premium")).isEqualTo(Integer.valueOf(100));
        assertThat(decoded.get("validDateFrom")).isEqualTo(from);
        assertThat(decoded.get("validDateTo")).isEqualTo(infinity);
    }

    @Test
    void bitemporal_custom_axis_names_are_refused_at_preflight_by_code() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraObject objectType=\"transactional\">"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>Ledger</ClassName>"
                + "<DefaultTable>LEDGER_CUSTOM_AXES</DefaultTable>"
                + asOf("validDate", "VALID_FROM", "VALID_TO", false)
                + asOf("sysTime", "SYS_IN", "SYS_OUT", true)
                + "<Attribute name=\"ledgerId\" javaType=\"long\" columnName=\"LEDGER_ID\" primaryKey=\"true\"/>"
                + "</MithraObject>";

        assertThatThrownBy(() -> {
            EntityMapping mapping = new MithraObjectXmlParser().parse(xml);
            PhysicalDesign.builder(mapping).build();
            new DynamoDbWriter(ddb.client(), mapping, new ItemCodec(mapping), new DefaultKeyStrategy());
        }).isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-015")
                .hasMessageContaining("validDate")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-015");

        assertTableAbsent("LEDGER_CUSTOM_AXES");
    }

    @Test
    void string_and_composite_primary_keys_round_trip() {
        EntityMapping sku = new MithraObjectXmlParser().parse(
                nonDatedXml("SkuItem", "SKU_ITEM",
                        "<Attribute name=\"sku\" javaType=\"String\" columnName=\"SKU\" primaryKey=\"true\"/>"
                                + "<Attribute name=\"label\" javaType=\"String\" columnName=\"LABEL\"/>"));
        new TableCreator(ddb.client()).create(sku);
        ItemCodec skuCodec = new ItemCodec(sku);
        DynamoDbWriter skuWriter = new DynamoDbWriter(
                ddb.client(), sku, skuCodec, new DefaultKeyStrategy());
        Map<String, Object> skuRow = new LinkedHashMap<String, Object>();
        skuRow.put("sku", "SKU-42");
        skuRow.put("label", "widget");
        skuWriter.insert(skuRow);

        String skuPk = new DefaultKeyStrategy().partitionKey(
                sku, Collections.singletonMap("sku", "SKU-42"));
        assertThat(skuPk).isEqualTo("v1#SKUITEM#SKU-42");
        Map<String, Object> skuDecoded = getAndDecode(sku, skuCodec, skuPk,
                DefaultKeyStrategy.NON_DATED_SORT_KEY);
        assertThat(skuDecoded.get("sku")).isEqualTo("SKU-42");
        assertThat(skuDecoded.get("label")).isEqualTo("widget");

        EntityMapping composite = new MithraObjectXmlParser().parse(
                nonDatedXml("TenantAccount", "TENANT_ACCOUNT",
                        "<Attribute name=\"tenantId\" javaType=\"String\" columnName=\"TENANT_ID\" primaryKey=\"true\"/>"
                                + "<Attribute name=\"accountId\" javaType=\"long\" columnName=\"ACCOUNT_ID\" primaryKey=\"true\"/>"
                                + "<Attribute name=\"status\" javaType=\"String\" columnName=\"STATUS\"/>"));
        new TableCreator(ddb.client()).create(composite);
        ItemCodec compositeCodec = new ItemCodec(composite);
        DynamoDbWriter compositeWriter = new DynamoDbWriter(
                ddb.client(), composite, compositeCodec, new DefaultKeyStrategy());
        Map<String, Object> compositeRow = new LinkedHashMap<String, Object>();
        compositeRow.put("tenantId", "acme");
        compositeRow.put("accountId", Long.valueOf(9L));
        compositeRow.put("status", "OPEN");
        compositeWriter.insert(compositeRow);

        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("tenantId", "acme");
        pkValues.put("accountId", Long.valueOf(9L));
        String compositePk = new DefaultKeyStrategy().partitionKey(composite, pkValues);
        assertThat(compositePk).isEqualTo("v1#TENANTACCOUNT#acme#9");
        Map<String, Object> compositeDecoded = getAndDecode(composite, compositeCodec, compositePk,
                DefaultKeyStrategy.NON_DATED_SORT_KEY);
        assertThat(compositeDecoded.get("tenantId")).isEqualTo("acme");
        assertThat(compositeDecoded.get("accountId")).isEqualTo(Long.valueOf(9L));
        assertThat(compositeDecoded.get("status")).isEqualTo("OPEN");
    }

    @Test
    void float_primary_key_is_refused_at_parse_before_any_write() {
        String xml = nonDatedXml("RateKey", "RATE_KEY",
                "<Attribute name=\"rate\" javaType=\"float\" columnName=\"RATE\" primaryKey=\"true\"/>");
        assertThatThrownBy(() -> new MithraObjectXmlParser().parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-016")
                .hasMessageContaining("float")
                .hasMessageContaining("rate")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-016");
        assertTableAbsent("RATE_KEY");
    }

    @Test
    void source_attribute_is_refused_at_parse_with_cfg_012_before_any_write() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraObject objectType=\"transactional\">"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>TenantBalance</ClassName>"
                + "<DefaultTable>TENANT_BALANCE</DefaultTable>"
                + "<SourceAttribute name=\"tenantId\" javaType=\"String\"/>"
                + "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                + "</MithraObject>";
        assertThatThrownBy(() -> new MithraObjectXmlParser().parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-012")
                .hasMessageContaining("sourceAttribute")
                .hasMessageContaining("tenantId")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-012");
        assertTableAbsent("TENANT_BALANCE");
    }

    @Test
    void keys_only_and_include_gsi_projections_are_refused_before_create_table() {
        EntityMapping mapping = new MithraObjectXmlParser().parse(
                nonDatedXml("CustomerKeysOnly", "CUSTOMER_KEYS_ONLY_ACC",
                        "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                                + "<Attribute name=\"email\" javaType=\"String\" columnName=\"EMAIL\"/>"));
        GsiSpec keysOnly = GsiSpec.uniqueAttribute("gsi_email", "email");
        PhysicalDesign keysOnlyDesign = PhysicalDesign.builder(mapping).addGsi(keysOnly).build();
        assertThatThrownBy(() -> new TableCreator(ddb.client()).create(keysOnlyDesign))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-014")
                .hasMessageContaining("KEYS_ONLY")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-014");
        assertTableAbsent("CUSTOMER_KEYS_ONLY_ACC");

        EntityMapping includeMapping = new MithraObjectXmlParser().parse(
                nonDatedXml("CustomerInclude", "CUSTOMER_INCLUDE_ACC",
                        "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                                + "<Attribute name=\"status\" javaType=\"String\" columnName=\"STATUS\"/>"
                                + "<Attribute name=\"name\" javaType=\"String\" columnName=\"NAME\"/>"));
        GsiSpec include = new GsiSpec("gsi_status", Collections.singletonList("status"), null, false,
                GsiSpec.Projection.INCLUDE, Arrays.asList("name"));
        PhysicalDesign includeDesign = PhysicalDesign.builder(includeMapping).addGsi(include).build();
        assertThatThrownBy(() -> new TableCreator(ddb.client()).create(includeDesign))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-014")
                .hasMessageContaining("INCLUDE")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-014");
        assertTableAbsent("CUSTOMER_INCLUDE_ACC");
    }

    @Test
    void all_projected_gsi_is_created_and_stamped_on_write() {
        EntityMapping mapping = new MithraObjectXmlParser().parse(
                nonDatedXml("CustomerAllGsi", "CUSTOMER_ALL_GSI_ACC",
                        "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                                + "<Attribute name=\"email\" javaType=\"String\" columnName=\"EMAIL\"/>"));
        GsiSpec all = GsiSpec.foreignKey("gsi_email", "email");
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(all).build();
        new TableCreator(ddb.client()).create(design);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbWriter writer = new DynamoDbWriter(
                ddb.client(), mapping, codec, new DefaultKeyStrategy(), design.gsis());
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Long.valueOf(3L));
        row.put("email", "a@example.com");
        writer.insert(row);

        ScanResponse scan = ddb.client().scan(b -> b.tableName(mapping.tableName()));
        assertThat(scan.items()).hasSize(1);
        Map<String, AttributeValue> item = scan.items().get(0);
        assertThat(item.get("gsi_email")).isNotNull();
        assertThat(item.get("EMAIL").s()).isEqualTo("a@example.com");
        assertThat(item.get("_rd_v")).isNotNull();
    }

    private Map<String, Object> getAndDecode(EntityMapping mapping, ItemCodec codec,
                                             String pk, String sk) {
        Map<String, AttributeValue> key = new LinkedHashMap<String, AttributeValue>();
        key.put("pk", AttributeValue.builder().s(pk).build());
        key.put("sk", AttributeValue.builder().s(sk).build());
        GetItemResponse got = ddb.client().getItem(b -> b
                .tableName(mapping.tableName())
                .key(key)
                .consistentRead(Boolean.TRUE));
        assertThat(got.hasItem()).as("expected item pk=%s sk=%s", pk, sk).isTrue();
        return codec.decode(got.item());
    }

    private void assertTableAbsent(String tableName) {
        assertThatThrownBy(() -> ddb.client().describeTable(b -> b.tableName(tableName)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static String validDateXml(String table) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraObject objectType=\"transactional\">"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>Policy</ClassName>"
                + "<DefaultTable>" + table + "</DefaultTable>"
                + asOf("validDate", "VALID_FROM", "VALID_TO", false)
                + "<Attribute name=\"policyId\" javaType=\"long\" columnName=\"POLICY_ID\" primaryKey=\"true\"/>"
                + "<Attribute name=\"premium\" javaType=\"int\" columnName=\"PREMIUM\"/>"
                + "</MithraObject>";
    }

    private static String nonDatedXml(String className, String table, String attributes) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraObject objectType=\"transactional\">"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>" + className + "</ClassName>"
                + "<DefaultTable>" + table + "</DefaultTable>"
                + attributes
                + "</MithraObject>";
    }

    private static String asOf(String name, String from, String to, boolean processing) {
        return "<AsOfAttribute name=\"" + name + "\" fromColumnName=\"" + from
                + "\" toColumnName=\"" + to + "\" toIsInclusive=\"false\" isProcessingDate=\""
                + processing + "\" futureExpiringRowsExist=\"false\""
                + " infinityDate=\"[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]\"/>";
    }

    private static Timestamp utc(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month - 1, day, 0, 0, 0);
        return new Timestamp(calendar.getTimeInMillis());
    }
}
