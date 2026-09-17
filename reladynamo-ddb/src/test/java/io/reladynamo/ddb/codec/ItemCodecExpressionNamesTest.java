package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.pk;
import static org.assertj.core.api.Assertions.assertThat;

final class ItemCodecExpressionNamesTest {

    @Test
    void should_alias_every_attribute_including_reserved_words() {
        List<AttributeMapping> attributes = new ArrayList<>();
        attributes.add(pk("id", "long"));
        attributes.add(attr("name", "String", false));
        attributes.add(attr("status", "String", true));
        attributes.add(attr("size", "int", true));
        attributes.add(attr("type", "String", true));
        attributes.add(attr("timestamp", "java.sql.Timestamp", true));
        EntityMapping mapping = entity("Order", attributes);
        ItemCodec codec = new ItemCodec(mapping);

        Map<String, String> names = codec.expressionAttributeNames();

        assertThat(names.keySet()).allMatch(key -> key.startsWith("#"));
        assertThat(names.keySet()).doesNotContain("name", "status", "size", "type", "timestamp");
        assertThat(names.values()).contains(
                "id", "name", "status", "size", "type", "timestamp", ItemCodec.SCHEMA_VERSION_ATTR);
        assertThat(codec.expressionNames().placeholder("name")).startsWith("#");
        assertThat(codec.expressionNames().placeholder(ItemCodec.SCHEMA_VERSION_ATTR))
                .isEqualTo("#rd_v");
        assertThat(names.get(codec.expressionNames().placeholder("name"))).isEqualTo("name");
    }
}
