import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-file bookkeeping: what we indexed, what it hashed to, and which nodes came out of it.
 *
 * The node id list is what makes deletion exact -- when a file changes we remove precisely the
 * nodes it produced instead of trying to infer them later, which is how stale nodes accumulate.
 * Change detection is tiered: size and mtime rule most files out for free, and content is only
 * hashed when that cheap check is inconclusive or the dirty journal named the file.
 */
final class ManifestStore {

    static final class Entry {
        String hash = "";
        long size;
        long mtime;
        String language;
        List<String> nodeIds = new ArrayList<>();
    }

    final Map<String, Entry> entries = new LinkedHashMap<>();

    static ManifestStore load(JsonStore s) {
        ManifestStore m = new ManifestStore();
        Map<String, Object> o = s.readObject("manifest.json");
        if (o == null) return m;
        for (Map.Entry<String, Object> e : Json.obj(o.get("files")).entrySet()) {
            Map<String, Object> v = Json.obj(e.getValue());
            Entry en = new Entry();
            en.hash = Json.str(v.get("hash"), "");
            en.size = Json.lng(v.get("size"), 0);
            en.mtime = Json.lng(v.get("mtime_ms"), 0);
            en.language = Json.str(v.get("language"), null);
            for (Object id : Json.arr(v.get("node_ids"))) en.nodeIds.add(String.valueOf(id));
            m.entries.put(e.getKey(), en);
        }
        return m;
    }

    void save(JsonStore s) throws IOException {
        Map<String, Object> files = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(entries.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            Entry e = entries.get(k);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("hash", e.hash);
            v.put("size", e.size);
            v.put("mtime_ms", e.mtime);
            if (e.language != null) v.put("language", e.language);
            List<String> ids = new ArrayList<>(e.nodeIds);
            Collections.sort(ids);
            v.put("node_ids", ids);
            files.put(k, v);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", GraphModel.SCHEMA_VERSION);
        root.put("files", files);
        s.write("manifest.json", root, false);
    }

    static String hash(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(b));
        }
    }

    static final class Diff {
        final List<String> added = new ArrayList<>();
        final List<String> modified = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> unchanged = new ArrayList<>();
        final Map<String, String> renamed = new LinkedHashMap<>();   // old -> new

        boolean isEmpty() { return added.isEmpty() && modified.isEmpty() && deleted.isEmpty(); }

        int changed() { return added.size() + modified.size() + deleted.size(); }
    }

    /**
     * @param forced paths the dirty journal named. They are hashed even if size and mtime look
     *               unchanged, because an editor can rewrite a file to the same length within
     *               the filesystem's timestamp granularity.
     */
    long referenceMs;

    Diff diff(Path root, List<String> current, Set<String> forced) {
        Diff d = new Diff();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : current) {
            seen.add(p);
            Entry e = entries.get(p);
            Path abs = root.resolve(p);
            long size = -1, mtime = -1;
            try {
                size = Files.size(abs);
                mtime = Files.getLastModifiedTime(abs).toMillis();
            } catch (IOException io) {
                // treat as changed; the extractor pass will record the read failure
            }
            if (e == null) { d.added.add(p); continue; }
            // size+mtime rules a file out cheaply, but a save that lands within the filesystem's
            // timestamp granularity and preserves the byte length would slip through. Anything
            // touched near the previous refresh is hashed anyway.
            boolean cheapSame = e.size == size && e.mtime == mtime;
            boolean nearLastRefresh = referenceMs > 0 && mtime >= referenceMs - 2000;
            if (cheapSame && !forced.contains(p) && !nearLastRefresh) { d.unchanged.add(p); continue; }
            String h = "";
            try {
                h = hash(Files.readAllBytes(abs));
            } catch (IOException io) {
                d.modified.add(p);
                continue;
            }
            if (h.equals(e.hash)) {
                e.size = size;
                e.mtime = mtime;
                d.unchanged.add(p);
            } else {
                d.modified.add(p);
            }
        }
        for (String p : entries.keySet()) if (!seen.contains(p)) d.deleted.add(p);

        // rename detection is reporting only -- the graph still rebuilds both sides, because a
        // moved file changes every node id that embeds its path
        for (String del : d.deleted) {
            Entry e = entries.get(del);
            if (e == null || e.hash.isEmpty()) continue;
            for (String add : d.added) {
                try {
                    if (hash(Files.readAllBytes(root.resolve(add))).equals(e.hash)) {
                        d.renamed.put(del, add);
                        break;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return d;
    }
}
