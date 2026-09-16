import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Python pass-1 extractor.
 *
 * Structure comes from an indentation walk over the masked source. A stack of
 * (indent, scope) tracks class/function nesting; blank lines and continuation
 * lines never pop a scope. Declarations, imports and calls are scanned only on
 * the masked text; route path strings are read back from the raw source at the
 * same offsets so literals survive masking.
 */
final class PythonExtractor implements Extractor {

    static final Set<String> SKIP_CALLS = Set.of(
            "print", "len", "range", "isinstance", "super", "getattr", "setattr",
            "str", "int", "dict", "list", "set", "tuple", "open", "enumerate",
            "zip", "sorted", "type", "format", "min", "max", "sum", "any", "all",
            "bool", "float", "bytes", "bytearray", "object", "property", "classmethod",
            "staticmethod", "abs", "round", "map", "filter", "iter", "next", "id",
            "hash", "repr", "ascii", "ord", "chr", "bin", "hex", "oct", "vars",
            "dir", "locals", "globals", "callable", "hasattr", "delattr", "issubclass",
            "memoryview", "slice", "complex", "divmod", "pow", "input",
            "exec", "eval", "compile", "exit", "quit", "help", "copyright", "credits",
            "license", "breakpoint", "aiter", "anext");

    static final Set<String> KEYWORDS = Set.of(
            "if", "elif", "else", "for", "while", "try", "except", "finally", "with",
            "def", "class", "return", "yield", "raise", "assert", "import", "from",
            "as", "pass", "break", "continue", "lambda", "and", "or", "not", "in",
            "is", "global", "nonlocal", "del", "await", "async", "match", "case");

    static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "delete", "patch");

    static final class Scope {
        String id;
        String qname;
        String type;            // file | module | class | enum | function | method | constructor
        int indent;             // indent of the declaring line (-1 for file)
        int start;              // char offset of body start
        int end = Integer.MAX_VALUE;
        GraphModel.Node node;
        Map<String, String> vars = new LinkedHashMap<>();
        java.util.Set<String> assigned = new java.util.LinkedHashSet<>();  // names bound locally in this scope
        Scope parent;
        String classQname;      // innermost enclosing class qname, or null
        boolean isEnum;
    }

    static final class Decorator {
        String name;            // simple name (last segment), e.g. dataclass, get
        String full;            // full receiver.attr text, e.g. router.get
        String rawArgs;         // raw source of (...) including parens, or ""
        int line;
    }

    public String language() { return "python"; }

    public boolean supports(String p) {
        return p != null && (p.endsWith(".py") || p.endsWith(".pyi"));
    }

    public FileFacts extract(String path, String src) {
        FileFacts f = new FileFacts(path, "python");
        if (src == null) src = "";
        char[] mask;
        try {
            mask = Lex.maskPython(src);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return f;
        }
        try {
            parse(f, path, src, mask);
        } catch (RuntimeException e) {
            f.error = "parse failed: " + e;
        }
        return f;
    }

    private void parse(FileFacts f, String path, String src, char[] mask) {
        int[] li = Lex.lineIndex(mask);
        char[] raw = src.toCharArray();
        String fileNodeId = GraphModel.fileId(path);
        f.namespace = PySyntax.modulePath(path);

        // Module node for the file's namespace identity.
        GraphModel.Node mod = new GraphModel.Node(
                GraphModel.symbolId("python", "module", path, f.namespace, -1),
                "module", PySyntax.simpleName(f.namespace), f.namespace, "python", path);
        mod.startLine = 1;
        mod.endLine = Math.max(1, Lex.lineOf(li, mask.length > 0 ? mask.length - 1 : 0));
        mod.visibility = "public";
        f.add(mod);
        f.defines(fileNodeId, mod.id, 1);

        List<Scope> allScopes = new ArrayList<>();
        Deque<Scope> stack = new ArrayDeque<>();
        Scope fileScope = new Scope();
        fileScope.id = fileNodeId;
        fileScope.qname = f.namespace;
        fileScope.type = "file";
        fileScope.indent = -1;
        fileScope.start = 0;
        fileScope.node = null;
        stack.push(fileScope);
        allScopes.add(fileScope);

        List<String> maskLines = Lex.lines(mask);
        List<String> rawLines = Lex.lines(raw);
        int lineCount = Math.max(maskLines.size(), rawLines.size()) - 1;

        List<Decorator> pendingDecos = new ArrayList<>();
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean continuing = false;
        StringBuilder logicalMask = new StringBuilder();
        StringBuilder logicalRaw = new StringBuilder();
        int logicalStartLine = 1;
        int logicalIndent = 0;

        for (int ln = 1; ln <= lineCount; ln++) {
            String ml = ln < maskLines.size() ? maskLines.get(ln) : "";
            String rl = ln < rawLines.size() ? rawLines.get(ln) : "";
            boolean blank = PySyntax.isBlankLine(ml);

            if (!continuing && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                if (blank) {
                    // Blank / fully-masked line: do not close scopes, drop pending only if not
                    // between decorators and a declaration? Keep decorators across blanks.
                    continue;
                }
                logicalMask.setLength(0);
                logicalRaw.setLength(0);
                logicalStartLine = ln;
                logicalIndent = PySyntax.countIndent(ml);
                logicalMask.append(ml);
                logicalRaw.append(rl);
            } else {
                logicalMask.append('\n').append(ml);
                logicalRaw.append('\n').append(rl);
            }

            // Update depths from this physical line (masked: strings already blanked).
            int[] d = PySyntax.depthDelta(ml);
            parenDepth = Math.max(0, parenDepth + d[0]);
            bracketDepth = Math.max(0, bracketDepth + d[1]);
            braceDepth = Math.max(0, braceDepth + d[2]);
            continuing = PySyntax.endsWithBackslash(ml) || parenDepth > 0 || bracketDepth > 0 || braceDepth > 0;

            if (continuing) continue;

            // Complete logical line.
            String head = logicalMask.toString();
            String rawHead = logicalRaw.toString();
            int line = logicalStartLine;
            int indent = logicalIndent;
            String trimmed = head.trim();
            if (trimmed.isEmpty()) {
                pendingDecos.clear();
                continue;
            }

            // Pop scopes whose indent is >= this line's indent.
            while (stack.size() > 1 && stack.peek().indent >= indent) {
                Scope closed = stack.pop();
                closed.end = PySyntax.lineStart(li, line);
                if (closed.node != null) closed.node.endLine = Math.max(closed.node.startLine, line - 1);
            }
            Scope cur = stack.peek();

            // Decorator line(s)
            if (trimmed.startsWith("@")) {
                pendingDecos.addAll(PySyntax.parseDecorators(trimmed, rawHead.trim(), line));
                continue;
            }

            // Imports
            if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                pendingDecos.clear();
                PyImports.parseImport(f, trimmed, line, fileScope);
                continue;
            }

            // class
            if (PySyntax.isKeywordAt(trimmed, "class")) {
                Scope s = PyDecl.emitClass(f, path, fileNodeId, trimmed, rawHead, line, indent, cur, pendingDecos, PySyntax.isTestFile(path));
                pendingDecos.clear();
                if (s != null) {
                    s.start = PySyntax.lineStart(li, line);
                    stack.push(s);
                    allScopes.add(s);
                }
                continue;
            }

            // def / async def
            String defLine = trimmed;
            if (defLine.startsWith("async ")) defLine = defLine.substring(6).trim();
            if (PySyntax.isKeywordAt(defLine, "def")) {
                Scope s = PyDecl.emitDef(f, path, fileNodeId, defLine, rawHead, line, indent, cur, pendingDecos, PySyntax.isTestFile(path));
                pendingDecos.clear();
                if (s != null) {
                    s.start = PySyntax.lineStart(li, line);
                    stack.push(s);
                    allScopes.add(s);
                }
                continue;
            }

            pendingDecos.clear();

            // Class body: annotated fields, enum constants, simple assignments for type tracking
            if (cur != null && ("class".equals(cur.type) || "enum".equals(cur.type))) {
                PyDecl.handleClassBodyLine(f, path, head, line, cur);
                continue;
            }

            // Module-level UPPER_CASE constant
            if (cur != null && ("file".equals(cur.type) || "module".equals(cur.type))) {
                String constName = PyDecl.moduleConstantName(head.trim());
                if (constName != null) {
                    PyDecl.emitModuleConstant(f, path, fileNodeId, constName, line);
                }
            }

            // Module / function body: typed assignments and simple constructor assignments
            if (cur != null) {
                PyDecl.handleAssignment(head, cur);
                PyDecl.recordAssignedName(head, cur);
            }
        }

        // Close remaining scopes
        int eof = mask.length;
        while (!stack.isEmpty()) {
            Scope s = stack.pop();
            s.end = eof;
            if (s.node != null && s.node.endLine == 0) {
                s.node.endLine = Lex.lineOf(li, eof > 0 ? eof - 1 : 0);
            }
        }
        mod.endLine = Lex.lineOf(li, eof > 0 ? eof - 1 : 0);

        collectCalls(f, mask, li, allScopes, fileNodeId, mod.id);
    }

    // ---------- declarations ----------

    // ---------- imports ----------

    // ---------- calls ----------

    private void collectCalls(FileFacts f, char[] mask, int[] li, List<Scope> scopes,
                              String fileNodeId, String moduleId) {
        ScopeCursor scopeCursor = new ScopeCursor(scopes);
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != '(') continue;
            String name = Lex.identBefore(mask, i);
            if (name.isEmpty()) continue;
            if (KEYWORDS.contains(name) || SKIP_CALLS.contains(name)) continue;
            int nameStart = i - name.length();
            // decorator application: @foo( or @router.get( — handled as declarations, not calls
            if (nameStart > 0 && mask[nameStart - 1] == '@') continue;
            if (PySyntax.isOnDecoratorLine(mask, nameStart)) continue;

            String receiver = null;
            if (nameStart > 0 && mask[nameStart - 1] == '.') {
                receiver = Lex.receiverBefore(mask, nameStart - 1);
                if (receiver.isEmpty()) receiver = null;
                // super().method( — Lex cannot see through the call parens; treat as super receiver
                if (receiver == null && PySyntax.isSuperDotCall(mask, nameStart - 1)) {
                    receiver = "super";
                }
            }

            Scope owner = scopeCursor.at(i);
            if (owner == null) continue;
            // Skip the def/class header call-like tokens: `def foo(` on the declaration line
            if (PySyntax.isDeclHeader(mask, nameStart)) continue;

            int arity = PySyntax.arity(mask, i);
            String from;
            if (owner.node != null && !"file".equals(owner.type) && !"module".equals(owner.type)) {
                from = owner.node.id;
            } else {
                // Module-level: prefer module node so ground-truth qname maps cleanly
                from = moduleId != null ? moduleId : fileNodeId;
            }
            // A name bound by a local assignment shadows any module-level function of the same
            // name. `format_id = str` followed by `format_id(x)` is a call to a local, and
            // linking it to the module function would be a confidently wrong edge.
            if (receiver == null && PyDecl.shadowedLocally(owner, name)) continue;
            String rt = PyDecl.lookupReceiverType(owner, receiver, scopes, i);
            f.refs.add(new Ref(from, receiver, rt, name, arity, Lex.lineOf(li, i), "call"));
        }
    }

    /**
     * Innermost scope containing an offset, in linear total time.
     *
     * The previous linear scan per call site was quadratic in the number of scopes, which is
     * fine for a fixture and fatal for a large generated file. Offsets are visited in
     * increasing order, so a stack of currently open scopes answers the same question.
     */
    private static final class ScopeCursor {
        private final List<Scope> sorted;
        private final java.util.ArrayDeque<Scope> open = new java.util.ArrayDeque<>();
        private int cursor = 0;

        ScopeCursor(List<Scope> scopes) {
            this.sorted = new ArrayList<>(scopes);
            this.sorted.sort((a, b) -> Integer.compare(a.start, b.start));
        }

        Scope at(int off) {
            while (!open.isEmpty() && open.peek().end <= off) open.pop();
            while (cursor < sorted.size() && sorted.get(cursor).start <= off) {
                Scope s = sorted.get(cursor++);
                if (s.end > off) open.push(s);
            }
            for (Scope s : open) {
                if (true) return s;
            }
            return null;
        }
    }

    private static Scope innermost(List<Scope> scopes, int off) {
        Scope best = null;
        for (Scope s : scopes) {
            if (s.start <= off && off < s.end) {
                if (best == null || s.start >= best.start) best = s;
            }
        }
        return best;
    }

    // ---------- decorators ----------

    // ---------- module path ----------

    // ---------- small helpers ----------

}
