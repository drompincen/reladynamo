package io.reladynamo.core.mapping;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MithraObjectXmlParserTest {

    private final MithraObjectXmlParser parser = new MithraObjectXmlParser();

    @Test
    void should_use_default_table_name_when_no_override() {
        EntityMapping mapping = parser.parse(nonTemporalXml());

        assertThat(mapping.tableName()).isEqualTo("CUSTOMER");
        assertThat(mapping.className()).isEqualTo("com.acme.domain.Customer");
    }

    @Test
    void should_map_attributes_including_pk_nullability_and_column_name() {
        EntityMapping mapping = parser.parse(nonTemporalXml());

        AttributeMapping id = mapping.attribute("customerId");
        assertThat(id.javaName()).isEqualTo("customerId");
        assertThat(id.itemName()).isEqualTo("CUSTOMER_ID");
        assertThat(id.javaType()).isEqualTo("long");
        assertThat(id.isPrimaryKey()).isTrue();
        assertThat(id.isNullable()).isFalse();

        AttributeMapping email = mapping.attribute("email");
        assertThat(email.itemName()).isEqualTo("EMAIL");
        assertThat(email.javaType()).isEqualTo("String");
        assertThat(email.isPrimaryKey()).isFalse();
        assertThat(email.isNullable()).isFalse();

        AttributeMapping nickname = mapping.attribute("nickname");
        assertThat(nickname.isNullable()).isTrue();
    }

    @Test
    void should_keep_primary_key_declaration_order() {
        String xml = object(
                "Position",
                "POSITION",
                "",
                "<Attribute name=\"accountId\" javaType=\"long\" columnName=\"ACCOUNT_ID\" primaryKey=\"true\"/>"
                        + "<Attribute name=\"productId\" javaType=\"int\" columnName=\"PRODUCT_ID\" primaryKey=\"true\"/>"
                        + "<Attribute name=\"quantity\" javaType=\"double\" columnName=\"QUANTITY\"/>");

        EntityMapping mapping = parser.parse(xml);

        assertThat(mapping.primaryKeyAttributes())
                .extracting(AttributeMapping::javaName)
                .containsExactly("accountId", "productId");
    }

    @Test
    void should_derive_none_when_no_as_of_attribute() {
        EntityMapping mapping = parser.parse(nonTemporalXml());

        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.NONE);
        assertThat(mapping.temporal().infinity()).isNull();
    }

    @Test
    void should_derive_bitemporal_when_two_as_of_attributes() {
        EntityMapping mapping = parser.parse(bitemporalXml());

        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.BITEMPORAL);
        assertThat(mapping.temporal().hasBusinessDate()).isTrue();
        assertThat(mapping.temporal().hasProcessingDate()).isTrue();
        assertThat(mapping.temporal().infinity()).isEqualTo(conventionalInfinity());
    }

    @Test
    void should_derive_business_only_when_single_non_processing_as_of() {
        String xml = object(
                "Pet",
                "PET",
                asOf("businessDate", "FROM_Z", "THRU_Z", false, false),
                "<Attribute name=\"petId\" javaType=\"long\" columnName=\"PET_ID\" primaryKey=\"true\"/>");

        EntityMapping mapping = parser.parse(xml);

        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.BUSINESS_ONLY);
        assertThat(mapping.temporal().hasBusinessDate()).isTrue();
        assertThat(mapping.temporal().hasProcessingDate()).isFalse();
    }

    @Test
    void should_derive_audit_only_when_single_processing_as_of() {
        String xml = object(
                "ClassificationResult",
                "CLASSIFICATION_RESULT",
                asOf("processingDate", "IN_Z", "OUT_Z", true, false),
                "<Attribute name=\"resultId\" javaType=\"int\" columnName=\"RESULT_ID\" primaryKey=\"true\"/>");

        EntityMapping mapping = parser.parse(xml);

        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.AUDIT_ONLY);
        assertThat(mapping.temporal().hasProcessingDate()).isTrue();
        assertThat(mapping.temporal().hasBusinessDate()).isFalse();
    }

    @Test
    void should_parse_future_expiring_rows_flag_without_changing_flavour() {
        String xml = object(
                "Rule",
                "RULE",
                asOf("businessDate", "FROM_Z", "THRU_Z", false, true)
                        + asOf("processingDate", "IN_Z", "OUT_Z", true, false),
                "<Attribute name=\"ruleId\" javaType=\"int\" columnName=\"RULE_ID\" primaryKey=\"true\"/>");

        EntityMapping mapping = parser.parse(xml);

        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.BITEMPORAL);
    }

    @Test
    void should_parse_from_file_bytes_only() throws Exception {
        Path file = Files.createTempFile("Customer", "MithraObject.xml");
        Files.write(file, nonTemporalXml().getBytes(StandardCharsets.UTF_8));

        EntityMapping mapping = parser.parse(file);

        assertThat(mapping.className()).isEqualTo("com.acme.domain.Customer");
        assertThat(mapping.tableName()).isEqualTo("CUSTOMER");
    }

    @Test
    void should_reject_external_entities_when_xml_declares_a_doctype() {
        String xxe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE MithraObject ["
                + "<!ENTITY xxe SYSTEM \"file:///etc/passwd\">"
                + "]>"
                + "<MithraObject objectType=\"transactional\">"
                + "<PackageName>&xxe;</PackageName>"
                + "<ClassName>Customer</ClassName>"
                + "<DefaultTable>CUSTOMER</DefaultTable>"
                + "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                + "</MithraObject>";

        assertThatThrownBy(() -> parser.parse(xxe))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void should_reject_model_when_infinity_is_null() {
        String xml = object(
                "Customer",
                "CUSTOMER",
                "<AsOfAttribute name=\"businessDate\" fromColumnName=\"FROM_Z\" toColumnName=\"THRU_Z\""
                        + " isProcessingDate=\"false\" infinityIsNull=\"true\"/>",
                "<Attribute name=\"customerId\" javaType=\"long\" columnName=\"CUSTOMER_ID\" primaryKey=\"true\"/>");

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage(MappingValidator.infinityIsNull(
                        "com.acme.domain.Customer", "businessDate").getMessage());
    }

    @Test
    void should_reject_when_no_primary_key() {
        String xml = object(
                "Customer",
                "CUSTOMER",
                "",
                "<Attribute name=\"email\" javaType=\"String\" columnName=\"EMAIL\"/>");

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage(MappingValidator.missingPrimaryKey("com.acme.domain.Customer").getMessage());
    }

    @Test
    void should_reject_mithra_pure_object() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraPureObject objectType=\"transactional\">"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>ScratchCache</ClassName>"
                + "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                + "</MithraPureObject>";

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage(MappingValidator.mithraPureObject("com.acme.domain.ScratchCache").getMessage());
    }

    @Test
    void should_reject_identity_column() {
        String xml = object(
                "Customer",
                "CUSTOMER",
                "",
                "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\" identity=\"true\"/>");

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage(MappingValidator.identityColumn("com.acme.domain.Customer", "id").getMessage());
    }

    @Test
    void should_require_from_and_to_column_names_on_as_of() {
        String xml = object(
                "Customer",
                "CUSTOMER",
                "<AsOfAttribute name=\"businessDate\" isProcessingDate=\"false\""
                        + " infinityDate=\"[com.acme.Infinity.get()]\"/>",
                "<Attribute name=\"customerId\" javaType=\"long\" columnName=\"CUSTOMER_ID\" primaryKey=\"true\"/>");

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromColumnName");
    }

    @Test
    void should_reject_mithra_temp_object() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraTempObject>"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>Scratch</ClassName>"
                + "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                + "</MithraTempObject>";

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("MithraTempObject")
                .hasMessageContaining("com.acme.domain.Scratch");
    }

    private static String nonTemporalXml() {
        return object(
                "Customer",
                "CUSTOMER",
                "",
                "<Attribute name=\"customerId\" javaType=\"long\" columnName=\"CUSTOMER_ID\" primaryKey=\"true\"/>"
                        + "<Attribute name=\"email\" javaType=\"String\" columnName=\"EMAIL\" maxLength=\"256\" nullable=\"false\"/>"
                        + "<Attribute name=\"nickname\" javaType=\"String\" columnName=\"NICKNAME\" maxLength=\"64\" nullable=\"true\"/>");
    }

    private static String bitemporalXml() {
        return object(
                "Customer",
                "CUSTOMER",
                asOf("businessDate", "FROM_Z", "THRU_Z", false, false)
                        + asOf("processingDate", "IN_Z", "OUT_Z", true, false),
                "<Attribute name=\"customerId\" javaType=\"long\" columnName=\"CUSTOMER_ID\" primaryKey=\"true\"/>");
    }

    private static String asOf(String name, String from, String to, boolean processing, boolean futureExpiring) {
        return "<AsOfAttribute name=\"" + name + "\" fromColumnName=\"" + from + "\" toColumnName=\"" + to + "\""
                + " toIsInclusive=\"false\" isProcessingDate=\"" + processing + "\""
                + " futureExpiringRowsExist=\"" + futureExpiring + "\""
                + " infinityDate=\"[com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity()]\"/>";
    }

    private static String object(String className, String table, String asOfXml, String attributes) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraObject objectType=\"transactional\">"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>" + className + "</ClassName>"
                + "<DefaultTable>" + table + "</DefaultTable>"
                + asOfXml
                + attributes
                + "</MithraObject>";
    }

    static Timestamp conventionalInfinity() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(9999, Calendar.DECEMBER, 1, 23, 59, 0);
        Timestamp timestamp = new Timestamp(calendar.getTimeInMillis());
        timestamp.setNanos(0);
        return timestamp;
    }
    @Test
    void refuses_an_object_model_that_declares_a_source_attribute() {
        // Reladomo's sourceAttribute routes objects to different databases — it is multi-tenancy.
        // The adapter has no concept of it: the parser did not read it, the key strategy does not
        // include it, and the planner cannot constrain it. Parsing such a model silently would put
        // every tenant's rows in one table with no discriminator in the key, and a query for one
        // tenant would return all of them. Refuse at configuration time instead.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MithraObject objectType=\"transactional\">"
                + "<PackageName>com.acme.domain</PackageName>"
                + "<ClassName>Balance</ClassName>"
                + "<DefaultTable>BALANCE</DefaultTable>"
                + "<SourceAttribute name=\"tenantId\" javaType=\"String\"/>"
                + "<Attribute name=\"id\" javaType=\"long\" columnName=\"ID\" primaryKey=\"true\"/>"
                + "</MithraObject>";

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-012")
                .hasMessageContaining("sourceAttribute")
                .hasMessageContaining("tenantId")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-012");
    }

    @Test
    void refuses_a_float_primary_key_at_parse() {
        String xml = object(
                "RateKey",
                "RATE_KEY",
                "",
                "<Attribute name=\"rate\" javaType=\"float\" columnName=\"RATE\" primaryKey=\"true\"/>");
        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-016")
                .hasMessageContaining("float")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-016");
    }

    @Test
    void parses_a_unitemporal_validDate_axis_into_from_to_attributes() {
        String xml = object(
                "Policy",
                "POLICY",
                asOf("validDate", "VALID_FROM", "VALID_TO", false, false),
                "<Attribute name=\"policyId\" javaType=\"long\" columnName=\"POLICY_ID\" primaryKey=\"true\"/>");
        EntityMapping mapping = parser.parse(xml);
        assertThat(mapping.temporal().flavour()).isEqualTo(TemporalMapping.Flavour.BUSINESS_ONLY);
        assertThat(mapping.attribute("validDateFrom").itemName()).isEqualTo("VALID_FROM");
        assertThat(mapping.attribute("validDateTo").itemName()).isEqualTo("VALID_TO");
    }

}
