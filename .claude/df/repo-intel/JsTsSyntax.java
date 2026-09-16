import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure text helpers for TypeScript and JavaScript source: identifiers, generics, type
 * annotations, quoted literals and balanced-delimiter scanning. Split out of JsTsExtractor for
 * the project's 500-line limit.
 */
final class JsTsSyntax {

    private JsTsSyntax() {}
    static String fileLanguage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
            return "javascript";
        }
        return "typescript";
    }
    static String stripExt(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot > slash) return path.substring(0, dot);
        return path;
    }
    static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
    static boolean isTestPath(String p) {
        String lower = p.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.contains(".test.") || lower.contains(".spec.")
                || lower.contains("/tests/") || lower.startsWith("tests/")
                || lower.contains("/__tests__/") || lower.startsWith("__tests__/");
    }
    static boolean isTopLevel(JsTsExtractor.Scope cur) {
        return cur != null && "file".equals(cur.type);
    }
    static int matchParenArr(char[] s, int open) {
        if (open < 0 || open >= s.length || s[open] != '(') return -1;
        int d = 0;
        for (int i = open; i < s.length; i++) {
            if (s[i] == '(') d++;
            else if (s[i] == ')') {
                d--;
                if (d == 0) return i;
            }
        }
        return -1;
    }
    static boolean hasTopLevelColon(char[] s, int from, int to) {
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        for (int i = from; i < to; i++) {
            char c = s[i];
            if (c == '(') dParen++;
            else if (c == ')') dParen = Math.max(0, dParen - 1);
            else if (c == '[') dBrack++;
            else if (c == ']') dBrack = Math.max(0, dBrack - 1);
            else if (c == '{') dBrace++;
            else if (c == '}') dBrace = Math.max(0, dBrace - 1);
            else if (c == '<') dAngle++;
            else if (c == '>') dAngle = Math.max(0, dAngle - 1);
            else if (c == ':' && dParen == 0 && dBrack == 0 && dBrace == 0 && dAngle == 0) return true;
        }
        return false;
    }
    static int nextNonWs(char[] s, int i) {
        while (i < s.length && Character.isWhitespace(s[i])) i++;
        return i;
    }
    /** Skip a TypeScript type starting at {@code start}, stopping before a body `{` or terminator. */
    static int skipTsType(char[] s, int start) {
        int j = nextNonWs(s, start);
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        boolean seen = false;
        while (j < s.length) {
            char c = s[j];
            if (c == '(') { dParen++; seen = true; j++; continue; }
            if (c == ')') {
                if (dParen == 0 && dAngle == 0 && dBrack == 0 && dBrace == 0) return j;
                dParen = Math.max(0, dParen - 1);
                j++;
                continue;
            }
            if (c == '[') { dBrack++; seen = true; j++; continue; }
            if (c == ']') { dBrack = Math.max(0, dBrack - 1); j++; continue; }
            if (c == '<') { dAngle++; seen = true; j++; continue; }
            if (c == '>') { dAngle = Math.max(0, dAngle - 1); j++; continue; }
            if (c == '{') {
                if (dParen == 0 && dAngle == 0 && dBrack == 0 && dBrace == 0 && seen) {
                    // already consumed a type token — this `{` starts the function body
                    return j;
                }
                // object type
                dBrace++;
                seen = true;
                j++;
                continue;
            }
            if (c == '}') {
                dBrace = Math.max(0, dBrace - 1);
                j++;
                if (dBrace == 0 && dParen == 0 && dAngle == 0 && dBrack == 0) {
                    int k = nextNonWs(s, j);
                    if (k < s.length && (s[k] == '&' || s[k] == '|' || s[k] == '[' || s[k] == '.')) {
                        j = k;
                        continue;
                    }
                    return j;
                }
                continue;
            }
            if ((c == ';' || c == ',' || c == '=' || c == '{' || c == ')')
                    && dParen == 0 && dAngle == 0 && dBrack == 0 && dBrace == 0 && seen) {
                return j;
            }
            if (!Character.isWhitespace(c)) seen = true;
            j++;
        }
        return j;
    }
    static int matchBraceArr(char[] s, int open) {
        if (open < 0 || open >= s.length || s[open] != '{') return -1;
        int d = 0;
        for (int i = open; i < s.length; i++) {
            if (s[i] == '{') d++;
            else if (s[i] == '}') {
                d--;
                if (d == 0) return i;
            }
        }
        return -1;
    }
    static int leadingWs(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
    static String safeSlice(char[] raw, int start, int end) {
        start = Math.max(0, Math.min(start, raw.length));
        end = Math.max(start, Math.min(end, raw.length));
        return new String(raw, start, end - start);
    }
    /** The last quoted literal in a statement -- the module specifier in every `... from "m"`. */
    static String lastStringLit(String x) {
        for (int i = x.length() - 1; i >= 0; i--) {
            char c = x.charAt(i);
            if (c != '"' && c != '\'' && c != '`') continue;
            int open = x.lastIndexOf(c, i - 1);
            if (open < 0) return null;
            return x.substring(open + 1, i);
        }
        return null;
    }
    static String collapseWs(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
    static String stringLit(String s, int from) {
        if (s == null) return null;
        for (int i = Math.max(0, from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                char q = c;
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < s.length() && s.charAt(j) != q) {
                    if (s.charAt(j) == '\\' && j + 1 < s.length()) { sb.append(s.charAt(j + 1)); j += 2; continue; }
                    sb.append(s.charAt(j++));
                }
                return sb.toString();
            }
        }
        return null;
    }
    static String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2) {
            char q = s.charAt(0);
            if ((q == '"' || q == '\'' || q == '`') && s.charAt(s.length() - 1) == q) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
    static String visibility(Set<String> mods) {
        if (mods.contains("private")) return "private";
        if (mods.contains("protected")) return "protected";
        if (mods.contains("public")) return "public";
        return "public";
    }
    static String baseType(String t) {
        if (t == null) return null;
        String s = stripGenerics(t).trim();
        // union / intersection: take first type token
        int pipe = indexOfTop(s, '|');
        if (pipe >= 0) s = s.substring(0, pipe).trim();
        int amp = indexOfTop(s, '&');
        if (amp >= 0) s = s.substring(0, amp).trim();
        // drop array []
        while (s.endsWith("[]")) s = s.substring(0, s.length() - 2).trim();
        // Promise already stripped generics -> empty? stripGenerics replaces with spaces
        s = s.trim();
        if (s.isEmpty()) return null;
        // last identifier-ish token
        String[] parts = s.split("\\s+");
        String last = parts[parts.length - 1];
        // qualified Type.Name -> keep simple or full? use simple last segment if dotted
        if (last.contains(".")) last = last.substring(last.lastIndexOf('.') + 1);
        // strip trailing non-ident
        int i = 0;
        while (i < last.length() && Lex.isIdentPart(last.charAt(i))) i++;
        if (i == 0) return null;
        return last.substring(0, i);
    }
    static String firstIdent(String s) {
        int i = 0;
        while (i < s.length() && !Lex.isIdentStart(s.charAt(i))) {
            if (!Character.isWhitespace(s.charAt(i))) return "";
            i++;
        }
        int j = i;
        while (j < s.length() && Lex.isIdentPart(s.charAt(j))) j++;
        return s.substring(i, j);
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
    static String stripGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        for (char c : s.toCharArray()) {
            if (c == '<') d++;
            else if (c == '>') { d = Math.max(0, d - 1); sb.append(' '); }
            else if (d == 0) sb.append(c);
        }
        return sb.toString();
    }
    static String stripLeadingGenerics(String s) {
        if (!s.startsWith("<")) return s;
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') d++;
            else if (c == '>') {
                d--;
                if (d == 0) return s.substring(i + 1);
            }
        }
        return s;
    }
    static int matching(String s, int open) {
        if (open < 0 || open >= s.length()) return -1;
        char o = s.charAt(open);
        char c = o == '(' ? ')' : o == '[' ? ']' : o == '{' ? '}' : o == '<' ? '>' : 0;
        if (c == 0) return -1;
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) d++;
            else if (s.charAt(i) == c) { d--; if (d == 0) return i; }
        }
        return -1;
    }
    static int indexOfTop(String s, char target) {
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // test before mutating depth so target '(' / '[' / '{' / '<' can be found
            if (c == target && dParen == 0 && dBrack == 0 && dBrace == 0 && dAngle == 0) return i;
            if (c == '(') dParen++;
            else if (c == ')') dParen = Math.max(0, dParen - 1);
            else if (c == '[') dBrack++;
            else if (c == ']') dBrack = Math.max(0, dBrack - 1);
            else if (c == '{') dBrace++;
            else if (c == '}') dBrace = Math.max(0, dBrace - 1);
            else if (c == '<') dAngle++;
            else if (c == '>') dAngle = Math.max(0, dAngle - 1);
        }
        return -1;
    }
    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') dParen++;
            else if (c == ')') dParen = Math.max(0, dParen - 1);
            else if (c == '[') dBrack++;
            else if (c == ']') dBrack = Math.max(0, dBrack - 1);
            else if (c == '{') dBrace++;
            else if (c == '}') dBrace = Math.max(0, dBrace - 1);
            else if (c == '<') dAngle++;
            else if (c == '>') dAngle = Math.max(0, dAngle - 1);
            else if (c == sep && dParen == 0 && dBrack == 0 && dBrace == 0 && dAngle == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
    static boolean isSimpleIdent(String s) {
        if (s.isEmpty() || !Lex.isIdentStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Lex.isIdentPart(s.charAt(i))) return false;
        return true;
    }
    static String parseExportName(String p) {
        String[] ab = parseAs(p);
        return ab[1] != null ? ab[1] : ab[0];
    }
    static String[] parseAs(String p) {
        String s = p.trim();
        if (s.startsWith("type ")) s = s.substring(5).trim();
        int as = findWord(s, "as");
        if (as < 0) return new String[]{firstIdent(s), null};
        String left = s.substring(0, as).trim();
        String right = s.substring(as + 2).trim();
        return new String[]{firstIdent(left), firstIdent(right)};
    }
}
