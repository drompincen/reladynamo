package io.reladynamo.core.bridge;

import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.finder.RelatedFinder;

import java.sql.Timestamp;

/**
 * Resolves an entity's infinity sentinel from its <b>generated attribute</b>, which is the only
 * source of truth for it.
 *
 * <p>{@code MithraObjectXmlParser} cannot classload an
 * {@code infinityDate="[com.gs...getDefaultInfinity()]"} snippet — that is a Java expression in an
 * XML attribute — so it substitutes a conventional UTC sentinel. Reladomo's actual value is
 * {@code 9999-12-01 23:59:00} in the <b>JVM default timezone</b>. Those agree in UTC and nowhere
 * else.
 *
 * <p>That disagreement caused finding 12: Reladomo's infinity special case was skipped because an
 * equality check compared the parser's sentinel against Reladomo's, and the current-row query
 * silently returned nothing. Patching the comparison site fixed that query; this fixes the cause, so
 * every other comparison of infinity is right by construction rather than by having been found.
 *
 * <p>Java 11 baseline.
 */
public final class InfinityResolver {

    private InfinityResolver() {
    }

    /**
     * @return the entity's infinity, or {@code null} for a non-dated entity
     */
    public static Timestamp resolve(RelatedFinder finder) {
        if (finder == null) {
            throw new IllegalArgumentException(
                    "finder is required to resolve infinity. Substituting a default is what caused "
                            + "finding 12 — the value must come from the generated attribute.");
        }
        AsOfAttribute[] asOf = finder.getAsOfAttributes();
        if (asOf == null || asOf.length == 0) {
            return null;
        }
        Timestamp infinity = asOf[0].getInfinityDate();
        for (int i = 1; i < asOf.length; i++) {
            Timestamp other = asOf[i].getInfinityDate();
            if (infinity != null && other != null && !infinity.equals(other)) {
                throw new IllegalStateException(
                        "axes disagree on infinity: " + asOf[0].getAttributeName() + "=" + infinity
                                + " but " + asOf[i].getAttributeName() + "=" + other
                                + ". The adapter assumes one sentinel per entity.");
            }
        }
        return infinity == null ? null : (Timestamp) infinity.clone();
    }
}
