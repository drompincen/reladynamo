import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bash / shell pass-1 extractor.
 *
 * Line-oriented walk over {@link Lex#maskShell} with a brace counter for function bodies.
 * Include paths are read back from the RAW source so quotes and {@code $(dirname ...)}
 * wrappers survive masking.
 */
final class BashExtractor implements Extractor {

    /** Control words and builtins that can never be repository functions. */
    private static final Set<String> SKIP = Set.of(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
            "case", "esac", "function", "return", "local", "export", "readonly", "declare",
            "set", "shift", "echo", "cd", "exit", "eval", "source", "trap", "read", "unset",
            "printf", "test", "true", "false", "break", "continue");

    public String language() { return "bash"; }

    public boolean supports(String relPath) {
        if (relPath == null || relPath.isEmpty()) return false;
        String lower = relPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return true;
        // Extensionless basename; shebang confirmed via isShellShebang when source is known.
        int slash = Math.max(relPath.lastIndexOf('/'), relPath.lastIndexOf('\\'));
        String base = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        return !base.isEmpty() && base.indexOf('.') < 0;
    }

    /** True when the first line is a shell shebang (extensionless selection). */
    static boolean isShellShebang(String source) {
        if (source == null || !source.startsWith("#!")) return false;
        int nl = source.indexOf('\n');
        String first = (nl < 0 ? source : source.substring(0, nl)).toLowerCase(Locale.ROOT);
        return first.contains("bash") || first.contains("sh");
    }

    public FileFacts extract(String path, String src) {
        FileFacts f = new FileFacts(path, "bash");
        f.namespace = path == null ? "" : path;
        if (src == null) { f.error = "null source"; return f; }
        char[] mask;
        try {
            mask = Lex.maskShell(src);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return f;
        }
        try {
            scan(f, path, src, mask);
        } catch (RuntimeException e) {
            f.error = e.toString();
        }
        return f;
    }

    // ---------- scan ----------

    private void scan(FileFacts f, String path, String src, char[] mask) {
        List<String> mLines = Lex.lines(mask);
        List<String> rLines = Lex.lines(src.toCharArray());
        String fileId = GraphModel.fileId(path);
        int depth = 0;
        GraphModel.Node currentFunc = null;
        String pendingName = null;
        int pendingLine = 0;
        int lineCount = Math.max(mLines.size(), rLines.size());

        for (int lineNo = 1; lineNo < lineCount; lineNo++) {
            String mLine = lineNo < mLines.size() ? mLines.get(lineNo) : "";
            String rLine = lineNo < rLines.size() ? rLines.get(lineNo) : "";

            // `name()` / `function name` on a prior line waiting for `{`
            if (pendingName != null && depth == 0 && currentFunc == null) {
                String t = ltrim(mLine);
                if (t.startsWith("{")) {
                    currentFunc = emitFunction(f, path, fileId, pendingName, pendingLine);
                    pendingName = null;
                } else if (!t.isEmpty()) {
                    pendingName = null;
                }
            }

            if (depth == 0 && currentFunc == null) {
                if (!tryInclude(f, mLine, rLine, lineNo)) {
                    String fname = matchFunctionName(mLine);
                    if (fname != null) {
                        int braceAt = indexOf(mLine, '{');
                        if (braceAt >= 0) {
                            currentFunc = emitFunction(f, path, fileId, fname, lineNo);
                            scanCalls(f, mLine, braceAt + 1, lineNo, currentFunc.id);
                        } else {
                            pendingName = fname;
                            pendingLine = lineNo;
                        }
                    } else {
                        tryAssignment(f, path, fileId, mLine, lineNo);
                        scanCalls(f, mLine, 0, lineNo, fileId);
                    }
                }
            } else {
                String from = currentFunc != null ? currentFunc.id : fileId;
                scanCalls(f, mLine, 0, lineNo, from);
            }

            for (int i = 0; i < mLine.length(); i++) {
                char c = mLine.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                    if (depth == 0 && currentFunc != null) {
                        currentFunc.endLine = lineNo;
                        currentFunc = null;
                    }
                }
            }
        }
        if (currentFunc != null && currentFunc.endLine == 0) {
            currentFunc.endLine = Math.max(1, lineCount - 1);
        }
    }

    // ---------- declarations ----------

    private GraphModel.Node emitFunction(FileFacts f, String path, String fileId, String name, int line) {
        String qn = path + ":" + name;
        GraphModel.Node n = new GraphModel.Node(
                GraphModel.symbolId("bash", "function", path, qn, -1),
                "function", name, qn, "bash", path);
        n.startLine = line;
        n.endLine = line;
        n.visibility = "public";
        f.add(n);
        f.defines(fileId, n.id, line);
        return n;
    }

    /** {@code source X} or {@code . X} at line start. Path from RAW so quotes survive. */
    private boolean tryInclude(FileFacts f, String mLine, String rLine, int line) {
        String mt = ltrim(mLine);
        String rt = ltrim(rLine);
        if (mt.isEmpty()) return false;

        String restRaw;
        if (mt.startsWith("source") && (mt.length() == 6 || isSep(mt.charAt(6)))) {
            restRaw = afterKeyword(rt, "source");
            if (restRaw == null) restRaw = skipWs(rt, 6);
        } else if (mt.charAt(0) == '.' && (mt.length() == 1 || isSep(mt.charAt(1)))) {
            restRaw = skipWs(rt, 1);
        } else {
            return false;
        }
        String path = readPathToken(restRaw);
        if (path == null || path.isEmpty()) return false;
        path = normalizeInclude(path);
        if (path.isEmpty()) return false;
        f.includes.add(path);
        f.imports.add(new ImportRef(null, path, null, "shell", false, line, rt.trim()));
        return true;
    }

    private void tryAssignment(FileFacts f, String path, String fileId, String mLine, int line) {
        String rest = ltrim(mLine);
        if (rest.isEmpty()) return;

        boolean exported = false;
        boolean readonly = false;

        if (rest.startsWith("export") && (rest.length() == 6 || isSep(rest.charAt(6)))) {
            exported = true;
            rest = skipWs(rest, 6);
        } else if (rest.startsWith("readonly") && (rest.length() == 8 || isSep(rest.charAt(8)))) {
            readonly = true;
            rest = skipWs(rest, 8);
        } else if (rest.startsWith("declare") && (rest.length() == 7 || isSep(rest.charAt(7)))) {
            rest = skipWs(rest, 7);
            while (rest.startsWith("-")) {
                int sp = rest.indexOf(' ');
                if (sp < 0) return;
                String flag = rest.substring(0, sp);
                if (flag.indexOf('x') >= 0) exported = true;
                if (flag.indexOf('r') >= 0) readonly = true;
                rest = skipWs(rest, sp + 1);
            }
        }

        while (rest.startsWith("-")) {
            int sp = rest.indexOf(' ');
            if (sp < 0) return;
            rest = skipWs(rest, sp + 1);
        }

        String name = readIdent(rest);
        if (name.isEmpty()) return;
        String after = rest.substring(name.length());
        if (!after.startsWith("=")) return;

        boolean screaming = isScreamingCase(name);
        String kind = (exported || readonly || screaming) ? "constant" : "field";
        String qn = path + ":" + name;
        GraphModel.Node n = new GraphModel.Node(
                GraphModel.symbolId("bash", kind, path, qn, -1),
                kind, name, qn, "bash", path);
        n.startLine = line;
        n.endLine = line;
        if (exported) n.attrs.put("exported", true);
        f.add(n);
        f.defines(fileId, n.id, line);
    }

    /** {@code name()} or {@code function name} / {@code function name()}, optional `{`. */
    private static String matchFunctionName(String mLine) {
        String t = ltrim(mLine);
        if (t.isEmpty()) return null;

        if (t.startsWith("function") && (t.length() == 8 || isSep(t.charAt(8)))) {
            String rest = skipWs(t, 8);
            String name = readIdent(rest);
            return name.isEmpty() ? null : name;
        }

        String name = readIdent(t);
        if (name.isEmpty()) return null;
        String after = skipWs(t, name.length());
        if (!after.startsWith("()")) return null;
        return name;
    }

    // ---------- calls ----------

    private void scanCalls(FileFacts f, String mLine, int fromIdx, int line, String fromId) {
        int n = mLine.length();
        int i = Math.max(0, fromIdx);
        boolean cmdPos = true;

        while (i < n) {
            char c = mLine.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '#' && (i == 0 || isSep(mLine.charAt(i - 1))
                    || Character.isWhitespace(mLine.charAt(i - 1)))) break;

            // [[ ... ]] or [ ... ] — not command context
            if (c == '[') {
                boolean dbl = i + 1 < n && mLine.charAt(i + 1) == '[';
                i = skipTestExpr(mLine, i, dbl);
                cmdPos = false;
                continue;
            }

            if (c == '|') {
                if (i + 1 < n && mLine.charAt(i + 1) == '|') i++;
                i++; cmdPos = true; continue;
            }
            if (c == '&') {
                if (i + 1 < n && mLine.charAt(i + 1) == '&') { i += 2; cmdPos = true; continue; }
                i++; cmdPos = true; continue;
            }
            if (c == ';' || c == '(' || c == ')' || c == '{' || c == '}') {
                i++; cmdPos = true; continue;
            }
            if (c == '<' || c == '>' || c == '!' || c == '=' || c == '\\') {
                i++; cmdPos = false; continue;
            }

            if (Lex.isIdentStart(c) || c == '.' || c == '/' || c == '-' || c == '~') {
                // lone `.` source builtin
                if (c == '.' && (i + 1 >= n || Character.isWhitespace(mLine.charAt(i + 1))
                        || mLine.charAt(i + 1) == '#')) {
                    i = skipToken(mLine, skipWsIdx(mLine, i + 1));
                    cmdPos = false;
                    continue;
                }
                if (!Lex.isIdentStart(c)) {
                    i = skipToken(mLine, i);
                    cmdPos = false;
                    continue;
                }
                int start = i;
                i++;
                while (i < n && Lex.isIdentPart(mLine.charAt(i))) i++;
                String word = mLine.substring(start, i);

                // NAME=value — not a call; env-prefix keeps command position
                int j = skipWsIdx(mLine, i);
                if (j < n && mLine.charAt(j) == '=' && (j + 1 >= n || mLine.charAt(j + 1) != '=')) {
                    i = skipAssignmentValue(mLine, j + 1);
                    cmdPos = true;
                    continue;
                }

                if (cmdPos) {
                    if (word.equals("then") || word.equals("else") || word.equals("elif")
                            || word.equals("do") || word.equals("if") || word.equals("while")
                            || word.equals("until")) {
                        cmdPos = true;
                        continue;
                    }
                    if (word.equals("for") || word.equals("select") || word.equals("case")) {
                        cmdPos = false;
                        continue;
                    }
                    if (word.equals("source")) {
                        i = skipToken(mLine, skipWsIdx(mLine, i));
                        cmdPos = false;
                        continue;
                    }
                    if (!SKIP.contains(word) && isPlainIdent(word)) {
                        f.refs.add(new Ref(fromId, null, null, word, -1, line, "call"));
                    }
                    cmdPos = false;
                }
                continue;
            }
            i++;
            cmdPos = false;
        }
    }

    // ---------- path / token helpers ----------

    private static String readPathToken(String s) {
        if (s == null) return null;
        String t = ltrim(s);
        if (t.isEmpty()) return null;
        char q = t.charAt(0);
        if (q == '"' || q == '\'') {
            int end = t.indexOf(q, 1);
            return end < 0 ? t.substring(1).trim() : t.substring(1, end);
        }
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == '&' || c == '|' || c == '#') break;
            i++;
        }
        return t.substring(0, i);
    }

    /** Strip a leading {@code $(dirname "$0")/}-style prefix when the tail is obvious. */
    private static String normalizeInclude(String path) {
        if (path == null) return "";
        String p = path.trim();
        int parenSlash = p.indexOf(")/");
        if (parenSlash >= 0 && p.contains("$(")) {
            String tail = p.substring(parenSlash + 2);
            if (!tail.isEmpty() && !tail.startsWith("$")) return tail;
        }
        int braceSlash = p.indexOf("}/");
        if (braceSlash >= 0 && p.contains("${")) {
            String tail = p.substring(braceSlash + 2);
            if (!tail.isEmpty() && !tail.startsWith("$")) return tail;
        }
        if (p.length() >= 2) {
            char a = p.charAt(0), b = p.charAt(p.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) p = p.substring(1, p.length() - 1);
        }
        return p;
    }

    private static String afterKeyword(String raw, String kw) {
        String t = ltrim(raw);
        if (t.length() < kw.length()) return null;
        if (!t.regionMatches(true, 0, kw, 0, kw.length())) return null;
        if (t.length() > kw.length() && !isSep(t.charAt(kw.length()))
                && !Character.isWhitespace(t.charAt(kw.length()))) return null;
        return skipWs(t, kw.length());
    }

    private static int skipTestExpr(String s, int i, boolean dbl) {
        int n = s.length();
        if (dbl) {
            i += 2;
            while (i < n - 1) {
                if (s.charAt(i) == ']' && s.charAt(i + 1) == ']') return i + 2;
                i++;
            }
            return n;
        }
        i++;
        while (i < n) {
            if (s.charAt(i) == ']') return i + 1;
            i++;
        }
        return n;
    }

    private static int skipAssignmentValue(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == '|' || c == '&'
                    || c == '(' || c == ')') break;
            i++;
        }
        return i;
    }

    private static int skipToken(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == '|' || c == '&'
                    || c == '(' || c == ')' || c == '{' || c == '}' || c == '<' || c == '>') break;
            i++;
        }
        return i;
    }

    private static boolean isScreamingCase(String name) {
        if (name == null || name.isEmpty() || !Character.isUpperCase(name.charAt(0))) return false;
        boolean letter = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                if (!Character.isUpperCase(c)) return false;
                letter = true;
            } else if (c != '_' && !Character.isDigit(c)) {
                return false;
            }
        }
        return letter;
    }

    private static boolean isPlainIdent(String word) {
        if (word == null || word.isEmpty() || !Lex.isIdentStart(word.charAt(0))) return false;
        for (int i = 1; i < word.length(); i++) {
            if (!Lex.isIdentPart(word.charAt(i))) return false;
        }
        return true;
    }

    private static String readIdent(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || !Lex.isIdentStart(s.charAt(i))) return "";
        int j = i + 1;
        while (j < s.length() && Lex.isIdentPart(s.charAt(j))) j++;
        return s.substring(i, j);
    }

    private static String ltrim(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static String skipWs(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static int skipWsIdx(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int indexOf(String s, char ch) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) return i;
        return -1;
    }

    private static boolean isSep(char c) {
        return Character.isWhitespace(c) || c == ';' || c == '|' || c == '&'
                || c == '(' || c == ')' || c == '{' || c == '}' || c == '<' || c == '>' || c == '#';
    }
}
