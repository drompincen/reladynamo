package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.finder.AndOperation;
import com.gs.fw.common.mithra.finder.AtomicEqualityOperation;
import com.gs.fw.common.mithra.finder.AtomicNotEqualityOperation;
import com.gs.fw.common.mithra.finder.AtomicSetBasedOperation;
import com.gs.fw.common.mithra.finder.MultiEqualityOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.OrOperation;
import com.gs.fw.common.mithra.finder.RangeOperation;
import io.reladynamo.core.plan.reladomo.ReladomoOperationAccess;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Evaluates Float/Double/BigDecimal residuals against a decoded Java-attribute map.
 *
 * <p>The codec stores float/double as IEEE-754 {@code B} and BigDecimal as a decimal {@code S}.
 * Those encodings are not DynamoDB-filter comparable, so the planner leaves the Reladomo
 * predicate as a residual and this class applies Java numeric semantics after decode:
 * primitive {@code == / < / >} for IEEE (so {@code 0.0 == -0.0} and {@code NaN} matches nothing)
 * and {@link BigDecimal#compareTo} for decimals (so {@code 1.10} equals {@code 1.1}).
 *
 * <p>Reladomo {@code NonPrimitiveEqOperation.matches} uses {@code Object.equals}, which is
 * scale-sensitive for {@code BigDecimal}. That is the wrong contract for a residual over
 * codec-decoded decimals, so this evaluator does not call {@code matches} on numeric leaves.
 */
public final class TypedNumericResidual {

    private TypedNumericResidual() {
    }

    public static boolean applies(Operation op) {
        if (op == null) {
            return false;
        }
        if (ReladomoOperationAccess.isIeeeOrDecimalOperation(op)) {
            return true;
        }
        if (op instanceof AndOperation) {
            List<Operation> kids = ReladomoOperationAccess.andOperands((AndOperation) op);
            for (int i = 0; i < kids.size(); i++) {
                if (applies(kids.get(i))) {
                    return true;
                }
            }
            return false;
        }
        if (op instanceof OrOperation) {
            List<Operation> kids = ReladomoOperationAccess.orOperands(op);
            for (int i = 0; i < kids.size(); i++) {
                if (applies(kids.get(i))) {
                    return true;
                }
            }
            return false;
        }
        if (op instanceof MultiEqualityOperation) {
            com.gs.fw.common.mithra.finder.AtomicOperation[] atomics =
                    ReladomoOperationAccess.multiAtomics((MultiEqualityOperation) op);
            for (int i = 0; i < atomics.length; i++) {
                if (applies(atomics[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean evaluate(Operation op, Map<String, Object> decoded, Object typedCandidate) {
        if (op == null) {
            return true;
        }
        if (ReladomoOperationAccess.isNone(op)) {
            return false;
        }
        if (ReladomoOperationAccess.isAll(op) || ReladomoOperationAccess.isNoOperation(op)) {
            return true;
        }
        if (op instanceof AndOperation) {
            List<Operation> kids = ReladomoOperationAccess.andOperands((AndOperation) op);
            for (int i = 0; i < kids.size(); i++) {
                if (!evaluate(kids.get(i), decoded, typedCandidate)) {
                    return false;
                }
            }
            return true;
        }
        if (op instanceof OrOperation) {
            List<Operation> kids = ReladomoOperationAccess.orOperands(op);
            for (int i = 0; i < kids.size(); i++) {
                if (evaluate(kids.get(i), decoded, typedCandidate)) {
                    return true;
                }
            }
            return false;
        }
        if (op instanceof MultiEqualityOperation) {
            com.gs.fw.common.mithra.finder.AtomicOperation[] atomics =
                    ReladomoOperationAccess.multiAtomics((MultiEqualityOperation) op);
            for (int i = 0; i < atomics.length; i++) {
                if (!evaluate(atomics[i], decoded, typedCandidate)) {
                    return false;
                }
            }
            return true;
        }
        if (ReladomoOperationAccess.isIeeeOrDecimalOperation(op)) {
            return matchNumericLeaf(op, decoded);
        }
        Object candidate = typedCandidate != null ? typedCandidate : decoded;
        Boolean result = op.matches(candidate);
        return ResidualPredicate.isPass(result);
    }

    private static boolean matchNumericLeaf(Operation op, Map<String, Object> decoded) {
        Attribute attr = ReladomoOperationAccess.attributeOf(op);
        String name = attr == null ? null : attr.getAttributeName();
        Object actual = name == null || decoded == null ? null : decoded.get(name);
        if (ReladomoOperationAccess.isIsNull(op)) {
            return actual == null;
        }
        if (ReladomoOperationAccess.isIsNotNull(op)) {
            return actual != null;
        }
        if (actual == null) {
            return false;
        }
        if (ReladomoOperationAccess.isEquality(op) && op instanceof AtomicEqualityOperation) {
            return numericEqual(actual, ((AtomicEqualityOperation) op).getParameterAsObject());
        }
        if (ReladomoOperationAccess.isNotEquality(op)) {
            Object expected = notEqParameter(op);
            return expected != null && !numericEqual(actual, expected);
        }
        if (ReladomoOperationAccess.isIn(op) && op instanceof AtomicSetBasedOperation) {
            return inContains(actual, ReladomoOperationAccess.inValues((AtomicSetBasedOperation) op, attr));
        }
        if (ReladomoOperationAccess.isNotIn(op) && op instanceof AtomicSetBasedOperation) {
            return !inContains(actual, ReladomoOperationAccess.inValues((AtomicSetBasedOperation) op, attr));
        }
        if (op instanceof RangeOperation) {
            Object bound = ReladomoOperationAccess.rangeParameter((RangeOperation) op);
            if (ReladomoOperationAccess.isGreaterThanEquals(op)) {
                return numericGreaterOrEqual(actual, bound);
            }
            if (ReladomoOperationAccess.isGreaterThan(op)) {
                return numericGreater(actual, bound);
            }
            if (ReladomoOperationAccess.isLessThanEquals(op)) {
                return numericLessOrEqual(actual, bound);
            }
            if (ReladomoOperationAccess.isLessThan(op)) {
                return numericLess(actual, bound);
            }
        }
        Object candidate = decoded;
        Boolean result = op.matches(candidate);
        return ResidualPredicate.isPass(result);
    }

    private static Object notEqParameter(Operation op) {
        if (op instanceof AtomicNotEqualityOperation) {
            return ((AtomicNotEqualityOperation) op).getParameterAsObject();
        }
        if (op instanceof AtomicEqualityOperation) {
            return ((AtomicEqualityOperation) op).getParameterAsObject();
        }
        return null;
    }

    private static boolean inContains(Object actual, List<Object> values) {
        if (values == null) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            if (numericEqual(actual, values.get(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean numericEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (isBigDecimal(actual) || isBigDecimal(expected)) {
            return toBigDecimal(actual).compareTo(toBigDecimal(expected)) == 0;
        }
        if (isDouble(actual) || isDouble(expected)) {
            return toDouble(actual) == toDouble(expected);
        }
        if (isFloat(actual) || isFloat(expected)) {
            return toFloat(actual) == toFloat(expected);
        }
        return actual.equals(expected);
    }

    static boolean numericGreater(Object actual, Object bound) {
        if (isBigDecimal(actual) || isBigDecimal(bound)) {
            return toBigDecimal(actual).compareTo(toBigDecimal(bound)) > 0;
        }
        if (isDouble(actual) || isDouble(bound)) {
            return toDouble(actual) > toDouble(bound);
        }
        return toFloat(actual) > toFloat(bound);
    }

    static boolean numericGreaterOrEqual(Object actual, Object bound) {
        if (isBigDecimal(actual) || isBigDecimal(bound)) {
            return toBigDecimal(actual).compareTo(toBigDecimal(bound)) >= 0;
        }
        if (isDouble(actual) || isDouble(bound)) {
            return toDouble(actual) >= toDouble(bound);
        }
        return toFloat(actual) >= toFloat(bound);
    }

    static boolean numericLess(Object actual, Object bound) {
        if (isBigDecimal(actual) || isBigDecimal(bound)) {
            return toBigDecimal(actual).compareTo(toBigDecimal(bound)) < 0;
        }
        if (isDouble(actual) || isDouble(bound)) {
            return toDouble(actual) < toDouble(bound);
        }
        return toFloat(actual) < toFloat(bound);
    }

    static boolean numericLessOrEqual(Object actual, Object bound) {
        if (isBigDecimal(actual) || isBigDecimal(bound)) {
            return toBigDecimal(actual).compareTo(toBigDecimal(bound)) <= 0;
        }
        if (isDouble(actual) || isDouble(bound)) {
            return toDouble(actual) <= toDouble(bound);
        }
        return toFloat(actual) <= toFloat(bound);
    }

    private static boolean isBigDecimal(Object v) {
        return v instanceof BigDecimal;
    }

    private static boolean isDouble(Object v) {
        return v instanceof Double;
    }

    private static boolean isFloat(Object v) {
        return v instanceof Float;
    }

    private static double toDouble(Object v) {
        return ((Number) v).doubleValue();
    }

    private static float toFloat(Object v) {
        return ((Number) v).floatValue();
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }
}
