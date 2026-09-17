package io.reladynamo.core.plan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the numeric residual contract that Reladomo {@code matches} does not provide:
 * IEEE primitive comparison (so {@code -0.0 == 0.0} and {@code NaN} matches nothing)
 * and {@code BigDecimal.compareTo() == 0} (numeric), not {@code equals()} (scale).
 */
class TypedNumericResidualTest {

    @Test
    void should_treat_negative_zero_as_equal_for_double_and_float() {
        assertThat(TypedNumericResidual.numericEqual(
                Double.valueOf(-0.0d), Double.valueOf(0.0d))).isTrue();
        assertThat(TypedNumericResidual.numericEqual(
                Double.valueOf(0.0d), Double.valueOf(-0.0d))).isTrue();
        assertThat(TypedNumericResidual.numericEqual(
                Float.valueOf(-0.0f), Float.valueOf(0.0f))).isTrue();
        assertThat(TypedNumericResidual.numericEqual(
                Float.valueOf(0.0f), Float.valueOf(-0.0f))).isTrue();
    }

    @Test
    void should_not_treat_nan_as_equal_to_itself() {
        assertThat(TypedNumericResidual.numericEqual(
                Double.valueOf(Double.NaN), Double.valueOf(Double.NaN))).isFalse();
        assertThat(TypedNumericResidual.numericEqual(
                Float.valueOf(Float.NaN), Float.valueOf(Float.NaN))).isFalse();
    }

    @Test
    void should_compare_big_decimal_by_numeric_value_not_scale() {
        assertThat(new BigDecimal("1.10").equals(new BigDecimal("1.1")))
                .as("pin that BigDecimal.equals is scale-sensitive, so we must not use it")
                .isFalse();
        assertThat(TypedNumericResidual.numericEqual(
                new BigDecimal("1.10"), new BigDecimal("1.1"))).isTrue();
        assertThat(TypedNumericResidual.numericEqual(
                new BigDecimal("1.1000"), new BigDecimal("1.10"))).isTrue();
        assertThat(TypedNumericResidual.numericGreater(
                new BigDecimal("10.0000"), new BigDecimal("9"))).isTrue();
        assertThat(TypedNumericResidual.numericLess(
                new BigDecimal("9"), new BigDecimal("10"))).isTrue();
        assertThat(TypedNumericResidual.numericEqual(
                new BigDecimal("1.10"), new BigDecimal("1.11"))).isFalse();
    }
}
