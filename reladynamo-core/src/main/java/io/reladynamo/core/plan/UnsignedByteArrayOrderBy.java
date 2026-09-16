package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.finder.orderby.AttributeBasedOrderBy;

/**
 * Reladomo-compatible {@code OrderBy} for {@code byte[]} that matches DynamoDB binary
 * order: unsigned lexicographic, then shorter first.
 *
 * <p>Reladomo 18.1.0 {@code ByteArrayOrderBy.compareWith} loops to the longer array's
 * length and reads the shorter array at that index, so empty vs non-empty (and any
 * prefix pair) throws {@code ArrayIndexOutOfBoundsException}. It also subtracts
 * signed bytes, so {@code 0x80} sorts before {@code 0x01}. Neither is DynamoDB's
 * order, and the crash is what finding 30's MATCH_H2 hit on fixture V row 2.
 *
 * <p>Extends {@link AttributeBasedOrderBy} so {@code equals} still matches Reladomo's
 * {@code ByteArrayOrderBy} (same attribute and direction) and
 * {@code CachedQuery.hasSameOrderBy} does not re-sort with the broken comparator.
 */
public final class UnsignedByteArrayOrderBy extends AttributeBasedOrderBy {

    public UnsignedByteArrayOrderBy(Attribute attribute, boolean ascending) {
        super(attribute, ascending);
    }

    @Override
    protected int compareAscending(Object left, Object right) {
        return RowOrderComparator.compareValues(
                getAttribute().valueOf(left), getAttribute().valueOf(right));
    }
}
