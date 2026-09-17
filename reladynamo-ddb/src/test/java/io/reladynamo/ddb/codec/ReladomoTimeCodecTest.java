package io.reladynamo.ddb.codec;

import com.gs.fw.common.mithra.util.Time;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;

final class ReladomoTimeCodecTest {

    @Test
    void should_round_trip_reladomo_time_as_utc_time_of_day_string() {
        ItemCodec codec = new ItemCodec(entity(attr("opensAt", "com.gs.fw.common.mithra.util.Time", false)));
        Time original = Time.withMillis(14, 30, 0, 123);

        Map<String, AttributeValue> item = codec.encode(values("opensAt", original));
        Time decoded = (Time) codec.decode(item).get("opensAt");

        assertThat(item.get("opensAt").s()).isEqualTo("14:30:00.123");
        assertThat(decoded.getHour()).isEqualTo((byte) 14);
        assertThat(decoded.getMinute()).isEqualTo((byte) 30);
        assertThat(decoded.getSecond()).isEqualTo((byte) 0);
        assertThat(decoded.getMillisecond()).isEqualTo(123);
    }

    @Test
    void should_treat_xml_Time_as_reladomo_time() {
        ItemCodec codec = new ItemCodec(entity(attr("opensAt", "Time", false)));
        Time original = Time.withMillis(0, 0, 0, 0);

        Map<String, AttributeValue> item = codec.encode(values("opensAt", original));

        assertThat(item.get("opensAt").s()).isEqualTo("00:00:00.000");
        Time decoded = (Time) codec.decode(item).get("opensAt");
        assertThat(decoded.getTime()).isEqualTo(original.getTime());
    }
}
