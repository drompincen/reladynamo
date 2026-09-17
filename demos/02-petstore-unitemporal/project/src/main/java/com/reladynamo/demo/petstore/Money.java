package com.reladynamo.demo.petstore;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Scale-2 money literals used by seed data and the demonstrations. */
public final class Money {

    private Money() {
    }

    public static BigDecimal of(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
