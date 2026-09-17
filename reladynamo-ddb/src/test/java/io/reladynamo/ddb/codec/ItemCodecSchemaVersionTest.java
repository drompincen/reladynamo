package io.reladynamo.ddb.codec;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ItemCodecSchemaVersionTest {

    @Test
    void should_write_schema_version_on_every_item() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", true)));

        Map<String, AttributeValue> item = codec.encode(values("name", "Ada"));

        assertThat(item).containsKey(ItemCodec.SCHEMA_VERSION_ATTR);
        assertThat(item.get(ItemCodec.SCHEMA_VERSION_ATTR).n()).isEqualTo("1");
    }

    @Test
    void should_reject_item_missing_schema_version() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", true)));
        Map<String, AttributeValue> item = Map.of(
                "id", AttributeValue.builder().n("1").build(),
                "name", AttributeValue.builder().s("Ada").build());

        assertThatThrownBy(() -> codec.decode(item))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("_rd_v");
    }

    @Test
    void should_reject_writer_version_above_supported_maximum_at_construction() {
        int unsupported = ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION + 1;

        assertThatThrownBy(() -> new ItemCodec(entity(attr("name", "String", true)), unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[" + ItemCodec.MIN_SUPPORTED_SCHEMA_VERSION
                        + "," + ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION + "]")
                .hasMessageContaining(String.valueOf(unsupported));
    }

    @Test
    void should_name_supported_range_when_writer_version_is_below_minimum() {
        assertThatThrownBy(() -> new ItemCodec(entity(attr("name", "String", true)), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[" + ItemCodec.MIN_SUPPORTED_SCHEMA_VERSION
                        + "," + ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION + "]")
                .hasMessageContaining("0");
    }

    @Test
    void should_round_trip_every_supported_schema_version() {
        for (int version = ItemCodec.MIN_SUPPORTED_SCHEMA_VERSION;
                version <= ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION; version++) {
            ItemCodec codec = new ItemCodec(entity(attr("name", "String", true)), version);

            Map<String, AttributeValue> item = codec.encode(values("name", "Ada"));
            Map<String, Object> decoded = codec.decode(item);

            assertThat(item.get(ItemCodec.SCHEMA_VERSION_ATTR).n())
                    .isEqualTo(Integer.toString(version));
            assertThat(decoded.get("name")).isEqualTo("Ada");
            assertThat(codec.schemaVersion()).isEqualTo(version);
        }
    }

    @Test
    void should_reject_item_stamped_with_unsupported_schema_version_at_decode() {
        ItemCodec codec = new ItemCodec(entity(attr("name", "String", true)));
        Map<String, AttributeValue> item = new LinkedHashMap<String, AttributeValue>(
                codec.encode(values("name", "Ada")));
        item.put(ItemCodec.SCHEMA_VERSION_ATTR,
                AttributeValue.builder()
                        .n(Integer.toString(ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION + 1))
                        .build());

        assertThatThrownBy(() -> codec.decode(item))
                .isInstanceOf(UnsupportedSchemaVersionException.class)
                .hasMessageContaining("[" + ItemCodec.MIN_SUPPORTED_SCHEMA_VERSION
                        + "," + ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION + "]")
                .satisfies(ex -> {
                    UnsupportedSchemaVersionException versionEx =
                            (UnsupportedSchemaVersionException) ex;
                    assertThat(versionEx.foundVersion())
                            .isEqualTo(ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION + 1);
                    assertThat(versionEx.minSupported())
                            .isEqualTo(ItemCodec.MIN_SUPPORTED_SCHEMA_VERSION);
                    assertThat(versionEx.maxSupported())
                            .isEqualTo(ItemCodec.MAX_SUPPORTED_SCHEMA_VERSION);
                });
    }
}
