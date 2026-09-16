import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded traversal and ranked deterministic search.
 *
 * Everything here exists to make the answer SMALL. Dumping a subgraph that a caller then has to
 * skim costs more context than the greps it replaced, so every result set is capped by nodes,
 * by edges and by serialised bytes, and truncation is always declared rather than hidden.
 *
 * There are no embeddings and no vector store: ranking is exact-match, token overlap, path
 * match and graph proximity, in that order.
 */
final class GraphQuery {

    static final int MAX_NODES = 25;
    static final int MAX_EDGES = 40;
    static final int MAX_BYTES = 15000;

    private static final Set<String> IMPACT_IN = Set.of(
            "CALLS", "EXTENDS", "IMPLEMENTS", "IMPORTS", "REFERENCES", "TESTS", "ROUTES_TO", "CONSUMES");

    private static final Map<String, Double> REL_WEIGHT = Map.of(
            "CALLS", 1.0, "EXTENDS", 1.0, "IMPLEMENTS", 1.0, "IMPORTS", 0.7,
            "REFERENCES", 0.6, "TESTS", 0.55, "ROUTES_TO", 0.9, "CONSUMES", 0.8);

    private final GraphModel.Graph g;

    GraphQuery(GraphModel.Graph g) { this.g = g; }

    // ---------- seeds ----------

    List<GraphModel.Node> seeds(String q, int max) {
        List<GraphModel.Node> out = new ArrayList<>();
        if (q == null || q.isEmpty()) return out;
        GraphModel.Node direct = g.nodes.get(q);
        if (direct != null) { out.add(direct); return out; }
        for (GraphModel.Node n : g.nodes.values()) if (q.equals(n.qname)) out.add(n);
        if (!out.isEmpty()) return cap(out, max);
        for (GraphModel.Node n : g.nodes.values()) if (q.equals(n.name)) out.add(n);
        if (!out.isEmpty()) return cap(out, max);
        for (GraphModel.Node n : g.nodes.values()) if (q.equals(n.file)) out.add(n);
        if (!out.isEmpty()) return cap(out, max);
        for (Object o : rank(q, max)) out.add((GraphModel.Node) o);
        return cap(out, max);
    }

    private static List<GraphModel.Node> cap(List<GraphModel.Node> l, int max) {
        return l.size() <= max ? l : new ArrayList<>(l.subList(0, max));
    }

    // ---------- search ----------

    private List<Object> rank(String text, int limit) {
        List<String> tokens = tokens(text);
        String lower = text.toLowerCase(Locale.ROOT);
        List<Object[]> scored = new ArrayList<>();
        for (GraphModel.Node n : g.nodes.values()) {
            double s = score(n, tokens, lower);
            if (s > 0) scored.add(new Object[]{n, s});
        }
        scored.sort((a, b) -> {
            int c = Double.compare((Double) b[1], (Double) a[1]);
            return c != 0 ? c : ((GraphModel.Node) a[0]).id.compareTo(((GraphModel.Node) b[0]).id);
        });
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) out.add(scored.get(i)[0]);
        return out;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("[^A-Za-z0-9_]+")) {
            if (part.isEmpty()) continue;
            for (String camel : part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                if (camel.length() > 1) out.add(camel.toLowerCase(Locale.ROOT));
            }
            if (part.length() > 1) out.add(part.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static double score(GraphModel.Node n, List<String> tokens, String rawLower) {
        if ("repository".equals(n.type)) return 0;
        String name = n.name == null ? "" : n.name;
        String qn = n.qname == null ? "" : n.qname;
        String file = n.file == null ? "" : n.file;
        String nameL = name.toLowerCase(Locale.ROOT);
        String qnL = qn.toLowerCase(Locale.ROOT);
        String fileL = file.toLowerCase(Locale.ROOT);
        double s = 0;
        if (qnL.equals(rawLower)) s += 1000;
        else if (nameL.equals(rawLower)) s += 800;
        Object sumObj = n.attrs.get("summary");
        String summary = sumObj instanceof String x ? x.toLowerCase(Locale.ROOT) : "";
        if (s == 0) {
            List<String> nameTokens = tokens(name + " " + file + " " + summary);
            int hit = 0;
            for (String t : new LinkedHashSet<>(tokens)) {
                if (nameTokens.contains(t)) { s += 70; hit++; }
                else if (nameL.contains(t)) { s += 45; hit++; }
                else if (qnL.contains(t)) { s += 25; hit++; }
                else if (fileL.contains(t)) { s += 20; hit++; }
                else if (!summary.isEmpty() && summary.contains(t)) { s += 30; hit++; }
            }
            if (hit == 0) return 0;
            if (hit == new LinkedHashSet<>(tokens).size() && tokens.size() > 1) s += 60;
        }
        s += switch (n.type) {
            case "class", "interface", "record", "enum" -> 30;
            case "endpoint" -> 28;
            case "method", "function" -> 20;
            case "file" -> 12;
            case "external_package" -> 4;
            default -> 8;
        };
        if (Boolean.TRUE.equals(n.attrs.get("test"))) s -= 18;
        if ("public".equals(n.visibility)) s += 6;
        return s;
    }

    Map<String, Object> search(String text, int limit) {
        List<Object> hits = rank(text, Math.max(1, Math.min(limit, MAX_NODES)));
        List<Object> results = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        for (Object o : hits) {
            GraphModel.Node n = (GraphModel.Node) o;
            results.add(brief(n));
            if (n.file != null) files.add(n.file);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("results", results);
        m.put("candidate_files", new ArrayList<>(files));
        return m;
    }

    // ---------- neighbourhood ----------

    Map<String, Object> neighbors(String q, int depth, int limit) {
        List<GraphModel.Node> s = seeds(q, 3);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seeds", briefs(s));
        if (s.isEmpty()) { m.put("results", List.of()); return m; }
        Set<String> seen = new LinkedHashSet<>();
        List<Object> edges = new ArrayList<>();
        List<Object> nodes = new ArrayList<>();
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        for (GraphModel.Node n : s) { seen.add(n.id); queue.add(new Object[]{n.id, 0}); }
        while (!queue.isEmpty() && nodes.size() < Math.min(limit, MAX_NODES) && edges.size() < MAX_EDGES) {
            Object[] cur = queue.poll();
            String id = (String) cur[0];
            int d = (Integer) cur[1];
            if (d >= depth) continue;
            for (GraphModel.Edge e : g.outgoing(id)) {
                if (edges.size() >= MAX_EDGES) break;
                edges.add(edge(e, "out"));
                if (seen.add(e.target)) {
                    GraphModel.Node t = g.nodes.get(e.target);
                    if (t != null) { nodes.add(brief(t)); queue.add(new Object[]{e.target, d + 1}); }
                }
            }
            for (GraphModel.Edge e : g.incoming(id)) {
                if (edges.size() >= MAX_EDGES) break;
                edges.add(edge(e, "in"));
                if (seen.add(e.source)) {
                    GraphModel.Node t = g.nodes.get(e.source);
                    if (t != null) { nodes.add(brief(t)); queue.add(new Object[]{e.source, d + 1}); }
                }
            }
        }
        m.put("results", nodes);
        m.put("edges", edges);
        return m;
    }

    Map<String, Object> related(String q, String relation, boolean incoming, int limit) {
        List<GraphModel.Node> s = seeds(q, 3);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seeds", briefs(s));
        List<Object> results = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GraphModel.Node n : s) {
            for (GraphModel.Edge e : incoming ? g.incoming(n.id) : g.outgoing(n.id)) {
                if (relation != null && !relation.equals(e.relation)) continue;
                String other = incoming ? e.source : e.target;
                if (!seen.add(other)) continue;
                GraphModel.Node o = g.nodes.get(other);
                if (o == null) continue;
                Map<String, Object> b = brief(o);
                b.put("relation", e.relation);
                b.put("confidence", e.confidence);
                b.put("via", (e.file == null ? "" : e.file) + (e.line > 0 ? ":" + e.line : ""));
                results.add(b);
                if (o.file != null) files.add(o.file);
                if (results.size() >= Math.min(limit, MAX_NODES)) break;
            }
        }
        m.put("results", results);
        m.put("candidate_files", new ArrayList<>(files));
        return m;
    }

    // ---------- impact ----------

    Map<String, Object> impact(String q, int depth, int limit) {
        List<GraphModel.Node> s = seeds(q, 3);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seeds", briefs(s));
        if (s.isEmpty()) { m.put("results", List.of()); m.put("candidate_files", List.of()); return m; }

        Map<String, Double> best = new HashMap<>();
        Map<String, String> reason = new HashMap<>();
        Map<String, Integer> dist = new HashMap<>();
        Map<String, String> firstRel = new HashMap<>();
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        Set<String> seedIds = new LinkedHashSet<>();

        for (GraphModel.Node n : s) {
            seedIds.add(n.id);
            queue.add(new Object[]{n.id, 0, 1.0, shortName(n)});
            // containment upward: changing a method also matters at its type and file
            String parent = parentOf(n.id);
            if (parent != null) queue.add(new Object[]{parent, 1, 0.65, shortName(n) + " <- CONTAINS <- " + shortName(g.nodes.get(parent))});
        }
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 20000) {
            Object[] cur = queue.poll();
            String id = (String) cur[0];
            int d = (Integer) cur[1];
            double sc = (Double) cur[2];
            String path = (String) cur[3];
            if (d >= depth) continue;
            for (GraphModel.Edge e : g.incoming(id)) {
                if (!IMPACT_IN.contains(e.relation)) continue;
                double w = REL_WEIGHT.getOrDefault(e.relation, 0.5);
                double conf = GraphModel.EXTRACTED.equals(e.confidence) ? 1.0
                        : GraphModel.INFERRED.equals(e.confidence) ? 0.85 : 0.4;
                double next = sc * w * conf * 0.75;
                GraphModel.Node src = g.nodes.get(e.source);
                if (src == null || seedIds.contains(src.id)) continue;
                double bonus = 1.0;
                if ("public".equals(src.visibility)) bonus += 0.15;
                if ("endpoint".equals(src.type)) bonus += 0.25;
                if (Boolean.TRUE.equals(src.attrs.get("test"))) bonus += 0.05;
                double val = next * bonus;
                Double prev = best.get(src.id);
                if (prev != null && prev >= val) continue;
                best.put(src.id, val);
                dist.put(src.id, d + 1);
                firstRel.put(src.id, e.relation);
                reason.put(src.id, shortName(src) + " -" + e.relation + "-> " + path);
                queue.add(new Object[]{src.id, d + 1, next, reason.get(src.id)});
            }
        }

        List<Map.Entry<String, Double>> ordered = new ArrayList<>(best.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(e -> -e.getValue())
                .thenComparing(Map.Entry::getKey));
        List<Object> results = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        for (Map.Entry<String, Double> e : ordered) {
            if (results.size() >= Math.min(limit, MAX_NODES)) break;
            GraphModel.Node n = g.nodes.get(e.getKey());
            if (n == null) continue;
            Map<String, Object> b = brief(n);
            b.put("distance", dist.getOrDefault(n.id, 0));
            b.put("relation", firstRel.get(n.id));
            b.put("score", Math.round(e.getValue() * 1000) / 1000.0);
            b.put("reason", reason.get(n.id));
            results.add(b);
            if (n.file != null) files.add(n.file);
        }
        m.put("results", results);
        m.put("candidate_files", new ArrayList<>(files));
        m.put("total_reachable", best.size());
        return m;
    }

    private String parentOf(String id) {
        for (GraphModel.Edge e : g.incoming(id)) {
            if ("CONTAINS".equals(e.relation) || "DEFINES".equals(e.relation)) return e.source;
        }
        return null;
    }

    // ---------- path ----------

    Map<String, Object> path(String from, String to) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<GraphModel.Node> a = seeds(from, 4);
        List<GraphModel.Node> b = seeds(to, 4);
        m.put("from_candidates", briefs(a));
        m.put("to_candidates", briefs(b));
        if (a.isEmpty() || b.isEmpty()) { m.put("results", List.of()); return m; }
        if (a.size() > 1 || b.size() > 1) m.put("note", "multiple symbols matched; showing the first resolvable path");

        Set<String> targets = new LinkedHashSet<>();
        for (GraphModel.Node n : b) targets.add(n.id);
        Map<String, Object[]> prev = new HashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GraphModel.Node n : a) { queue.add(n.id); seen.add(n.id); }
        String hitId = null;
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 50000) {
            String id = queue.poll();
            if (targets.contains(id) && !seen.isEmpty()) { hitId = id; break; }
            for (GraphModel.Edge e : g.outgoing(id)) {
                if (seen.add(e.target)) { prev.put(e.target, new Object[]{id, e, "out"}); queue.add(e.target); }
            }
            for (GraphModel.Edge e : g.incoming(id)) {
                if (seen.add(e.source)) { prev.put(e.source, new Object[]{id, e, "in"}); queue.add(e.source); }
            }
        }
        List<Object> hops = new ArrayList<>();
        if (hitId != null) {
            List<Object> rev = new ArrayList<>();
            String cur = hitId;
            while (prev.containsKey(cur)) {
                Object[] p = prev.get(cur);
                GraphModel.Edge e = (GraphModel.Edge) p[1];
                Map<String, Object> hop = new LinkedHashMap<>();
                hop.put("from", shortName(g.nodes.get((String) p[0])));
                hop.put("relation", e.relation);
                hop.put("direction", p[2]);
                hop.put("to", shortName(g.nodes.get(cur)));
                hop.put("confidence", e.confidence);
                if (e.file != null) hop.put("file", e.file);
                if (e.line > 0) hop.put("line", e.line);
                rev.add(hop);
                cur = (String) p[0];
            }
            for (int i = rev.size() - 1; i >= 0; i--) hops.add(rev.get(i));
        }
        m.put("results", hops);
        m.put("found", hitId != null);
        return m;
    }

    // ---------- explain ----------

    Map<String, Object> explain(String q) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<GraphModel.Node> s = seeds(q, 4);
        if (s.isEmpty()) { m.put("results", List.of()); return m; }
        if (s.size() > 1) {
            m.put("ambiguous", true);
            m.put("results", briefs(s));
            return m;
        }
        GraphModel.Node n = s.get(0);
        Map<String, Object> d = brief(n);
        d.put("visibility", n.visibility);
        if (n.signature != null) d.put("signature", n.signature);
        if (!n.attrs.isEmpty()) d.put("attrs", n.attrs);
        String parent = parentOf(n.id);
        if (parent != null) d.put("container", shortName(g.nodes.get(parent)));

        d.put("callers", names(g.incoming(n.id), "CALLS", true, 6));
        d.put("callees", names(g.outgoing(n.id), "CALLS", false, 6));
        d.put("extends", names(g.outgoing(n.id), "EXTENDS", false, 4));
        d.put("implements", names(g.outgoing(n.id), "IMPLEMENTS", false, 4));
        d.put("implemented_by", names(g.incoming(n.id), "IMPLEMENTS", true, 5));
        d.put("extended_by", names(g.incoming(n.id), "EXTENDS", true, 5));
        d.put("imported_by", names(g.incoming(n.id), "IMPORTS", true, 5));
        d.put("depends_on", names(g.outgoing(n.id), "DEPENDS_ON", false, 6));
        d.put("tested_by", names(g.incoming(n.id), "TESTS", true, 5));
        d.put("routes", names(g.incoming(n.id), "ROUTES_TO", true, 4));
        List<Object> members = new ArrayList<>();
        for (GraphModel.Edge e : g.outgoing(n.id)) {
            if (!"CONTAINS".equals(e.relation) && !"DEFINES".equals(e.relation)) continue;
            GraphModel.Node c = g.nodes.get(e.target);
            if (c != null && members.size() < 12) members.add(shortName(c) + " [" + c.type + "]");
        }
        d.put("members", members);
        m.put("results", List.of(d));
        return m;
    }

    private List<Object> names(List<GraphModel.Edge> edges, String rel, boolean incoming, int max) {
        List<Object> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GraphModel.Edge e : edges) {
            if (!rel.equals(e.relation)) continue;
            String id = incoming ? e.source : e.target;
            if (!seen.add(id)) continue;
            GraphModel.Node n = g.nodes.get(id);
            if (n == null) continue;
            out.add(shortName(n) + " (" + e.confidence.charAt(0) + ")"
                    + (e.file != null && e.line > 0 ? " " + e.file + ":" + e.line : ""));
            if (out.size() >= max) break;
        }
        return out;
    }

    // ---------- stats ----------

    Map<String, Object> stats() {
        Map<String, Object> byType = new java.util.TreeMap<>();
        for (GraphModel.Node n : g.nodes.values()) byType.merge(n.type, 1, (a, b) -> (Integer) a + (Integer) b);
        Map<String, Object> byRel = new java.util.TreeMap<>();
        Map<String, Object> byConf = new java.util.TreeMap<>();
        Map<String, Object> byLang = new java.util.TreeMap<>();
        for (GraphModel.Edge e : g.edges) {
            byRel.merge(e.relation, 1, (a, b) -> (Integer) a + (Integer) b);
            byConf.merge(e.confidence, 1, (a, b) -> (Integer) a + (Integer) b);
        }
        for (GraphModel.Node n : g.nodes.values()) {
            if ("file".equals(n.type) && n.language != null) byLang.merge(n.language, 1, (a, b) -> (Integer) a + (Integer) b);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", g.nodes.size());
        m.put("edges", g.edges.size());
        m.put("node_types", byType);
        m.put("relations", byRel);
        m.put("confidence", byConf);
        m.put("files_by_language", byLang);
        return m;
    }

    // ---------- shared shaping ----------

    static String shortName(GraphModel.Node n) {
        if (n == null) return "?";
        if (n.qname != null && !n.qname.isEmpty()) return n.qname;
        return n.name == null ? n.id : n.name;
    }

    static Map<String, Object> brief(GraphModel.Node n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id);
        m.put("type", n.type);
        m.put("name", n.name);
        if (n.qname != null && !n.qname.isEmpty() && !n.qname.equals(n.name)) m.put("qualified_name", n.qname);
        if (n.file != null) m.put("file", n.file);
        if (n.startLine > 0) m.put("line", n.startLine);
        return m;
    }

    private List<Object> briefs(List<GraphModel.Node> ns) {
        List<Object> out = new ArrayList<>();
        for (GraphModel.Node n : ns) out.add(brief(n));
        return out;
    }

    private static Map<String, Object> edge(GraphModel.Edge e, String dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", e.source);
        m.put("relation", e.relation);
        m.put("to", e.target);
        m.put("direction", dir);
        m.put("confidence", e.confidence);
        if (e.file != null) m.put("file", e.file);
        if (e.line > 0) m.put("line", e.line);
        return m;
    }

    /**
     * Trim a result envelope until it fits the byte budget, declaring the truncation.
     * Measured against the PRETTY form, because that is what is printed -- budgeting the
     * compact form silently ships a payload a third larger than the stated limit.
     */
    static boolean enforceBudget(Map<String, Object> envelope, int maxBytes) {
        boolean truncated = false;
        while (Json.writePretty(envelope).length() > maxBytes) {
            List<Object> results = Json.arr(envelope.get("results"));
            List<Object> edges = Json.arr(envelope.get("edges"));
            if (edges.size() > 1) { edges.remove(edges.size() - 1); envelope.put("edges", edges); }
            else if (results.size() > 1) { results.remove(results.size() - 1); envelope.put("results", results); }
            else break;
            truncated = true;
        }
        return truncated;
    }
}
