package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraObject;
import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.extractor.Extractor;
import com.gs.fw.common.mithra.finder.RelatedFinder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value snapshot of mapped attributes. Primitive null flags are preserved;
 * byte arrays compare by content; timestamps by epoch millis and nanos; floats/doubles
 * by raw bits; decimals by unscaled value and scale.
 */
final class TypedSnapshot {

    final Map<String, Object> values;

    TypedSnapshot(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }

    static TypedSnapshot ofObject(RelatedFinder finder, Object item) {
        MithraObject mithra = (MithraObject) item;
        return ofData(finder, mithra.zGetCurrentData());
    }

    static TypedSnapshot ofData(RelatedFinder finder, MithraDataObject data) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Attribute[] attributes = finder.getPersistentAttributes();
        for (int i = 0; i < attributes.length; i++) {
            Attribute attribute = attributes[i];
            String name = attribute.getAttributeName();
            Extractor extractor = (Extractor) attribute;
            if (extractor.isAttributeNull(data)) {
                out.put(name, null);
            } else {
                out.put(name, copyValue(extractor.valueOf(data)));
            }
        }
        return new TypedSnapshot(out);
    }

    static TypedSnapshot ofManifest(ValueRow row) {
        return new TypedSnapshot(copyMap(row.toMap()));
    }

    int rowId() {
        Object v = values.get("rowId");
        return v instanceof Integer ? ((Integer) v).intValue() : -1;
    }

    int scopeId() {
        Object v = values.get("scopeId");
        return v instanceof Integer ? ((Integer) v).intValue() : -1;
    }

    Object get(String name) {
        return values.get(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypedSnapshot)) {
            return false;
        }
        TypedSnapshot other = (TypedSnapshot) o;
        if (values.size() != other.values.size()) {
            return false;
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!valueEquals(e.getValue(), other.values.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            h = 31 * h + Objects.hashCode(e.getKey());
            h = 31 * h + valueHash(e.getValue());
        }
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append('=').append(format(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    static boolean valueEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof byte[] && b instanceof byte[]) {
            return Arrays.equals((byte[]) a, (byte[]) b);
        }
        if (a instanceof Timestamp && b instanceof Timestamp) {
            Timestamp ta = (Timestamp) a;
            Timestamp tb = (Timestamp) b;
            return ta.getTime() == tb.getTime() && ta.getNanos() == tb.getNanos();
        }
        if (a instanceof Double && b instanceof Double) {
            return Double.doubleToRawLongBits(((Double) a).doubleValue())
                    == Double.doubleToRawLongBits(((Double) b).doubleValue());
        }
        if (a instanceof Float && b instanceof Float) {
            return Float.floatToRawIntBits(((Float) a).floatValue())
                    == Float.floatToRawIntBits(((Float) b).floatValue());
        }
        if (a instanceof BigDecimal && b instanceof BigDecimal) {
            BigDecimal da = (BigDecimal) a;
            BigDecimal db = (BigDecimal) b;
            return da.scale() == db.scale() && da.unscaledValue().equals(db.unscaledValue());
        }
        return a.equals(b);
    }

    static int valueHash(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof byte[]) {
            return Arrays.hashCode((byte[]) v);
        }
        if (v instanceof Timestamp) {
            Timestamp t = (Timestamp) v;
            return 31 * Long.hashCode(t.getTime()) + t.getNanos();
        }
        if (v instanceof Double) {
            return Long.hashCode(Double.doubleToRawLongBits(((Double) v).doubleValue()));
        }
        if (v instanceof Float) {
            return Float.floatToRawIntBits(((Float) v).floatValue());
        }
        if (v instanceof BigDecimal) {
            BigDecimal d = (BigDecimal) v;
            return 31 * d.unscaledValue().hashCode() + d.scale();
        }
        return v.hashCode();
    }

    static String format(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof byte[]) {
            return "hex(" + toHex((byte[]) v) + ")";
        }
        if (v instanceof Timestamp) {
            Timestamp t = (Timestamp) v;
            return "T(" + t.getTime() + "ns=" + t.getNanos() + ")";
        }
        if (v instanceof Double) {
            return "dbits=" + Long.toHexString(Double.doubleToRawLongBits(((Double) v).doubleValue()));
        }
        if (v instanceof Float) {
            return "fbits=" + Integer.toHexString(Float.floatToRawIntBits(((Float) v).floatValue()));
        }
        if (v instanceof BigDecimal) {
            BigDecimal d = (BigDecimal) v;
            return d.toPlainString() + "s" + d.scale();
        }
        return String.valueOf(v);
    }

    static List<TypedSnapshot> copyList(List<TypedSnapshot> in) {
        return new ArrayList<TypedSnapshot>(in);
    }

    private static Object copyValue(Object v) {
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            return Arrays.copyOf(b, b.length);
        }
        if (v instanceof Timestamp) {
            return ((Timestamp) v).clone();
        }
        return v;
    }

    private static Map<String, Object> copyMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), copyValue(e.getValue()));
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", Integer.valueOf(bytes[i] & 0xff)));
        }
        return sb.toString();
    }
}
