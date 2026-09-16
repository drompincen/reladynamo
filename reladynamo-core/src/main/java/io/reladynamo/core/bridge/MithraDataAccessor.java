package io.reladynamo.core.bridge;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.attribute.AsOfAttribute;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges a Reladomo {@link MithraDataObject} to the flat attribute map the codec consumes.
 *
 * <p>Reladomo offers no generic {@code Attribute.valueOf(Object)} and no {@code isAttributeNull} on
 * the base {@code Attribute} class — value extraction is declared on each typed subclass. So this is
 * explicit dispatch, verified against reladomo-18.1.0 rather than assumed.
 *
 * <p>The failure mode to guard against is <b>silent omission</b>: an attribute type nobody dispatched
 * would simply not appear in the map, and the item would persist with a field missing rather than
 * failing. So an unrecognised attribute type throws — a loud failure at the boundary beats a quietly
 * incomplete row that is only noticed when someone reads it back.
 *
 * <p>Java 11 baseline.
 */
public final class MithraDataAccessor {

    /** Reladomo names the boundary columns <axis>From / <axis>To — not "Thru". */
    private static final String[] TEMPORAL_SUFFIXES = {"From", "To"};

    private MithraDataAccessor() {
    }

    /** Extracts every persistent (non-temporal) attribute, preserving declaration order. */
    public static Map<String, Object> extract(RelatedFinder finder, MithraDataObject data) {
        if (finder == null) {
            throw new IllegalArgumentException(
                    "finder is required: without it there is no attribute list, and returning an "
                            + "empty map would silently persist an item with no fields");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Attribute[] attributes = finder.getPersistentAttributes();
        if (attributes == null) {
            return out;
        }
        for (Attribute a : attributes) {
            out.put(a.getAttributeName(), valueOf(a, data));
        }
        return out;
    }

    /**
     * Extracts the temporal boundaries — up to four values, which are exactly what the differential
     * gate compares. Returns an empty map for a non-dated entity.
     */
    public static Map<String, Object> extractTemporal(RelatedFinder finder, MithraDataObject data) {
        if (finder == null) {
            throw new IllegalArgumentException("finder is required");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        AsOfAttribute[] asOf = finder.getAsOfAttributes();
        if (asOf == null || data == null) {
            return out;
        }
        // getPersistentAttributes() already carries the temporal columns, and reading them through
        // that list is the path Reladomo actually populates. Going via AsOfAttribute.getFromAttribute()
        // returns null here, so this filters the working extraction rather than duplicating it.
        Map<String, Object> all = extract(finder, data);
        for (AsOfAttribute a : asOf) {
            String name = a.getAttributeName();
            for (String suffix : TEMPORAL_SUFFIXES) {
                String key = name + suffix;
                if (all.containsKey(key)) {
                    out.put(key, all.get(key));
                }
            }
        }
        return out;
    }

    /**
     * Typed dispatch. Ordered most-common-first; {@code BigDecimalAttribute} is checked before the
     * numeric primitives because it is a NonPrimitiveNumericAttribute and must not be mistaken for one.
     */
    private static Object valueOf(Attribute attribute, MithraDataObject data) {
        if (data == null) {
            return null;
        }
        if (attribute instanceof StringAttribute) {
            return ((StringAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof IntegerAttribute) {
            return ((IntegerAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof LongAttribute) {
            return ((LongAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof TimestampAttribute) {
            return ((TimestampAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof BigDecimalAttribute) {
            return ((BigDecimalAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof DoubleAttribute) {
            return ((DoubleAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof FloatAttribute) {
            return ((FloatAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof BooleanAttribute) {
            return ((BooleanAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof ShortAttribute) {
            return ((ShortAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof ByteAttribute) {
            return ((ByteAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof CharAttribute) {
            return ((CharAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof DateAttribute) {
            return ((DateAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof TimeAttribute) {
            return ((TimeAttribute) attribute).valueOf(data);
        }
        if (attribute instanceof ByteArrayAttribute) {
            return ((ByteArrayAttribute) attribute).valueOf(data);
        }
        throw new IllegalStateException(
                "no extractor for attribute '" + attribute.getAttributeName() + "' of type "
                        + attribute.getClass().getName()
                        + ". Add it to MithraDataAccessor rather than letting it be omitted: a missing "
                        + "dispatch silently drops the column from the persisted item.");
    }
}
