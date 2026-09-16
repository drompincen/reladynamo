import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ensureCurrent(): the only way anything reads the graph.
 *
 * Two properties matter more than speed here. First, an incremental refresh must produce
 * exactly what a clean rebuild would produce -- which is why pass-1 facts are cached per file
 * and pass-2 resolution is always re-run globally. Adding a second class with the same simple
 * name has to be able to turn a previously confident edge ambiguous, and that is only true if
 * resolution sees every file's references, not just the ones that changed.
 *
 * Second, a failed refresh must leave the previous graph intact. New state is validated in
 * memory, then written through a temp file, and the old graph is restored if validation fails.
 */
final class IncrementalUpdater {

    static final String GRAPH = "graph.json";
    static final String FACTS = "facts.json";
    static final String META = "metadata.json";
    static final String DIRTY = "dirty";

    private final Path root;
    private final JsonStore store;
    private final ExtractorRegistry registry = new ExtractorRegistry();
    private final long maxBytes;

    IncrementalUpdater(Path root, JsonStore store, long maxBytes) {
        this.root = root;
        this.store = store;
        this.maxBytes = maxBytes;
    }

    static final class Result {
        GraphModel.Graph graph;
        String action = "noop";
        boolean ok = true;
        int files, parsed, changed;
        long ms;
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String error;
        Map<String, Object> validation;
    }

    Result ensureCurrent(boolean force) { return ensureCurrent(force, true); }

    /**
     * @param needGraph whether the caller will actually read the graph. A freshness check does
     *                  not: parsing a 25,000-node graph.json purely to answer "is anything
     *                  stale?" cost ~55 s on a 10,000-file repository, which is most of what a
     *                  SessionStart hook or a plain `ensure` was paying for. Corruption is still
     *                  caught, just at the first query that needs the graph rather than earlier.
     */
    Result ensureCurrent(boolean force, boolean needGraph) {
        long t0 = System.nanoTime();
        Result r = new Result();
        try {
            Map<String, Object> meta = store.readObject(META);
            GraphModel.Graph graph = loadGraph();
            // A changed extractor with an unchanged version constant would otherwise keep
            // serving a graph built by the old parser. The launcher fingerprints the engine
            // sources, so parser capability changes force a rebuild on their own.
            String stamp = System.getenv("DROMFLOW_REPO_INTEL_ENGINE_STAMP");
            boolean stampChanged = stamp != null && !stamp.isBlank() && meta != null
                    && !stamp.equals(Json.str(meta.get("engine_stamp"), stamp));
            boolean rebuild = force || graph == null
                    || graph.schemaVersion != GraphModel.SCHEMA_VERSION
                    || meta == null
                    || !GraphModel.ENGINE_VERSION.equals(Json.str(meta.get("engine_version"), ""))
                    || stampChanged
                    || !"ready".equals(Json.str(meta.get("status"), ""));

            RepositoryScanner scanner = new RepositoryScanner(root, maxBytes);
            List<String> current = scanner.enumerate();
            r.files = current.size();
            r.skipped = scanner.skipped;

            ManifestStore manifest = rebuild ? new ManifestStore() : ManifestStore.load(store);
            if (meta != null) {
                String last = Json.str(meta.get("last_incremental_refresh"), null);
                try { if (last != null) manifest.referenceMs = java.time.Instant.parse(last).toEpochMilli(); }
                catch (RuntimeException ignored) { }
            }
            Set<String> dirty = readDirty();
            ManifestStore.Diff diff = manifest.diff(root, current, dirty);
            r.changed = diff.changed();

            if (!rebuild && diff.isEmpty()) {
                clearDirty();
                r.graph = graph;
                r.action = "noop";
                r.ms = ms(t0);
                return r;
            }

            // Mark the state as being rewritten BEFORE touching it. graph, manifest and facts
            // are three files and cannot be renamed as one transaction; a crash between them
            // would otherwise leave a ready-looking state whose parts disagree. With this, an
            // interrupted refresh is simply rebuilt.
            markWriting();
            Map<String, Extractor.FileFacts> cached = rebuild ? new LinkedHashMap<>() : loadFacts();
            Map<String, Extractor.FileFacts> all = new LinkedHashMap<>();
            for (String p : current) {
                Extractor.FileFacts prior = cached.get(p);
                boolean reuse = prior != null && diff.unchanged.contains(p);
                if (reuse) { all.put(p, prior); continue; }
                Extractor.FileFacts ff = parse(p, manifest);
                if (ff != null) {
                    all.put(p, ff);
                    r.parsed++;
                    if (ff.error != null) r.failed.add(p + ": " + ff.error);
                }
            }
            for (String gone : diff.deleted) manifest.entries.remove(gone);

            GraphModel.Graph fresh = assemble(all);
            Resolver resolver = new Resolver(fresh, all);
            resolver.run();
            fresh.sort();

            for (Map.Entry<String, Extractor.FileFacts> e : all.entrySet()) {
                ManifestStore.Entry en = manifest.entries.get(e.getKey());
                if (en == null) continue;
                en.nodeIds.clear();
                for (GraphModel.Node n : e.getValue().nodes) en.nodeIds.add(n.id);
            }

            Map<String, Object> validation = GraphValidator.validate(fresh, all.keySet());
            r.validation = validation;
            if (GraphValidator.fatal(validation)) {
                r.ok = false;
                r.action = "validation_failed";
                r.error = "refresh rejected: " + Json.write(validation.get("errors"));
                r.graph = graph;
                writeMeta(r, resolver, all, false);
                r.ms = ms(t0);
                return r;
            }

            fresh.metadata = JsonStore.map(
                    "engine_version", GraphModel.ENGINE_VERSION,
                    "schema_version", GraphModel.SCHEMA_VERSION,
                    "generated", now(),
                    "files", all.size(),
                    "nodes", fresh.nodes.size(),
                    "edges", fresh.edges.size());

            store.backup(GRAPH);
            store.write(GRAPH, fresh.toJson(), false);
            manifest.save(store);
            saveFacts(all);
            clearDirty();

            r.graph = fresh;
            r.action = rebuild ? "full_intake" : "incremental";
            writeMeta(r, resolver, all, true);
            r.ms = ms(t0);
            return r;
        } catch (IOException | RuntimeException e) {
            r.ok = false;
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            r.action = "failed";
            if (r.graph == null) {
                store.restore(GRAPH);
                r.graph = loadGraph();
            }
            r.ms = ms(t0);
            try { writeMeta(r, null, Map.of(), false); } catch (RuntimeException ignored) { }
            return r;
        }
    }

    private static long ms(long t0) { return Math.max(0, (System.nanoTime() - t0) / 1_000_000); }

    private static String now() { return java.time.Instant.now().toString(); }

    // ---------- parsing ----------

    private Extractor.FileFacts parse(String rel, ManifestStore manifest) {
        Path abs = root.resolve(rel);
        byte[] bytes;
        try {
            // Re-check immediately before opening. admission ran earlier, and a repository is
            // untrusted input that can change underneath us between the two moments.
            if (Files.isSymbolicLink(abs)) return null;
            if (!abs.toRealPath().startsWith(root)) return null;
            if (Files.size(abs) > maxBytes) return null;
            bytes = Files.readAllBytes(abs);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String src = new String(bytes, StandardCharsets.UTF_8);
        Extractor ex = registry.forPath(rel, src);
        String lang = ex != null ? ex.language() : ExtractorRegistry.languageOf(rel);

        Extractor.FileFacts ff;
        if (ex == null) {
            ff = new Extractor.FileFacts(rel, lang);
        } else {
            try {
                ff = ex.extract(rel, src);
                if (ff == null) ff = new Extractor.FileFacts(rel, lang);
            } catch (RuntimeException | StackOverflowError t) {
                // one malformed file must never abort a repository scan
                ff = new Extractor.FileFacts(rel, lang);
                ff.error = "extractor " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
        }
        if (ff.language == null) ff.language = lang;
        String sum = headerSummary(src);
        if (sum != null && !sum.isEmpty()) ff.summary = sum;

        ManifestStore.Entry en = new ManifestStore.Entry();
        en.hash = ManifestStore.hash(bytes);
        en.size = bytes.length;
        try {
            en.mtime = Files.getLastModifiedTime(abs).toMillis();
        } catch (IOException ignored) {
        }
        en.language = ff.language;
        manifest.entries.put(rel, en);
        return ff;
    }


    /**
     * The file's own one-line description, taken from its header comment.
     *
     * Extractors mask comments, which is right for structure and wrong for orientation: in a
     * shell- or config-heavy repository almost all of the vocabulary a person would search for
     * lives in that first line ("install, update, or uninstall"). One line per file is cheap and
     * makes text search work on repositories that declare few symbols.
     */
    static String headerSummary(String src) {
        int taken = 0;
        StringBuilder sb = new StringBuilder();
        for (String raw : src.split("\n", 40)) {
            String t = raw.strip();
            if (t.isEmpty() && sb.length() == 0) continue;
            if (t.startsWith("#!")) continue;
            String body = null;
            if (t.startsWith("//")) body = t.substring(2);
            else if (t.startsWith("#")) body = t.substring(1);
            else if (t.startsWith("/*")) body = t.substring(2).replace("*/", "");
            else if (t.startsWith("*")) body = t.substring(1).replace("*/", "");
            else if (t.startsWith("\"\"\"") || t.startsWith("'''")) body = t.substring(3);
            if (body == null) break;
            body = body.strip();
            if (body.isEmpty()) { if (sb.length() > 0) break; else continue; }
            if (sb.length() > 0) sb.append(' ');
            sb.append(body);
            if (++taken >= 3 || sb.length() > 160) break;
        }
        String out = sb.toString().strip();
        return out.length() > 200 ? out.substring(0, 200) : out;
    }

    private GraphModel.Graph assemble(Map<String, Extractor.FileFacts> all) {
        GraphModel.Graph g = new GraphModel.Graph();
        String repoName = root.getFileName() == null ? "repository" : root.getFileName().toString();
        GraphModel.Node repo = new GraphModel.Node(GraphModel.repoId(repoName), "repository", repoName, repoName, null, null);
        repo.attrs.put("files", all.size());
        g.addNode(repo);
        List<String> paths = new ArrayList<>(all.keySet());
        java.util.Collections.sort(paths);
        for (String p : paths) {
            Extractor.FileFacts f = all.get(p);
            GraphModel.Node fn = new GraphModel.Node(GraphModel.fileId(p), "file",
                    p.substring(p.lastIndexOf('/') + 1), p, f.language, p);
            if (f.namespace != null && !f.namespace.isEmpty()) fn.attrs.put("namespace", f.namespace);
            if (f.error != null) fn.attrs.put("parse_error", f.error);
            if (f.summary != null && !f.summary.isEmpty()) fn.attrs.put("summary", f.summary);
            if (Syntax.isTestPath(p)) fn.attrs.put("test", true);
            g.addNode(fn);
            g.addEdge(new GraphModel.Edge(repo.id, fn.id, "CONTAINS", GraphModel.EXTRACTED, p, 0, "scan"));
            for (GraphModel.Node n : f.nodes) g.addNode(n);
        }
        for (String p : paths) for (GraphModel.Edge e : all.get(p).edges) g.addEdge(e);
        return g;
    }

    // ---------- state ----------

    GraphModel.Graph loadGraph() {
        Map<String, Object> m = store.readObject(GRAPH);
        if (m == null) return null;
        try {
            GraphModel.Graph g = GraphModel.Graph.fromJson(m);
            return g.nodes.isEmpty() && g.schemaVersion == 0 ? null : g;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Flip the persisted status out of "ready" for the duration of a rewrite. */
    private void markWriting() {
        Map<String, Object> meta = store.readObject(META);
        if (meta == null) return;
        meta.put("status", "writing");
        try { store.write(META, meta, true); } catch (IOException ignored) { }
    }

    private Set<String> readDirty() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Path p = store.path(DIRTY);
            if (!Files.isRegularFile(p)) return out;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                int tab = line.indexOf('\t');
                String path = tab < 0 ? line : line.substring(0, tab);
                path = path.trim();
                if (path.startsWith("./")) path = path.substring(2);
                if (!path.isEmpty()) out.add(path);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return out;
    }

    private void clearDirty() {
        try { Files.deleteIfExists(store.path(DIRTY)); } catch (IOException ignored) { }
    }

    private void writeMeta(Result r, Resolver resolver, Map<String, Extractor.FileFacts> all, boolean ready) {
        Map<String, Object> prev = store.readObject(META);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine_version", GraphModel.ENGINE_VERSION);
        m.put("schema_version", GraphModel.SCHEMA_VERSION);
        String stamp = System.getenv("DROMFLOW_REPO_INTEL_ENGINE_STAMP");
        if (stamp != null && !stamp.isBlank()) m.put("engine_stamp", stamp);
        String dfv = System.getenv("DROMFLOW_VERSION");
        if (dfv != null && !dfv.isBlank()) m.put("drom_flow_version", dfv);
        m.put("repository_root", root.toString());
        m.put("status", ready ? "ready" : "degraded");
        m.put("last_full_intake", "full_intake".equals(r.action) ? now()
                : prev == null ? null : prev.get("last_full_intake"));
        m.put("last_incremental_refresh", ready ? now() : prev == null ? null : prev.get("last_incremental_refresh"));
        m.put("last_action", r.action);
        m.put("last_duration_ms", r.ms);
        m.put("parser_capabilities", registry.capabilities());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("files", r.files);
        counts.put("parsed_this_run", r.parsed);
        counts.put("nodes", r.graph == null ? 0 : r.graph.nodes.size());
        counts.put("edges", r.graph == null ? 0 : r.graph.edges.size());
        m.put("counts", counts);
        if (resolver != null) m.put("resolution", resolver.stats());
        m.put("failed_files", r.failed.size() > 50 ? r.failed.subList(0, 50) : r.failed);
        m.put("skipped", r.skipped.size() > 50 ? r.skipped.subList(0, 50) : r.skipped);
        if (r.error != null) m.put("last_error", r.error);
        if (r.validation != null) m.put("validation", r.validation.get("checks"));
        try { store.write(META, m, true); } catch (IOException ignored) { }
    }

    // ---------- pass-1 fact cache ----------

    private Map<String, Extractor.FileFacts> loadFacts() {
        Map<String, Extractor.FileFacts> out = new LinkedHashMap<>();
        Map<String, Object> m = store.readObject(FACTS);
        if (m == null) return out;
        for (Map.Entry<String, Object> e : Json.obj(m.get("files")).entrySet()) {
            try { out.put(e.getKey(), factsFromJson(e.getKey(), Json.obj(e.getValue()))); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    private void saveFacts(Map<String, Extractor.FileFacts> all) throws IOException {
        Map<String, Object> files = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(all.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) files.put(k, factsToJson(all.get(k)));
        store.write(FACTS, JsonStore.map("schema_version", GraphModel.SCHEMA_VERSION, "files", files), false);
    }

    static Map<String, Object> factsToJson(Extractor.FileFacts f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("l", f.language);
        m.put("ns", f.namespace);
        if (f.summary != null) m.put("sum", f.summary);
        if (f.error != null) m.put("err", f.error);
        List<Object> ns = new ArrayList<>();
        for (GraphModel.Node n : f.nodes) ns.add(n.toJson());
        m.put("n", ns);
        List<Object> es = new ArrayList<>();
        for (GraphModel.Edge e : f.edges) es.add(e.toJson());
        m.put("e", es);
        List<Object> im = new ArrayList<>();
        for (Extractor.ImportRef i : f.imports) im.add(List.of(nz(i.alias), nz(i.module), nz(i.member), nz(i.kind), i.wildcard ? 1 : 0, i.line));
        m.put("im", im);
        List<Object> rf = new ArrayList<>();
        for (Extractor.Ref r : f.refs) rf.add(List.of(nz(r.from), nz(r.receiver), nz(r.receiverType), nz(r.name), r.arity, r.line, nz(r.kind)));
        m.put("rf", rf);
        List<Object> su = new ArrayList<>();
        for (Extractor.TypeRef t : f.supers) su.add(List.of(nz(t.from), nz(t.name), nz(t.relation), t.line));
        m.put("su", su);
        List<Object> dp = new ArrayList<>();
        for (Extractor.DepRef d : f.deps) dp.add(List.of(nz(d.ecosystem), nz(d.name), nz(d.version), nz(d.scope), d.indirect ? 1 : 0, d.line));
        m.put("dp", dp);
        m.put("inc", new ArrayList<Object>(f.includes));
        m.put("ex", new LinkedHashMap<>(f.exports));
        return m;
    }

    static Extractor.FileFacts factsFromJson(String path, Map<String, Object> m) {
        Extractor.FileFacts f = new Extractor.FileFacts(path, Json.str(m.get("l"), "other"));
        f.namespace = Json.str(m.get("ns"), "");
        f.summary = Json.str(m.get("sum"), null);
        f.error = Json.str(m.get("err"), null);
        for (Object o : Json.arr(m.get("n"))) f.nodes.add(GraphModel.Node.fromJson(Json.obj(o)));
        for (Object o : Json.arr(m.get("e"))) f.edges.add(GraphModel.Edge.fromJson(Json.obj(o)));
        for (Object o : Json.arr(m.get("im"))) {
            List<Object> a = Json.arr(o);
            f.imports.add(new Extractor.ImportRef(un(a, 0), un(a, 1), un(a, 2), un(a, 3),
                    Json.integer(a.get(4), 0) == 1, Json.integer(a.get(5), 0), ""));
        }
        for (Object o : Json.arr(m.get("rf"))) {
            List<Object> a = Json.arr(o);
            f.refs.add(new Extractor.Ref(un(a, 0), un(a, 1), un(a, 2), un(a, 3),
                    Json.integer(a.get(4), -1), Json.integer(a.get(5), 0), un(a, 6)));
        }
        for (Object o : Json.arr(m.get("su"))) {
            List<Object> a = Json.arr(o);
            f.supers.add(new Extractor.TypeRef(un(a, 0), un(a, 1), un(a, 2), Json.integer(a.get(3), 0)));
        }
        for (Object o : Json.arr(m.get("dp"))) {
            List<Object> a = Json.arr(o);
            f.deps.add(new Extractor.DepRef(un(a, 0), un(a, 1), un(a, 2), un(a, 3),
                    Json.integer(a.get(4), 0) == 1, Json.integer(a.get(5), 0)));
        }
        for (Object o : Json.arr(m.get("inc"))) f.includes.add(String.valueOf(o));
        for (Map.Entry<String, Object> e : Json.obj(m.get("ex")).entrySet()) f.exports.put(e.getKey(), String.valueOf(e.getValue()));
        return f;
    }

    private static Object nz(String s) { return s == null ? "" : s; }

    private static String un(List<Object> a, int i) {
        if (i >= a.size()) return null;
        String s = String.valueOf(a.get(i));
        return s.isEmpty() ? null : s;
    }
}
