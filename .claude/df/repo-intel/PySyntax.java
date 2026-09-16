import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure text helpers for Python source: indentation, identifiers, dotted names, argument counting
 * and module-path derivation. No state, no graph -- split out of PythonExtractor when it outgrew
 * the project's 500-line limit.
 */
final class PySyntax {

    private PySyntax() {}
    static String modulePath(String relPath) {
        if (relPath == null) return "";
        String p = relPath.replace('\\', '/');
        if (p.startsWith("./")) p = p.substring(2);
        if (p.endsWith(".py")) p = p.substring(0, p.length() - 3);
        else if (p.endsWith(".pyi")) p = p.substring(0, p.length() - 4);
        String[] parts = p.split("/");
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            if (s.isEmpty() || ".".equals(s)) continue;
            out.add(s);
        }
        if (!out.isEmpty() && "__init__".equals(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return String.join(".", out);
    }
    static boolean isTestFile(String path) {
        if (path == null) return false;
        String p = path.replace('\\', '/');
        String base = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
        return p.startsWith("tests/") || p.contains("/tests/")
                || base.startsWith("test_") || base.endsWith("_test.py") || base.endsWith("_test.pyi");
    }
    static String visibility(String name) {
        return name != null && name.startsWith("_") ? "private" : "public";
    }
    static String simpleName(String qname) {
        if (qname == null || qname.isEmpty()) return "";
        int i = qname.lastIndexOf('.');
        return i < 0 ? qname : qname.substring(i + 1);
    }
    static boolean isUpperName(String name) {
        if (name == null || name.isEmpty()) return false;
        boolean hasLetter = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) return false;
            } else if (c != '_' && !Character.isDigit(c)) {
                return false;
            }
        }
        return hasLetter;
    }
    static boolean isIdent(String s) {
        if (s == null || s.isEmpty() || !Lex.isIdentStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Lex.isIdentPart(s.charAt(i))) return false;
        return true;
    }
    static boolean isDottedName(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String p : s.split("\\.")) if (!isIdent(p)) return false;
        return true;
    }
    static String takeDottedName(String s) {
        if (s == null) return "";
        s = s.trim();
        int i = 0;
        while (i < s.length() && (Lex.isIdentPart(s.charAt(i)) || s.charAt(i) == '.')) i++;
        String t = s.substring(0, i);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t;
    }
    static String firstIdent(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length() && !Lex.isIdentStart(s.charAt(i))) {
            if (!Character.isWhitespace(s.charAt(i))) return "";
            i++;
        }
        int j = i;
        while (j < s.length() && Lex.isIdentPart(s.charAt(j))) j++;
        return s.substring(i, j);
    }
    static boolean isKeywordAt(String t, String kw) {
        if (!t.startsWith(kw)) return false;
        if (t.length() == kw.length()) return true;
        char c = t.charAt(kw.length());
        return !Lex.isIdentPart(c);
    }
    static boolean isBlankLine(String ml) {
        for (int i = 0; i < ml.length(); i++) {
            char c = ml.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') return false;
        }
        return true;
    }
    static int countIndent(String line) {
        int i = 0, n = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ') { n++; i++; }
            else if (c == '\t') { n += 4; i++; }
            else break;
        }
        return n;
    }
    static boolean endsWithBackslash(String line) {
        int i = line.length() - 1;
        while (i >= 0 && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\r')) i--;
        return i >= 0 && line.charAt(i) == '\\';
    }
    static int[] depthDelta(String line) {
        int p = 0, b = 0, c = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '(') p++;
            else if (ch == ')') p--;
            else if (ch == '[') b++;
            else if (ch == ']') b--;
            else if (ch == '{') c++;
            else if (ch == '}') c--;
        }
        return new int[]{p, b, c};
    }
    static int lineStart(int[] li, int line) {
        if (line < 0) return 0;
        if (line >= li.length) return li[li.length - 1];
        return li[line];
    }
    /**
     * If RHS is a simple constructor-like Call {@code TypeName(...)}, return the type name.
     * Only Capitalized callees count — {@code untyped_factory()} must not become a known type
     * (see ground-truth negative for mystery.save).
     */
    static String constructorName(String rhs) {
        if (rhs == null) return null;
        String t = rhs.trim();
        int lp = t.indexOf('(');
        if (lp <= 0) return null;
        String callee = t.substring(0, lp).trim();
        if (!isDottedName(callee) && !isIdent(callee)) return null;
        String simple = callee.contains(".") ? callee.substring(callee.lastIndexOf('.') + 1) : callee;
        if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))) return null;
        int rp = matching(t, lp);
        if (rp < 0) return null;
        String after = t.substring(rp + 1).trim();
        if (!after.isEmpty() && !after.startsWith("#")) return null;
        return baseType(callee);
    }
    static String baseType(String t) {
        if (t == null) return null;
        String s = t.trim();
        if (s.startsWith("\"") || s.startsWith("'")) {
            s = s.substring(1);
            if (s.endsWith("\"") || s.endsWith("'")) s = s.substring(0, s.length() - 1);
        }
        // drop Callable[...] / list[...] style
        s = stripGenerics(s).trim();
        // Union / Optional leftovers: take first segment before |
        int pipe = s.indexOf('|');
        if (pipe >= 0) s = s.substring(0, pipe).trim();
        s = takeDottedName(s);
        return s.isEmpty() ? null : s;
    }
    /** Split "name: Type = default" -> [name, type or null]. */
    static String[] splitPyParam(String p) {
        String s = p.trim();
        if (s.startsWith("**")) s = s.substring(2).trim();
        else if (s.startsWith("*")) s = s.substring(1).trim();
        if (s.isEmpty()) return null;
        int eq = indexOfTop(s, '=');
        if (eq >= 0) s = s.substring(0, eq).trim();
        int colon = indexOfTop(s, ':');
        String name;
        String type = null;
        if (colon >= 0) {
            name = s.substring(0, colon).trim();
            type = s.substring(colon + 1).trim();
        } else {
            name = s.trim();
        }
        name = firstIdent(name);
        if (name.isEmpty()) return null;
        if (type != null) type = baseType(type);
        return new String[]{name, type};
    }
    static String stripGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        for (char c : s.toCharArray()) {
            if (c == '[') d++;
            else if (c == ']') { d = Math.max(0, d - 1); sb.append(' '); }
            else if (d == 0) sb.append(c);
        }
        return sb.toString();
    }
    static int matching(String s, int open) {
        if (open < 0 || open >= s.length()) return -1;
        char o = s.charAt(open);
        char c = o == '(' ? ')' : o == '[' ? ']' : o == '{' ? '}' : 0;
        if (c == 0) return -1;
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) d++;
            else if (s.charAt(i) == c) {
                d--;
                if (d == 0) return i;
            }
        }
        return -1;
    }
    static int indexOfTop(String s, char target) {
        int p = 0, b = 0, c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') p++;
            else if (ch == ')') p--;
            else if (ch == '[') b++;
            else if (ch == ']') b--;
            else if (ch == '{') c++;
            else if (ch == '}') c--;
            else if (ch == target && p == 0 && b == 0 && c == 0) return i;
        }
        return -1;
    }
    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int p = 0, b = 0, c = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') p++;
            else if (ch == ')') p--;
            else if (ch == '[') b++;
            else if (ch == ']') b--;
            else if (ch == '{') c++;
            else if (ch == '}') c--;
            else if (ch == sep && p == 0 && b == 0 && c == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
    static int findWord(String s, String w) {
        int from = 0;
        while (true) {
            int i = s.indexOf(w, from);
            if (i < 0) return -1;
            boolean lb = i == 0 || !Lex.isIdentPart(s.charAt(i - 1));
            int e = i + w.length();
            boolean rb = e >= s.length() || !Lex.isIdentPart(s.charAt(e));
            if (lb && rb) return i;
            from = i + 1;
        }
    }
    /** First string literal inside decorator/call arguments. */
    static String pathArg(String rawArgs) {
        if (rawArgs == null) return null;
        for (int i = 0; i < rawArgs.length(); i++) {
            char q = rawArgs.charAt(i);
            if (q != '"' && q != '\'') continue;
            int e = i + 1;
            while (e < rawArgs.length() && rawArgs.charAt(e) != q) {
                if (rawArgs.charAt(e) == '\\' && e + 1 < rawArgs.length()) e += 2;
                else e++;
            }
            if (e < rawArgs.length()) return rawArgs.substring(i + 1, e);
        }
        return null;
    }
    static int arity(char[] s, int lparen) {
        int d = 0, count = 0;
        boolean any = false;
        for (int i = lparen; i < s.length; i++) {
            char c = s[i];
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) return any ? count + 1 : 0;
            } else if (c == ',' && d == 1) count++;
            else if (!Character.isWhitespace(c) && d >= 1) any = true;
        }
        return -1;
    }
    /** True when the call sits on a physical line whose first non-ws char is `@`. */
    static boolean isOnDecoratorLine(char[] s, int pos) {
        int i = pos;
        while (i > 0 && s[i - 1] != '\n') i--;
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++;
        return i < s.length && s[i] == '@';
    }
    /** Detect `super().name` where `pos` is the index of the `.` before name. */
    static boolean isSuperDotCall(char[] s, int dotPos) {
        int i = dotPos;
        if (i <= 0 || s[i] != '.') return false;
        i--;
        while (i >= 0 && Character.isWhitespace(s[i])) i--;
        if (i < 0 || s[i] != ')') return false;
        // match back over balanced parens of super(...)
        int d = 0;
        for (; i >= 0; i--) {
            if (s[i] == ')') d++;
            else if (s[i] == '(') {
                d--;
                if (d == 0) {
                    String id = Lex.identBefore(s, i);
                    return "super".equals(id);
                }
            }
        }
        return false;
    }
    static boolean isDeclHeader(char[] s, int nameStart) {
        int i = nameStart;
        while (i > 0 && Character.isWhitespace(s[i - 1])) i--;
        // look for def / class / async before the name
        String prev = Lex.identBefore(s, i);
        return "def".equals(prev) || "class".equals(prev) || "async".equals(prev);
    }
    static List<PythonExtractor.Decorator> parseDecorators(String trimmedMask, String trimmedRaw, int line) {
        List<PythonExtractor.Decorator> out = new ArrayList<>();
        // One logical line may only have one decorator in Python.
        if (!trimmedMask.startsWith("@")) return out;
        String body = trimmedMask.substring(1).trim();
        String rawBody = trimmedRaw.startsWith("@") ? trimmedRaw.substring(1).trim() : trimmedRaw;
        PythonExtractor.Decorator d = new PythonExtractor.Decorator();
        d.line = line;
        int lp = body.indexOf('(');
        String target = lp >= 0 ? body.substring(0, lp).trim() : body.trim();
        // strip trailing junk
        target = takeDottedName(target);
        d.full = target;
        d.name = target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : target;
        if (lp >= 0) {
            int rp = matching(body, lp);
            if (rp > lp && rp <= rawBody.length()) {
                d.rawArgs = rawBody.substring(lp, Math.min(rp + 1, rawBody.length()));
            } else if (lp < rawBody.length()) {
                // fall back: find matching in raw
                int rp2 = matching(rawBody, lp);
                d.rawArgs = rp2 > lp ? rawBody.substring(lp, rp2 + 1) : "";
            } else {
                d.rawArgs = "";
            }
        } else {
            d.rawArgs = "";
        }
        out.add(d);
        return out;
    }
}
