package io.reladynamo.core.plan.reladomo;

import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.attribute.BigDecimalAttribute;
import com.gs.fw.common.mithra.attribute.DoubleAttribute;
import com.gs.fw.common.mithra.attribute.FloatAttribute;
import com.gs.fw.common.mithra.finder.All;
import com.gs.fw.common.mithra.finder.AndOperation;
import com.gs.fw.common.mithra.finder.AtomicEqualityOperation;
import com.gs.fw.common.mithra.finder.AtomicOperation;
import com.gs.fw.common.mithra.finder.AtomicSetBasedOperation;
import com.gs.fw.common.mithra.finder.InOperation;
import com.gs.fw.common.mithra.finder.IsNullOperation;
import com.gs.fw.common.mithra.finder.MappedOperation;
import com.gs.fw.common.mithra.finder.MultiEqualityOperation;
import com.gs.fw.common.mithra.finder.NoOperation;
import com.gs.fw.common.mithra.finder.None;
import com.gs.fw.common.mithra.finder.NotExistsOperation;
import com.gs.fw.common.mithra.finder.NotInOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.OrOperation;
import com.gs.fw.common.mithra.finder.RangeOperation;
import com.gs.fw.common.mithra.finder.asofop.AsOfEdgePointOperation;
import com.gs.fw.common.mithra.finder.asofop.AsOfEqInfiniteNullOperation;
import com.gs.fw.common.mithra.finder.asofop.AsOfEqOperation;
import com.gs.fw.common.mithra.finder.orderby.ChainedOrderBy;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import com.gs.fw.common.mithra.finder.string.StringLikeOperation;
import com.gs.fw.common.mithra.util.InternalList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 18.1.0-pinned access to Reladomo {@link Operation} trees. Public API where it exists;
 * {@code setAccessible} on the three private layouts the design enumerates.
 */
public final class ReladomoOperationAccess {

    private static final Field AND_OPERANDS = field(AndOperation.class, "operands");
    private static final Method OR_GET_OPERATIONS = method(OrOperation.class, "getOperations");
    private static final Field MULTI_ATOMICS = field(MultiEqualityOperation.class, "atomicOperations");
    private static final Field LIKE_PARAMETER = field(StringLikeOperation.class, "parameter");
    private static final Field CHAINED_ORDER_BYS = field(ChainedOrderBy.class, "orderBys");

    static {
        AND_OPERANDS.setAccessible(true);
        OR_GET_OPERATIONS.setAccessible(true);
        MULTI_ATOMICS.setAccessible(true);
        LIKE_PARAMETER.setAccessible(true);
        CHAINED_ORDER_BYS.setAccessible(true);
    }

    private ReladomoOperationAccess() {
    }

    public static void selfCheck(Operation andSample, Operation orSample, Operation multiSample) {
        if (!(andSample instanceof AndOperation) && !(andSample instanceof MultiEqualityOperation)) {
            throw new IllegalStateException(
                    "Reladomo 18.1.0 self-check failed: AND of two atomics was "
                            + andSample.getClass().getName());
        }
        if (!(orSample instanceof OrOperation)) {
            throw new IllegalStateException(
                    "Reladomo 18.1.0 self-check failed: OR of two atomics was "
                            + orSample.getClass().getName());
        }
        operands(andSample);
        orOperands(orSample);
        if (multiSample instanceof MultiEqualityOperation) {
            multiAtomics((MultiEqualityOperation) multiSample);
        }
    }

    public static List<Operation> operands(Operation op) {
        if (op instanceof AndOperation) {
            return andOperands((AndOperation) op);
        }
        if (op instanceof OrOperation) {
            return orOperands(op);
        }
        if (op instanceof MultiEqualityOperation) {
            List<Operation> out = new ArrayList<Operation>();
            AtomicOperation[] atomics = multiAtomics((MultiEqualityOperation) op);
            Collections.addAll(out, atomics);
            return out;
        }
        return Collections.singletonList(op);
    }

    public static List<Operation> andOperands(AndOperation and) {
        try {
            InternalList list = (InternalList) AND_OPERANDS.get(and);
            List<Operation> out = new ArrayList<Operation>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add((Operation) list.get(i));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AndOperation.operands layout changed in Reladomo", e);
        }
    }

    public static List<Operation> orOperands(Operation or) {
        try {
            Operation[] ops = (Operation[]) OR_GET_OPERATIONS.invoke(or);
            List<Operation> out = new ArrayList<Operation>(ops.length);
            Collections.addAll(out, ops);
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OrOperation.getOperations layout changed in Reladomo", e);
        }
    }

    public static AtomicOperation[] multiAtomics(MultiEqualityOperation multi) {
        try {
            return (AtomicOperation[]) MULTI_ATOMICS.get(multi);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "MultiEqualityOperation.atomicOperations layout changed in Reladomo", e);
        }
    }

    public static String likeParameter(StringLikeOperation like) {
        try {
            return (String) LIKE_PARAMETER.get(like);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("StringLikeOperation.parameter layout changed in Reladomo", e);
        }
    }

    public static Attribute attributeOf(Operation op) {
        if (op instanceof AtomicOperation) {
            return ((AtomicOperation) op).getAttribute();
        }
        if (op instanceof All) {
            return attributeVia("attr", op);
        }
        if (op instanceof None) {
            return attributeVia("attr", op);
        }
        return null;
    }

    private static Attribute attributeVia(String fieldName, Operation op) {
        try {
            Field f = op.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return (Attribute) f.get(op);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static String attributeJavaName(Operation op) {
        Attribute attr = attributeOf(op);
        return attr == null ? null : attr.getAttributeName();
    }

    /**
     * Float/double are stored as IEEE-754 {@code B} and BigDecimal as a decimal {@code S}.
     * Those encodings are not DynamoDB-filter comparable, so the planner leaves the
     * Reladomo predicate as a typed residual.
     */
    public static boolean isIeeeOrDecimalAttribute(Attribute attr) {
        if (attr == null) {
            return false;
        }
        if (attr instanceof DoubleAttribute
                || attr instanceof FloatAttribute
                || attr instanceof BigDecimalAttribute) {
            return true;
        }
        Class<?> type = attr.valueType();
        return type == Double.class || type == Double.TYPE
                || type == Float.class || type == Float.TYPE
                || type == BigDecimal.class;
    }

    public static boolean isIeeeOrDecimalOperation(Operation op) {
        return isIeeeOrDecimalAttribute(attributeOf(op));
    }

    public static boolean isIeeeOrDecimalJavaType(String javaType) {
        if (javaType == null) {
            return false;
        }
        return "double".equalsIgnoreCase(javaType)
                || "float".equalsIgnoreCase(javaType)
                || "BigDecimal".equalsIgnoreCase(javaType);
    }

    public static boolean isFloatOrDoubleAttribute(Attribute attr) {
        if (attr == null) {
            return false;
        }
        if (attr instanceof DoubleAttribute || attr instanceof FloatAttribute) {
            return true;
        }
        Class<?> type = attr.valueType();
        return type == Double.class || type == Double.TYPE
                || type == Float.class || type == Float.TYPE;
    }

    public static Object equalityParameter(AtomicEqualityOperation eq) {
        return eq.getParameterAsObject();
    }

    public static Object rangeParameter(RangeOperation range) {
        try {
            Method getParameter = range.getClass().getMethod("getParameter");
            return getParameter.invoke(range);
        } catch (NoSuchMethodException e) {
            try {
                Method extractor = RangeOperation.class.getMethod("getStaticExtractor");
                Object ex = extractor.invoke(range);
                Method valueOf = ex.getClass().getMethod("valueOf", Object.class);
                return valueOf.invoke(ex, range);
            } catch (ReflectiveOperationException e2) {
                throw new IllegalStateException(
                        "cannot read RangeOperation parameter from " + range.getClass().getName(), e2);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "cannot read RangeOperation parameter from " + range.getClass().getName(), e);
        }
    }

    public static boolean isEquality(Operation op) {
        return op instanceof AtomicEqualityOperation
                && !(op instanceof AsOfEqOperation)
                && !(op instanceof AsOfEdgePointOperation)
                && !(op instanceof AsOfEqInfiniteNullOperation)
                && !(op instanceof IsNullOperation)
                && !isNotEquality(op);
    }

    public static boolean isNotEquality(Operation op) {
        String n = op.getClass().getSimpleName();
        return n.contains("NotEq") || n.contains("NotEqual");
    }

    public static boolean isAsOfEq(Operation op) {
        return op instanceof AsOfEqOperation;
    }

    public static boolean isAsOfEdge(Operation op) {
        return op instanceof AsOfEdgePointOperation;
    }

    public static boolean isAsOfInfiniteNull(Operation op) {
        return op instanceof AsOfEqInfiniteNullOperation;
    }

    public static boolean isIn(Operation op) {
        return op instanceof InOperation && !(op instanceof NotInOperation);
    }

    public static boolean isNotIn(Operation op) {
        return op instanceof NotInOperation;
    }

    public static boolean isRange(Operation op) {
        return op instanceof RangeOperation;
    }

    public static boolean isGreaterThan(Operation op) {
        return classNameContains(op, "GreaterThan") && !classNameContains(op, "GreaterThanEquals");
    }

    public static boolean isGreaterThanEquals(Operation op) {
        return classNameContains(op, "GreaterThanEquals");
    }

    public static boolean isLessThan(Operation op) {
        return classNameContains(op, "LessThan") && !classNameContains(op, "LessThanEquals");
    }

    public static boolean isLessThanEquals(Operation op) {
        return classNameContains(op, "LessThanEquals");
    }

    public static boolean isStartsWith(Operation op) {
        return classNameContains(op, "StartsWith") && !classNameContains(op, "NotStartsWith");
    }

    public static boolean isEndsWith(Operation op) {
        return classNameContains(op, "EndsWith") && !classNameContains(op, "NotEndsWith");
    }

    public static boolean isContains(Operation op) {
        return classNameContains(op, "Contains") && !classNameContains(op, "NotContains");
    }

    public static boolean isNotStartsWith(Operation op) {
        return classNameContains(op, "NotStartsWith");
    }

    public static boolean isNotEndsWith(Operation op) {
        return classNameContains(op, "NotEndsWith");
    }

    public static boolean isNotContains(Operation op) {
        return classNameContains(op, "NotContains");
    }

    public static boolean isLike(Operation op) {
        return op instanceof StringLikeOperation
                && (classNameContains(op, "StringLikeOperation")
                || classNameContains(op, "WildCardEq"));
    }

    public static boolean isIsNull(Operation op) {
        return op instanceof IsNullOperation || classNameContains(op, "IsNullOperation");
    }

    public static boolean isIsNotNull(Operation op) {
        return classNameContains(op, "IsNotNull");
    }

    public static boolean isMapped(Operation op) {
        return op instanceof MappedOperation;
    }

    public static boolean isNotExists(Operation op) {
        return op instanceof NotExistsOperation;
    }

    public static boolean isTupleExists(Operation op) {
        return "TupleExistsOperation".equals(op.getClass().getSimpleName());
    }

    public static boolean isAll(Operation op) {
        return op instanceof All;
    }

    public static boolean isNone(Operation op) {
        return op instanceof None || op.zIsNone();
    }

    public static boolean isNoOperation(Operation op) {
        return op instanceof NoOperation;
    }

    public static boolean isAsOfAttribute(Attribute attr) {
        return attr instanceof AsOfAttribute;
    }

    /**
     * Reladomo's {@code getSetValueAs*} reads {@code copiedArray}, which stays null until
     * {@code populateCopiedArray()} (SQL path). Call that, or fall back to the private set field.
     */
    public static List<Object> inValues(AtomicSetBasedOperation in, Attribute attribute) {
        populateCopiedArray(in);
        int n = in.getSetSize();
        List<Object> fromField = inValuesFromSetField(in);
        if (fromField != null && fromField.size() == n) {
            return fromField;
        }
        List<Object> values = new ArrayList<Object>(n);
        Class<?> type = attribute == null ? Object.class : attribute.valueType();
        for (int i = 0; i < n; i++) {
            values.add(inValue(in, i, type));
        }
        return values;
    }

    /**
     * 18.1.0 {@code ChainedOrderBy} stores the chain in a private {@code orderBys} array.
     * There is no public getter; javap-confirmed 2026-09-14.
     */
    public static OrderBy[] chainedOrderBys(ChainedOrderBy chained) {
        if (chained == null) {
            return new OrderBy[0];
        }
        try {
            OrderBy[] parts = (OrderBy[]) CHAINED_ORDER_BYS.get(chained);
            if (parts == null) {
                return new OrderBy[0];
            }
            return parts;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Reladomo 18.1.0 ChainedOrderBy.orderBys is no longer accessible", e);
        }
    }

    public static String dump(Object op) {
        if (op == null) {
            return "null";
        }
        try {
            return String.valueOf(op);
        } catch (RuntimeException e) {
            return op.getClass().getName();
        }
    }

    private static void populateCopiedArray(AtomicSetBasedOperation in) {
        Class<?> c = in.getClass();
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod("populateCopiedArray");
                m.setAccessible(true);
                m.invoke(in);
                return;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            } catch (ReflectiveOperationException e) {
                return;
            }
        }
    }

    private static List<Object> inValuesFromSetField(AtomicSetBasedOperation in) {
        Object set = readSetField(in);
        if (set == null) {
            return null;
        }
        try {
            Method toArray = set.getClass().getMethod("toArray");
            Object arr = toArray.invoke(set);
            if (arr instanceof int[]) {
                int[] ints = (int[]) arr;
                List<Object> out = new ArrayList<Object>(ints.length);
                for (int i = 0; i < ints.length; i++) {
                    out.add(Integer.valueOf(ints[i]));
                }
                return out;
            }
            if (arr instanceof long[]) {
                long[] longs = (long[]) arr;
                List<Object> out = new ArrayList<Object>(longs.length);
                for (int i = 0; i < longs.length; i++) {
                    out.add(Long.valueOf(longs[i]));
                }
                return out;
            }
            if (arr instanceof short[]) {
                short[] shorts = (short[]) arr;
                List<Object> out = new ArrayList<Object>(shorts.length);
                for (int i = 0; i < shorts.length; i++) {
                    out.add(Short.valueOf(shorts[i]));
                }
                return out;
            }
            if (arr instanceof byte[]) {
                byte[] bytes = (byte[]) arr;
                List<Object> out = new ArrayList<Object>(bytes.length);
                for (int i = 0; i < bytes.length; i++) {
                    out.add(Byte.valueOf(bytes[i]));
                }
                return out;
            }
            if (arr instanceof Object[]) {
                Object[] objs = (Object[]) arr;
                List<Object> out = new ArrayList<Object>(objs.length);
                Collections.addAll(out, objs);
                return out;
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        if (set instanceof java.util.Collection) {
            return new ArrayList<Object>((java.util.Collection<?>) set);
        }
        return null;
    }

    private static Object readSetField(Object in) {
        Class<?> c = in.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("set");
                f.setAccessible(true);
                return f.get(in);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static Object inValue(AtomicSetBasedOperation in, int i, Class<?> type) {
        if (type == Integer.class || type == int.class) {
            return Integer.valueOf(in.getSetValueAsInt(i));
        }
        if (type == Long.class || type == long.class) {
            return Long.valueOf(in.getSetValueAsLong(i));
        }
        if (type == Short.class || type == short.class) {
            return Short.valueOf(in.getSetValueAsShort(i));
        }
        if (type == Byte.class || type == byte.class) {
            return Byte.valueOf(in.getSetValueAsByte(i));
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.valueOf(in.getSetValueAsBoolean(i));
        }
        if (type == Double.class || type == double.class) {
            return Double.valueOf(in.getSetValueAsDouble(i));
        }
        if (type == Float.class || type == float.class) {
            return Float.valueOf(in.getSetValueAsFloat(i));
        }
        if (type == Character.class || type == char.class) {
            return Character.valueOf(in.getSetValueAsChar(i));
        }
        if (type == String.class) {
            return in.getSetValueAsString(i);
        }
        if (BigDecimal.class.isAssignableFrom(type)) {
            return in.getSetValueAsBigDecimal(i);
        }
        if (java.sql.Timestamp.class.isAssignableFrom(type)) {
            return in.getSetValueAsTimestamp(i);
        }
        if (java.util.Date.class.isAssignableFrom(type)) {
            return in.getSetValueAsDate(i);
        }
        try {
            return in.getSetValueAsString(i);
        } catch (RuntimeException e) {
            return in.getSetValueAsInt(i);
        }
    }

    private static boolean classNameContains(Operation op, String token) {
        return op.getClass().getSimpleName().contains(token);
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
