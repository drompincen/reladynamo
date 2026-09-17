package io.reladynamo.ddb.differential.findermatrix;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Exact §5 fixture manifests. Notation helpers are construction-only, not finder APIs.
 * {@code T(ms)} is a Timestamp at epoch millisecond {@code ms}. {@code D("...")} is
 * {@code new BigDecimal} of that string. {@code hex(...)} is a fresh byte[].
 */
final class FixtureManifests {

    /** Reused 64 KiB pattern for bundle P construction; copy when snapshotting. */
    static final byte[] P_BYTES = fill((byte) 0x5A, 65536);

    private FixtureManifests() {
    }

    static Timestamp T(long ms) {
        return new Timestamp(ms);
    }

    static BigDecimal D(String s) {
        return new BigDecimal(s);
    }

    static byte[] hex(int... bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) bytes[i];
        }
        return out;
    }

    static ValueRow v(int rowId) {
        return V().get(rowId - 1);
    }

    static List<ValueRow> V() {
        List<ValueRow> rows = new ArrayList<ValueRow>();
        rows.add(payload(1, 1, 1, Integer.valueOf(-10), Long.valueOf(-9007199254740993L),
                Double.valueOf(-10.5d), Float.valueOf(-10.5f), D("-10.5000"), "alpha",
                Boolean.FALSE, T(-1001L), hex(0x00, 0xFF)));
        rows.add(payload(1, 2, 1, Integer.valueOf(0), Long.valueOf(0L),
                Double.valueOf(0.0d), Float.valueOf(0.0f), D("0.0000"), "",
                Boolean.FALSE, T(-1L), new byte[0]));
        rows.add(payload(1, 3, 1, Integer.valueOf(2), Long.valueOf(9007199254740992L),
                Double.valueOf(2.5d), Float.valueOf(2.5f), D("2.5000"), "beta",
                Boolean.TRUE, T(0L), hex(0x01, 0x02)));
        rows.add(payload(1, 4, 1, Integer.valueOf(10), Long.valueOf(9007199254740993L),
                Double.valueOf(10.5d), Float.valueOf(10.5f), D("10.5000"), "alphabet",
                Boolean.TRUE, T(1L), hex(0x80)));
        rows.add(payload(1, 5, 1, null, null, null, null, null, null, null, null, null));
        rows.add(payload(1, 6, 1, Integer.valueOf(2), Long.valueOf(9007199254740992L),
                Double.valueOf(2.5d), Float.valueOf(2.5f), D("2.5000"), "zbeta",
                Boolean.TRUE, T(0L), hex(0x01, 0x02)));
        rows.add(payload(1, 7, 1, Integer.valueOf(100), Long.valueOf(9007199254740994L),
                Double.valueOf(100.5d), Float.valueOf(100.5f), D("100.5000"), "aXmidYz",
                Boolean.FALSE, T(1001L), hex(0xFF)));
        return rows;
    }

    static List<ValueRow> X() {
        ValueRow src = v(3);
        return Collections.singletonList(
                payload(2, 3, 2, Integer.valueOf(99), src.longValue, src.doubleValue, src.floatValue,
                        src.decimalValue, "other-scope", src.booleanValue, src.timestampValue, hex(0x01, 0x02)));
    }

    static List<ValueRow> L() {
        int[] ints = {-1, 0, 99, 100, 101, 102};
        String[] texts = {"-1", "0", "99", "100", "101", "102"};
        List<ValueRow> rows = new ArrayList<ValueRow>();
        for (int r = 1; r <= 6; r++) {
            ValueRow base = v(4).copyReplacing(3, r, 3);
            rows.add(base.withInt(Integer.valueOf(ints[r - 1]))
                    .withDouble(Double.valueOf((double) ints[r - 1]))
                    .withText(texts[r - 1]));
        }
        return rows;
    }

    static List<ValueRow> S() {
        String[] texts = {
                "aXmidYz", "a_mid_z", "amidYz", "aXmidYzx", "a*", "a%b", "a_b", "a?b", null
        };
        List<ValueRow> rows = new ArrayList<ValueRow>();
        for (int r = 1; r <= 9; r++) {
            rows.add(v(4).copyReplacing(4, r, 4).withText(texts[r - 1]));
        }
        return rows;
    }

    static List<ValueRow> O() {
        String[] decimals = {
                "9007199254740993.0000", "9007199254740992.0000", "9007199254740994.0000"
        };
        List<ValueRow> rows = new ArrayList<ValueRow>();
        for (int r = 1; r <= 3; r++) {
            rows.add(v(4).copyReplacing(5, r, 5).withDecimal(D(decimals[r - 1])));
        }
        return rows;
    }

    static List<ValueRow> F() {
        String[] texts = {"A", "B", "A", "B"};
        int[] ints = {1, 2, 3, 4};
        List<ValueRow> rows = new ArrayList<ValueRow>();
        for (int r = 1; r <= 4; r++) {
            rows.add(v(4).copyReplacing(6, r, 6)
                    .withText(texts[r - 1])
                    .withInt(Integer.valueOf(ints[r - 1])));
        }
        return rows;
    }

    static List<ValueRow> M() {
        String[] texts = {null, null, "", "hello"};
        List<ValueRow> rows = new ArrayList<ValueRow>();
        for (int r = 1; r <= 4; r++) {
            rows.add(v(4).copyReplacing(7, r, 7).withText(texts[r - 1]));
        }
        return rows;
    }

    static List<ValueRow> P() {
        List<ValueRow> rows = new ArrayList<ValueRow>();
        for (int r = 1; r <= 24; r++) {
            String text = r <= 20 ? "miss" : "hit";
            rows.add(v(4).copyReplacing(8, r, 8)
                    .withInt(Integer.valueOf(r))
                    .withDecimal(D(r + ".0000"))
                    .withText(text)
                    .withBytes(P_BYTES));
        }
        return rows;
    }

    static List<ValueRow> Z() {
        Double[] doubles = {
                Double.valueOf(Double.NEGATIVE_INFINITY),
                Double.valueOf(Double.POSITIVE_INFINITY),
                Double.valueOf(Double.NaN)
        };
        Float[] floats = {
                Float.valueOf(Float.NEGATIVE_INFINITY),
                Float.valueOf(Float.POSITIVE_INFINITY),
                Float.valueOf(Float.NaN)
        };
        List<ValueRow> rows = new ArrayList<ValueRow>();
        for (int r = 1; r <= 3; r++) {
            rows.add(v(4).copyReplacing(9, r, 9)
                    .withDouble(doubles[r - 1])
                    .withFloat(floats[r - 1]));
        }
        return rows;
    }

    /** Bundles the minimum cases actually execute: V + X + F. */
    static List<ValueRow> coreSeed() {
        List<ValueRow> rows = new ArrayList<ValueRow>();
        rows.addAll(V());
        rows.addAll(X());
        rows.addAll(F());
        return rows;
    }

    static List<String> mappedAttributeNames() {
        return Arrays.asList(
                "scopeId", "rowId", "bucketId",
                "intValue", "longValue", "doubleValue", "floatValue", "decimalValue",
                "textValue", "booleanValue", "timestampValue", "bytesValue");
    }

    private static ValueRow payload(int scopeId, int rowId, int bucketId,
                                    Integer intValue, Long longValue, Double doubleValue, Float floatValue,
                                    BigDecimal decimalValue, String textValue, Boolean booleanValue,
                                    Timestamp timestampValue, byte[] bytesValue) {
        return new ValueRow(scopeId, rowId, bucketId, intValue, longValue, doubleValue, floatValue,
                decimalValue, textValue, booleanValue, timestampValue, bytesValue);
    }

    private static byte[] fill(byte value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }
}
