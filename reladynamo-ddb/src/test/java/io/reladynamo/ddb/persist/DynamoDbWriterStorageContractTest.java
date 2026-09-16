package io.reladynamo.ddb.persist;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.ReladynamoConfigException;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.codec.ItemTooLargeException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-13: the stored item, not the codec payload, is the 400 KB contract; reserved
 * GSI names are refused when the writer is constructed.
 */
final class DynamoDbWriterStorageContractTest {

    @Test
    void should_refuse_near_limit_payload_once_long_keys_and_gsi_keys_are_appended() {
        EntityMapping mapping = blobMapping();
        ItemCodec codec = new ItemCodec(mapping);
        AtomicBoolean putCalled = new AtomicBoolean(false);
        DynamoDbWriter writer = new DynamoDbWriter(
                recordingClient(putCalled, new AtomicReference<PutItemRequest>()),
                mapping,
                codec,
                new DefaultKeyStrategy(),
                Collections.singletonList(GsiSpec.uniqueAttribute("byLookup", "lookup")));

        Map<String, Object> row = oversizedAfterKeysRow(codec);

        int payloadSize = codec.estimateSize(codec.encode(row));
        assertThat(payloadSize).isLessThanOrEqualTo(ItemCodec.MAX_ITEM_SIZE_BYTES);
        assertThatCode(() -> codec.encode(row)).doesNotThrowAnyException();

        assertThatThrownBy(() -> writer.insert(row))
                .isInstanceOf(ItemTooLargeException.class)
                .satisfies(thrown -> {
                    ItemTooLargeException ex = (ItemTooLargeException) thrown;
                    assertThat(ex.entity()).isEqualTo("com.acme.Blob");
                    assertThat(ex.measuredSizeBytes()).isGreaterThan(ItemCodec.MAX_ITEM_SIZE_BYTES);
                    assertThat(ex.measuredSizeBytes()).isGreaterThan(payloadSize);
                    assertThat(ex.getMessage())
                            .contains("com.acme.Blob")
                            .contains(String.valueOf(ex.measuredSizeBytes()))
                            .contains(String.valueOf(ItemCodec.MAX_ITEM_SIZE_BYTES));
                });
        assertThat(putCalled).isFalse();
    }

    @Test
    void should_refuse_writer_construction_when_mapping_collides_with_gsi_key() {
        EntityMapping mapping = new EntityMapping(
                "com.acme.domain.Address",
                "ADDRESS",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("addressId", "ADDRESS_ID", "long", true, false),
                        new AttributeMapping("customerId", "CUSTOMER_ID", "long", false, false),
                        new AttributeMapping("label", "gsi_customerId", "String", false, false)));

        assertThatThrownBy(() -> new DynamoDbWriter(
                recordingClient(new AtomicBoolean(), new AtomicReference<PutItemRequest>()),
                mapping,
                new ItemCodec(mapping),
                new DefaultKeyStrategy(),
                Collections.singletonList(GsiSpec.foreignKey("byCustomer", "customerId"))))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("RELADYNAMO-CFG-013")
                .hasMessageContaining("gsi_customerId")
                .extracting(ex -> ((ReladynamoConfigException) ex).code())
                .isEqualTo("RELADYNAMO-CFG-013");
    }

    private static Map<String, Object> oversizedAfterKeysRow(ItemCodec codec) {
        String longCode = repeat('k', 1800);
        String longLookup = repeat('g', 1800);
        int payloadChars = ItemCodec.MAX_ITEM_SIZE_BYTES - 6000;
        Map<String, Object> row = rowOf(longCode, repeat('x', payloadChars), longLookup);
        while (true) {
            try {
                Map<String, AttributeValue> encoded = codec.encode(row);
                int size = codec.estimateSize(encoded);
                if (size <= ItemCodec.MAX_ITEM_SIZE_BYTES - 200) {
                    return row;
                }
                payloadChars -= 512;
                if (payloadChars < 1024) {
                    throw new IllegalStateException("could not fit a codec-legal payload under 400 KB");
                }
                row = rowOf(longCode, repeat('x', payloadChars), longLookup);
            } catch (ItemTooLargeException ex) {
                payloadChars -= 512;
                if (payloadChars < 1024) {
                    throw ex;
                }
                row = rowOf(longCode, repeat('x', payloadChars), longLookup);
            }
        }
    }

    private static Map<String, Object> rowOf(String code, String payload, String lookup) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("payload", payload);
        row.put("lookup", lookup);
        return row;
    }

    private static EntityMapping blobMapping() {
        return new EntityMapping(
                "com.acme.Blob",
                "blob",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("code", "code", "String", true, false),
                        new AttributeMapping("payload", "payload", "String", false, false),
                        new AttributeMapping("lookup", "lookup", "String", false, true)));
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static DynamoDbClient recordingClient(AtomicBoolean putCalled,
                                                  AtomicReference<PutItemRequest> captured) {
        return (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                    if ("putItem".equals(method.getName())) {
                        putCalled.set(true);
                        if (args != null && args.length == 1 && args[0] instanceof PutItemRequest) {
                            captured.set((PutItemRequest) args[0]);
                        }
                        throw new AssertionError("putItem must not be called for an oversized final item");
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == PutItemResponse.class) {
                        return PutItemResponse.builder().build();
                    }
                    if (ret == Void.TYPE) {
                        return null;
                    }
                    if (ret == boolean.class) {
                        return Boolean.FALSE;
                    }
                    return null;
                });
    }
}
