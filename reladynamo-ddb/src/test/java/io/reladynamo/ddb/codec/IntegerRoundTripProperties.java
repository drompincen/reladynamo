package io.reladynamo.ddb.codec;

import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;

final class IntegerRoundTripProperties {

    private final ItemCodec ints = new ItemCodec(entity(attr("v", "int", false)));
    private final ItemCodec longs = new ItemCodec(entity(attr("v", "long", false)));
    private final ItemCodec shorts = new ItemCodec(entity(attr("v", "short", false)));
    private final ItemCodec bytes = new ItemCodec(entity(attr("v", "byte", false)));

    @Property(tries = 1000)
    void should_round_trip_every_int(@ForAll int value) {
        Map<String, Object> decoded = ints.decode(ints.encode(values("v", value)));
        assertThat(decoded.get("v")).isEqualTo(value);
        assertThat(ints.encode(values("v", value)).get("v").n()).isEqualTo(Integer.toString(value));
    }

    @Property(tries = 1000)
    void should_round_trip_every_long(@ForAll long value) {
        Map<String, Object> decoded = longs.decode(longs.encode(values("v", value)));
        assertThat(decoded.get("v")).isEqualTo(value);
        assertThat(longs.encode(values("v", value)).get("v").n()).isEqualTo(Long.toString(value));
    }

    @Property(tries = 500)
    void should_round_trip_every_short(@ForAll short value) {
        Map<String, Object> decoded = shorts.decode(shorts.encode(values("v", value)));
        assertThat(decoded.get("v")).isEqualTo(value);
    }

    @Property(tries = 256)
    void should_round_trip_every_byte(@ForAll byte value) {
        Map<String, Object> decoded = bytes.decode(bytes.encode(values("v", value)));
        assertThat(decoded.get("v")).isEqualTo(value);
    }
}
