import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which files exist and which of them may be read.
 *
 * Inside a Git repository the enumeration is `git ls-files -co --exclude-standard`, which gives
 * tracked plus untracked-but-not-ignored files and honours nested .gitignore rules for free.
 * Outside Git there is a filesystem walk with the same exclusion set.
 *
 * The host repository is untrusted input. Every candidate is checked for escape (symlinks and
 * traversal that leave the root), for secrets, for binary content and for size before any
 * extractor is allowed to see it.
 */
final class RepositoryScanner {

    static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "dist", "build", "target", "out", "vendor",
            ".venv", "venv", "__pycache__", ".mypy_cache", ".pytest_cache", ".gradle", ".idea",
            ".vscode", ".next", ".nuxt", "coverage", ".tox", "obj", ".terraform");
    // NB: "bin" is deliberately NOT excluded. It is build output in some ecosystems but it is
    // where the entrypoint scripts live in shell projects, and losing those loses the graph's
    // most useful nodes.

    /** Never indexed, whatever the host's ignore rules say. */
    static final Set<String> SECRET_NAMES = Set.of(
            ".env", ".envrc", ".netrc", ".npmrc", ".pypirc", "credentials", "credentials.json",
            "credentials.yaml", "credentials.yml", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            ".htpasswd", ".pgpass", ".dockercfg", "secrets.yaml", "secrets.yml", "secrets.json",
            "service-account.json", "serviceaccount.json", "kubeconfig", ".git-credentials");

    static final Set<String> SECRET_SUFFIXES = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".ppk", ".asc", ".gpg",
            ".p8", ".crt", ".cer", ".der", ".kdbx");

    private final Path root;
    private final long maxBytes;
    final List<String> skipped = new ArrayList<>();

    RepositoryScanner(Path root, long maxBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    List<String> enumerate() {
        Set<String> paths = suppliedList();
        if (paths == null) paths = gitFiles();
        if (paths == null) paths = walk();
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            if (indexable(p)) out.add(p);
        }
        out.sort(String::compareTo);
        return out;
    }

    // ---------- enumeration ----------

    /**
     * A file list handed over by the launcher. Used when the JVM cannot run the repository's
     * git itself -- a Windows JDK driven from WSL, most often. The list is a convenience, not
     * a trust boundary: every entry still goes through indexable() before it is opened.
     */
    private Set<String> suppliedList() {
        String p = System.getenv("DROMFLOW_REPO_INTEL_FILELIST");
        if (p == null || p.isBlank()) return null;
        try {
            Path f = Path.of(p);
            if (!Files.isRegularFile(f)) return null;
            Set<String> out = new LinkedHashSet<>();
            for (String s : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\0")) {
                if (!s.isEmpty()) out.add(s.replace('\\', '/'));
            }
            return out.isEmpty() ? null : out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Set<String> gitFiles() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", root.toString(),
                    "ls-files", "-co", "--exclude-standard", "-z");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            byte[] data;
            try (InputStream in = p.getInputStream()) {
                data = in.readAllBytes();
            }
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            Set<String> out = new LinkedHashSet<>();
            for (String s : new String(data, StandardCharsets.UTF_8).split("\0")) {
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return null;
        }
    }

    private Set<String> walk() {
        Set<String> out = new LinkedHashSet<>();
        walk(root, out, 0);
        return out;
    }

    private void walk(Path dir, Set<String> out, int depth) {
        if (depth > 40) return;
        try (var s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String name = p.getFileName().toString();
                if (Files.isSymbolicLink(p)) continue;               // never follow links during the walk
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (EXCLUDED_DIRS.contains(name) || name.equals(".state")) continue;
                    walk(p, out, depth + 1);
                } else {
                    out.add(rel(p));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable directory is not a reason to abandon the scan
        }
    }

    String rel(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ---------- admission ----------

    boolean indexable(String relPath) {
        if (relPath == null || relPath.isEmpty()) return false;
        if (relPath.startsWith("/") || relPath.contains("..")) { skip(relPath, "traversal"); return false; }
        String lower = relPath.toLowerCase(Locale.ROOT);
        for (String seg : relPath.split("/")) {
            if (EXCLUDED_DIRS.contains(seg)) return false;
        }
        if (lower.startsWith(".claude/.state/") || lower.startsWith(".claude/.grok-fleet/")
                || lower.startsWith(".claude/.javaducker/") || lower.startsWith("setup-backup/")) return false;

        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        String lname = name.toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(lname) || lname.startsWith(".env.")) { skip(relPath, "secret"); return false; }
        for (String sfx : SECRET_SUFFIXES) if (lname.endsWith(sfx)) { skip(relPath, "secret"); return false; }

        Path abs = root.resolve(relPath);
        try {
            if (!Files.exists(abs, LinkOption.NOFOLLOW_LINKS)) return false;
            if (Files.isSymbolicLink(abs)) {
                Path target = abs.toRealPath();
                if (!target.startsWith(root)) { skip(relPath, "symlink escapes repository root"); return false; }
                skip(relPath, "symlink");
                return false;   // in-repo symlinks duplicate a file that is already enumerated
            }
            Path realPath = abs.toRealPath();
            if (!realPath.startsWith(root)) { skip(relPath, "outside repository root"); return false; }
            if (Files.isDirectory(realPath)) return false;
            long size = Files.size(realPath);
            if (size > maxBytes) { skip(relPath, "oversized (" + size + " bytes)"); return false; }
            if (size == 0) return false;
            if (isBinary(realPath)) { skip(relPath, "binary"); return false; }
        } catch (IOException | RuntimeException e) {
            skip(relPath, "unreadable");
            return false;
        }
        return true;
    }

    private void skip(String p, String why) {
        if (skipped.size() < 500) skipped.add(p + "\t" + why);
    }

    static boolean isBinary(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[Math.min(8192, (int) Math.min(Integer.MAX_VALUE, Files.size(p)))];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) if (buf[i] == 0) return true;
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** Read a file as UTF-8, replacing undecodable bytes rather than failing the scan. */
    static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
