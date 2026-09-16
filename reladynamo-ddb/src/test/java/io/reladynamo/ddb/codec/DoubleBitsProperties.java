package io.reladynamo.ddb.codec;

import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;

final class DoubleBitsProperties {

    private final ItemCodec doubles = new ItemCodec(entity(attr("quantity", "double", false)));
    private final ItemCodec floats = new ItemCodec(entity(attr("quantity", "float", false)));

    @Property(tries = 2000)
    void should_round_trip_every_double_bit_pattern(@ForAll long bits) {
        double original = Double.longBitsToDouble(bits);
        Map<String, AttributeValue> item = doubles.encode(values("quantity", original));
        Double decoded = (Double) doubles.decode(item).get("quantity");
        assertThat(Double.doubleToRawLongBits(decoded)).isEqualTo(bits);
        assertThat(item.get("quantity").b()).isNotNull();
        assertThat(item.get("quantity").n()).isNull();
    }

    @Property(tries = 2000)
    void should_round_trip_every_float_bit_pattern(@ForAll int bits) {
        float original = Float.intBitsToFloat(bits);
        Map<String, AttributeValue> item = floats.encode(values("quantity", original));
        Float decoded = (Float) floats.decode(item).get("quantity");
        assertThat(Float.floatToRawIntBits(decoded)).isEqualTo(bits);
        assertThat(item.get("quantity").b().asByteArray()).hasSize(4);
    }
}
