package io.reladynamo.ddb.codec;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static io.reladynamo.ddb.codec.ItemCodecRoundTripTest.assertRoundTrip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ItemCodecNumericFidelityTest {

    @Test
    void should_preserve_zero_with_scale() {
        ItemCodec codec = new ItemCodec(entity(attr("amount", "BigDecimal", false)));
        BigDecimal zero = new BigDecimal("0.00");

        Map<String, AttributeValue> item = codec.encode(values("amount", zero));
        BigDecimal decoded = (BigDecimal) codec.decode(item).get("amount");

        assertThat(item.get("amount").s()).isEqualTo("0.00");
        assertThat(decoded.scale()).isEqualTo(2);
        assertThat(decoded).isEqualTo(zero);
    }

    @Test
    void should_preserve_big_decimal_scale_including_trailing_zeros() {
        ItemCodec codec = new ItemCodec(entity(attr("amount", "BigDecimal", false)));
        BigDecimal amount = new BigDecimal("1.10");

        Map<String, AttributeValue> item = codec.encode(values("amount", amount));
        BigDecimal decoded = (BigDecimal) codec.decode(item).get("amount");

        assertThat(item.get("amount").s()).isEqualTo("1.10");
        assertThat(decoded.scale()).isEqualTo(2);
        assertThat(decoded).isEqualTo(amount);
    }

    @Test
    void should_reject_big_decimal_with_more_than_38_significant_digits() {
        ItemCodec codec = new ItemCodec(entity(attr("amount", "BigDecimal", false)));
        BigDecimal tooWide = new BigDecimal("123456789012345678901234567890123456789");

        assertThatThrownBy(() -> codec.encode(values("amount", tooWide)))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("38")
                .hasMessageContaining("truncate");
    }

    @Test
    void should_reject_big_decimal_outside_dynamodb_exponent_range() {
        ItemCodec codec = new ItemCodec(entity(attr("amount", "BigDecimal", false)));

        BigDecimal tooSmall = BigDecimal.ONE.movePointLeft(131);
        BigDecimal tooLarge = new BigDecimal("1E+126");
        assertThatThrownBy(() -> codec.encode(values("amount", tooSmall)))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("exponent");
        assertThatThrownBy(() -> codec.encode(values("amount", tooLarge)))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("exponent");
    }

    @Test
    void should_reject_big_decimal_with_negative_scale() {
        ItemCodec codec = new ItemCodec(entity(attr("amount", "BigDecimal", false)));
        BigDecimal negativeScale = new BigDecimal("1E+2");

        assertThatThrownBy(() -> codec.encode(values("amount", negativeScale)))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("negative scale");
    }

    @Test
    void should_store_double_as_ieee_binary_not_n() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "double", false)));
        Map<String, AttributeValue> item = codec.encode(values("quantity", Math.PI));
        assertThat(item.get("quantity").b()).isNotNull();
        assertThat(item.get("quantity").n()).isNull();
        assertThat(item.get("quantity").b().asByteArray()).hasSize(8);
    }

    @Test
    void should_round_trip_double_specials_including_nan_inf_negative_zero_and_subnormals() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "double", false)));
        double[] specials = new double[] {
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.MIN_NORMAL,
                -0.0,
                0.0,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(1L),
                -Double.MIN_VALUE,
                Math.PI,
                Math.E
        };
        for (double d : specials) {
            assertRoundTrip(codec, "quantity", d);
        }
        Map<String, Object> decodedNegZero =
                codec.decode(codec.encode(values("quantity", -0.0)));
        assertThat(Double.doubleToRawLongBits((Double) decodedNegZero.get("quantity")))
                .isEqualTo(Double.doubleToRawLongBits(-0.0));
    }

    @Test
    void should_round_trip_float_specials() {
        ItemCodec codec = new ItemCodec(entity(attr("quantity", "float", false)));
        float[] specials = new float[] {
                Float.MIN_VALUE,
                Float.MAX_VALUE,
                Float.MIN_NORMAL,
                -0.0f,
                0.0f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY
        };
        for (float f : specials) {
            assertRoundTrip(codec, "quantity", f);
        }
        Map<String, AttributeValue> item = codec.encode(values("quantity", Float.NaN));
        assertThat(item.get("quantity").b().asByteArray()).hasSize(4);
        assertThat(item.get("quantity").n()).isNull();
    }
}
