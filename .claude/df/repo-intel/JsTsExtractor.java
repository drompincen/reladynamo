import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JavaScript / TypeScript pass-1 extractor.
 *
 * Brace walk over masked source, same shape as {@link JavaExtractor}. Template literals are
 * masked as strings via {@link Lex#maskCLike}. Annotation-like literals (route paths, module
 * specifiers) are read back from the raw source at the same offsets.
 */
final class JsTsExtractor implements Extractor {

    static final Set<String> EXT = Set.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs");

    static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "readonly", "abstract", "async",
            "override", "declare", "default", "export", "const", "get", "set", "accessor");

    static final Set<String> NOT_CALL = Set.of(
            "if", "for", "while", "switch", "catch", "return", "typeof", "function",
            "else", "do", "try", "finally", "throw", "new", "case", "break", "continue",
            "class", "interface", "enum", "type", "const", "let", "var", "import", "export",
            "from", "of", "in", "as", "is", "extends", "implements", "yield", "with",
            "debugger", "delete", "instanceof", "void", "await", "package", "default",
            "true", "false", "null", "undefined", "this");

    static final Set<String> ROUTE_VERBS = Set.of("get", "post", "put", "delete", "patch", "all");

    static final Set<String> TYPE_KW = Set.of("class", "interface", "enum", "type");

    public String language() { return "typescript"; }

    public boolean supports(String p) {
        if (p == null) return false;
        String lower = p.toLowerCase(Locale.ROOT);
        for (String e : EXT) if (lower.endsWith(e)) return true;
        return false;
    }

    static final class Scope {
        String id;
        String qname;
        String type;            // file / class / interface / enum / function / method / constructor / block
        int openDepth;
        int start;
        int end = Integer.MAX_VALUE; // exclusive; set when the matching `}` is seen
        GraphModel.Node node;
        Map<String, String> vars = new LinkedHashMap<>();
        Scope parent;
        boolean isTypeBody;     // class / interface / enum
    }

    public FileFacts extract(String path, String src) {
        String fileLang = JsTsSyntax.fileLanguage(path);
        FileFacts f = new FileFacts(path, fileLang);
        f.namespace = JsTsSyntax.stripExt(path);
        char[] mask;
        try {
            mask = Lex.maskCLike(src == null ? "" : src, false, true);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return f;
        }
        try {
            walk(f, path, fileLang, src == null ? "" : src, mask);
        } catch (RuntimeException e) {
            f.error = "parse failed: " + e;
        }
        return f;
    }

    private void walk(FileFacts f, String path, String fileLang, String src, char[] mask) {
        int[] li = Lex.lineIndex(mask);
        char[] raw = src.toCharArray();
        String fileNodeId = GraphModel.fileId(path);
        boolean testFile = JsTsSyntax.isTestPath(path);

        List<Scope> allScopes = new ArrayList<>();
        Deque<Scope> stack = new ArrayDeque<>();
        Scope fileScope = new Scope();
        fileScope.id = fileNodeId;
        fileScope.type = "file";
        fileScope.qname = "";
        fileScope.openDepth = -1;
        fileScope.start = 0;
        stack.push(fileScope);
        allScopes.add(fileScope);

        if (testFile) {
            GraphModel.Node tn = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, "test", path, path, -1),
                    "test", JsTsSyntax.baseName(path), path, fileLang, path);
            tn.startLine = 1;
            tn.endLine = 1;
            tn.attrs.put("test", true);
            f.add(tn);
            f.defines(fileNodeId, tn.id, 1);
        }

        int depth = 0, headStart = 0, n = mask.length;

        for (int i = 0; i < n; i++) {
            char c = mask[i];
            if (c == '{') {
                // import/export brace lists are not scopes: import { a } from "m"
                String preview = new String(mask, headStart, i - headStart).trim();
                if (JsTsScan.looksLikeImportExport(preview)) {
                    int close = JsTsSyntax.matchBraceArr(mask, i);
                    if (close > i) { i = close; continue; }
                }
                String head = new String(mask, headStart, i - headStart);
                String rawHead = JsTsSyntax.safeSlice(raw, headStart, i);
                int line = Lex.lineOf(li, headStart + JsTsSyntax.leadingWs(head));
                Scope cur = stack.peek();
                Scope s = declare(f, path, fileLang, head, rawHead, line, cur, i, testFile);
                if (s == null) {
                    s = new Scope();
                    s.type = "block";
                    s.qname = cur.qname;
                    s.parent = cur;
                    s.id = cur.id;
                }
                s.openDepth = depth;
                s.start = i;
                depth++;
                stack.push(s);
                allScopes.add(s);
                headStart = i + 1;
            } else if (c == '}') {
                // flush remaining head for enum bodies (comma-separated, no ';')
                Scope top = stack.peek();
                if (top != null && "enum".equals(top.type) && headStart < i) {
                    String head = new String(mask, headStart, i - headStart);
                    String rawHead = JsTsSyntax.safeSlice(raw, headStart, i);
                    int line = Lex.lineOf(li, headStart + JsTsSyntax.leadingWs(head));
                    JsTsEmit.emitEnumMembers(f, path, fileLang, head, rawHead, line, top);
                }
                depth = Math.max(0, depth - 1);
                while (stack.size() > 1 && stack.peek().openDepth >= depth) {
                    Scope s = stack.pop();
                    s.end = i + 1;
                    if (s.node != null) s.node.endLine = Lex.lineOf(li, i);
                }
                headStart = i + 1;
            } else if (c == ';') {
                String head = new String(mask, headStart, i - headStart);
                String rawHead = JsTsSyntax.safeSlice(raw, headStart, i);
                int line = Lex.lineOf(li, headStart + JsTsSyntax.leadingWs(head));
                statement(f, path, fileLang, head, rawHead, line, stack.peek(), testFile);
                headStart = i + 1;
            }
        }
        while (!stack.isEmpty()) {
            Scope s = stack.pop();
            if (s.end == Integer.MAX_VALUE) s.end = n;
            if (s.node != null && s.node.endLine == 0) s.node.endLine = Lex.lineOf(li, n > 0 ? n - 1 : 0);
        }

        collectCalls(f, path, fileLang, mask, raw, li, allScopes, fileNodeId);
        collectRoutes(f, path, fileLang, mask, raw, li, allScopes, fileNodeId);
    }

    // ---------- statements ending in `;` ----------

    private void statement(FileFacts f, String path, String fileLang, String head, String rawHead,
                           int line, Scope cur, boolean testFile) {
        String t = head.trim();
        if (t.isEmpty()) return;

        // imports / re-exports / export lists
        if (JsTsImports.handleImportOrExport(f, path, fileLang, t, rawHead.trim(), line, cur)) return;

        // require(...) CommonJS
        if (JsTsImports.handleRequire(f, t, rawHead.trim(), line, cur)) return;

        // type alias: type Name = ...
        if (JsTsSyntax.isTopLevel(cur) || "block".equals(cur.type)) {
            Decl td = JsTsDecl.parseTypeAlias(t);
            if (td != null) {
                JsTsEmit.emitTopDecl(f, path, fileLang, td, line, cur, testFile, true);
                return;
            }
        }

        // function declaration without body already handled via `{`; abstract-style ends here rarely

        // class fields / methods ending with `;` (abstract methods, fields)
        if (cur != null && cur.isTypeBody && cur.node != null) {
            if ("interface".equals(cur.type)) return; // interface props are not graph nodes
            if ("enum".equals(cur.type)) {
                JsTsEmit.emitEnumMembers(f, path, fileLang, t, rawHead, line, cur);
                return;
            }
            Decl d = JsTsDecl.parseMember(t, rawHead);
            if (d == null) return;
            if (d.isMethod) {
                JsTsEmit.emitMethod(f, path, fileLang, d, line, cur, null, testFile);
            } else if (d.name != null && !d.name.isEmpty()) {
                JsTsEmit.emitField(f, path, fileLang, d, line, cur, testFile);
            }
            return;
        }

        // top-level const/let/var / function expression / arrow
        if (JsTsSyntax.isTopLevel(cur)) {
            Decl d = JsTsDecl.parseVarOrFunction(t, rawHead);
            if (d != null) {
                JsTsEmit.emitTopDecl(f, path, fileLang, d, line, cur, testFile, true);
                // remember annotated locals / simple bindings for receiverType
                if (d.name != null) {
                    if (d.typeName != null && !"any".equals(d.typeName) && !"unknown".equals(d.typeName)) {
                        cur.vars.put(d.name, JsTsSyntax.baseType(d.typeName));
                    } else {
                        String ctor = JsTsScan.constructedType(t);
                        if (ctor != null) cur.vars.put(d.name, ctor);
                    }
                }
            }
            return;
        }

        // locals inside functions: type annotations, plus `new Type()` which is just as certain
        if (cur != null && (cur.node != null || "block".equals(cur.type))) {
            Decl d = JsTsDecl.parseVarOrFunction(t, rawHead);
            if (d != null && d.name != null) {
                if (d.typeName != null && !"any".equals(d.typeName) && !"unknown".equals(d.typeName)) {
                    cur.vars.put(d.name, JsTsSyntax.baseType(d.typeName));
                } else {
                    String ctor = JsTsScan.constructedType(t);
                    if (ctor != null) cur.vars.put(d.name, ctor);
                }
            }
        }
    }

    // ---------- declaration heads ending in `{` ----------

    private Scope declare(FileFacts f, String path, String fileLang, String head, String rawHead,
                          int line, Scope cur, int off, boolean testFile) {
        String t = head.trim();
        if (t.isEmpty()) return null;

        // export default function/class or function/class
        Decl d = JsTsDecl.parseBraceDecl(t, rawHead);
        if (d == null) {
            // arrow/function expression assigned to const: const f = (...) => {
            Decl v = JsTsDecl.parseVarOrFunction(t, rawHead);
            if (v != null && v.isMethod && JsTsSyntax.isTopLevel(cur)) {
                GraphModel.Node node = JsTsEmit.emitTopDecl(f, path, fileLang, v, line, cur, testFile, true);
                if (node == null) return null;
                Scope sc = new Scope();
                sc.id = node.id;
                sc.qname = node.qname;
                sc.type = node.type;
                sc.node = node;
                sc.parent = cur;
                JsTsDecl.bindParams(sc, v);
                return sc;
            }
            return null;
        }

        if (d.typeKeyword != null) {
            String kind = switch (d.typeKeyword) {
                case "interface" -> "interface";
                case "enum" -> "enum";
                case "type" -> "type";
                default -> "class";
            };
            String qn = d.name;
            GraphModel.Node node = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, kind, path, qn, -1),
                    kind, d.name, qn, fileLang, path);
            node.startLine = line;
            node.visibility = JsTsSyntax.visibility(d.modifiers);
            if (testFile) node.attrs.put("test", true);
            f.add(node);
            if (JsTsSyntax.isTopLevel(cur)) f.defines(GraphModel.fileId(path), node.id, line);
            else if (cur.id != null) f.contains(cur.id, node.id, line);

            if (d.isExport) f.exports.put(d.name, node.id);
            if (d.isDefaultExport) f.exports.put("default", node.id);

            for (String s : d.extendsNames) f.supers.add(new TypeRef(node.id, s, "EXTENDS", line));
            for (String s : d.implementsNames) f.supers.add(new TypeRef(node.id, s, "IMPLEMENTS", line));

            Scope sc = new Scope();
            sc.id = node.id;
            sc.qname = qn;
            sc.type = kind;
            sc.node = node;
            sc.parent = cur;
            sc.isTypeBody = "class".equals(kind) || "interface".equals(kind) || "enum".equals(kind);
            return sc;
        }

        // function declaration (top-level or nested)
        if (d.isMethod && d.name != null && (JsTsSyntax.isTopLevel(cur) || "block".equals(cur.type) || cur.node != null)) {
            if (cur != null && cur.isTypeBody && cur.node != null && "class".equals(cur.type)) {
                return JsTsEmit.emitMethod(f, path, fileLang, d, line, cur, off, testFile);
            }
            // top-level / nested function
            if (JsTsSyntax.isTopLevel(cur) || !cur.isTypeBody) {
                String qn = d.name;
                int arity = d.params.size();
                GraphModel.Node node = new GraphModel.Node(
                        GraphModel.symbolId(fileLang, "function", path, qn, arity),
                        "function", d.name, qn, fileLang, path);
                node.startLine = line;
                node.endLine = line;
                node.visibility = JsTsSyntax.visibility(d.modifiers);
                node.signature = d.name + "(" + String.join(", ", d.params) + ")";
                if (testFile) node.attrs.put("test", true);
                f.add(node);
                if (JsTsSyntax.isTopLevel(cur)) f.defines(GraphModel.fileId(path), node.id, line);
                else if (cur.id != null && cur.node != null) f.contains(cur.id, node.id, line);
                else f.defines(GraphModel.fileId(path), node.id, line);
                if (d.isExport) f.exports.put(d.name, node.id);
                if (d.isDefaultExport) f.exports.put("default", node.id);
                Scope sc = new Scope();
                sc.id = node.id;
                sc.qname = qn;
                sc.type = "function";
                sc.node = node;
                sc.parent = cur;
                JsTsDecl.bindParams(sc, d);
                return sc;
            }
        }

        // class member method / constructor
        if (d.isMethod && cur != null && cur.isTypeBody && cur.node != null && "class".equals(cur.type)) {
            return JsTsEmit.emitMethod(f, path, fileLang, d, line, cur, off, testFile);
        }

        return null;
    }

    // ---------- imports / exports / require ----------

    // ---------- calls ----------

    private void collectCalls(FileFacts f, String path, String fileLang, char[] mask, char[] raw,
                              int[] li, List<Scope> scopes, String fileNodeId) {
        ScopeCursor scopeCursor = new ScopeCursor(scopes);
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != '(') continue;
            String name = Lex.identBefore(mask, i);
            if (name.isEmpty() || NOT_CALL.contains(name)) continue;
            int nameStart = i - name.length();
            String receiver = null;
            if (nameStart > 0 && mask[nameStart - 1] == '.') {
                receiver = Lex.receiverBefore(mask, nameStart - 1);
                if (receiver.isEmpty()) receiver = null;
            }
            // declaration site: `function foo(`, `async getCase(`, `getName() {`, typed params
            if (receiver == null && JsTsScan.isDeclarationSite(mask, nameStart, name, i)) continue;
            Scope owner = scopeCursor.at(i);
            if (owner == null) continue;
            if (owner.node != null && owner.node.startLine == Lex.lineOf(li, i)
                    && name.equals(owner.node.name) && receiver == null) continue;
            // require( is an import, not a call ref
            if ("require".equals(name) && receiver == null) continue;

            boolean isNew = JsTsScan.precededByNew(mask, nameStart);
            int arity = JsTsScan.arity(raw, i);
            String from = owner.node != null ? owner.node.id : fileNodeId;
            // file-level test node: prefer file id for refs (GT keys off the file)
            if (from != null && from.equals(owner.id) && "test".equals(owner.type)) from = fileNodeId;
            String rt = receiver == null ? null : JsTsScan.lookupVar(owner, receiver);
            // never invent a receiverType for `any` / `unknown`
            if ("any".equals(rt) || "unknown".equals(rt)) rt = null;
            f.refs.add(new Ref(from, receiver, rt, name, arity, Lex.lineOf(li, i), isNew ? "new" : "call"));
        }
    }

    private void collectRoutes(FileFacts f, String path, String fileLang, char[] mask, char[] raw,
                               int[] li, List<Scope> scopes, String fileNodeId) {
        ScopeCursor routeCursor = new ScopeCursor(scopes);
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != '(') continue;
            String verb = Lex.identBefore(mask, i);
            if (verb.isEmpty() || !ROUTE_VERBS.contains(verb.toLowerCase(Locale.ROOT))) continue;
            int verbStart = i - verb.length();
            if (verbStart <= 0 || mask[verbStart - 1] != '.') continue;
            String receiver = Lex.receiverBefore(mask, verbStart - 1);
            if (receiver.isEmpty()) continue;
            // router.get / app.post etc.
            String head = receiver.contains(".")
                    ? receiver.substring(receiver.lastIndexOf('.') + 1) : receiver;
            // accept any receiver; conventional router/app/server

            // first string arg from RAW — must look like a URL path, not Map.get("key")
            String routePath = JsTsScan.firstStringArg(raw, i);
            if (routePath == null || routePath.isEmpty()) continue;
            if (routePath.charAt(0) != '/' && routePath.charAt(0) != '*') continue;
            int line = Lex.lineOf(li, i);
            String http = verb.toUpperCase(Locale.ROOT);
            if ("ALL".equals(http)) http = "ALL";
            String eq = http + " " + routePath;
            GraphModel.Node ep = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, "endpoint", path, eq, -1),
                    "endpoint", eq, eq, fileLang, path);
            ep.startLine = line;
            ep.endLine = line;
            ep.attrs.put("http_method", http);
            ep.attrs.put("path", routePath);
            f.add(ep);
            f.defines(fileNodeId, ep.id, line);

            // handler: second arg — identifier vs inline
            int[] argBounds = JsTsScan.nthArgBounds(mask, i, 1);
            if (argBounds == null) continue;
            String argText = new String(mask, argBounds[0], argBounds[1] - argBounds[0]).trim();
            if (argText.isEmpty()) continue;
            // named identifier only (no '(' and no '=>')
            if (JsTsSyntax.isSimpleIdent(argText)) {
                Scope owner = routeCursor.at(i);
                String from = owner != null && owner.node != null ? owner.node.id : fileNodeId;
                f.refs.add(new Ref(from, null, null, argText, -1, line, "call"));
                // leave ROUTES_TO to resolver
            } else {
                // inline handler: ROUTES_TO enclosing function (or file-level symbol if any)
                Scope owner = routeCursor.at(i);
                String target = owner != null && owner.node != null ? owner.node.id : fileNodeId;
                f.edges.add(new GraphModel.Edge(ep.id, target, "ROUTES_TO", GraphModel.EXTRACTED,
                        path, line, "express-route"));
            }
        }
    }

    // ---------- head parsing ----------

    static final class Decl {
        Set<String> modifiers = new LinkedHashSet<>();
        String typeKeyword;
        String name;
        String typeName;
        boolean isMethod;
        boolean isExport;
        boolean isDefaultExport;
        List<String> params = new ArrayList<>();
        List<String> extendsNames = new ArrayList<>();
        List<String> implementsNames = new ArrayList<>();
    }

    // ---------- small helpers ----------

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
                if (s.node != null || "file".equals(s.type)) return s;
            }
            return null;
        }
    }

    private static Scope innermost(List<Scope> scopes, int off) {
        Scope best = null;
        for (Scope s : scopes) {
            if (s.node == null && !"file".equals(s.type)) continue;
            if (off < s.start || off >= s.end) continue;
            if (best == null || s.start >= best.start) best = s;
        }
        return best;
    }

}
