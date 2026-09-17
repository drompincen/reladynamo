package io.reladynamo.core.plan;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reladynamo-owned tagged scalar so {@link QueryPlan} has zero AWS SDK types.
 */
public final class ExpressionValue {

    public enum Kind { S, N, B, BOOL, NULL }

    private final Kind kind;
    private final String s;
    private final byte[] b;
    private final Boolean bool;

    private ExpressionValue(Kind kind, String s, byte[] b, Boolean bool) {
        this.kind = kind;
        this.s = s;
        this.b = b == null ? null : Arrays.copyOf(b, b.length);
        this.bool = bool;
    }

    public static ExpressionValue s(String v) {
        return new ExpressionValue(Kind.S, Objects.requireNonNull(v, "s"), null, null);
    }

    public static ExpressionValue n(String decimal) {
        return new ExpressionValue(Kind.N, Objects.requireNonNull(decimal, "n"), null, null);
    }

    public static ExpressionValue b(byte[] v) {
        return new ExpressionValue(Kind.B, null, Objects.requireNonNull(v, "b"), null);
    }

    public static ExpressionValue bool(boolean v) {
        return new ExpressionValue(Kind.BOOL, null, null, Boolean.valueOf(v));
    }

    public static ExpressionValue nul() {
        return new ExpressionValue(Kind.NULL, null, null, null);
    }

    public Kind kind() {
        return kind;
    }

    public String s() {
        return s;
    }

    public String n() {
        return kind == Kind.N ? s : null;
    }

    public byte[] b() {
        return b == null ? null : Arrays.copyOf(b, b.length);
    }

    public Boolean bool() {
        return bool;
    }

    public Object javaValue() {
        switch (kind) {
            case S:
            case N:
                return s;
            case B:
                return b();
            case BOOL:
                return bool;
            case NULL:
                return null;
            default:
                return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExpressionValue)) {
            return false;
        }
        ExpressionValue that = (ExpressionValue) o;
        return kind == that.kind
                && Objects.equals(s, that.s)
                && Arrays.equals(b, that.b)
                && Objects.equals(bool, that.bool);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(kind, s, bool);
        result = 31 * result + Arrays.hashCode(b);
        return result;
    }

    @Override
    public String toString() {
        switch (kind) {
            case S:
                return "S(" + s + ")";
            case N:
                return "N(" + s + ")";
            case B:
                return "B(len=" + (b == null ? 0 : b.length) + ")";
            case BOOL:
                return "BOOL(" + bool + ")";
            case NULL:
                return "NULL";
            default:
                return kind.name();
        }
    }
}
