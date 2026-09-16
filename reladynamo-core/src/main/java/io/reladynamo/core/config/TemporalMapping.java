package io.reladynamo.core.config;

import java.sql.Timestamp;

/**
 * How one entity's temporal axes are stored. Mirrors Reladomo's {@code AsOfAttribute} declarations.
 *
 * <p>The flavour is derived from the object model XML, never guessed: it selects which
 * {@code TemporalDirector} Reladomo will drive, and the adapter must not second-guess that choice.
 */
public final class TemporalMapping {

    public enum Flavour {
        /** businessDate + processingDate. */
        BITEMPORAL,
        /** processingDate only. */
        AUDIT_ONLY,
        /** businessDate only, non-audited. */
        BUSINESS_ONLY,
        /** No AsOfAttribute at all. */
        NONE
    }

    private final Flavour flavour;
    private final Timestamp infinity;

    private TemporalMapping(Flavour flavour, Timestamp infinity) {
        this.flavour = flavour;
        this.infinity = infinity;
    }

    public static TemporalMapping none() {
        return new TemporalMapping(Flavour.NONE, null);
    }

    public static TemporalMapping of(Flavour flavour, Timestamp infinity) {
        if (flavour == null) {
            throw new IllegalArgumentException("flavour is required");
        }
        if (flavour != Flavour.NONE && infinity == null) {
            throw new IllegalArgumentException(
                    "a temporal entity needs its infinity sentinel: Reladomo compares against it by "
                            + "value, so it must come from the object model, not a default");
        }
        return new TemporalMapping(flavour, infinity == null ? null : (Timestamp) infinity.clone());
    }

    public Flavour flavour() {
        return flavour;
    }

    /** Defensive copy — {@link Timestamp} is mutable and this value is shared configuration. */
    public Timestamp infinity() {
        return infinity == null ? null : (Timestamp) infinity.clone();
    }

    public boolean hasBusinessDate() {
        return flavour == Flavour.BITEMPORAL || flavour == Flavour.BUSINESS_ONLY;
    }

    public boolean hasProcessingDate() {
        return flavour == Flavour.BITEMPORAL || flavour == Flavour.AUDIT_ONLY;
    }
}
