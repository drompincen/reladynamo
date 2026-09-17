package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-07: physical-design application must reconcile requested vs actual, not just
 * treat {@code ACTIVE} as "ready".
 */
class TableCreatorReconciliationTest {

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
    void should_create_when_table_is_absent() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerCreateAbsent");
        TableCreator creator = new TableCreator(ddb.client());

        SchemaReconcileResult result = creator.apply(mapping);

        assertThat(result.outcome()).isEqualTo(SchemaReconcileOutcome.CREATE);
        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        assertThat(described.table().tableStatus()).isEqualTo(TableStatus.ACTIVE);
        assertThat(described.table().keySchema())
                .extracting(KeySchemaElement::attributeName)
                .containsExactly("pk", "sk");
    }

    @Test
    void should_refuse_when_existing_table_has_incompatible_key_schema() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerWrongKeys");
        createHashOnlyTable(mapping.tableName(), "id");
        TableCreator creator = new TableCreator(ddb.client());

        SchemaReconcileResult result = creator.apply(mapping);

        assertThat(result.outcome()).isEqualTo(SchemaReconcileOutcome.INCOMPATIBLE);
        assertThat(result.detail()).contains("pk").contains("sk");
        assertThatThrownBy(() -> creator.create(mapping))
                .isInstanceOf(SchemaReconcileException.class)
                .satisfies(thrown -> {
                    SchemaReconcileException ex = (SchemaReconcileException) thrown;
                    assertThat(ex.code()).isEqualTo(SchemaReconcileException.CODE_INCOMPATIBLE);
                    assertThat(ex.outcome()).isEqualTo(SchemaReconcileOutcome.INCOMPATIBLE);
                });
        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        assertThat(described.table().keySchema())
                .extracting(KeySchemaElement::attributeName)
                .containsExactly("id");
    }

    @Test
    void should_add_missing_gsi_when_key_schema_matches_and_table_is_empty() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerAddGsi");
        new TableCreator(ddb.client()).create(mapping);
        GsiSpec gsi = GsiSpec.foreignKey("gsi_email", "email");
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();
        TableCreator creator = new TableCreator(ddb.client());

        SchemaReconcileResult result = creator.apply(design);

        assertThat(result.outcome()).isEqualTo(SchemaReconcileOutcome.ADD_INDEX);
        assertThat(result.missingIndexNames()).containsExactly("gsi_email");
        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        assertThat(described.table().globalSecondaryIndexes())
                .extracting(GlobalSecondaryIndexDescription::indexName)
                .containsExactly("gsi_email");
    }

    @Test
    void should_accept_matching_table_without_mutation() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerValidateMatch");
        GsiSpec gsi = GsiSpec.foreignKey("gsi_email", "email");
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();
        TableCreator creator = new TableCreator(ddb.client());
        creator.create(design);
        DescribeTableResponse before = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        int indexCount = before.table().globalSecondaryIndexes() == null
                ? 0
                : before.table().globalSecondaryIndexes().size();

        SchemaReconcileResult result = creator.apply(design);

        assertThat(result.outcome()).isEqualTo(SchemaReconcileOutcome.VALIDATE);
        DescribeTableResponse after = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        int afterCount = after.table().globalSecondaryIndexes() == null
                ? 0
                : after.table().globalSecondaryIndexes().size();
        assertThat(afterCount).isEqualTo(indexCount);
        assertThat(after.table().keySchema()).isEqualTo(before.table().keySchema());
        assertThat(after.table().tableStatus()).isEqualTo(TableStatus.ACTIVE);
    }

    @Test
    void should_refuse_backfill_when_existing_rows_lack_gsi_key_attributes() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerBackfillGsi");
        new TableCreator(ddb.client()).create(mapping);
        Map<String, AttributeValue> item = new LinkedHashMap<String, AttributeValue>();
        item.put("pk", AttributeValue.builder().s("Customer#1").build());
        item.put("sk", AttributeValue.builder().s("v1#ND").build());
        item.put("id", AttributeValue.builder().n("1").build());
        item.put("name", AttributeValue.builder().s("Ada").build());
        ddb.client().putItem(PutItemRequest.builder()
                .tableName(mapping.tableName())
                .item(item)
                .build());
        GsiSpec gsi = GsiSpec.foreignKey("gsi_email", "email");
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();
        TableCreator creator = new TableCreator(ddb.client());

        SchemaReconcileResult result = creator.apply(design);

        assertThat(result.outcome()).isEqualTo(SchemaReconcileOutcome.BACKFILL_INDEX_KEY);
        assertThat(result.missingIndexNames()).contains("gsi_email");
        assertThatThrownBy(() -> creator.create(design))
                .isInstanceOf(SchemaReconcileException.class)
                .satisfies(thrown -> {
                    SchemaReconcileException ex = (SchemaReconcileException) thrown;
                    assertThat(ex.code()).isEqualTo(SchemaReconcileException.CODE_BACKFILL_INDEX_KEY);
                    assertThat(ex.outcome()).isEqualTo(SchemaReconcileOutcome.BACKFILL_INDEX_KEY);
                });
        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        List<GlobalSecondaryIndexDescription> indexes = described.table().globalSecondaryIndexes();
        assertThat(indexes == null ? 0 : indexes.size()).isEqualTo(0);
    }

    private static void createHashOnlyTable(String tableName, String hashAttribute) {
        ddb.client().createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName(hashAttribute)
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName(hashAttribute)
                        .keyType(KeyType.HASH)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        waitUntilActive(tableName);
    }

    private static void waitUntilActive(String tableName) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            try {
                TableStatus status = ddb.client()
                        .describeTable(b -> b.tableName(tableName))
                        .table()
                        .tableStatus();
                if (status == TableStatus.ACTIVE) {
                    return;
                }
            } catch (ResourceNotFoundException missing) {
                // keep polling
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for " + tableName);
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + tableName, interrupted);
            }
        }
    }
}
