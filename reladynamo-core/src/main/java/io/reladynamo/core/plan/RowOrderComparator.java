package io.reladynamo.core.plan;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Comparator for decoded DynamoDB rows. Nulls sort first, matching Reladomo
 * {@code AttributeBasedOrderBy.compare}.
 *
 * <p>Numbers compare by exact numeric value: {@link BigDecimal#compareTo} never goes
 * through {@code double}. {@code byte[]} compares lexicographically unsigned, matching
 * DynamoDB binary order. Types whose order cannot be established are refused with
 * {@code RELADYNAMO-PLAN-013} rather than falling back to {@link String#valueOf}.
 */
public final class RowOrderComparator implements Comparator<Map<String, Object>>, Serializable {

    private static final long serialVersionUID = 1L;

    private final List<SortTerm> terms;

    public RowOrderComparator(List<SortTerm> terms) {
        Objects.requireNonNull(terms, "terms");
        this.terms = Collections.unmodifiableList(new ArrayList<SortTerm>(terms));
    }

    public List<SortTerm> terms() {
        return terms;
    }

    @Override
    public int compare(Map<String, Object> left, Map<String, Object> right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        for (int i = 0; i < terms.size(); i++) {
            SortTerm term = terms.get(i);
            int c = compareValues(left.get(term.attributeName()), right.get(term.attributeName()));
            if (c != 0) {
                return term.ascending() ? c : -c;
            }
        }
        return 0;
    }

    static int compareValues(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Number && right instanceof Number) {
            return compareNumbers((Number) left, (Number) right);
        }
        if (left instanceof byte[] && right instanceof byte[]) {
            return compareUnsignedBytes((byte[]) left, (byte[]) right);
        }
        if (left instanceof Comparable && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) left;
            return comparable.compareTo(right);
        }
        throw new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-013: Cannot order values of type "
                        + left.getClass().getSimpleName()
                        + " and "
                        + right.getClass().getSimpleName()
                        + "; Reladynamo refuses a silent fallback to String.valueOf.");
    }

    private static int compareNumbers(Number left, Number right) {
        if (left instanceof BigDecimal || right instanceof BigDecimal
                || left instanceof BigInteger || right instanceof BigInteger) {
            return toBigDecimal(left).compareTo(toBigDecimal(right));
        }
        if (left instanceof Float || right instanceof Float
                || left instanceof Double || right instanceof Double) {
            return Double.compare(left.doubleValue(), right.doubleValue());
        }
        return Long.compare(left.longValue(), right.longValue());
    }

    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        }
        if (number instanceof BigInteger) {
            return new BigDecimal((BigInteger) number);
        }
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Float || number instanceof Double) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        throw new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-013: Cannot order values of type "
                        + number.getClass().getSimpleName()
                        + "; Reladynamo refuses a silent fallback to String.valueOf.");
    }

    /**
     * DynamoDB binary comparison: unsigned lexicographic, then shorter first.
     */
    private static int compareUnsignedBytes(byte[] left, byte[] right) {
        int n = Math.min(left.length, right.length);
        for (int i = 0; i < n; i++) {
            int a = left[i] & 0xff;
            int b = right[i] & 0xff;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
