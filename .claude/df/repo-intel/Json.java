import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal deterministic JSON reader/writer.
 *
 * Deliberately dependency-free: repo-intel must run in environments where outbound Maven
 * access is blocked, so pulling Jackson would make the whole subsystem unavailable exactly
 * where graceful degradation matters most. Object key order is insertion order, so the same
 * graph always serialises to the same bytes -- which is what makes incremental-vs-rebuild
 * equivalence checkable by comparing files.
 */
final class Json {
    private Json() {}

    // ---------- writing ----------

    static String write(Object o) { StringBuilder sb = new StringBuilder(); write(o, sb, -1, 0); return sb.toString(); }

    static String writePretty(Object o) { StringBuilder sb = new StringBuilder(); write(o, sb, 2, 0); return sb.toString(); }

    private static void write(Object o, StringBuilder sb, int indent, int depth) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { escape(s, sb); return; }
        if (o instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (o instanceof Number n) { sb.append(num(n)); return; }
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                nl(sb, indent, depth + 1);
                escape(String.valueOf(e.getKey()), sb);
                sb.append(':');
                if (indent >= 0) sb.append(' ');
                write(e.getValue(), sb, indent, depth + 1);
            }
            nl(sb, indent, depth);
            sb.append('}');
            return;
        }
        if (o instanceof Iterable<?> it) {
            List<Object> items = new ArrayList<>();
            for (Object x : it) items.add(x);
            if (items.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                nl(sb, indent, depth + 1);
                write(items.get(i), sb, indent, depth + 1);
            }
            nl(sb, indent, depth);
            sb.append(']');
            return;
        }
        escape(String.valueOf(o), sb);
    }

    private static void nl(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * depth; i++) sb.append(' ');
    }

    private static String num(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return n.toString();
    }

    private static void escape(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- reading ----------

    static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new IllegalArgumentException("trailing content at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    static String str(Object o, String dflt) { return o instanceof String s ? s : dflt; }

    static long lng(Object o, long dflt) { return o instanceof Number n ? n.longValue() : dflt; }

    static int integer(Object o, int dflt) { return o instanceof Number n ? n.intValue() : dflt; }

    static boolean bool(Object o, boolean dflt) { return o instanceof Boolean b ? b : dflt; }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> number();
            };
        }

        Object lit(String w, Object v) {
            if (!s.startsWith(w, i)) throw new IllegalArgumentException("bad literal at " + i);
            i += w.length();
            return v;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i) != ':') throw new IllegalArgumentException("expected : at " + i);
                i++; ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i);
                i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i - 1));
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i);
                i++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i - 1));
            }
        }

        String string() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw new IllegalArgumentException("bad value at " + st);
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                try { return Long.parseLong(t); } catch (NumberFormatException ignored) { /* fall through */ }
            }
            return Double.parseDouble(t);
        }
    }
}
