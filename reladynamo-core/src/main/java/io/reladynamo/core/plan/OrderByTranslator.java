package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.finder.orderby.AttributeBasedOrderBy;
import com.gs.fw.common.mithra.finder.orderby.ByteArrayOrderBy;
import com.gs.fw.common.mithra.finder.orderby.ChainedOrderBy;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.plan.reladomo.ReladomoOperationAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns a Reladomo {@link OrderBy} into sort terms using the 18.1.0 public API
 * ({@code AttributeBasedOrderBy.getAttribute()}, {@code Attribute.ascendingOrderBy()})
 * plus the pinned private {@code ChainedOrderBy.orderBys} field. Never parses
 * {@code OrderBy.toString()}.
 */
public final class OrderByTranslator {

    private OrderByTranslator() {
    }

    public static List<SortTerm> terms(OrderBy orderBy) {
        if (orderBy == null) {
            return Collections.emptyList();
        }
        List<SortTerm> out = new ArrayList<SortTerm>();
        collect(orderBy, out);
        return out;
    }

    public static RowOrderComparator comparator(OrderBy orderBy) {
        return new RowOrderComparator(terms(orderBy));
    }

    /**
     * Reladomo 18.1.0 {@code ByteArrayOrderBy} indexes the shorter array with the longer
     * array's length, so empty vs non-empty (and any prefix pair) throws
     * {@code ArrayIndexOutOfBoundsException}. It also subtracts signed bytes, which is
     * not DynamoDB's unsigned lexicographic binary order.
     *
     * <p>Replaces each {@code ByteArrayOrderBy} with {@link UnsignedByteArrayOrderBy}.
     * Other terms, including {@link ChainedOrderBy} structure, are preserved.
     * {@code AttributeBasedOrderBy.equals} ignores the concrete class, so Reladomo
     * {@code CachedQuery.hasSameOrderBy} still matches.
     */
    public static OrderBy withUnsignedByteArrayOrder(OrderBy orderBy) {
        if (orderBy == null) {
            return null;
        }
        if (orderBy instanceof UnsignedByteArrayOrderBy) {
            return orderBy;
        }
        if (orderBy instanceof ChainedOrderBy) {
            OrderBy[] parts = ReladomoOperationAccess.chainedOrderBys((ChainedOrderBy) orderBy);
            if (parts.length == 0) {
                return orderBy;
            }
            OrderBy result = withUnsignedByteArrayOrder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                result = result.and(withUnsignedByteArrayOrder(parts[i]));
            }
            return result;
        }
        if (orderBy instanceof ByteArrayOrderBy) {
            AttributeBasedOrderBy abo = (AttributeBasedOrderBy) orderBy;
            boolean ascending = abo.equals(abo.getAttribute().ascendingOrderBy());
            return new UnsignedByteArrayOrderBy(abo.getAttribute(), ascending);
        }
        return orderBy;
    }

    public static boolean matchesNativeSk(List<SortTerm> terms, PhysicalDesign design) {
        if (terms == null || terms.isEmpty() || design == null) {
            return false;
        }
        List<String> sk = nativeSkAttributeNames(design);
        if (sk.isEmpty() || sk.size() != terms.size()) {
            return false;
        }
        boolean firstAscending = terms.get(0).ascending();
        for (int i = 0; i < terms.size(); i++) {
            SortTerm term = terms.get(i);
            if (!sk.get(i).equals(term.attributeName())) {
                return false;
            }
            if (term.ascending() != firstAscending) {
                return false;
            }
        }
        return true;
    }

    public static boolean allAscending(List<SortTerm> terms) {
        if (terms == null || terms.isEmpty()) {
            return true;
        }
        for (int i = 0; i < terms.size(); i++) {
            if (!terms.get(i).ascending()) {
                return false;
            }
        }
        return true;
    }

    static List<String> nativeSkAttributeNames(PhysicalDesign design) {
        List<String> names = new ArrayList<String>(2);
        TemporalMapping.Flavour flavour = design.temporal().flavour();
        if (flavour == TemporalMapping.Flavour.BITEMPORAL
                || flavour == TemporalMapping.Flavour.AUDIT_ONLY) {
            names.add(design.processingFromJavaName());
        }
        if (flavour == TemporalMapping.Flavour.BITEMPORAL
                || flavour == TemporalMapping.Flavour.BUSINESS_ONLY) {
            names.add(design.businessFromJavaName());
        }
        return names;
    }

    private static void collect(OrderBy orderBy, List<SortTerm> out) {
        if (orderBy instanceof ChainedOrderBy) {
            OrderBy[] parts = ReladomoOperationAccess.chainedOrderBys((ChainedOrderBy) orderBy);
            for (int i = 0; i < parts.length; i++) {
                collect(parts[i], out);
            }
            return;
        }
        if (orderBy instanceof AttributeBasedOrderBy) {
            out.add(term((AttributeBasedOrderBy) orderBy));
            return;
        }
        throw new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-009: OrderBy " + orderBy.getClass().getName()
                        + " is not an AttributeBasedOrderBy or ChainedOrderBy; refusing to guess from toString().");
    }

    private static SortTerm term(AttributeBasedOrderBy orderBy) {
        Attribute attribute = orderBy.getAttribute();
        if (attribute == null) {
            throw new ReladynamoUnplannableOperationException(
                    "RELADYNAMO-PLAN-009: AttributeBasedOrderBy has no attribute.");
        }
        boolean ascending = orderBy.equals(attribute.ascendingOrderBy());
        return new SortTerm(attribute.getAttributeName(), ascending);
    }
}
