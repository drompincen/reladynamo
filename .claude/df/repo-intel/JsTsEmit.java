import java.util.ArrayList;
import java.util.List;

/**
 * Node emission for TypeScript and JavaScript declarations: top-level declarations, class
 * members, fields and enum members, with their containment edges and the export mapping the
 * resolver later chains re-exports through.
 */
final class JsTsEmit {

    private JsTsEmit() {}

    static JsTsExtractor.Scope emitMethod(Extractor.FileFacts f, String path, String fileLang, JsTsExtractor.Decl d, int line,
                             JsTsExtractor.Scope cur, Integer off, boolean testFile) {
        boolean ctor = "constructor".equals(d.name);
        String kind = ctor ? "constructor" : "method";
        String qn = cur.qname + "." + d.name;
        int arity = d.params.size();
        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId(fileLang, kind, path, qn, arity),
                kind, d.name, qn, fileLang, path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = JsTsSyntax.visibility(d.modifiers);
        node.signature = d.name + "(" + String.join(", ", d.params) + ")"
                + (d.typeName != null ? " : " + d.typeName : "");
        if (d.modifiers.contains("static")) node.attrs.put("static", true);
        if (testFile) node.attrs.put("test", true);
        f.add(node);
        f.contains(cur.id, node.id, line);

        // parameter properties: constructor(private svc: CaseService)
        if (ctor) {
            for (String p : d.params) {
                String[] kv = JsTsScan.splitTsParam(p);
                if (kv == null) continue;
                if (kv.length >= 3 && kv[2] != null) {
                    // access-modifier parameter property -> field
                    JsTsExtractor.Decl fd = new JsTsExtractor.Decl();
                    fd.name = kv[1];
                    fd.typeName = kv[0];
                    fd.modifiers.add(kv[2]);
                    emitField(f, path, fileLang, fd, line, cur, testFile);
                }
                if (kv[0] != null && !"any".equals(kv[0]) && !"unknown".equals(kv[0])) {
                    // bound on method scope below
                }
            }
        }

        if (off == null) {
            // abstract / signature-only
            return null;
        }
        JsTsExtractor.Scope sc = new JsTsExtractor.Scope();
        sc.id = node.id;
        sc.qname = qn;
        sc.type = kind;
        sc.node = node;
        sc.parent = cur;
        JsTsDecl.bindParams(sc, d);
        // this -> enclosing class for receiverType lookups
        sc.vars.put("this", cur.qname);
        return sc;
    }

    static void emitField(Extractor.FileFacts f, String path, String fileLang, JsTsExtractor.Decl d, int line,
                           JsTsExtractor.Scope cur, boolean testFile) {
        String qn = cur.qname + "." + d.name;
        GraphModel.Node fn = new GraphModel.Node(
                GraphModel.symbolId(fileLang, "field", path, qn, -1),
                "field", d.name, qn, fileLang, path);
        fn.startLine = line;
        fn.endLine = line;
        fn.visibility = JsTsSyntax.visibility(d.modifiers);
        if (d.typeName != null) fn.signature = d.typeName + " " + d.name;
        if (testFile) fn.attrs.put("test", true);
        f.add(fn);
        f.contains(cur.id, fn.id, line);
        if (d.typeName != null && !"any".equals(d.typeName) && !"unknown".equals(d.typeName)) {
            cur.vars.put(d.name, JsTsSyntax.baseType(d.typeName));
        }
    }

    static GraphModel.Node emitTopDecl(Extractor.FileFacts f, String path, String fileLang, JsTsExtractor.Decl d, int line,
                                        JsTsExtractor.Scope cur, boolean testFile, boolean exportedOk) {
        if (d.name == null || d.name.isEmpty()) return null;
        String kind;
        int arity = -1;
        if (d.typeKeyword != null) {
            kind = switch (d.typeKeyword) {
                case "interface" -> "interface";
                case "enum" -> "enum";
                case "type" -> "type";
                default -> "class";
            };
        } else if (d.isMethod) {
            kind = "function";
            arity = d.params.size();
        } else {
            kind = "constant";
        }
        String qn = d.name;
        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId(fileLang, kind, path, qn, arity),
                kind, d.name, qn, fileLang, path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = JsTsSyntax.visibility(d.modifiers);
        if (d.isMethod) node.signature = d.name + "(" + String.join(", ", d.params) + ")";
        if (testFile) node.attrs.put("test", true);
        f.add(node);
        if (JsTsSyntax.isTopLevel(cur)) f.defines(GraphModel.fileId(path), node.id, line);
        else if (cur != null && cur.id != null) f.contains(cur.id, node.id, line);

        if (exportedOk) {
            if (d.isExport) f.exports.put(d.name, node.id);
            if (d.isDefaultExport) f.exports.put("default", node.id);
        }
        return node;
    }

    static void emitEnumMembers(Extractor.FileFacts f, String path, String fileLang, String head,
                                 String rawHead, int line, JsTsExtractor.Scope cur) {
        for (String part : JsTsSyntax.splitTop(head, ',')) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // Name = value  or  Name
            String name = JsTsSyntax.firstIdent(p);
            if (name.isEmpty() || JsTsExtractor.MODIFIERS.contains(name)) continue;
            String qn = cur.qname + "." + name;
            GraphModel.Node c = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, "constant", path, qn, -1),
                    "constant", name, qn, fileLang, path);
            c.startLine = line;
            c.endLine = line;
            c.visibility = "public";
            f.add(c);
            f.contains(cur.id, c.id, line);
        }
    }
}
