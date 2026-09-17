package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.mapping.ReladynamoConfigException;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableCreatorTest {

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
    void should_create_table_with_pk_and_sk_strings_and_pay_per_request() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerPayPerRequest");

        new TableCreator(ddb.client()).create(mapping);

        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        assertThat(described.table().tableStatus()).isEqualTo(TableStatus.ACTIVE);
        assertThat(described.table().keySchema())
                .extracting(KeySchemaElement::attributeName)
                .containsExactly("pk", "sk");
        assertThat(described.table().keySchema())
                .extracting(KeySchemaElement::keyType)
                .containsExactly(KeyType.HASH, KeyType.RANGE);
        assertThat(described.table().attributeDefinitions())
                .allSatisfy(def -> assertThat(def.attributeType()).isEqualTo(ScalarAttributeType.S));
        assertThat(described.table().billingModeSummary().billingMode())
                .isEqualTo(BillingMode.PAY_PER_REQUEST);
    }

    @Test
    void should_create_gsi_on_foreign_key_and_reach_active() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerFkGsi");
        GsiSpec gsi = GsiSpec.foreignKey("gsi_email", "email");
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();

        new TableCreator(ddb.client()).create(design);

        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        assertThat(described.table().tableStatus()).isEqualTo(TableStatus.ACTIVE);
        assertThat(described.table().globalSecondaryIndexes())
                .extracting(GlobalSecondaryIndexDescription::indexName)
                .containsExactly("gsi_email");
        assertThat(described.table().globalSecondaryIndexes())
                .extracting(GlobalSecondaryIndexDescription::indexStatus)
                .containsExactly(IndexStatus.ACTIVE);
        assertThat(described.table().globalSecondaryIndexes().get(0).keySchema())
                .extracting(KeySchemaElement::attributeName)
                .containsExactly("gsi_email", "sk");
        assertThat(described.table().attributeDefinitions())
                .extracting(def -> def.attributeName())
                .contains("pk", "sk", "gsi_email");
    }

    @Test
    void should_treat_creating_an_existing_table_as_a_noop() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerIdempotent");
        TableCreator creator = new TableCreator(ddb.client());

        creator.create(mapping);

        assertThatCode(() -> creator.create(mapping)).doesNotThrowAnyException();
        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        assertThat(described.table().tableStatus()).isEqualTo(TableStatus.ACTIVE);
    }

    @Test
    void should_create_provisioned_table_when_configured() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerProvisioned");
        TableCreator.Options options = TableCreator.Options.builder()
                .billingMode(BillingMode.PROVISIONED)
                .readCapacityUnits(5L)
                .writeCapacityUnits(5L)
                .build();

        new TableCreator(ddb.client(), options).create(mapping);

        DescribeTableResponse described = ddb.client().describeTable(b -> b.tableName(mapping.tableName()));
        assertThat(described.table().provisionedThroughput().readCapacityUnits()).isEqualTo(5L);
        assertThat(described.table().provisionedThroughput().writeCapacityUnits()).isEqualTo(5L);
    }

    @Test
    void should_fail_with_clear_message_when_wait_for_active_times_out() {
        AtomicInteger describes = new AtomicInteger();
        DynamoDbClient stuck = (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class[]{DynamoDbClient.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("describeTable".equals(method.getName())) {
                            describes.incrementAndGet();
                            throw ResourceNotFoundException.builder()
                                    .message("never appears")
                                    .build();
                        }
                        if ("createTable".equals(method.getName())) {
                            return software.amazon.awssdk.services.dynamodb.model.CreateTableResponse.builder()
                                    .build();
                        }
                        if ("toString".equals(method.getName())) {
                            return "stuck-client";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return Integer.valueOf(1);
                        }
                        if ("equals".equals(method.getName())) {
                            return Boolean.valueOf(proxy == args[0]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });

        TableCreator.Options options = TableCreator.Options.builder()
                .waitTimeout(Duration.ofMillis(20))
                .pollInterval(Duration.ofMillis(5))
                .build();
        EntityMapping mapping = ExecFixtures.customerMapping("NeverActive");

        assertThatThrownBy(() -> new TableCreator(stuck, options).create(mapping))
                .isInstanceOf(TableCreateTimeoutException.class)
                .hasMessageContaining("NeverActive")
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("20");
        assertThat(describes.get()).isGreaterThan(0);
    }

    @Test
    void should_refuse_keys_only_gsi_projection_at_startup() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerKeysOnlyGsi");
        GsiSpec gsi = GsiSpec.uniqueAttribute("gsi_email", "email");
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();

        assertThatThrownBy(() -> new TableCreator(ddb.client()).create(design))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-014")
                .hasMessageContaining("KEYS_ONLY")
                .hasMessageContaining("gsi_email")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-014");
    }

    @Test
    void should_refuse_include_gsi_projection_at_startup() {
        EntityMapping mapping = ExecFixtures.customerMapping("CustomerIncludeGsi");
        GsiSpec gsi = new GsiSpec("gsi_status", Arrays.asList("status"), null, false,
                GsiSpec.Projection.INCLUDE, Arrays.asList("name"));
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();

        assertThatThrownBy(() -> new TableCreator(ddb.client()).create(design))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-014")
                .hasMessageContaining("INCLUDE")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-014");
    }
}
