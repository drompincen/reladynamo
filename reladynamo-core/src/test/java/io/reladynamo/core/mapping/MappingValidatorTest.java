package io.reladynamo.core.mapping;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MappingValidatorTest {

    @Test
    void should_accept_a_well_formed_entity_mapping() {
        EntityMapping mapping = new EntityMapping(
                "com.acme.domain.Customer",
                "CUSTOMER",
                TemporalMapping.none(),
                Collections.singletonList(new AttributeMapping("id", "ID", "long", true, false)));

        assertThatCode(() -> MappingValidator.validate(mapping)).doesNotThrowAnyException();
    }

    @Test
    void should_emit_cfg_001_for_missing_primary_key() {
        assertThat(MappingValidator.missingPrimaryKey("com.acme.domain.Customer").getMessage())
                .isEqualTo("RELADYNAMO-CFG-001: Object com.acme.domain.Customer has no primaryKey=\"true\" attribute; DynamoDB partition key cannot be derived.");
        assertThat(MappingValidator.missingPrimaryKey("com.acme.domain.Customer").code())
                .isEqualTo("RELADYNAMO-CFG-001");
    }

    @Test
    void should_emit_cfg_002_for_pure_object() {
        assertThat(MappingValidator.mithraPureObject("com.acme.domain.ScratchCache").getMessage())
                .isEqualTo("RELADYNAMO-CFG-002: MithraPureObject com.acme.domain.ScratchCache cannot be bound to DynamoDB; use MithraPureObjectFactory or convert to MithraObject.");
    }

    @Test
    void should_emit_cfg_003_for_infinity_is_null() {
        assertThat(MappingValidator.infinityIsNull("com.acme.domain.Customer", "businessDate").getMessage())
                .isEqualTo("RELADYNAMO-CFG-003: Object com.acme.domain.Customer AsOfAttribute businessDate sets infinityIsNull=true; Reladynamo requires a concrete infinity Timestamp for lexicographic sort keys.");
    }

    @Test
    void should_emit_cfg_004_for_identity_column() {
        assertThat(MappingValidator.identityColumn("com.acme.domain.Customer", "id").getMessage())
                .isEqualTo("RELADYNAMO-CFG-004: Attribute com.acme.domain.Customer.id has identity=\"true\"; DynamoDB has no identity columns. Use primaryKeyGeneratorStrategy=\"SimulatedSequence\" or assign keys in application code.");
    }

    @Test
    void should_fail_startup_validation_when_gsi_attribute_missing() {
        assertThatThrownBy(() -> MappingValidator.rejectUnknownGsiAttribute(
                "byCustomer",
                "com.acme.domain.Address",
                "custId",
                Arrays.asList("customerId", "addressId")))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage("RELADYNAMO-CFG-005: reladynamo.xml Gsi 'byCustomer' on com.acme.domain.Address references attribute 'custId' which does not exist on the MithraObject model (known: customerId, addressId).")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-005");
    }

    @Test
    void should_emit_cfg_006_when_provisioned_capacity_is_missing() {
        assertThat(MappingValidator.provisionedCapacityMissing("prod_customer").getMessage())
                .isEqualTo("RELADYNAMO-CFG-006: Table prod_customer billingMode=PROVISIONED but readCapacityUnits/writeCapacityUnits are missing or ≤ 0.");
    }

    @Test
    void should_emit_cfg_007_when_ttl_is_not_a_number() {
        assertThat(MappingValidator.ttlNotNumber("purgeAfterEpochSec", "com.acme.domain.Customer", "String").getMessage())
                .isEqualTo("RELADYNAMO-CFG-007: Ttl attribute 'purgeAfterEpochSec' on com.acme.domain.Customer must map to a Number (epoch seconds); javaType=String is invalid.");
    }

    @Test
    void should_emit_cfg_008_when_key_attribute_allows_hash() {
        assertThat(MappingValidator.hashInKeyAttribute("com.acme.domain.Customer", "code").getMessage())
                .isEqualTo("RELADYNAMO-CFG-008: Attribute com.acme.domain.Customer.code is part of the DynamoDB partition key and allows values containing '#'; forbid '#' in key attributes or supply a Keys escapeStrategy.");
    }

    @Test
    void should_emit_cfg_009_when_write_amplification_is_unacknowledged() {
        assertThat(MappingValidator.writeAmplification("com.acme.domain.Order", 3).getMessage())
                .isEqualTo("RELADYNAMO-CFG-009: Object com.acme.domain.Order derives 3 GSIs; set DynamoDefaults acknowledgeWriteAmplification=\"true\" after reviewing write cost, or remove unused Gsi entries.");
    }

    @Test
    void should_emit_cfg_010_when_sort_key_order_mismatches_table_tag() {
        assertThat(MappingValidator.sortKeyOrderMismatch(
                "com.acme.domain.Customer",
                "BUSINESS_THEN_PROCESSING",
                "prod_customer",
                "PROCESSING_THEN_BUSINESS").getMessage())
                .isEqualTo("RELADYNAMO-CFG-010: PhysicalDesign for com.acme.domain.Customer requests sortKeyTemporalOrder=BUSINESS_THEN_PROCESSING but table prod_customer is tagged rd:skOrder=PROCESSING_THEN_BUSINESS; key grammar changes require a new table migration (see evolution matrix).");
    }

    @Test
    void should_refuse_mapping_when_item_name_collides_with_pk() {
        assertReservedCollision("pk");
    }

    @Test
    void should_refuse_mapping_when_item_name_collides_with_sk() {
        assertReservedCollision("sk");
    }

    @Test
    void should_refuse_mapping_when_item_name_collides_with_schema_version() {
        assertReservedCollision("_rd_v");
    }

    @Test
    void should_refuse_mapping_when_two_attributes_share_an_item_name() {
        assertThatThrownBy(() -> new EntityMapping(
                "com.acme.domain.Customer",
                "CUSTOMER",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("id", "ID", "long", true, false),
                        new AttributeMapping("code", "COL", "String", false, false),
                        new AttributeMapping("alias", "COL", "String", false, false))))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage("RELADYNAMO-CFG-013: Object com.acme.domain.Customer maps attributes 'code' and 'alias' to the same item name 'COL'; item names must be unique and disjoint from pk, sk, _rd_v, and every GSI key attribute.")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-013");
    }

    @Test
    void should_refuse_mapping_when_item_name_collides_with_a_gsi_key() {
        EntityMapping mapping = new EntityMapping(
                "com.acme.domain.Address",
                "ADDRESS",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("addressId", "ADDRESS_ID", "long", true, false),
                        new AttributeMapping("customerId", "CUSTOMER_ID", "long", false, false),
                        new AttributeMapping("label", "gsi_customerId", "String", false, false)));

        assertThatThrownBy(() -> MappingValidator.validate(
                mapping, Collections.singleton("gsi_customerId")))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage("RELADYNAMO-CFG-013: Object com.acme.domain.Address maps attribute 'label' to reserved storage name 'gsi_customerId'; item names must be unique and disjoint from pk, sk, _rd_v, and every GSI key attribute.")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-013");
    }

    private static void assertReservedCollision(String reserved) {
        assertThatThrownBy(() -> new EntityMapping(
                "com.acme.domain.Customer",
                "CUSTOMER",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("id", "ID", "long", true, false),
                        new AttributeMapping("status", reserved, "String", false, false))))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage("RELADYNAMO-CFG-013: Object com.acme.domain.Customer maps attribute 'status' to reserved storage name '"
                        + reserved
                        + "'; item names must be unique and disjoint from pk, sk, _rd_v, and every GSI key attribute.")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-013");
    }

    @Test
    void should_emit_cfg_015_for_unresolvable_temporal_axis_names() {
        ReladynamoConfigException ex = MappingValidator.unsupportedTemporalAxis(
                "com.acme.domain.Ledger", "validDate, sysTime");
        assertThat(ex.code()).isEqualTo("RELADYNAMO-CFG-015");
        assertThat(ex.getMessage()).contains("validDate");
        assertThat(ex.getMessage()).contains("RELADYNAMO-CFG-015");
    }

    @Test
    void should_emit_cfg_016_for_unsupported_primary_key_type() {
        ReladynamoConfigException ex = MappingValidator.unsupportedPrimaryKeyType(
                "com.acme.domain.RateKey", "rate", "float");
        assertThat(ex.code()).isEqualTo("RELADYNAMO-CFG-016");
        assertThat(ex.getMessage()).isEqualTo(
                "RELADYNAMO-CFG-016: Attribute com.acme.domain.RateKey.rate has javaType=float which is not a supported primary-key type; Reladynamo partition-key components must be String, boolean, byte/short/int/long, char, Timestamp, Date, Time, BigDecimal, or byte[].");
    }

    @Test
    void should_emit_cfg_014_for_keys_only_or_include_gsi_projection() {
        ReladynamoConfigException keysOnly = MappingValidator.unsupportedGsiProjection(
                "com.acme.domain.Customer", "gsi_email", "KEYS_ONLY");
        assertThat(keysOnly.code()).isEqualTo("RELADYNAMO-CFG-014");
        assertThat(keysOnly.getMessage()).isEqualTo(
                "RELADYNAMO-CFG-014: Object com.acme.domain.Customer GSI 'gsi_email' uses projection KEYS_ONLY; Reladynamo does not hydrate missing attributes from the base table, so KEYS_ONLY and INCLUDE would reconstruct omitted fields as null. Use Projection.ALL, or omit the GSI.");
        ReladynamoConfigException include = MappingValidator.unsupportedGsiProjection(
                "com.acme.domain.Customer", "gsi_status", "INCLUDE");
        assertThat(include.getMessage()).contains("INCLUDE");
        assertThat(include.code()).isEqualTo("RELADYNAMO-CFG-014");
    }
}
