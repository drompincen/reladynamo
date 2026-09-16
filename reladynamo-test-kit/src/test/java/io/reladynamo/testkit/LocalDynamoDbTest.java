package io.reladynamo.testkit;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the in-process DynamoDB Local harness actually works here — native libraries loaded, server
 * reachable, round trip intact. If this fails, nothing downstream in the differential suite is
 * trustworthy, so it is worth being an explicit test rather than an assumption.
 */
class LocalDynamoDbTest {

    @Test
    void starts_and_round_trips_an_item() {
        try (LocalDynamoDb ddb = LocalDynamoDb.start()) {
            ddb.client().createTable(b -> b
                    .tableName("spike")
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("pk")
                                    .attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk")
                                    .attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("pk", AttributeValue.builder().s("v1#CUSTOMER#1").build());
            item.put("sk", AttributeValue.builder().s("v1#P#20260601000000000#B#20260601000000000").build());
            item.put("quantity", AttributeValue.builder().n("42.5").build());
            ddb.client().putItem(b -> b.tableName("spike").item(item));

            Map<String, AttributeValue> key = new HashMap<>();
            key.put("pk", item.get("pk"));
            key.put("sk", item.get("sk"));
            GetItemResponse got = ddb.client().getItem(b -> b.tableName("spike").key(key).consistentRead(true));

            assertThat(got.hasItem()).isTrue();
            assertThat(got.item().get("quantity").n()).isEqualTo("42.5");
        }
    }
}
