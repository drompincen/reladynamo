import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass 2: repository-wide resolution.
 *
 * The governing rule is that a wrong confident edge costs more than a missing edge. So every
 * resolution path here is syntax-directed -- an import binding, a declared type, a package, an
 * include -- and a bare simple-name coincidence is never enough on its own to produce a CALLS
 * edge. When more than one target is genuinely plausible the edges are emitted as AMBIGUOUS
 * rather than silently collapsed to the first match.
 */
final class Resolver {

    private static final int MAX_CANDIDATES = 4;

    private final GraphModel.Graph g;
    private final Map<String, Extractor.FileFacts> facts;
    private final Map<String, Set<String>> includes = new HashMap<>();

    int resolved, ambiguous, unresolved;

    private ResolverIndex idx;

    Resolver(GraphModel.Graph g, Map<String, Extractor.FileFacts> facts) {
        this.g = g;
        this.facts = facts;
    }

    private static final class Cand {
        final List<GraphModel.Node> nodes = new ArrayList<>();
        boolean direct;
    }

    void run() {
        idx = new ResolverIndex(g, facts);
        for (Extractor.FileFacts f : facts.values()) resolveImports(f);
        buildIncludeClosure();
        for (Extractor.FileFacts f : facts.values()) {
            resolveSupers(f);
            resolveDeps(f);
            switch (f.language == null ? "" : f.language) {
                case "java" -> javaRefs(f);
                case "python" -> pythonRefs(f);
                case "typescript", "javascript" -> tsRefs(f);
                case "bash" -> bashRefs(f);
                default -> { }
            }
        }
        markTests();
        g.dedupeEdges();
    }

    // ---------- indexes ----------

    private void emit(String from, List<GraphModel.Node> cands, String relation, String file,
                      int line, String resolverName, String singleConfidence) {
        if (from == null || cands.isEmpty()) { unresolved++; return; }
        List<GraphModel.Node> uniq = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GraphModel.Node n : cands) if (n != null && seen.add(n.id)) uniq.add(n);
        if (uniq.isEmpty()) { unresolved++; return; }
        if (uniq.size() == 1) {
            g.addEdge(new GraphModel.Edge(from, uniq.get(0).id, relation, singleConfidence, file, line, resolverName));
            resolved++;
            return;
        }
        for (int i = 0; i < Math.min(uniq.size(), MAX_CANDIDATES); i++) {
            g.addEdge(new GraphModel.Edge(from, uniq.get(i).id, relation, GraphModel.AMBIGUOUS, file, line, resolverName));
        }
        ambiguous++;
    }

    // ---------- imports ----------

    private void resolveImports(Extractor.FileFacts f) {
        String fileId = GraphModel.fileId(f.path);
        for (Extractor.ImportRef im : f.imports) {
            switch (im.kind == null ? "" : im.kind) {
                case "java", "java-static" -> {
                    if (im.wildcard) {
                        // `import com.acme.model.*` imports a package: record the package itself
                        // and every repository type it actually brings into scope.
                        String pkg = im.module == null ? "" : im.module;
                        if (pkg.isEmpty()) continue;
                        GraphModel.Node pn = idx.packageNode(pkg);
                        g.addEdge(new GraphModel.Edge(fileId, pn.id, "IMPORTS", GraphModel.EXTRACTED,
                                f.path, im.line, "java-wildcard-import"));
                        for (GraphModel.Node n : idx.typesInPackage(pkg)) {
                            g.addEdge(new GraphModel.Edge(fileId, n.id, "IMPORTS", GraphModel.EXTRACTED,
                                    f.path, im.line, "java-wildcard-import"));
                        }
                        continue;
                    }
                    String q = im.module == null || im.module.isEmpty() ? im.member : im.module + "." + im.member;
                    if (q == null) continue;
                    List<GraphModel.Node> c = new ArrayList<>(idx.byQname.getOrDefault(q, List.of()));
                    if (!c.isEmpty()) {
                        emit(fileId, c, "IMPORTS", f.path, im.line, "java-import", GraphModel.EXTRACTED);
                    } else {
                        // The import names a symbol this repository does not define. That is still
                        // a fact worth keeping: it is how "who uses Spring" gets answered.
                        GraphModel.Node ext = idx.externalNode("java", q);
                        g.addEdge(new GraphModel.Edge(fileId, ext.id, "IMPORTS", GraphModel.EXTRACTED, f.path, im.line, "java-import-external"));
                    }
                }
                case "python" -> {
                    String q = im.member == null || im.member.isEmpty() ? im.module : im.module + "." + im.member;
                    List<GraphModel.Node> c = new ArrayList<>(idx.byQname.getOrDefault(q, List.of()));
                    if (c.isEmpty() && idx.repoFiles.contains(ResolverIndex.moduleToPath(q))) {
                        GraphModel.Node fn = g.nodes.get(GraphModel.fileId(ResolverIndex.moduleToPath(q)));
                        if (fn != null) c.add(fn);
                    }
                    if (!c.isEmpty()) {
                        emit(fileId, c, "IMPORTS", f.path, im.line, "python-import", GraphModel.EXTRACTED);
                    } else if (q != null && !q.isEmpty()) {
                        GraphModel.Node ext = idx.externalNode("python", q);
                        g.addEdge(new GraphModel.Edge(fileId, ext.id, "IMPORTS", GraphModel.EXTRACTED, f.path, im.line, "python-import-external"));
                    }
                }
                case "es", "cjs" -> {
                    String target = idx.resolveSpec(f.path, im.module);
                    if (target != null) {
                        GraphModel.Node fn = g.nodes.get(GraphModel.fileId(target));
                        if (fn != null) emit(fileId, List.of(fn), "IMPORTS", f.path, im.line, "es-module-path", GraphModel.EXTRACTED);
                        // and the specific binding, which is what impact analysis actually follows
                        if (!im.wildcard && im.member != null && !im.member.isEmpty()) {
                            List<GraphModel.Node> mem = exported(target, im.member);
                            if (!mem.isEmpty()) emit(fileId, mem, "IMPORTS", f.path, im.line, "es-named-import", GraphModel.EXTRACTED);
                        }
                    } else if (im.module != null && !im.module.isEmpty()
                            && !im.module.startsWith(".") && !im.module.startsWith("/")) {
                        String pkg = ResolverIndex.externalNpmName(im.module);
                        GraphModel.Node n = idx.pkgNode("npm", pkg);
                        g.addEdge(new GraphModel.Edge(fileId, n.id, "IMPORTS", GraphModel.EXTRACTED, f.path, im.line, "external-module"));
                    }
                }
                case "shell" -> { /* handled by the include closure */ }
                default -> { }
            }
        }
    }

    // ---------- inheritance ----------

    private void resolveSupers(Extractor.FileFacts f) {
        for (Extractor.TypeRef t : f.supers) {
            Cand c = typeCandidates(f, t.name);
            if (!c.nodes.isEmpty()) {
                emit(t.from, c.nodes, t.relation, f.path, t.line,
                        f.language + "-type-resolution", c.direct ? GraphModel.EXTRACTED : GraphModel.INFERRED);
                continue;
            }
            // Not defined here. If an import names it, the supertype is an external symbol and
            // saying so is more useful than dropping the relationship on the floor.
            String simple = t.name.contains(".") ? t.name.substring(t.name.lastIndexOf('.') + 1) : t.name;
            for (Extractor.ImportRef im : f.imports) {
                if (im.alias == null || !im.alias.equals(simple)) continue;
                String q = im.module == null || im.module.isEmpty() ? im.member : im.module + "." + im.member;
                if (q == null || q.isEmpty()) continue;
                String eco = "python".equals(f.language) ? "python" : "java".equals(f.language) ? "java" : "npm";
                GraphModel.Node ext = idx.externalNode(eco, q);
                g.addEdge(new GraphModel.Edge(t.from, ext.id, t.relation, GraphModel.EXTRACTED,
                        f.path, t.line, f.language + "-external-supertype"));
                break;
            }
        }
    }

    /** Language-aware type lookup. `direct` means the syntax pointed at the target unambiguously. */
    private Cand typeCandidates(Extractor.FileFacts f, String name) {
        Cand c = new Cand();
        if (name == null || name.isEmpty()) return c;
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;

        if (name.contains(".")) {
            c.nodes.addAll(idx.typesByQ(name));
            if (!c.nodes.isEmpty()) { c.direct = true; return c; }
        }
        for (Extractor.ImportRef im : f.imports) {
            if (im.alias == null || !im.alias.equals(simple)) continue;
            String q = null;
            if ("java".equals(im.kind) || "java-static".equals(im.kind) || "python".equals(im.kind)) {
                q = im.module == null || im.module.isEmpty() ? im.member : im.module + "." + im.member;
            } else if ("es".equals(im.kind) || "cjs".equals(im.kind)) {
                String tf = idx.resolveSpec(f.path, im.module);
                if (tf != null) {
                    for (GraphModel.Node n : idx.byFile.getOrDefault(tf, List.of())) {
                        if (ResolverIndex.isTypeNode(n) && (n.name.equals(im.member) || n.name.equals(simple))) c.nodes.add(n);
                    }
                }
            }
            if (q != null) c.nodes.addAll(idx.typesByQ(q));
            if (!c.nodes.isEmpty()) { c.direct = true; return c; }
        }
        for (Extractor.ImportRef im : f.imports) {
            if (im.wildcard && im.module != null && !im.module.isEmpty()) c.nodes.addAll(idx.typesByQ(im.module + "." + simple));
        }
        if (!c.nodes.isEmpty()) { c.direct = true; return c; }
        if (f.namespace != null && !f.namespace.isEmpty()) {
            c.nodes.addAll(idx.typesByQ(f.namespace + "." + simple));
            if (!c.nodes.isEmpty()) { c.direct = true; return c; }
        }
        for (GraphModel.Node n : idx.byFile.getOrDefault(f.path, List.of())) {
            if (ResolverIndex.isTypeNode(n) && simple.equals(n.name)) c.nodes.add(n);
        }
        if (!c.nodes.isEmpty()) { c.direct = true; return c; }
        for (GraphModel.Node n : idx.bySimple.getOrDefault(simple, List.of())) if (ResolverIndex.isTypeNode(n)) c.nodes.add(n);
        return c;
    }

    // ---------- dependencies ----------

    private void resolveDeps(Extractor.FileFacts f) {
        for (Extractor.DepRef d : f.deps) {
            GraphModel.Node n = idx.pkgNode(d.ecosystem, d.name);
            if (d.version != null && !d.version.isEmpty()) n.attrs.putIfAbsent("version", d.version);
            g.addEdge(new GraphModel.Edge(GraphModel.fileId(f.path), n.id, "DEPENDS_ON", GraphModel.EXTRACTED,
                    f.path, d.line, d.indirect ? "manifest-indirect" : "manifest"));
        }
    }

    // ---------- calls, per language ----------

    private void javaRefs(Extractor.FileFacts f) {
        Map<String, String> staticImports = new LinkedHashMap<>();
        for (Extractor.ImportRef im : f.imports) {
            if ("java-static".equals(im.kind) && im.alias != null) staticImports.put(im.alias, im.module);
        }
        for (Extractor.Ref r : f.refs) {
            List<GraphModel.Node> c = new ArrayList<>();
            if ("type".equals(r.kind)) {
                Cand tc = typeCandidates(f, r.name);
                if (tc.direct) emit(r.from, tc.nodes, "REFERENCES", f.path, r.line, "java-type-resolution", GraphModel.EXTRACTED);
                continue;
            }
            if ("new".equals(r.kind)) {
                Cand tc = typeCandidates(f, r.name);
                for (GraphModel.Node t : tc.nodes) c.addAll(idx.methodsOf(t, Syntax.simple(t.qname), r.arity, 0));
                if (c.isEmpty() && tc.direct) {
                    emit(r.from, tc.nodes, "REFERENCES", f.path, r.line, "java-constructor", GraphModel.EXTRACTED);
                    continue;
                }
                emit(r.from, c, "CALLS", f.path, r.line, "java-constructor", GraphModel.INFERRED);
                continue;
            }
            if (r.receiver == null) {
                GraphModel.Node t = idx.enclosingType(r.from);
                c.addAll(idx.methodsOf(t, r.name, r.arity, 0));
                if (c.isEmpty() && staticImports.containsKey(r.name)) {
                    for (GraphModel.Node st : idx.typesByQ(staticImports.get(r.name))) {
                        c.addAll(idx.methodsOf(st, r.name, r.arity, 0));
                    }
                }
            } else if (r.receiverType != null) {
                for (GraphModel.Node t : typeCandidates(f, r.receiverType).nodes) c.addAll(idx.methodsOf(t, r.name, r.arity, 0));
            } else if ("this".equals(r.receiver)) {
                c.addAll(idx.methodsOf(idx.enclosingType(r.from), r.name, r.arity, 0));
            } else if (!r.receiver.isEmpty() && Character.isUpperCase(r.receiver.charAt(0))) {
                Cand tc = typeCandidates(f, r.receiver);
                if (tc.direct) for (GraphModel.Node t : tc.nodes) c.addAll(idx.methodsOf(t, r.name, r.arity, 0));
            }
            emit(r.from, c, "CALLS", f.path, r.line, "java-import-and-type-resolution", GraphModel.INFERRED);
        }
    }

    private void pythonRefs(Extractor.FileFacts f) {
        Map<String, String> bind = new LinkedHashMap<>();
        for (Extractor.ImportRef im : f.imports) {
            if (im.alias == null) continue;
            String target = im.member == null || im.member.isEmpty() ? im.module : im.module + "." + im.member;
            bind.put(im.alias, target);
        }
        for (Extractor.Ref r : f.refs) {
            List<GraphModel.Node> c = new ArrayList<>();
            if ("cls".equals(r.receiver) || "cls".equals(r.name)) {
                // inside a classmethod, cls IS the enclosing class
                GraphModel.Node t = idx.enclosingType(r.from);
                if (t != null) {
                    List<GraphModel.Node> ctor = "cls".equals(r.name) ? idx.methodsOf(t, "__init__", -1, 0)
                            : idx.methodsOf(t, r.name, r.arity, 0);
                    emit(r.from, ctor.isEmpty() ? List.of(t) : ctor, "CALLS", f.path, r.line,
                            "python-classmethod", GraphModel.INFERRED);
                    continue;
                }
            }
            if ("super".equals(r.receiver)) {
                GraphModel.Node t = idx.enclosingType(r.from);
                if (t != null) {
                    for (GraphModel.Edge e : g.outgoing(t.id)) {
                        if ("EXTENDS".equals(e.relation)) c.addAll(idx.methodsOf(g.nodes.get(e.target), r.name, r.arity, 0));
                    }
                }
                emit(r.from, c, "CALLS", f.path, r.line, "python-super", GraphModel.INFERRED);
                continue;
            }
            if (r.receiver == null) {
                if (bind.containsKey(r.name)) c.addAll(idx.callableByQ(bind.get(r.name)));
                if (c.isEmpty() && f.namespace != null && !f.namespace.isEmpty()) {
                    c.addAll(idx.callableByQ(f.namespace + "." + r.name));
                }
                if (c.isEmpty()) c.addAll(idx.methodsOf(idx.enclosingType(r.from), r.name, r.arity, 0));
                if (c.isEmpty()) {
                    // `Case(...)` where Case is a class is a construction, not an unknown call.
                    Cand tc = typeCandidates(f, r.name);
                    if (!tc.nodes.isEmpty()) {
                        // Construction is both a use of the class and a call of its initialiser.
                        emit(r.from, tc.nodes, "CALLS", f.path, r.line, "python-construction", GraphModel.INFERRED);
                        List<GraphModel.Node> ctor = new ArrayList<>();
                        for (GraphModel.Node t : tc.nodes) ctor.addAll(idx.methodsOf(t, "__init__", -1, 0));
                        if (!ctor.isEmpty()) emit(r.from, ctor, "CALLS", f.path, r.line, "python-construction", GraphModel.INFERRED);
                        continue;
                    }
                    if (bind.containsKey(r.name)) {
                        GraphModel.Node ext = idx.externalNode("python", bind.get(r.name));
                        g.addEdge(new GraphModel.Edge(r.from, ext.id, "CALLS", GraphModel.EXTRACTED,
                                f.path, r.line, "python-external-call"));
                        continue;
                    }
                }
            } else {
                String base = bind.get(r.receiver);
                if (base != null) {
                    c.addAll(idx.callableByQ(base + "." + r.name));
                    if (c.isEmpty()) for (GraphModel.Node t : idx.typesByQ(base)) c.addAll(idx.methodsOf(t, r.name, r.arity, 0));
                }
                if (c.isEmpty() && r.receiverType != null) {
                    for (GraphModel.Node t : typeCandidates(f, r.receiverType).nodes) c.addAll(idx.methodsOf(t, r.name, r.arity, 0));
                }
                if (c.isEmpty() && base != null) {
                    // the receiver is an imported module this repository does not define:
                    // `logging.getLogger(...)`. The dependency is real even if the target is not ours.
                    GraphModel.Node ext = idx.externalNode("python", base + "." + r.name);
                    g.addEdge(new GraphModel.Edge(r.from, ext.id, "CALLS", GraphModel.EXTRACTED,
                            f.path, r.line, "python-external-call"));
                    continue;
                }
            }
            emit(r.from, c, "CALLS", f.path, r.line, "python-import-and-scope", GraphModel.INFERRED);
        }
    }

    private void tsRefs(Extractor.FileFacts f) {
        Map<String, String[]> bind = new LinkedHashMap<>();   // alias -> [targetFile, member|null]
        for (Extractor.ImportRef im : f.imports) {
            if (im.alias == null) continue;
            String tf = idx.resolveSpec(f.path, im.module);
            if (tf == null) continue;
            bind.put(im.alias, new String[]{tf, im.wildcard ? null : im.member});
        }
        for (Extractor.Ref r : f.refs) {
            List<GraphModel.Node> c = new ArrayList<>();
            if ("super".equals(r.name) || "super".equals(r.receiver)) {
                GraphModel.Node t = idx.enclosingType(r.from);
                if (t != null) {
                    String member = "super".equals(r.name) ? "constructor" : r.name;
                    for (GraphModel.Edge e : g.outgoing(t.id)) {
                        if ("EXTENDS".equals(e.relation)) c.addAll(idx.methodsOf(g.nodes.get(e.target), member, -1, 0));
                    }
                }
                emit(r.from, c, "CALLS", f.path, r.line, "es-super", GraphModel.INFERRED);
                continue;
            }
            if (r.receiver == null) {
                String[] b = bind.get(r.name);
                if (b != null) {
                    List<GraphModel.Node> bound = exported(b[0], b[1] == null ? r.name : b[1]);
                    // a bound name that turns out to be a class means construction
                    for (GraphModel.Node n : bound) {
                        List<GraphModel.Node> ctor = ResolverIndex.isTypeNode(n) ? idx.methodsOf(n, "constructor", -1, 0) : List.of();
                        if (!ctor.isEmpty()) c.addAll(ctor); else c.add(n);
                    }
                }
                if (c.isEmpty()) {
                    for (GraphModel.Node n : idx.byFile.getOrDefault(f.path, List.of())) {
                        if (r.name.equals(n.name) && ResolverIndex.CALLABLE.contains(n.type)) c.add(n);
                    }
                }
                if (c.isEmpty()) {
                    Cand tc = typeCandidates(f, r.name);
                    if (!tc.nodes.isEmpty()) {
                        List<GraphModel.Node> ctor = new ArrayList<>();
                        for (GraphModel.Node t : tc.nodes) ctor.addAll(idx.methodsOf(t, "constructor", -1, 0));
                        emit(r.from, ctor.isEmpty() ? tc.nodes : ctor, "CALLS", f.path, r.line,
                                "es-construction", GraphModel.INFERRED);
                        continue;
                    }
                }
                if (c.isEmpty()) c.addAll(idx.methodsOf(idx.enclosingType(r.from), r.name, r.arity, 0));
            } else {
                String[] b = bind.get(r.receiver);
                if (b != null) c.addAll(exported(b[0], r.name));
                if (c.isEmpty() && r.receiverType != null && !"any".equals(r.receiverType)) {
                    for (GraphModel.Node t : typeCandidates(f, r.receiverType).nodes) c.addAll(idx.methodsOf(t, r.name, r.arity, 0));
                }
                if (c.isEmpty() && ("this".equals(r.receiver) || r.receiver.startsWith("this."))) {
                    c.addAll(idx.methodsOf(idx.enclosingType(r.from), r.name, r.arity, 0));
                }
            }
            emit(r.from, c, "CALLS", f.path, r.line, "es-module-and-type-resolution", GraphModel.INFERRED);
        }
    }

    private List<GraphModel.Node> exported(String file, String member) {
        List<GraphModel.Node> out = new ArrayList<>();
        if (file == null || member == null) return out;
        Extractor.FileFacts tf = facts.get(file);
        if (tf != null) {
            String id = tf.exports.get(member);
            if (id != null && !id.isEmpty()) {
                GraphModel.Node n = g.nodes.get(id);
                if (n != null) { out.add(n); return out; }
            }
        }
        for (GraphModel.Node n : idx.byFile.getOrDefault(file, List.of())) {
            if (member.equals(n.name) && (ResolverIndex.CALLABLE.contains(n.type) || ResolverIndex.isTypeNode(n))) out.add(n);
        }
        return out;
    }

    // ---------- shell ----------

    private void buildIncludeClosure() {
        Map<String, Set<String>> direct = new HashMap<>();
        for (Extractor.FileFacts f : facts.values()) {
            if (!"bash".equals(f.language)) continue;
            Set<String> targets = new LinkedHashSet<>();
            for (String inc : f.includes) {
                String t = idx.matchInclude(f.path, inc);
                if (t != null) {
                    targets.add(t);
                    GraphModel.Node fn = g.nodes.get(GraphModel.fileId(t));
                    if (fn != null) {
                        g.addEdge(new GraphModel.Edge(GraphModel.fileId(f.path), fn.id, "IMPORTS",
                                GraphModel.EXTRACTED, f.path, 0, "shell-source"));
                    }
                }
            }
            direct.put(f.path, targets);
        }
        for (String p : direct.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>(direct.getOrDefault(p, Set.of()));
            while (!q.isEmpty()) {
                String cur = q.poll();
                if (!seen.add(cur)) continue;
                q.addAll(direct.getOrDefault(cur, Set.of()));
            }
            includes.put(p, seen);
        }
    }

    private void bashRefs(Extractor.FileFacts f) {
        Set<String> scope = new LinkedHashSet<>();
        scope.add(f.path);
        scope.addAll(includes.getOrDefault(f.path, Set.of()));
        for (Extractor.Ref r : f.refs) {
            List<GraphModel.Node> c = new ArrayList<>();
            for (String file : scope) {
                for (GraphModel.Node n : idx.byFile.getOrDefault(file, List.of())) {
                    if ("function".equals(n.type) && r.name.equals(n.name)) c.add(n);
                }
            }
            emit(r.from, c, "CALLS", f.path, r.line, "shell-source-closure", GraphModel.INFERRED);
        }
    }

    // ---------- tests ----------

    private void markTests() {
        List<GraphModel.Edge> snapshot = new ArrayList<>(g.edges);
        for (GraphModel.Edge e : snapshot) {
            if (!"CALLS".equals(e.relation) || !GraphModel.INFERRED.equals(e.confidence)) continue;
            GraphModel.Node src = g.nodes.get(e.source);
            GraphModel.Node tgt = g.nodes.get(e.target);
            if (src == null || tgt == null) continue;
            if (!Boolean.TRUE.equals(src.attrs.get("test"))) continue;
            if (Boolean.TRUE.equals(tgt.attrs.get("test"))) continue;
            g.addEdge(new GraphModel.Edge(e.source, e.target, "TESTS", GraphModel.INFERRED,
                    e.file, e.line, "test-calls-symbol"));
        }
    }

    Map<String, Object> stats() {
        return JsonStore.map("resolved", resolved, "ambiguous", ambiguous, "unresolved", unresolved);
    }

}
