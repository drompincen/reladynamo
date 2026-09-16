import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integrity checks run before any new state is allowed to replace healthy state.
 *
 * The important ones are the ones that catch silent decay rather than crashes: dangling edge
 * endpoints, nodes belonging to files that no longer exist, and paths that point outside the
 * repository. Those are the failure modes that make a graph quietly wrong.
 */
final class GraphValidator {

    private GraphValidator() {}

    static Map<String, Object> validate(GraphModel.Graph g, Set<String> currentFiles) {
        List<Object> errors = new ArrayList<>();
        Map<String, Object> checks = new LinkedHashMap<>();

        checks.put("schema_version", g.schemaVersion);
        if (g.schemaVersion != GraphModel.SCHEMA_VERSION) {
            errors.add(err("schema_mismatch", "graph schema " + g.schemaVersion
                    + " != engine schema " + GraphModel.SCHEMA_VERSION));
        }

        Set<String> ids = new LinkedHashSet<>();
        int badPath = 0, badType = 0, staleFile = 0;
        for (GraphModel.Node n : g.nodes.values()) {
            if (!ids.add(n.id)) errors.add(err("duplicate_node_id", n.id));
            if (n.id == null || n.id.isEmpty()) errors.add(err("empty_node_id", n.type + " " + n.name));
            if (!GraphModel.NODE_TYPES.contains(n.type)) { badType++; errors.add(err("bad_node_type", n.type + " on " + n.id)); }
            if (n.file != null) {
                if (n.file.startsWith("/") || n.file.contains("..") || n.file.contains("\\")) {
                    badPath++;
                    errors.add(err("unsafe_path", n.file));
                } else if (currentFiles != null && !currentFiles.isEmpty() && !currentFiles.contains(n.file)) {
                    staleFile++;
                    if (staleFile <= 5) errors.add(err("stale_file_node", n.id + " -> " + n.file));
                }
            }
        }
        checks.put("nodes", g.nodes.size());
        checks.put("unsafe_paths", badPath);
        checks.put("unknown_node_types", badType);
        checks.put("stale_file_nodes", staleFile);

        int dangling = 0, badRel = 0, badConf = 0;
        Set<String> edgeKeys = new LinkedHashSet<>();
        int dupes = 0;
        for (GraphModel.Edge e : g.edges) {
            if (!g.nodes.containsKey(e.source)) { dangling++; if (dangling <= 5) errors.add(err("dangling_source", e.source)); }
            if (!g.nodes.containsKey(e.target)) { dangling++; if (dangling <= 5) errors.add(err("dangling_target", e.target)); }
            if (!GraphModel.RELATIONS.contains(e.relation)) { badRel++; errors.add(err("bad_relation", e.relation)); }
            if (!GraphModel.EXTRACTED.equals(e.confidence) && !GraphModel.INFERRED.equals(e.confidence)
                    && !GraphModel.AMBIGUOUS.equals(e.confidence)) {
                badConf++;
                errors.add(err("bad_confidence", String.valueOf(e.confidence)));
            }
            if (!edgeKeys.add(e.key())) dupes++;
        }
        checks.put("edges", g.edges.size());
        checks.put("dangling_endpoints", dangling);
        checks.put("bad_relations", badRel);
        checks.put("bad_confidences", badConf);
        checks.put("duplicate_edges", dupes);
        if (dupes > 0) errors.add(err("duplicate_edges", dupes + " duplicate (source, relation, target) triples"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", errors.isEmpty());
        out.put("checks", checks);
        out.put("errors", errors);
        return out;
    }

    /** A graph that fails these is not allowed to replace a healthy one. */
    static boolean fatal(Map<String, Object> report) {
        for (Object o : Json.arr(report.get("errors"))) {
            String code = Json.str(Json.obj(o).get("code"), "");
            if (code.equals("schema_mismatch") || code.startsWith("dangling") || code.equals("duplicate_node_id")
                    || code.equals("unsafe_path") || code.equals("empty_node_id")) return true;
        }
        return false;
    }

    private static Map<String, Object> err(String code, Object detail) {
        return JsonStore.map("code", code, "detail", String.valueOf(detail));
    }
}
