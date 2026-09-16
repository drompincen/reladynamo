package io.reladynamo.core.plan;

import java.util.Objects;

/**
 * One OrderBy column, derived from Reladomo {@code AttributeBasedOrderBy.getAttribute()} and
 * {@code equals(attribute.ascendingOrderBy())} — never from {@code OrderBy.toString()}.
 */
public final class SortTerm {

    private final String attributeName;
    private final boolean ascending;

    public SortTerm(String attributeName, boolean ascending) {
        this.attributeName = Objects.requireNonNull(attributeName, "attributeName");
        this.ascending = ascending;
    }

    public String attributeName() {
        return attributeName;
    }

    public boolean ascending() {
        return ascending;
    }
}
