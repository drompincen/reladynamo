import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Python declaration handling: classes, defs, decorators, imports (including relative ones),
 * assignments, and the receiver-type lookup that stops a call resolving on a guess.
 *
 * Split out of PythonExtractor for the 500-line limit. The extractor still owns the walk and the
 * scope stack; everything here is handed the scope it needs.
 */
final class PyDecl {

    private PyDecl() {}
    static PythonExtractor.Scope emitClass(Extractor.FileFacts f, String path, String fileNodeId, String trimmed, String rawHead,
                            int line, int indent, PythonExtractor.Scope cur, List<PythonExtractor.Decorator> decos, boolean testFile) {
        // class Name(bases):
        String after = trimmed.substring(5).trim();
        String name = PySyntax.firstIdent(after);
        if (name.isEmpty()) return null;

        List<String> bases = new ArrayList<>();
        int lp = after.indexOf('(');
        int colon = after.lastIndexOf(':');
        if (lp >= 0 && (colon < 0 || lp < colon)) {
            int rp = PySyntax.matching(after, lp);
            if (rp > lp) {
                for (String part : PySyntax.splitTop(after.substring(lp + 1, rp), ',')) {
                    String b = part.trim();
                    if (b.isEmpty()) continue;
                    if (b.contains("=")) continue; // keyword arg e.g. metaclass=
                    b = PySyntax.stripGenerics(b).trim();
                    // take dotted name only
                    String bn = PySyntax.takeDottedName(b);
                    if (bn.isEmpty() || "object".equals(bn)) continue;
                    bases.add(bn);
                }
            }
        }

        boolean isEnum = false;
        for (String b : bases) {
            String simple = b.contains(".") ? b.substring(b.lastIndexOf('.') + 1) : b;
            if ("Enum".equals(simple) || "IntEnum".equals(simple) || "StrEnum".equals(simple)
                    || "Flag".equals(simple) || "IntFlag".equals(simple)) {
                isEnum = true;
                break;
            }
        }

        String kind = isEnum ? "enum" : "class";
        String parentQ = cur.qname == null || cur.qname.isEmpty() ? f.namespace : cur.qname;
        // Top-level class: qname is namespace.Name; nested: parent.Name
        String qn;
        if ("file".equals(cur.type) || "module".equals(cur.type) || "function".equals(cur.type)
                || "method".equals(cur.type) || "constructor".equals(cur.type)) {
            if ("file".equals(cur.type) || "module".equals(cur.type)) {
                qn = f.namespace.isEmpty() ? name : f.namespace + "." + name;
            } else {
                qn = parentQ + "." + name;
            }
        } else {
            qn = parentQ + "." + name;
        }

        // Nested class inside class
        if ("class".equals(cur.type) || "enum".equals(cur.type)) {
            qn = cur.qname + "." + name;
        }

        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId("python", kind, path, qn, -1), kind, name, qn, "python", path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = PySyntax.visibility(name);
        applyDecorators(node, decos);
        if (testFile) node.attrs.put("test", true);
        f.add(node);

        if ("file".equals(cur.type) || "module".equals(cur.type)) {
            f.defines(fileNodeId, node.id, line);
        } else if (cur.id != null) {
            f.contains(cur.id, node.id, line);
        } else {
            f.defines(fileNodeId, node.id, line);
        }

        for (String b : bases) {
            f.supers.add(new Extractor.TypeRef(node.id, b, "EXTENDS", line));
        }

        PythonExtractor.Scope sc = new PythonExtractor.Scope();
        sc.id = node.id;
        sc.qname = qn;
        sc.type = kind;
        sc.indent = indent;
        sc.node = node;
        sc.parent = cur;
        sc.classQname = qn;
        sc.isEnum = isEnum;
        return sc;
    }
    static PythonExtractor.Scope emitDef(Extractor.FileFacts f, String path, String fileNodeId, String defLine, String rawHead,
                          int line, int indent, PythonExtractor.Scope cur, List<PythonExtractor.Decorator> decos, boolean testFile) {
        // def name(params) -> ret:
        String after = defLine.substring(3).trim();
        String name = PySyntax.firstIdent(after);
        if (name.isEmpty()) return null;

        List<String> params = new ArrayList<>();
        int lp = after.indexOf('(');
        if (lp >= 0) {
            int rp = PySyntax.matching(after, lp);
            if (rp > lp) {
                for (String p : PySyntax.splitTop(after.substring(lp + 1, rp), ',')) {
                    String t = p.trim();
                    if (!t.isEmpty()) params.add(t);
                }
            }
        }

        boolean inClass = cur != null && ("class".equals(cur.type) || "enum".equals(cur.type));
        boolean isDunderInit = "__init__".equals(name);
        String kind;
        if (inClass) {
            kind = isDunderInit ? "constructor" : "method";
        } else if (testFile && name.startsWith("test")) {
            kind = "test";
        } else {
            kind = "function";
        }

        String qn;
        if (inClass) {
            qn = cur.qname + "." + name;
        } else if ("function".equals(cur.type) || "method".equals(cur.type) || "constructor".equals(cur.type)
                || "test".equals(cur.type)) {
            qn = cur.qname + "." + name;
        } else {
            qn = f.namespace.isEmpty() ? name : f.namespace + "." + name;
        }

        int arity = params.size();
        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId("python", kind, path, qn, arity), kind, name, qn, "python", path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = PySyntax.visibility(name);
        node.signature = name + "(" + String.join(", ", params) + ")";
        applyDecorators(node, decos);
        if (testFile) node.attrs.put("test", true);
        f.add(node);

        if (inClass) {
            f.contains(cur.id, node.id, line);
        } else if ("function".equals(cur.type) || "method".equals(cur.type) || "constructor".equals(cur.type)
                || "test".equals(cur.type)) {
            f.contains(cur.id, node.id, line);
        } else {
            f.defines(fileNodeId, node.id, line);
        }

        // Route endpoints from decorators like @router.get("/cases/{id}")
        for (PythonExtractor.Decorator d : decos) {
            maybeEndpoint(f, path, fileNodeId, node, d, line);
        }

        PythonExtractor.Scope sc = new PythonExtractor.Scope();
        sc.id = node.id;
        sc.qname = qn;
        sc.type = kind;
        sc.indent = indent;
        sc.node = node;
        sc.parent = cur;
        sc.classQname = inClass ? cur.qname : (cur != null ? cur.classQname : null);

        // Bind parameters (and self/cls).
        for (int i = 0; i < params.size(); i++) {
            String[] kv = PySyntax.splitPyParam(params.get(i));
            if (kv == null) continue;
            String pname = kv[0];
            String ptype = kv[1];
            if ("self".equals(pname) && sc.classQname != null) {
                sc.vars.put("self", sc.classQname);
            } else if ("cls".equals(pname) && sc.classQname != null) {
                sc.vars.put("cls", sc.classQname);
            } else if (ptype != null) {
                sc.vars.put(pname, PySyntax.baseType(ptype));
            }
        }
        // classmethod/staticmethod: still bind cls if first param is cls even without decorator check
        return sc;
    }
    static void maybeEndpoint(Extractor.FileFacts f, String path, String fileNodeId, GraphModel.Node fn,
                               PythonExtractor.Decorator d, int line) {
        // full like "router.get" or "app.post"
        if (d.full == null || !d.full.contains(".")) return;
        int dot = d.full.lastIndexOf('.');
        String verb = d.full.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!PythonExtractor.HTTP_VERBS.contains(verb)) return;
        String routePath = PySyntax.pathArg(d.rawArgs);
        if (routePath == null) routePath = "";
        String http = verb.toUpperCase(Locale.ROOT);
        String eq = http + " " + routePath;
        GraphModel.Node ep = new GraphModel.Node(
                GraphModel.symbolId("python", "endpoint", path, eq, -1),
                "endpoint", eq, eq, "python", path);
        ep.startLine = line;
        ep.endLine = line;
        ep.attrs.put("http_method", http);
        ep.attrs.put("path", routePath);
        f.add(ep);
        f.edges.add(new GraphModel.Edge(ep.id, fn.id, "ROUTES_TO", GraphModel.EXTRACTED, path, line, "decorator"));
        f.defines(fileNodeId, ep.id, line);
    }
    static void handleClassBodyLine(Extractor.FileFacts f, String path, String head, int line, PythonExtractor.Scope cur) {
        String t = head.trim();
        if (t.startsWith("#") || t.isEmpty()) return;
        // Skip nested control-flow headers if any slip through
        if (PySyntax.isKeywordAt(t, "def") || PySyntax.isKeywordAt(t, "class") || t.startsWith("async ")) return;

        // Annotated field: name: Type or name: Type = ...
        int colon = PySyntax.indexOfTop(t, ':');
        int eq = PySyntax.indexOfTop(t, '=');
        if (colon > 0 && (eq < 0 || colon < eq)) {
            String namePart = t.substring(0, colon).trim();
            String name = PySyntax.firstIdent(namePart);
            if (name.isEmpty() || !name.equals(namePart)) {
                // not a simple field (could be something else)
            } else {
                String rest = t.substring(colon + 1).trim();
                String typePart = rest;
                if (eq > colon) {
                    // type is between : and =
                    typePart = t.substring(colon + 1, eq).trim();
                }
                String typeName = PySyntax.baseType(typePart);
                String qn = cur.qname + "." + name;
                String kind = "field";
                GraphModel.Node fn = new GraphModel.Node(
                        GraphModel.symbolId("python", kind, path, qn, -1), kind, name, qn, "python", path);
                fn.startLine = line;
                fn.endLine = line;
                fn.visibility = PySyntax.visibility(name);
                if (typeName != null && !typeName.isEmpty()) {
                    fn.signature = typeName + " " + name;
                    cur.vars.put(name, typeName);
                }
                f.add(fn);
                f.contains(cur.id, fn.id, line);
                return;
            }
        }

        // Assignment: NAME = ...  (enum constant or class-level constant)
        if (eq > 0) {
            String lhs = t.substring(0, eq).trim();
            String name = PySyntax.firstIdent(lhs);
            if (name.isEmpty() || !name.equals(lhs)) return;
            if (PySyntax.isUpperName(name) || cur.isEnum) {
                String qn = cur.qname + "." + name;
                GraphModel.Node cn = new GraphModel.Node(
                        GraphModel.symbolId("python", "constant", path, qn, -1),
                        "constant", name, qn, "python", path);
                cn.startLine = line;
                cn.endLine = line;
                cn.visibility = PySyntax.visibility(name);
                f.add(cn);
                f.contains(cur.id, cn.id, line);
            }
            String rhs = t.substring(eq + 1).trim();
            String ctor = PySyntax.constructorName(rhs);
            if (ctor != null) cur.vars.put(name, ctor);
        }
    }
    static void handleAssignment(String head, PythonExtractor.Scope cur) {
        String t = head.trim();
        if (t.isEmpty() || t.startsWith("#")) return;
        int eq = PySyntax.indexOfTop(t, '=');
        if (eq <= 0) return;
        // Ignore ==, !=, <=, >=, :=
        if (eq + 1 < t.length() && t.charAt(eq + 1) == '=') return;
        if (eq > 0 && (t.charAt(eq - 1) == '!' || t.charAt(eq - 1) == '<' || t.charAt(eq - 1) == '>'
                || t.charAt(eq - 1) == ':')) return;

        String lhs = t.substring(0, eq).trim();
        String rhs = t.substring(eq + 1).trim();

        // self.repo = CaseRepository()
        if (lhs.startsWith("self.") && lhs.indexOf('.', 5) < 0) {
            String field = lhs.substring(5).trim();
            if (PySyntax.isIdent(field)) {
                String ctor = PySyntax.constructorName(rhs);
                if (ctor != null) {
                    PythonExtractor.Scope classScope = cur;
                    while (classScope != null && !"class".equals(classScope.type)
                            && !"enum".equals(classScope.type)) {
                        classScope = classScope.parent;
                    }
                    if (classScope != null) classScope.vars.put(field, ctor);
                    cur.vars.put("self." + field, ctor);
                }
            }
            return;
        }

        // name: Type = ...  or name = ...
        int colon = PySyntax.indexOfTop(lhs, ':');
        String name;
        String ann = null;
        if (colon > 0) {
            name = PySyntax.firstIdent(lhs.substring(0, colon).trim());
            ann = PySyntax.baseType(lhs.substring(colon + 1).trim());
            if (!PySyntax.isIdent(lhs.substring(0, colon).trim())) return;
        } else {
            name = PySyntax.firstIdent(lhs);
            if (!PySyntax.isIdent(lhs)) return;
        }
        if (name == null || name.isEmpty()) return;

        if (ann != null) {
            cur.vars.put(name, ann);
        } else {
            String ctor = PySyntax.constructorName(rhs);
            if (ctor != null) cur.vars.put(name, ctor);
            else if (PySyntax.isIdent(rhs) || PySyntax.isDottedName(rhs)) {
                // alias = other_name (e.g. format_id = str)
                String b = PySyntax.baseType(rhs);
                if (b != null) cur.vars.put(name, b);
            }
        }
    }
    /** Detect module-level UPPER_CASE = ... assignment; returns the name or null. */
    static String moduleConstantName(String t) {
        if (t == null || t.isEmpty()) return null;
        int eq = PySyntax.indexOfTop(t, '=');
        if (eq <= 0) return null;
        if (eq + 1 < t.length() && t.charAt(eq + 1) == '=') return null;
        if (t.charAt(eq - 1) == '!' || t.charAt(eq - 1) == '<' || t.charAt(eq - 1) == '>'
                || t.charAt(eq - 1) == ':') return null;
        String lhs = t.substring(0, eq).trim();
        int colon = PySyntax.indexOfTop(lhs, ':');
        if (colon > 0) lhs = lhs.substring(0, colon).trim();
        if (!PySyntax.isIdent(lhs) || !PySyntax.isUpperName(lhs)) return null;
        return lhs;
    }
    static void emitModuleConstant(Extractor.FileFacts f, String path, String fileNodeId, String name, int line) {
        String qn = f.namespace.isEmpty() ? name : f.namespace + "." + name;
        GraphModel.Node cn = new GraphModel.Node(
                GraphModel.symbolId("python", "constant", path, qn, -1),
                "constant", name, qn, "python", path);
        cn.startLine = line;
        cn.endLine = line;
        cn.visibility = PySyntax.visibility(name);
        f.add(cn);
        f.defines(fileNodeId, cn.id, line);
    }
    /** Record a plain `name = ...` binding so later calls know the name is shadowed. */
    static void recordAssignedName(String head, PythonExtractor.Scope cur) {
        String t = head.trim();
        int eq = t.indexOf('=');
        if (eq <= 0) return;
        if (eq + 1 < t.length() && (t.charAt(eq + 1) == '=' )) return;
        char prev = t.charAt(eq - 1);
        if (prev == '!' || prev == '<' || prev == '>' || prev == '=') return;
        String lhs = t.substring(0, eq).trim();
        if (lhs.isEmpty() || lhs.contains(" ") || lhs.contains(".") || lhs.contains("(") || lhs.contains("[")) return;
        if (!Lex.isIdentStart(lhs.charAt(0))) return;
        for (int i = 1; i < lhs.length(); i++) if (!Lex.isIdentPart(lhs.charAt(i))) return;
        cur.assigned.add(lhs);
    }
    /** Walk enclosing function scopes looking for a local binding of this name. */
    static boolean shadowedLocally(PythonExtractor.Scope owner, String name) {
        for (PythonExtractor.Scope s = owner; s != null; s = s.parent) {
            if (s.assigned.contains(name)) return true;
            if ("class".equals(s.type) || "file".equals(s.type) || "module".equals(s.type)) return false;
        }
        return false;
    }
    static String lookupReceiverType(PythonExtractor.Scope s, String receiver, List<PythonExtractor.Scope> scopes, int off) {
        if (receiver == null) {
            return null;
        }
        // self / cls
        if ("self".equals(receiver) || "cls".equals(receiver)) {
            for (PythonExtractor.Scope p = s; p != null; p = p.parent) {
                String t = p.vars.get(receiver);
                if (t != null) return t;
                if (p.classQname != null) return p.classQname;
            }
            return null;
        }
        // super().method — receiver type is the declared base when there is exactly one useful base;
        // otherwise leave null and let the resolver use EXTENDS edges.
        if ("super".equals(receiver)) {
            return null;
        }

        String head = receiver;
        String rest = null;
        int dot = receiver.indexOf('.');
        if (dot >= 0) {
            head = receiver.substring(0, dot);
            rest = receiver.substring(dot + 1);
        }

        if ("self".equals(head) || "cls".equals(head)) {
            // self.repo -> look up repo on class scope
            String field = rest == null ? null : (rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest);
            if (field == null) {
                for (PythonExtractor.Scope p = s; p != null; p = p.parent) {
                    if (p.classQname != null) return p.classQname;
                }
                return null;
            }
            for (PythonExtractor.Scope p = s; p != null; p = p.parent) {
                if ("class".equals(p.type) || "enum".equals(p.type)) {
                    String t = p.vars.get(field);
                    if (t != null) return t;
                }
                String t = p.vars.get(field);
                if (t != null && ("class".equals(p.type) || "enum".equals(p.type))) return t;
            }
            // Also check class scope vars for field stored under field name
            for (PythonExtractor.Scope p = s; p != null; p = p.parent) {
                String t = p.vars.get(field);
                if (t != null) return t;
            }
            return null; // untyped attribute — leave null (mystery.save trap)
        }

        for (PythonExtractor.Scope p = s; p != null; p = p.parent) {
            String t = p.vars.get(head);
            if (t != null) return t;
        }
        // Walk all scopes that contain this offset for file-level import bindings
        for (PythonExtractor.Scope sc : scopes) {
            if ("file".equals(sc.type) || "module".equals(sc.type)) {
                String t = sc.vars.get(head);
                if (t != null) return t;
            }
        }
        return null;
    }
    static void applyDecorators(GraphModel.Node node, List<PythonExtractor.Decorator> decos) {
        if (decos == null || decos.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (PythonExtractor.Decorator d : decos) {
            names.add(d.name);
            if ("dataclass".equals(d.name)) node.attrs.put("dataclass", true);
            if ("staticmethod".equals(d.name) || "classmethod".equals(d.name)) {
                node.attrs.put("static", true);
            }
        }
        node.attrs.put("decorators", names);
    }
}
