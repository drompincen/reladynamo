package io.reladynamo.ddb.codec;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ItemCodecItemSizeTest {

    @Test
    void should_reject_item_when_over_400kb_naming_entity_pk_and_size() {
        ItemCodec codec = new ItemCodec(entity(attr("blob", "String", false)));
        char[] buf = new char[ItemCodec.MAX_ITEM_SIZE_BYTES];
        Arrays.fill(buf, 'x');
        String huge = new String(buf);

        assertThatThrownBy(() -> codec.encode(values("blob", huge)))
                .isInstanceOf(ItemTooLargeException.class)
                .satisfies(thrown -> {
                    ItemTooLargeException ex = (ItemTooLargeException) thrown;
                    assertThat(ex.entity()).isEqualTo("Position");
                    assertThat(ex.primaryKey()).contains("id=1");
                    assertThat(ex.measuredSizeBytes()).isGreaterThan(ItemCodec.MAX_ITEM_SIZE_BYTES);
                    assertThat(ex.getMessage())
                            .contains("Position")
                            .contains("id=1")
                            .contains(String.valueOf(ex.measuredSizeBytes()));
                });
    }

    @Test
    void should_estimate_string_item_as_utf8_name_plus_value() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", false)));
        Map<String, AttributeValue> item = codec.encode(values("name", "Ada"));
        int size = codec.estimateSize(item);
        assertThat(size).isGreaterThan(0);
        assertThat(size).isLessThan(ItemCodec.MAX_ITEM_SIZE_BYTES);
    }
}
