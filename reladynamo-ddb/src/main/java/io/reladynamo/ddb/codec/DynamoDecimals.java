package io.reladynamo.ddb.codec;

import java.math.BigDecimal;

/**
 * DynamoDB {@code N} limits applied as a pre-write gate for {@link BigDecimal} values.
 *
 * <p>Even though the canonical payload encoding is a string (so scale survives), a value that
 * could not be a DynamoDB number is rejected rather than persisted. Silent truncation of money
 * is the failure this class exists to prevent.
 */
final class DynamoDecimals {

    static final int MAX_PRECISION = 38;

    /** Smallest positive magnitude DynamoDB will accept as {@code N}. */
    static final BigDecimal MIN_MAGNITUDE = new BigDecimal("1E-130");

    /** Largest magnitude DynamoDB will accept as {@code N} (38 nines × 10^125). */
    static final BigDecimal MAX_MAGNITUDE =
            new BigDecimal("9.9999999999999999999999999999999999999E+125");

    private DynamoDecimals() {
    }

    static void validate(BigDecimal value, String attributeName) {
        if (value.precision() > MAX_PRECISION) {
            throw new CodecException(
                    "BigDecimal attribute " + attributeName + " has " + value.precision()
                            + " significant digits; DynamoDB allows at most " + MAX_PRECISION
                            + " — refusing to truncate");
        }
        if (value.signum() != 0) {
            BigDecimal magnitude = value.abs();
            if (magnitude.compareTo(MIN_MAGNITUDE) < 0 || magnitude.compareTo(MAX_MAGNITUDE) > 0) {
                throw new CodecException(
                        "BigDecimal attribute " + attributeName + " magnitude "
                                + value.toEngineeringString()
                                + " is outside DynamoDB exponent range [1E-130, 9.99...E+125]");
            }
        }
        if (value.scale() < 0) {
            throw new CodecException(
                    "BigDecimal attribute " + attributeName
                            + " has negative scale " + value.scale()
                            + "; Reladomo DECIMAL scale is >= 0 and toPlainString() would drop it");
        }
    }
}
