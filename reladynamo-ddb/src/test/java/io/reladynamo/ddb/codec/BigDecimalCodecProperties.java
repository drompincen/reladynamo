package io.reladynamo.ddb.codec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;

final class BigDecimalCodecProperties {

    private final ItemCodec codec = new ItemCodec(entity(attr("amount", "BigDecimal", false)));

    @Provide
    Arbitrary<BigDecimal> dynamoSafeDecimals() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-1E+20"), new BigDecimal("1E+20"))
                .ofScale(4)
                .map(d -> d.setScale(4, RoundingMode.DOWN))
                .filter(d -> d.precision() <= 38 && d.scale() >= 0);
    }

    @Property(tries = 1000)
    void should_round_trip_big_decimal_equals_not_just_compare_to(
            @ForAll("dynamoSafeDecimals") BigDecimal original) {
        Map<String, AttributeValue> item = codec.encode(values("amount", original));
        BigDecimal decoded = (BigDecimal) codec.decode(item).get("amount");
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.scale()).isEqualTo(original.scale());
        assertThat(item.get("amount").s()).isEqualTo(original.toPlainString());
        assertThat(item.get("amount").n()).isNull();
    }
}
