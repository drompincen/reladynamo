import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offset-level scanning over masked JS/TS source: argument bounds, arity, whether a `(` is a
 * declaration site or a call, and the small amount of local type knowledge (`new Foo()`, type
 * annotations) that lets a receiver be typed without guessing.
 */
final class JsTsScan {

    private JsTsScan() {}
    /** Argument count at a call site. Reads RAW so string-literal args are not blanked. */
    static int arity(char[] s, int lparen) {
        int d = 0, count = 0;
        boolean any = false;
        for (int i = lparen; i < s.length; i++) {
            char c = s[i];
            if (c == '"' || c == '\'' || c == '`') {
                if (d >= 1) any = true;
                char q = c;
                i++;
                while (i < s.length && s[i] != q) {
                    if (s[i] == '\\' && i + 1 < s.length) i += 2;
                    else i++;
                }
                continue;
            }
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) {
                    if (count > 0) return count + 1;
                    return any ? 1 : 0;
                }
            } else if (c == ',' && d == 1) count++;
            else if (!Character.isWhitespace(c) && d >= 1) any = true;
        }
        return -1;
    }
    static String firstStringArg(char[] raw, int lparen) {
        int d = 0;
        for (int i = lparen; i < raw.length; i++) {
            char c = raw[i];
            if (c == '(') d++;
            else if (c == ')') { d--; if (d == 0) return null; }
            else if ((c == '"' || c == '\'' || c == '`') && d == 1) {
                char q = c;
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < raw.length && raw[j] != q) {
                    if (raw[j] == '\\' && j + 1 < raw.length) { sb.append(raw[j + 1]); j += 2; continue; }
                    sb.append(raw[j++]);
                }
                return sb.toString();
            } else if (c == ',' && d == 1) {
                return null; // first arg not a string
            }
        }
        return null;
    }
    /** Start/end offsets of the n-th argument (0-based) inside call at lparen. */
    static int[] nthArgBounds(char[] s, int lparen, int n) {
        int d = 0, arg = 0, start = lparen + 1;
        for (int i = lparen; i < s.length; i++) {
            char c = s[i];
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) {
                    if (arg == n) return new int[]{start, i};
                    return null;
                }
            } else if (c == ',' && d == 1) {
                if (arg == n) return new int[]{start, i};
                arg++;
                start = i + 1;
            } else if (c == '{' || c == '[') {
                // depth via matching would be better; approximate with d using same counter by scanning
                int m = JsTsSyntax.matching(new String(s, i, s.length - i), 0);
                if (m > 0) i += m;
            }
        }
        return null;
    }
    static boolean precededByNew(char[] s, int nameStart) {
        int i = nameStart;
        while (i > 0 && Character.isWhitespace(s[i - 1])) i--;
        return i >= 3 && new String(s, i - 3, 3).equals("new")
                && (i - 4 < 0 || !Lex.isIdentPart(s[i - 4]));
    }
    static boolean isFunctionExpr(String rhs) {
        String r = rhs.trim();
        if (r.startsWith("async ")) r = r.substring(6).trim();
        if (r.startsWith("function")) return true;
        // arrow: (...) =>  /  (...): T =>  /  id =>
        int arrow = r.indexOf("=>");
        if (arrow < 0) return false;
        String before = r.substring(0, arrow).trim();
        if (before.isEmpty()) return false;
        if (before.startsWith("(")) return true;
        if (Lex.isIdentStart(before.charAt(0)) && before.indexOf(' ') < 0 && before.indexOf(':') < 0) return true;
        // bare name with optional type is rare for arrow; accept ends with )
        return before.endsWith(")") || before.endsWith(">");
    }
    static List<String> extractParamsFromFn(String rhs) {
        List<String> out = new ArrayList<>();
        String r = rhs.trim();
        if (r.startsWith("async ")) r = r.substring(6).trim();
        if (r.startsWith("function")) {
            int lp = r.indexOf('(');
            if (lp < 0) return out;
            int rp = JsTsSyntax.matching(r, lp);
            if (rp < 0) return out;
            for (String p : JsTsSyntax.splitTop(r.substring(lp + 1, rp), ',')) {
                if (!p.isBlank()) out.add(p.trim());
            }
            return out;
        }
        int arrow = r.indexOf("=>");
        if (arrow < 0) return out;
        String before = r.substring(0, arrow).trim();
        // strip return type on arrow params: (s: string): string =>
        // before is params side
        if (before.startsWith("(")) {
            int rp = JsTsSyntax.matching(before, 0);
            if (rp > 0) {
                for (String p : JsTsSyntax.splitTop(before.substring(1, rp), ',')) {
                    if (!p.isBlank()) out.add(p.trim());
                }
            }
        } else {
            String id = JsTsSyntax.firstIdent(before);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }
    /**
     * Returns [type, name, accessModifierOrNull].
     */
    static String[] splitTsParam(String p) {
        String s = p.trim();
        if (s.isEmpty()) return null;
        // strip leading modifiers for parameter properties
        String access = null;
        for (String m : new String[]{"private", "public", "protected", "readonly"}) {
            if (s.startsWith(m + " ") || s.startsWith(m + "\t")) {
                if (!"readonly".equals(m)) access = m;
                s = s.substring(m.length()).trim();
            }
        }
        if (s.startsWith("...")) s = s.substring(3).trim();
        // name: type = default
        int eq = JsTsSyntax.indexOfTop(s, '=');
        if (eq >= 0) s = s.substring(0, eq).trim();
        int colon = JsTsSyntax.indexOfTop(s, ':');
        String name;
        String type = null;
        if (colon >= 0) {
            name = s.substring(0, colon).trim();
            type = s.substring(colon + 1).trim();
        } else {
            name = s.trim();
        }
        // drop optional ?
        if (name.endsWith("?")) name = name.substring(0, name.length() - 1).trim();
        name = JsTsSyntax.firstIdent(name);
        if (name.isEmpty()) return null;
        return new String[]{type, name, access};
    }
    /**
     * True when {@code name(} is a function/method/constructor declaration head rather than a call.
     * Uses: leading keywords, typed parameter lists, or a body `{` after an optional return type.
     */
    static boolean isDeclarationSite(char[] mask, int nameStart, String name, int lparen) {
        if ("constructor".equals(name)) return true;
        int i = nameStart;
        while (i > 0 && Character.isWhitespace(mask[i - 1])) i--;
        // generator: function *name(
        if (i > 0 && mask[i - 1] == '*') {
            int k = i - 1;
            while (k > 0 && Character.isWhitespace(mask[k - 1])) k--;
            String prevStar = Lex.identBefore(mask, k);
            if ("function".equals(prevStar)) return true;
        }
        String prev = Lex.identBefore(mask, i);
        if ("function".equals(prev) || "async".equals(prev) || "static".equals(prev)
                || "get".equals(prev) || "set".equals(prev)
                || "public".equals(prev) || "private".equals(prev) || "protected".equals(prev)
                || "readonly".equals(prev) || "abstract".equals(prev) || "override".equals(prev)
                || "declare".equals(prev) || "export".equals(prev) || "default".equals(prev)) {
            return true;
        }
        int rp = JsTsSyntax.matchParenArr(mask, lparen);
        if (rp < 0) return false;
        // TypeScript typed parameters only appear on declarations
        if (JsTsSyntax.hasTopLevelColon(mask, lparen + 1, rp)) return true;
        int j = JsTsSyntax.nextNonWs(mask, rp + 1);
        if (j < mask.length && mask[j] == ':') {
            j = JsTsSyntax.skipTsType(mask, j + 1);
            j = JsTsSyntax.nextNonWs(mask, j);
        }
        return j < mask.length && mask[j] == '{';
    }
    /**
     * True when `{` opens an import/export name list, not a declaration body.
     * Matches {@code import { a }}, {@code import type { T }}, {@code export { a } from},
     * {@code export type { T } from}. Does NOT match {@code export class}/{@code function}/{@code enum}.
     */
    static boolean looksLikeImportExport(String head) {
        String t = head.trim();
        if (t.startsWith("import") && (t.length() == 6 || !Lex.isIdentPart(t.charAt(6)))) {
            // import / import type / import X,  — brace list of bindings
            return true;
        }
        if (t.startsWith("export") && (t.length() == 6 || !Lex.isIdentPart(t.charAt(6)))) {
            String rest = t.substring(6).trim();
            if (rest.isEmpty()) return true;                 // export {
            if (rest.startsWith("{")) return true;            // export { a } / export { a } from "m"
            if (rest.startsWith("type")) {
                String after = rest.substring(4).trim();
                // export type { ... }  vs  export type Name =
                return after.isEmpty() || after.startsWith("{");
            }
            // export default { ... } is an expression, not a name-list — treat as body
            if (rest.startsWith("default")) return false;
            // export const/function/class/enum/interface/async — declaration bodies
            return false;
        }
        return false;
    }
    static String stripExportPrefix(String t, JsTsExtractor.Decl d) {
        String s = t;
        while (true) {
            if (s.startsWith("export ")) {
                d.isExport = true;
                s = s.substring(7).trim();
                if (s.startsWith("default ")) {
                    d.isDefaultExport = true;
                    s = s.substring(8).trim();
                }
                continue;
            }
            if (s.startsWith("declare ")) { s = s.substring(8).trim(); continue; }
            if (s.startsWith("async ")) {
                d.modifiers.add("async");
                s = s.substring(6).trim();
                continue;
            }
            break;
        }
        return s;
    }
    /**
     * The type of `... = new Foo(...)`. An explicit constructor call states the type as firmly
     * as an annotation does, and in JS it is often the only thing that does.
     */
    static String constructedType(String head) {
        int eq = head.indexOf('=');
        if (eq < 0) return null;
        String rhs = head.substring(eq + 1).trim();
        if (!rhs.startsWith("new ")) return null;
        String rest = rhs.substring(4).trim();
        int end = 0;
        while (end < rest.length() && (Lex.isIdentPart(rest.charAt(end)) || rest.charAt(end) == '.')) end++;
        if (end == 0) return null;
        String t = rest.substring(0, end);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
    static String findNodeIdByName(Extractor.FileFacts f, String name) {
        for (GraphModel.Node n : f.nodes) {
            if (name.equals(n.name) && (n.qname.equals(name) || n.qname.endsWith("." + name))) {
                return n.id;
            }
        }
        // also check exports already bound
        if (f.exports.containsKey(name)) {
            String id = f.exports.get(name);
            if (id != null && !id.isEmpty()) return id;
        }
        return null;
    }
    static String lookupVar(JsTsExtractor.Scope s, String receiver) {
        String head = receiver.contains(".") ? receiver.substring(0, receiver.indexOf('.')) : receiver;
        if ("this".equals(head)) {
            for (JsTsExtractor.Scope p = s; p != null; p = p.parent) {
                if (p.vars.containsKey("this")) return p.vars.get("this");
                if (p.node != null && ("class".equals(p.type))) return p.qname;
            }
            return null;
        }
        for (JsTsExtractor.Scope p = s; p != null; p = p.parent) {
            String t = p.vars.get(head);
            if (t != null) return t;
        }
        return null;
    }
}
