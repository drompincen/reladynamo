package io.reladynamo.core.bridge;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.attribute.BigDecimalAttribute;
import com.gs.fw.common.mithra.attribute.BooleanAttribute;
import com.gs.fw.common.mithra.attribute.ByteArrayAttribute;
import com.gs.fw.common.mithra.attribute.ByteAttribute;
import com.gs.fw.common.mithra.attribute.CharAttribute;
import com.gs.fw.common.mithra.attribute.DateAttribute;
import com.gs.fw.common.mithra.attribute.DoubleAttribute;
import com.gs.fw.common.mithra.attribute.FloatAttribute;
import com.gs.fw.common.mithra.attribute.IntegerAttribute;
import com.gs.fw.common.mithra.attribute.LongAttribute;
import com.gs.fw.common.mithra.attribute.ShortAttribute;
import com.gs.fw.common.mithra.attribute.StringAttribute;
import com.gs.fw.common.mithra.attribute.TimeAttribute;
import com.gs.fw.common.mithra.attribute.TimestampAttribute;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.util.Time;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a decoded item back into a Reladomo {@link MithraDataObject} — the inverse of
 * {@link MithraDataAccessor}.
 *
 * <p>Reladomo declares value setters on the typed attribute subclasses ({@code setValue(T, Integer)}
 * and friends), with nothing generic on the base class, so this mirrors the extractor's explicit
 * dispatch. The two must agree on names and types; a round-trip test pins that, because an extractor
 * and populator that disagree would corrupt data in a way neither side's own tests would catch.
 *
 * <p>Two deliberate refusals:
 * <ul>
 *   <li>An <b>unknown attribute name</b> throws. Ignoring it means the stored item carried data the
 *       object never received.
 *   <li>A <b>null value</b> is written as null rather than skipped. Skipping would leave whatever the
 *       fresh data object happened to hold — a default that reads as real data.
 * </ul>
 *
 * <p>Java 11 baseline.
 */
public final class MithraDataPopulator {

    private MithraDataPopulator() {
    }

    public static void populate(RelatedFinder finder, MithraDataObject data,
                                Map<String, Object> values) {
        if (finder == null) {
            throw new IllegalArgumentException("finder is required to resolve attribute names");
        }
        if (values == null) {
            throw new IllegalArgumentException("values are required");
        }
        Map<String, Attribute> byName = index(finder);
        java.util.Set<String> boundaries = temporalBoundaryNames(finder);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Attribute a = byName.get(e.getKey());
            if (a == null) {
                throw new IllegalArgumentException(
                        "no attribute '" + e.getKey() + "' on this object; known: " + byName.keySet()
                                + ". Ignoring it would mean the stored item carried data the object "
                                + "never received.");
            }
            if (e.getValue() == null && boundaries.contains(e.getKey())) {
                // The generated setter for a temporal boundary compares against infinity and NPEs on
                // null. A persisted bitemporal row always carries all four boundaries, so a null here
                // means the item is malformed — say that, rather than surfacing an NPE from inside
                // generated code where the cause is invisible.
                throw new IllegalArgumentException(
                        "temporal boundary '" + e.getKey() + "' is null; a dated row always carries "
                                + "all of its boundaries, so this item is malformed");
            }
            if (data != null) {
                set(a, data, e.getValue());
            }
        }
    }

    /** Names of the four (or two) boundary columns, which are never legitimately null. */
    private static java.util.Set<String> temporalBoundaryNames(RelatedFinder finder) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        com.gs.fw.common.mithra.attribute.AsOfAttribute[] asOf = finder.getAsOfAttributes();
        if (asOf != null) {
            for (com.gs.fw.common.mithra.attribute.AsOfAttribute a : asOf) {
                out.add(a.getAttributeName() + "From");
                out.add(a.getAttributeName() + "To");
            }
        }
        return out;
    }

    private static Map<String, Attribute> index(RelatedFinder finder) {
        Map<String, Attribute> byName = new LinkedHashMap<>();
        Attribute[] attributes = finder.getPersistentAttributes();
        if (attributes != null) {
            for (Attribute a : attributes) {
                byName.put(a.getAttributeName(), a);
            }
        }
        return byName;
    }

    @SuppressWarnings("unchecked")
    private static void set(Attribute attribute, MithraDataObject data, Object value) {
        if (value == null) {
            attribute.setValueNull(data);
            return;
        }
        if (attribute instanceof StringAttribute) {
            ((StringAttribute) attribute).setValue(data, (String) value);
        } else if (attribute instanceof IntegerAttribute) {
            ((IntegerAttribute) attribute).setValue(data, (Integer) value);
        } else if (attribute instanceof LongAttribute) {
            ((LongAttribute) attribute).setValue(data, (Long) value);
        } else if (attribute instanceof TimestampAttribute) {
            ((TimestampAttribute) attribute).setValue(data, (Timestamp) value);
        } else if (attribute instanceof BigDecimalAttribute) {
            ((BigDecimalAttribute) attribute).setValue(data, (BigDecimal) value);
        } else if (attribute instanceof DoubleAttribute) {
            ((DoubleAttribute) attribute).setValue(data, (Double) value);
        } else if (attribute instanceof FloatAttribute) {
            ((FloatAttribute) attribute).setValue(data, (Float) value);
        } else if (attribute instanceof BooleanAttribute) {
            ((BooleanAttribute) attribute).setValue(data, (Boolean) value);
        } else if (attribute instanceof ShortAttribute) {
            ((ShortAttribute) attribute).setValue(data, (Short) value);
        } else if (attribute instanceof ByteAttribute) {
            ((ByteAttribute) attribute).setValue(data, (Byte) value);
        } else if (attribute instanceof CharAttribute) {
            ((CharAttribute) attribute).setValue(data, (Character) value);
        } else if (attribute instanceof DateAttribute) {
            ((DateAttribute) attribute).setValue(data, (Date) value);
        } else if (attribute instanceof TimeAttribute) {
            ((TimeAttribute) attribute).setValue(data, (Time) value);
        } else if (attribute instanceof ByteArrayAttribute) {
            ((ByteArrayAttribute) attribute).setValue(data, (byte[]) value);
        } else {
            throw new IllegalStateException(
                    "no populator for attribute '" + attribute.getAttributeName() + "' of type "
                            + attribute.getClass().getName()
                            + ". Add it here rather than letting the value be dropped.");
        }
    }
}
