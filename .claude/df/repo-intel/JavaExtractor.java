import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java pass-1 extractor.
 *
 * Structure comes from a brace walk over the masked source: the text between the last
 * statement/block boundary and a `{` or `;` is the declaration head, which is all a
 * syntactic extractor needs. Multi-line signatures fall out for free, and method bodies
 * can never be mistaken for declarations because every `;` resets the head.
 *
 * Annotation arguments are read back from the RAW source at the same offsets, because the
 * masked text has had the route path and topic name blanked out along with every other
 * string literal.
 */
final class JavaExtractor implements Extractor {

    static final Set<String> NOT_DECL = Set.of(
            "if", "for", "while", "switch", "try", "catch", "finally", "else", "do", "return",
            "throw", "new", "case", "assert", "synchronized", "yield", "instanceof", "break", "continue");

    private static final Set<String> MAPPING = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping");

    private static final Set<String> STEREOTYPE = Set.of(
            "RestController", "Controller", "Service", "Repository", "Component", "Configuration");

    public String language() { return "java"; }

    public boolean supports(String p) { return p.endsWith(".java"); }

    private static final class Scope {
        String id;              // node id, or null for anonymous blocks
        String qname;
        String type;            // class/interface/enum/record/method/constructor/block
        int openDepth;
        int start;              // offset of `{`
        GraphModel.Node node;
        Map<String, String> vars = new LinkedHashMap<>();  // local/field name -> declared type
        Scope parent;
    }

    public FileFacts extract(String path, String src) {
        FileFacts f = new FileFacts(path, "java");
        char[] mask;
        try {
            mask = Lex.maskCLike(src, true, false);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return f;
        }
        int[] li = Lex.lineIndex(mask);
        char[] raw = src.toCharArray();
        String fileNodeId = GraphModel.fileId(path);

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

        int depth = 0, headStart = 0, n = mask.length;
        boolean sawEnumHeader = false;

        for (int i = 0; i < n; i++) {
            char c = mask[i];
            if (c == '{') {
                String head = new String(mask, headStart, i - headStart);
                String rawHead = new String(raw, headStart, i - headStart);
                int line = Lex.lineOf(li, headStart + leadingWs(head));
                Scope cur = stack.peek();
                Scope s = declare(f, path, head, rawHead, line, cur, i);
                if (s == null) {
                    s = new Scope();
                    s.type = "block";
                    s.qname = cur.qname;
                    s.parent = cur;
                }
                s.openDepth = depth;
                s.start = i;
                depth++;
                stack.push(s);
                allScopes.add(s);
                sawEnumHeader = "enum".equals(s.type);
                headStart = i + 1;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
                while (stack.size() > 1 && stack.peek().openDepth >= depth) {
                    Scope s = stack.pop();
                    if (s.node != null) s.node.endLine = Lex.lineOf(li, i);
                }
                headStart = i + 1;
            } else if (c == ';') {
                String head = new String(mask, headStart, i - headStart);
                String rawHead = new String(raw, headStart, i - headStart);
                int line = Lex.lineOf(li, headStart + leadingWs(head));
                statement(f, path, head, rawHead, line, stack.peek(), sawEnumHeader);
                if (sawEnumHeader) sawEnumHeader = false;
                headStart = i + 1;
            }
        }
        while (!stack.isEmpty()) {
            Scope s = stack.pop();
            if (s.node != null && s.node.endLine == 0) s.node.endLine = Lex.lineOf(li, n > 0 ? n - 1 : 0);
        }

        addImplicitConstructors(f, path);
        collectCalls(f, mask, li, allScopes, fileNodeId);
        return f;
    }

    /**
     * `new CaseRepository()` must resolve even when the class declares no constructor. Java
     * supplies a default one, so the graph does too -- marked implicit so it is never mistaken
     * for something written by hand.
     */
    private static void addImplicitConstructors(FileFacts f, String path) {
        java.util.Set<String> hasCtor = new java.util.LinkedHashSet<>();
        List<GraphModel.Node> types = new ArrayList<>();
        for (GraphModel.Node n : f.nodes) {
            if ("constructor".equals(n.type) && n.qname != null) {
                int dot = n.qname.lastIndexOf('.');
                if (dot > 0) hasCtor.add(n.qname.substring(0, dot));
            } else if ("class".equals(n.type) || "enum".equals(n.type)) {
                types.add(n);
            }
        }
        for (GraphModel.Node t : types) {
            if (hasCtor.contains(t.qname)) continue;
            String cq = t.qname + "." + t.name;
            GraphModel.Node ctor = new GraphModel.Node(
                    GraphModel.symbolId("java", "constructor", path, cq, 0), "constructor", t.name, cq, "java", path);
            ctor.startLine = t.startLine;
            ctor.endLine = t.startLine;
            ctor.visibility = t.visibility;
            ctor.attrs.put("implicit", true);
            f.add(ctor);
            f.contains(t.id, ctor.id, t.startLine);
        }
    }

    private static int leadingWs(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    // ---------- statements that end in `;` ----------

    private void statement(FileFacts f, String path, String head, String rawHead, int line,
                           Scope cur, boolean enumHeader) {
        String t = head.trim();
        if (t.isEmpty()) return;

        if (t.startsWith("package ")) { f.namespace = t.substring(8).trim(); return; }

        if (t.startsWith("import ")) {
            String spec = t.substring(7).trim();
            boolean isStatic = spec.startsWith("static ");
            if (isStatic) spec = spec.substring(7).trim();
            boolean wildcard = spec.endsWith(".*");
            if (wildcard) spec = spec.substring(0, spec.length() - 2);
            String alias = wildcard ? null : spec.substring(spec.lastIndexOf('.') + 1);
            String module = wildcard ? spec : spec.substring(0, Math.max(0, spec.lastIndexOf('.')));
            f.imports.add(new ImportRef(alias, module, wildcard ? null : alias,
                    isStatic ? "java-static" : "java", wildcard, line, t));
            return;
        }

        // A block (if/for/try) carries no node of its own, but locals declared inside it still
        // need their types recorded -- calls made through them are attributed to the method.
        if (cur.node == null && !"file".equals(cur.type) && !"block".equals(cur.type)) return;

        // enum constants: the first `;`-terminated head inside an enum body
        if (enumHeader && cur.node != null && "enum".equals(cur.type)) {
            for (String part : Syntax.splitTop(t, ',')) {
                String name = Syntax.firstIdent(part);
                if (name.isEmpty()) continue;
                GraphModel.Node c = new GraphModel.Node(
                        GraphModel.symbolId("java", "constant", path, cur.qname + "." + name, -1),
                        "constant", name, cur.qname + "." + name, "java", path);
                c.startLine = line;
                c.endLine = line;
                c.visibility = "public";
                f.add(c);
                f.contains(cur.id, c.id, line);
            }
            return;
        }

        // abstract / interface method declaration, or a field
        if (cur.node != null && Syntax.isType(cur.type)) {
            JavaDecl.Decl d = JavaDecl.parse(t, rawHead);
            if (d == null) return;
            if (d.isMethod) {
                emitMethod(f, path, d, line, cur, null);
            } else if (d.name != null && !d.name.isEmpty() && d.typeName != null) {
                String qn = cur.qname + "." + d.name;
                String kind = d.modifiers.contains("static") && d.modifiers.contains("final")
                        ? "constant" : "field";
                GraphModel.Node fn = new GraphModel.Node(
                        GraphModel.symbolId("java", kind, path, qn, -1), kind, d.name, qn, "java", path);
                fn.startLine = line;
                fn.endLine = line;
                fn.visibility = Syntax.visibility(d.modifiers);
                fn.signature = d.typeName + " " + d.name;
                f.add(fn);
                f.contains(cur.id, fn.id, line);
                cur.vars.put(d.name, Syntax.baseType(d.typeName));
                if (!Syntax.isJdkType(d.typeName)) f.refs.add(new Ref(cur.id, null, null, Syntax.baseType(d.typeName), -1, line, "type"));
            }
            return;
        }

        // A local declared inside an if/for/try block must be visible to the whole method:
        // call sites are attributed to the enclosing method, so a type recorded on the block
        // scope alone would never be found again.
        if (cur.node != null || "block".equals(cur.type)) {
            JavaDecl.Decl d = JavaDecl.parse(t, rawHead);
            if (d != null && !d.isMethod && d.name != null && d.typeName != null) {
                Scope target = cur;
                while (target != null && target.node == null && !"file".equals(target.type)) target = target.parent;
                (target == null ? cur : target).vars.put(d.name, Syntax.baseType(d.typeName));
            }
        }
    }

    // ---------- declaration heads that end in `{` ----------

    private Scope declare(FileFacts f, String path, String head, String rawHead, int line, Scope cur, int off) {
        String t = head.trim();
        if (t.isEmpty()) return null;
        JavaDecl.Decl d = JavaDecl.parse(t, rawHead);
        if (d == null) return null;

        if (d.typeKeyword != null) {
            String qn = (cur.qname == null || cur.qname.isEmpty())
                    ? (f.namespace.isEmpty() ? d.name : f.namespace + "." + d.name)
                    : cur.qname + "." + d.name;
            String kind = switch (d.typeKeyword) {
                case "interface" -> "interface";
                case "enum" -> "enum";
                case "record" -> "record";
                case "@interface" -> "type";
                default -> "class";
            };
            GraphModel.Node node = new GraphModel.Node(
                    GraphModel.symbolId("java", kind, path, qn, -1), kind, d.name, qn, "java", path);
            node.startLine = line;
            node.visibility = Syntax.visibility(d.modifiers);
            if (!d.annotations.isEmpty()) node.attrs.put("annotations", new ArrayList<>(d.annotations.keySet()));
            for (String a : d.annotations.keySet()) {
                if (STEREOTYPE.contains(a)) node.attrs.put("stereotype", a);
            }
            String classRoute = null;
            for (Map.Entry<String, String> e : d.annotations.entrySet()) {
                if (MAPPING.contains(e.getKey())) classRoute = Syntax.pathArg(e.getValue());
            }
            if (classRoute != null) node.attrs.put("route_prefix", classRoute);
            if (Syntax.isTestPath(path) || d.name.endsWith("Test") || d.name.startsWith("Test")) node.attrs.put("test", true);
            f.add(node);
            if ("file".equals(cur.type)) f.defines(GraphModel.fileId(path), node.id, line);
            else f.contains(cur.id, node.id, line);

            for (String s : d.extendsNames) f.supers.add(new TypeRef(node.id, s, "EXTENDS", line));
            for (String s : d.implementsNames) f.supers.add(new TypeRef(node.id, s, "IMPLEMENTS", line));

            Scope sc = new Scope();
            sc.id = node.id;
            sc.qname = qn;
            sc.type = kind;
            sc.node = node;
            sc.parent = cur;
            // record components are fields, plus the implicit canonical constructor
            if ("record".equals(kind)) {
                String cq = qn + "." + d.name;
                GraphModel.Node ctor = new GraphModel.Node(
                        GraphModel.symbolId("java", "constructor", path, cq, d.params.size()),
                        "constructor", d.name, cq, "java", path);
                ctor.startLine = line;
                ctor.endLine = line;
                ctor.visibility = "public";
                ctor.signature = d.name + "(" + String.join(", ", d.params) + ")";
                ctor.attrs.put("implicit", true);
                f.add(ctor);
                f.contains(node.id, ctor.id, line);
                for (String p : d.params) {
                    String[] kv = Syntax.splitParam(p);
                    if (kv == null) continue;
                    String fq = qn + "." + kv[1];
                    GraphModel.Node fn = new GraphModel.Node(
                            GraphModel.symbolId("java", "field", path, fq, -1), "field", kv[1], fq, "java", path);
                    fn.startLine = line;
                    fn.endLine = line;
                    fn.visibility = "public";
                    fn.signature = kv[0] + " " + kv[1];
                    f.add(fn);
                    f.contains(node.id, fn.id, line);
                    GraphModel.Node acc = new GraphModel.Node(
                            GraphModel.symbolId("java", "method", path, fq, 0), "method", kv[1], fq, "java", path);
                    acc.startLine = line;
                    acc.endLine = line;
                    acc.visibility = "public";
                    acc.signature = kv[1] + "() : " + kv[0];
                    acc.attrs.put("implicit", true);
                    f.add(acc);
                    f.contains(node.id, acc.id, line);
                    sc.vars.put(kv[1], Syntax.baseType(kv[0]));
                }
            }
            return sc;
        }

        if (d.isMethod && cur.node != null && Syntax.isType(cur.type)) {
            return emitMethod(f, path, d, line, cur, off);
        }
        return null;
    }

    private Scope emitMethod(FileFacts f, String path, JavaDecl.Decl d, int line, Scope cur, Integer off) {
        boolean ctor = d.typeName == null && d.name.equals(Syntax.simple(cur.qname));
        String kind = ctor ? "constructor" : "method";
        String qn = cur.qname + "." + d.name;
        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId("java", kind, path, qn, d.params.size()), kind, d.name, qn, "java", path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = Syntax.visibility(d.modifiers);
        node.signature = d.name + "(" + String.join(", ", d.params) + ")"
                + (d.typeName != null ? " : " + d.typeName : "");
        if (!d.annotations.isEmpty()) node.attrs.put("annotations", new ArrayList<>(d.annotations.keySet()));
        if (d.modifiers.contains("static")) node.attrs.put("static", true);
        if (Syntax.isTestPath(path) || d.annotations.containsKey("Test")) node.attrs.put("test", true);
        f.add(node);
        f.contains(cur.id, node.id, line);

        // route endpoint
        for (Map.Entry<String, String> e : d.annotations.entrySet()) {
            if (MAPPING.contains(e.getKey())) {
                String p = Syntax.pathArg(e.getValue());
                String verb = switch (e.getKey()) {
                    case "GetMapping" -> "GET";
                    case "PostMapping" -> "POST";
                    case "PutMapping" -> "PUT";
                    case "DeleteMapping" -> "DELETE";
                    case "PatchMapping" -> "PATCH";
                    default -> "ANY";
                };
                String prefix = cur.node != null ? String.valueOf(cur.node.attrs.getOrDefault("route_prefix", "")) : "";
                String full = Syntax.joinRoute(prefix, p == null ? "" : p);
                String eq = verb + " " + full;
                GraphModel.Node ep = new GraphModel.Node(
                        GraphModel.symbolId("java", "endpoint", path, eq, -1), "endpoint", eq, eq, "java", path);
                ep.startLine = line;
                ep.endLine = line;
                ep.attrs.put("http_method", verb);
                ep.attrs.put("path", full);
                f.add(ep);
                f.edges.add(new GraphModel.Edge(ep.id, node.id, "ROUTES_TO", GraphModel.EXTRACTED, path, line, "spring-annotation"));
                f.defines(GraphModel.fileId(path), ep.id, line);
            }
            if (e.getKey().equals("KafkaListener")) {
                String topic = Syntax.pathArg(e.getValue());
                if (topic != null && !topic.isEmpty()) {
                    GraphModel.Node tn = new GraphModel.Node(
                            GraphModel.pkgId("topic", topic), "topic", topic, topic, "java", null);
                    f.add(tn);
                    f.edges.add(new GraphModel.Edge(node.id, tn.id, "CONSUMES", GraphModel.EXTRACTED, path, line, "spring-annotation"));
                }
            }
        }

        Scope sc = new Scope();
        sc.id = node.id;
        sc.qname = qn;
        sc.type = kind;
        sc.node = node;
        sc.parent = cur;
        for (String p : d.params) {
            String[] kv = Syntax.splitParam(p);
            if (kv != null) sc.vars.put(kv[1], Syntax.baseType(kv[0]));
        }
        return off == null ? null : sc;
    }

    // ---------- calls ----------

    private void collectCalls(FileFacts f, char[] mask, int[] li, List<Scope> scopes, String fileNodeId) {
        // Scope lookup used to be a linear scan per call site, which is quadratic in a large
        // generated file and was the reason a 9,655-file repository never finished indexing.
        // Offsets here only ever increase, so a cursor over scopes sorted by start gives the
        // same answer in linear time.
        List<Scope> sorted = new ArrayList<>(scopes);
        sorted.sort((a, b) -> Integer.compare(a.start, b.start));
        int cursor = 0;
        Scope owner = null;
        for (int i = 0; i < mask.length; i++) {
            while (cursor < sorted.size() && sorted.get(cursor).start <= i) {
                Scope s = sorted.get(cursor++);
                if (s.node != null || "file".equals(s.type)) owner = s;
            }
            if (mask[i] != '(') continue;
            String name = Lex.identBefore(mask, i);
            if (name.isEmpty() || NOT_DECL.contains(name)) continue;
            int nameStart = i - name.length();
            String receiver = null;
            if (nameStart > 0 && mask[nameStart - 1] == '.') {
                receiver = Lex.receiverBefore(mask, nameStart - 1);
                if (receiver.isEmpty()) receiver = null;
            } else if (nameStart > 0 && mask[nameStart - 1] == '@') {
                continue; // annotation use, handled from the declaration head
            }
            // a declaration head, not a call: `void foo(` is preceded by a type token
            if (owner == null) continue;
            if (owner.node != null && owner.node.startLine == Lex.lineOf(li, i)
                    && name.equals(owner.node.name) && receiver == null) continue;
            boolean isNew = precededByNew(mask, nameStart);
            int arity = Syntax.arity(mask, i);
            String from = owner.node != null ? owner.node.id : fileNodeId;
            String rt = receiver == null ? null : lookupVar(owner, receiver);
            f.refs.add(new Ref(from, receiver, rt, name, arity, Lex.lineOf(li, i), isNew ? "new" : "call"));
        }
    }

    private static boolean precededByNew(char[] s, int nameStart) {
        int i = nameStart;
        while (i > 0 && Character.isWhitespace(s[i - 1])) i--;
        return i >= 3 && new String(s, i - 3, 3).equals("new")
                && (i - 4 < 0 || !Lex.isIdentPart(s[i - 4]));
    }

    private static String lookupVar(Scope s, String receiver) {
        String head = receiver.contains(".") ? receiver.substring(0, receiver.indexOf('.')) : receiver;
        if (head.equals("this")) {
            String rest = receiver.contains(".") ? receiver.substring(receiver.indexOf('.') + 1) : null;
            if (rest == null) return null;
            head = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
        }
        for (Scope p = s; p != null; p = p.parent) {
            String t = p.vars.get(head);
            if (t != null) return t;
        }
        return null;
    }

    // ---------- head parsing ----------

}
