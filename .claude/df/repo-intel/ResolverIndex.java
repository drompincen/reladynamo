import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lookup layer pass 2 resolves against: qualified-name, simple-name, file and package
 * indexes, plus member lookup that walks supertypes, plus the factories for the shared nodes
 * (external symbols, packages) that several files can legitimately point at.
 *
 * Split out of Resolver when it outgrew the project's 500-line limit. Everything here answers
 * "what exists and where"; Resolver decides "and therefore what does this reference mean".
 */
final class ResolverIndex {

    final Map<String, List<GraphModel.Node>> byQname = new HashMap<>();
    final Map<String, List<GraphModel.Node>> bySimple = new HashMap<>();
    final Map<String, List<GraphModel.Node>> byFile = new HashMap<>();
    final Map<String, String> parentOf = new HashMap<>();
    final Set<String> repoFiles = new LinkedHashSet<>();

    /** Node types a call can legitimately land on. */
    static final Set<String> CALLABLE = Set.of("method", "constructor", "function");

    private final GraphModel.Graph g;
    private final Map<String, Extractor.FileFacts> facts;

    ResolverIndex(GraphModel.Graph g, Map<String, Extractor.FileFacts> facts) {
        this.g = g;
        this.facts = facts;
        build();
    }

    void build() {
        for (GraphModel.Node n : g.nodes.values()) {
            if ("file".equals(n.type)) { repoFiles.add(n.qname == null || n.qname.isEmpty() ? n.name : n.qname); continue; }
            if (n.qname != null && !n.qname.isEmpty()) byQname.computeIfAbsent(n.qname, k -> new ArrayList<>()).add(n);
            if (n.name != null && !n.name.isEmpty()) bySimple.computeIfAbsent(n.name, k -> new ArrayList<>()).add(n);
            if (n.file != null) byFile.computeIfAbsent(n.file, k -> new ArrayList<>()).add(n);
        }
        repoFiles.clear();
        repoFiles.addAll(facts.keySet());
        for (GraphModel.Edge e : g.edges) {
            if ("CONTAINS".equals(e.relation) || "DEFINES".equals(e.relation)) parentOf.putIfAbsent(e.target, e.source);
        }
    }

    static boolean isTypeNode(GraphModel.Node n) {
        return "class".equals(n.type) || "interface".equals(n.type) || "enum".equals(n.type)
                || "record".equals(n.type) || "type".equals(n.type);
    }

    List<GraphModel.Node> typesByQ(String q) {
        List<GraphModel.Node> out = new ArrayList<>();
        for (GraphModel.Node n : byQname.getOrDefault(q, List.of())) if (isTypeNode(n)) out.add(n);
        return out;
    }

    List<GraphModel.Node> callableByQ(String q) {
        List<GraphModel.Node> out = new ArrayList<>();
        for (GraphModel.Node n : byQname.getOrDefault(q, List.of())) if (CALLABLE.contains(n.type)) out.add(n);
        return out;
    }

    GraphModel.Node enclosingType(String nodeId) {
        String cur = nodeId;
        for (int i = 0; i < 8 && cur != null; i++) {
            GraphModel.Node n = g.nodes.get(cur);
            if (n != null && isTypeNode(n)) return n;
            cur = parentOf.get(cur);
        }
        return null;
    }

    List<GraphModel.Node> methodsOf(GraphModel.Node type, String name, int arity, int depth) {
        List<GraphModel.Node> out = new ArrayList<>();
        if (type == null || depth > 4) return out;
        for (GraphModel.Edge e : g.outgoing(type.id)) {
            if (!"CONTAINS".equals(e.relation)) continue;
            GraphModel.Node c = g.nodes.get(e.target);
            if (c != null && name.equals(c.name) && CALLABLE.contains(c.type)) out.add(c);
        }
        if (!out.isEmpty()) return byArity(out, arity);
        for (GraphModel.Edge e : g.outgoing(type.id)) {
            if (!"EXTENDS".equals(e.relation) && !"IMPLEMENTS".equals(e.relation)) continue;
            out.addAll(methodsOf(g.nodes.get(e.target), name, arity, depth + 1));
        }
        return byArity(out, arity);
    }

    static List<GraphModel.Node> byArity(List<GraphModel.Node> in, int arity) {
        if (arity < 0 || in.size() < 2) return in;
        List<GraphModel.Node> exact = new ArrayList<>();
        for (GraphModel.Node n : in) if (n.id.endsWith("(" + arity + ")")) exact.add(n);
        return exact.isEmpty() ? in : exact;
    }

    Map<String, List<GraphModel.Node>> byPackage;

    /** Top-level types declared directly in a package. Built once; a per-import scan of every
     *  node was quadratic on repositories with many wildcard imports. */
    List<GraphModel.Node> typesInPackage(String pkg) {
        if (byPackage == null) {
            byPackage = new HashMap<>();
            for (GraphModel.Node n : g.nodes.values()) {
                if (!isTypeNode(n) || n.qname == null) continue;
                int dot = n.qname.lastIndexOf('.');
                if (dot <= 0) continue;
                byPackage.computeIfAbsent(n.qname.substring(0, dot), k -> new ArrayList<>()).add(n);
            }
        }
        return byPackage.getOrDefault(pkg, List.of());
    }

    /** A symbol this repository imports but does not define. Keyed by its written name. */
    GraphModel.Node externalNode(String eco, String qname) {
        String id = GraphModel.pkgId(eco, qname);
        GraphModel.Node n = g.nodes.get(id);
        if (n == null) {
            String simple = qname.contains(".") ? qname.substring(qname.lastIndexOf('.') + 1) : qname;
            n = new GraphModel.Node(id, "external_package", simple, qname, eco, null);
            n.attrs.put("external", true);
            g.addNode(n);
        }
        return n;
    }

    GraphModel.Node pkgNode(String eco, String name) {
        String id = GraphModel.pkgId(eco, name);
        GraphModel.Node n = g.nodes.get(id);
        if (n == null) {
            n = new GraphModel.Node(id, "external_package", name, name, eco, null);
            n.attrs.put("ecosystem", eco);
            g.addNode(n);
        }
        return n;
    }

    GraphModel.Node packageNode(String pkg) {
        String id = "pkg:package:" + pkg;
        GraphModel.Node n = g.nodes.get(id);
        if (n == null) {
            n = new GraphModel.Node(id, "package", pkg.substring(pkg.lastIndexOf('.') + 1), pkg, "java", null);
            g.addNode(n);
        }
        return n;
    }

    String resolveSpec(String fromFile, String spec) {
        if (spec == null || spec.isEmpty()) return null;
        if (!(spec.startsWith("./") || spec.startsWith("../") || spec.startsWith("/"))) return null;
        String dir = fromFile.contains("/") ? fromFile.substring(0, fromFile.lastIndexOf('/')) : "";
        String joined = normalize(spec.startsWith("/") ? spec.substring(1) : dir + "/" + spec);
        String[] exts = {"", ".ts", ".tsx", ".d.ts", ".js", ".jsx", ".mjs", ".cjs",
                "/index.ts", "/index.tsx", "/index.js", "/index.jsx"};
        for (String e : exts) if (repoFiles.contains(joined + e)) return joined + e;
        // an import written without extension may also point at a .js emitted from .ts
        if (joined.endsWith(".js")) {
            String base = joined.substring(0, joined.length() - 3);
            for (String e : new String[]{".ts", ".tsx"}) if (repoFiles.contains(base + e)) return base + e;
        }
        return null;
    }

    static String normalize(String p) {
        List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) { if (!out.isEmpty()) out.remove(out.size() - 1); continue; }
            out.add(seg);
        }
        return String.join("/", out);
    }

    String matchInclude(String fromFile, String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\"", "").replace("'", "");
        s = s.replaceAll("\\$\\([^)]*\\)", "").replaceAll("\\$\\{[^}]*\\}", "").replace("$0", "");
        while (s.startsWith("/")) s = s.substring(1);
        s = normalize(s);
        if (s.isEmpty()) return null;
        if (repoFiles.contains(s)) return s;
        String dir = fromFile.contains("/") ? fromFile.substring(0, fromFile.lastIndexOf('/')) : "";
        String rel = normalize(dir + "/" + s);
        if (repoFiles.contains(rel)) return rel;
        String best = null;
        for (String f : repoFiles) {
            if (f.endsWith("/" + s) || f.equals(s)) {
                if (best == null || f.length() < best.length()) best = f;
            }
        }
        return best;
    }

    static String externalNpmName(String spec) {
        String s = spec;
        if (s.startsWith("@")) {
            int second = s.indexOf('/', s.indexOf('/') + 1);
            return second < 0 ? s : s.substring(0, second);
        }
        int slash = s.indexOf('/');
        return slash < 0 ? s : s.substring(0, slash);
    }

    static String moduleToPath(String dotted) { return dotted.replace('.', '/') + ".py"; }
}
