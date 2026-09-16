///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES Json.java Lex.java Syntax.java GraphModel.java Extractor.java JavaExtractor.java JavaDecl.java PythonExtractor.java PySyntax.java PyDecl.java PyImports.java
//SOURCES JsTsExtractor.java JsTsSyntax.java JsTsScan.java JsTsImports.java JsTsDecl.java JsTsEmit.java BashExtractor.java ManifestExtractor.java ManifestJvm.java ManifestWeb.java ExtractorRegistry.java
//SOURCES RepositoryScanner.java ManifestStore.java JsonStore.java Resolver.java
//SOURCES IncrementalUpdater.java GraphQuery.java GraphValidator.java ResolverIndex.java

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Private entry point for drom-flow's repository intelligence.
 *
 * This is not a user-facing CLI. It is invoked by drom-flow's own hooks and skills through the
 * `run` wrapper, and every structural command begins with ensureCurrent() so that no caller
 * ever has to reason about whether the graph is fresh.
 *
 * Output is always a JSON envelope on stdout; diagnostics go to stderr. A query that matches
 * nothing is a successful query with an empty result, not an engine failure.
 */
public class RepoIntel {

    private static final int EXIT_OK = 0, EXIT_NOT_FOUND = 1, EXIT_USAGE = 2;

    public static void main(String[] args) {
        long t0 = System.nanoTime();
        if (args.length == 0) { usage(); System.exit(EXIT_USAGE); }

        String command = args[0];
        List<String> positional = new ArrayList<>();
        Path root = null, stateDir = null;
        int limit = GraphQuery.MAX_NODES, depth = 3, maxBytes = GraphQuery.MAX_BYTES;
        boolean force = false, ensure = true;

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--root" -> root = Paths.get(args[++i]);
                case "--state" -> stateDir = Paths.get(args[++i]);
                case "--limit" -> limit = parseInt(args[++i], limit);
                case "--depth" -> depth = parseInt(args[++i], depth);
                case "--max-bytes" -> maxBytes = parseInt(args[++i], maxBytes);
                case "--force" -> force = true;
                case "--no-ensure" -> ensure = false;
                default -> {
                    if (a.startsWith("--")) { System.err.println("unknown option: " + a); System.exit(EXIT_USAGE); }
                    positional.add(a);
                }
            }
        }

        if (root == null) {
            String env = System.getenv("DROMFLOW_REPO_ROOT");
            root = Paths.get(env != null && !env.isEmpty() ? env : ".");
        }
        root = root.toAbsolutePath().normalize();
        if (stateDir == null) stateDir = root.resolve(".claude/.state/repo-intel");

        long maxFileBytes = 1_000_000L;
        String mb = System.getenv("DROMFLOW_REPO_INTEL_MAX_FILE_BYTES");
        if (mb != null && !mb.isBlank()) maxFileBytes = parseInt(mb, 1_000_000);

        JsonStore store = new JsonStore(stateDir);
        IncrementalUpdater updater = new IncrementalUpdater(root, store, maxFileBytes);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("command", command);
        String query = positional.isEmpty() ? null : positional.get(0);
        if (query != null) envelope.put("query", query);

        try {
            Files.createDirectories(stateDir);
            IncrementalUpdater.Result state = null;
            // `ensure` only reports freshness; it never needs the graph parsed into memory.
            boolean needsGraph = !command.equals("ensure");
            if (ensure || command.equals("ensure") || command.equals("rebuild")) {
                state = updater.ensureCurrent(force || command.equals("rebuild"), needsGraph);
            }
            GraphModel.Graph graph = state != null && state.graph != null ? state.graph
                    : (needsGraph ? updater.loadGraph() : null);

            Map<String, Object> gmeta = new LinkedHashMap<>();
            gmeta.put("schema_version", graph == null ? 0 : graph.schemaVersion);
            gmeta.put("stale", graph == null);
            if (state != null) {
                gmeta.put("action", state.action);
                gmeta.put("refresh_ms", state.ms);
                gmeta.put("files", state.files);
                gmeta.put("parsed", state.parsed);
                if (!state.ok) gmeta.put("degraded", true);
            }
            if (graph != null) { gmeta.put("nodes", graph.nodes.size()); gmeta.put("edges", graph.edges.size()); }
            envelope.put("graph", gmeta);

            if (graph == null && !needsGraph) {
                // a freshness-only run: report what happened and stop, without parsing anything
                envelope.put("results", List.of());
                if (state != null) {
                    envelope.put("failed_files", state.failed.size() > 10 ? state.failed.subList(0, 10) : state.failed);
                    if (state.error != null) envelope.put("error_detail", state.error);
                }
                envelope.put("truncated", false);
                print(envelope, t0);
                System.exit(EXIT_OK);
            }
            if (graph == null) {
                envelope.put("ok", false);
                envelope.put("error", JsonStore.map("code", "no_graph",
                        "message", "repository intelligence has no usable graph; fall back to source search"));
                envelope.put("results", List.of());
                print(envelope, t0);
                System.exit(EXIT_NOT_FOUND);
            }

            GraphQuery q = new GraphQuery(graph);
            Map<String, Object> payload = switch (command) {
                case "ensure", "rebuild" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("results", List.of());
                    if (state != null) {
                        m.put("failed_files", state.failed.size() > 10 ? state.failed.subList(0, 10) : state.failed);
                        if (state.error != null) m.put("error_detail", state.error);
                    }
                    yield m;
                }
                case "stats" -> {
                    Map<String, Object> m = new LinkedHashMap<>(q.stats());
                    Map<String, Object> meta = store.readObject(IncrementalUpdater.META);
                    if (meta != null) m.put("metadata", meta);
                    m.put("results", List.of());
                    yield m;
                }
                case "symbol" -> {
                    requireQuery(query);
                    Map<String, Object> m = new LinkedHashMap<>();
                    List<Object> out = new ArrayList<>();
                    for (GraphModel.Node n : q.seeds(query, limit)) out.add(GraphQuery.brief(n));
                    m.put("results", out);
                    yield m;
                }
                case "search" -> { requireQuery(query); yield q.search(String.join(" ", positional), limit); }
                case "neighbors" -> { requireQuery(query); yield q.neighbors(query, depth, limit); }
                case "callers" -> { requireQuery(query); yield q.related(query, "CALLS", true, limit); }
                case "callees" -> { requireQuery(query); yield q.related(query, "CALLS", false, limit); }
                case "dependencies" -> { requireQuery(query); yield q.related(query, null, false, limit); }
                case "dependents" -> { requireQuery(query); yield q.related(query, null, true, limit); }
                case "impact" -> { requireQuery(query); yield q.impact(query, depth, limit); }
                case "path" -> {
                    if (positional.size() < 2) { System.err.println("path needs two symbols"); System.exit(EXIT_USAGE); }
                    yield q.path(positional.get(0), positional.get(1));
                }
                case "explain" -> { requireQuery(query); yield q.explain(query); }
                case "verify" -> {
                    Map<String, Object> v = GraphValidator.validate(graph, null);
                    Map<String, Object> m = new LinkedHashMap<>(v);
                    m.put("results", v.get("errors"));
                    yield m;
                }
                default -> { usage(); System.exit(EXIT_USAGE); yield Map.of(); }
            };

            envelope.putAll(payload);
            envelope.putIfAbsent("results", List.of());
            boolean truncated = GraphQuery.enforceBudget(envelope, maxBytes);
            envelope.put("truncated", truncated);
            envelope.put("ok", !Boolean.FALSE.equals(envelope.get("ok")));

            store.appendLine("query-log.jsonl", JsonStore.map(
                    "ts", java.time.Instant.now().toString(),
                    "command", command,
                    "query", query == null ? "" : query,
                    "nodes_returned", Json.arr(envelope.get("results")).size(),
                    "edges_returned", Json.arr(envelope.get("edges")).size(),
                    "duration_ms", (System.nanoTime() - t0) / 1_000_000), 2000);

            print(envelope, t0);
            boolean empty = Json.arr(envelope.get("results")).isEmpty();
            boolean expectsResults = !command.equals("ensure") && !command.equals("rebuild")
                    && !command.equals("stats") && !command.equals("verify");
            System.exit(expectsResults && empty ? EXIT_NOT_FOUND : EXIT_OK);
        } catch (Exception e) {
            envelope.put("ok", false);
            envelope.put("error", JsonStore.map("code", "engine_error",
                    "message", e.getClass().getSimpleName() + ": " + e.getMessage()));
            envelope.put("results", List.of());
            print(envelope, t0);
            System.exit(EXIT_NOT_FOUND);
        }
    }

    private static void requireQuery(String q) {
        if (q == null) { System.err.println("this command needs a symbol or text argument"); System.exit(EXIT_USAGE); }
    }

    private static int parseInt(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return dflt; }
    }

    private static void print(Map<String, Object> envelope, long t0) {
        envelope.put("duration_ms", (System.nanoTime() - t0) / 1_000_000);
        System.out.println(Json.writePretty(envelope));
    }

    private static void usage() {
        System.err.println("""
                repo-intel (private drom-flow subsystem)
                  ensure | rebuild | stats | verify
                  symbol <name> | search <text> | explain <name>
                  callers <name> | callees <name> | dependencies <name> | dependents <name>
                  neighbors <name> | impact <name> | path <a> <b>
                options: --root DIR --state DIR --limit N --depth N --max-bytes N --force --no-ensure""");
    }
}
