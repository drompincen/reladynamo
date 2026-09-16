import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistence for every piece of repo-intel state.
 *
 * All writes are temp-file plus atomic rename. A refresh that dies halfway must never leave a
 * truncated graph behind: a corrupt graph is worse than no graph, because it answers confidently
 * and wrongly instead of falling back to ordinary source search.
 */
final class JsonStore {

    private final Path dir;

    JsonStore(Path stateDir) { this.dir = stateDir; }

    Path dir() { return dir; }

    Path path(String name) { return dir.resolve(name); }

    boolean exists(String name) { return Files.isRegularFile(dir.resolve(name)); }

    Map<String, Object> readObject(String name) {
        try {
            Path p = dir.resolve(name);
            if (!Files.isRegularFile(p)) return null;
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (s.isBlank()) return null;
            Object o = Json.parse(s);
            return o instanceof Map ? Json.obj(o) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    void write(String name, Object value, boolean pretty) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp." + ProcessHandle.current().pid());
        String s = pretty ? Json.writePretty(value) : Json.write(value);
        Files.write(tmp, s.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void backup(String name) {
        try {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p)) Files.copy(p, dir.resolve(name + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    boolean restore(String name) {
        try {
            Path bak = dir.resolve(name + ".bak");
            if (!Files.isRegularFile(bak)) return false;
            Files.copy(bak, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    void appendLine(String name, Map<String, Object> record, int maxLines) {
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve(name);
            Files.writeString(p, Json.write(record) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            if (maxLines > 0 && Files.size(p) > 512L * 1024) {
                var lines = Files.readAllLines(p);
                if (lines.size() > maxLines) {
                    Path t = dir.resolve(name + ".trim");
                    Files.write(t, (String.join("\n", lines.subList(lines.size() - maxLines, lines.size())) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    Files.move(t, p, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // diagnostics must never break a query
        }
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
