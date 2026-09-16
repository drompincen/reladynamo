import java.util.ArrayList;
import java.util.List;

/**
 * Comment and string masking, shared by every extractor.
 *
 * Every extractor scans the MASKED text, never the raw source. Masking replaces the body of
 * comments and string literals with spaces while preserving length and newlines, so offsets
 * and line numbers still line up with the original file. That single rule is what stops a
 * commented-out import, a call inside a docstring, or a route path that happens to contain
 * "class Foo" from ever reaching the graph.
 */
final class Lex {
    private Lex() {}

    /** Mask C-family source: // line comments, block comments, ' and " literals, Java text blocks. */
    static char[] maskCLike(String src, boolean textBlocks, boolean templateLiterals) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' '; out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) blank(out, i++);
                if (i < n) { out[i++] = ' '; if (i < n) out[i++] = ' '; }
            } else if (textBlocks && c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                out[i++] = ' '; out[i++] = ' '; out[i++] = ' ';
                while (i < n && !(out[i] == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"')) {
                    if (out[i] == '\\' && i + 1 < n) { out[i++] = ' '; blank(out, i++); continue; }
                    blank(out, i++);
                }
                for (int k = 0; k < 3 && i < n; k++) out[i++] = ' ';
            } else if (c == '"' || c == '\'' || (templateLiterals && c == '`')) {
                char q = c;
                out[i++] = ' ';
                while (i < n && out[i] != q) {
                    if (out[i] == '\\' && i + 1 < n) { out[i++] = ' '; blank(out, i++); continue; }
                    if (q != '`' && out[i] == '\n') break; // unterminated literal: do not swallow the file
                    blank(out, i++);
                }
                if (i < n && out[i] == q) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return out;
    }

    /** Mask Python: # comments, ''' and """ docstrings, ' and " literals (prefixes included). */
    static char[] maskPython(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '#') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if ((c == '"' || c == '\'') && i + 2 < n && out[i + 1] == c && out[i + 2] == c) {
                char q = c;
                out[i++] = ' '; out[i++] = ' '; out[i++] = ' ';
                while (i < n && !(out[i] == q && i + 2 < n && out[i + 1] == q && out[i + 2] == q)) {
                    if (out[i] == '\\' && i + 1 < n) { out[i++] = ' '; blank(out, i++); continue; }
                    blank(out, i++);
                }
                for (int k = 0; k < 3 && i < n; k++) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                char q = c;
                out[i++] = ' ';
                while (i < n && out[i] != q) {
                    if (out[i] == '\\' && i + 1 < n) { out[i++] = ' '; blank(out, i++); continue; }
                    if (out[i] == '\n') break;
                    blank(out, i++);
                }
                if (i < n && out[i] == q) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * Mask shell: # comments (only at a word boundary, so ${#x} and a#b survive), '...' and
     * "..." literals, and heredoc bodies.
     */
    static char[] maskShell(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '#' && (i == 0 || out[i - 1] == '\n' || out[i - 1] == ' ' || out[i - 1] == '\t'
                    || out[i - 1] == ';' || out[i - 1] == '(')) {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '<' && i + 1 < n && out[i + 1] == '<') {
                int j = i + 2;
                while (j < n && (out[j] == '-' || out[j] == ' ' || out[j] == '\'' || out[j] == '"')) j++;
                int ts = j;
                while (j < n && (Character.isLetterOrDigit(out[j]) || out[j] == '_')) j++;
                String tag = new String(out, ts, Math.max(0, j - ts));
                if (tag.isEmpty()) { i += 2; continue; }
                while (j < n && out[j] != '\n') j++;
                i = Math.min(j + 1, n);
                while (i < n) {
                    int ls = i;
                    int le = ls;
                    while (le < n && out[le] != '\n') le++;
                    String line = new String(out, ls, le - ls).trim();
                    if (line.equals(tag)) { i = Math.min(le + 1, n); break; }
                    for (int k = ls; k < le; k++) blank(out, k);
                    i = Math.min(le + 1, n);
                }
            } else if (c == '\'' || c == '"') {
                char q = c;
                out[i++] = ' ';
                while (i < n && out[i] != q) {
                    if (q == '"' && out[i] == '\\' && i + 1 < n) { out[i++] = ' '; blank(out, i++); continue; }
                    blank(out, i++);
                }
                if (i < n && out[i] == q) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return out;
    }

    private static void blank(char[] a, int i) { if (a[i] != '\n' && a[i] != '\r') a[i] = ' '; }

    // ---------- line utilities ----------

    /** 1-based line number for a character offset. */
    static int[] lineIndex(char[] src) {
        int[] starts = new int[countLines(src) + 2];
        int n = 1;
        starts[0] = 0;
        starts[1] = 0;
        for (int i = 0; i < src.length; i++) if (src[i] == '\n') starts[++n] = i + 1;
        return starts;
    }

    private static int countLines(char[] src) {
        int c = 0;
        for (char ch : src) if (ch == '\n') c++;
        return c;
    }

    static int lineOf(int[] starts, int offset) {
        int lo = 1, hi = starts.length - 1, ans = 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= offset) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    /** Split masked source into lines, keeping 1-based indexing (index 0 unused). */
    static List<String> lines(char[] src) {
        List<String> out = new ArrayList<>();
        out.add("");
        StringBuilder sb = new StringBuilder();
        for (char c : src) {
            if (c == '\n') { out.add(sb.toString()); sb.setLength(0); } else if (c != '\r') sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    static boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_' || c == '$'; }

    static boolean isIdentPart(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    /** Read the identifier ending at (exclusive) index end, walking backwards. Empty if none. */
    static String identBefore(char[] s, int end) {
        int i = end;
        while (i > 0 && isIdentPart(s[i - 1])) i--;
        if (i == end) return "";
        if (!isIdentStart(s[i])) return "";
        return new String(s, i, end - i);
    }

    /** Read the dotted receiver chain ending at (exclusive) index end, e.g. "a.b.c". */
    static String receiverBefore(char[] s, int end) {
        int i = end;
        while (i > 0) {
            int j = i;
            while (j > 0 && isIdentPart(s[j - 1])) j--;
            if (j == i) break;
            if (j > 0 && s[j - 1] == '.') { i = j - 1; } else { i = j; break; }
        }
        if (i >= end) return "";
        String t = new String(s, i, end - i);
        return t.startsWith(".") ? t.substring(1) : t;
    }
}
