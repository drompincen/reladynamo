package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.pk;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ItemCodecNullsTest {

    @Test
    void should_store_dynamodb_null_for_nullable_int() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "int", true)));

        Map<String, AttributeValue> item = codec.encode(values("quantity", null));

        assertThat(item).containsKey("quantity");
        assertThat(item.get("quantity").nul()).isTrue();
        assertThat(item.get("quantity").n()).isNull();
        assertThat(item.get("quantity").bool()).isNull();
    }

    @Test
    void should_not_store_zero_for_null_integer() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "int", true)));

        Map<String, AttributeValue> item = codec.encode(values("quantity", null));

        assertThat(item.get("quantity").n()).isNull();
        assertThat(item.get("quantity").nul()).isTrue();
        if (item.get("quantity").n() != null) {
            throw new AssertionError("null quantity was stored as N=" + item.get("quantity").n());
        }
    }

    @Test
    void should_restore_null_when_nullable_attribute_is_absent() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "int", true)));
        Map<String, AttributeValue> item = codec.encode(values("quantity", 7));
        item.remove("quantity");

        Map<String, Object> decoded = codec.decode(item);

        assertThat(decoded.get("quantity")).isNull();
        assertThat(decoded).containsEntry("id", 1L);
    }

    @Test
    void should_round_trip_empty_string_as_distinct_from_null() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", true)));

        Map<String, AttributeValue> emptyItem = codec.encode(values("name", ""));
        Map<String, AttributeValue> nullItem = codec.encode(values("name", null));

        assertThat(emptyItem.get("name").s()).isEqualTo("");
        assertThat(emptyItem.get("name").nul()).isNotEqualTo(Boolean.TRUE);
        assertThat(nullItem.get("name").nul()).isTrue();
        assertThat(nullItem.get("name").s()).isNull();
        assertThat(codec.decode(emptyItem).get("name")).isEqualTo("");
        assertThat(codec.decode(nullItem).get("name")).isNull();
    }

    @Test
    void should_round_trip_false_as_distinct_from_null_boolean() {
        ItemCodec codec = new ItemCodec(entity(attr("flag", "boolean", true)));

        Map<String, AttributeValue> falseItem = codec.encode(values("flag", Boolean.FALSE));
        Map<String, AttributeValue> nullItem = codec.encode(values("flag", null));

        assertThat(falseItem.get("flag").bool()).isFalse();
        assertThat(nullItem.get("flag").nul()).isTrue();
        assertThat(nullItem.get("flag").bool()).isNull();
        assertThat(codec.decode(falseItem).get("flag")).isEqualTo(Boolean.FALSE);
        assertThat(codec.decode(nullItem).get("flag")).isNull();
    }

    @Test
    void should_round_trip_zero_as_distinct_from_null_int() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "int", true)));

        Map<String, Object> zero = codec.decode(codec.encode(values("quantity", 0)));
        Map<String, Object> absent = codec.decode(codec.encode(values("quantity", null)));

        assertThat(zero.get("quantity")).isEqualTo(0);
        assertThat(absent.get("quantity")).isNull();
    }

    @Test
    void should_reject_null_primary_key() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", true)));
        Map<String, Object> attributes = values("name", "Ada");
        attributes.put("id", null);

        assertThatThrownBy(() -> codec.encode(attributes))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("primary key");
    }

    @Test
    void should_reject_null_non_nullable_attribute() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", false)));

        assertThatThrownBy(() -> codec.encode(values("name", null)))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("non-nullable");
    }

    @Test
    void should_restore_null_when_item_stores_dynamodb_null_type() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "int", true)));
        Map<String, AttributeValue> item = codec.encode(values("quantity", 7));
        item.put("quantity", AttributeValue.builder().nul(true).build());

        Map<String, Object> decoded = codec.decode(item);

        assertThat(decoded.get("quantity")).isNull();
    }

    @Test
    void should_reject_missing_non_nullable_attribute_on_decode() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", false)));
        Map<String, AttributeValue> item = codec.encode(values("name", "Ada"));
        item.remove("name");

        assertThatThrownBy(() -> codec.decode(item))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("non-nullable");
    }

    @Test
    void should_reject_dynamodb_null_for_non_nullable_attribute() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", false)));
        Map<String, AttributeValue> item = codec.encode(values("name", "Ada"));
        item.put("name", AttributeValue.builder().nul(true).build());

        assertThatThrownBy(() -> codec.decode(item))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("non-nullable");
    }

    @Test
    void should_reject_empty_string_primary_key() {
        List<AttributeMapping> attributes = new ArrayList<>();
        attributes.add(pk("code", "String"));
        attributes.add(attr("name", "String", true));
        ItemCodec codec = new ItemCodec(entity("Ticker", attributes));
        Map<String, Object> attributesMap = new LinkedHashMap<>();
        attributesMap.put("code", "");
        attributesMap.put("name", "Ada");

        assertThatThrownBy(() -> codec.encode(attributesMap))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("empty");
    }
}
