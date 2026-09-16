import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package-manifest pass-1 extractor.
 *
 * Each supported file yields one {@code config} node plus a {@link DepRef} per declared
 * dependency. External package nodes and DEPENDS_ON edges are left to the resolver so the
 * same library named in two manifests collapses to one node.
 *
 * Parsing is text-only: no XML entity resolution, no Gradle evaluation, no npm install.
 */
final class ManifestExtractor implements Extractor {

    public String language() { return "config"; }

    public boolean supports(String relPath) {
        String name = fileName(relPath);
        if (name.equals("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("package.json")
                || name.equals("pyproject.toml")
                || name.equals("requirements.txt")
                || name.equals("go.mod")) {
            return true;
        }
        return name.startsWith("requirements-") && name.endsWith(".txt");
    }

    public FileFacts extract(String path, String src) {
        FileFacts f = new FileFacts(path, "config");
        if (src == null) {
            f.error = "null source";
            return f;
        }
        try {
            String name = fileName(path);
            GraphModel.Node cfg = new GraphModel.Node(
                    GraphModel.symbolId("config", "config", path, path, -1),
                    "config", name, path, "config", path);
            cfg.startLine = 1;
            cfg.endLine = 1;
            f.add(cfg);
            f.defines(GraphModel.fileId(path), cfg.id, 1);

            if (name.equals("pom.xml")) ManifestJvm.extractPom(f, path, src);
            else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) ManifestJvm.extractGradle(f, path, src);
            else if (name.equals("package.json")) ManifestWeb.extractPackageJson(f, path, src);
            else if (name.equals("pyproject.toml")) ManifestWeb.extractPyproject(f, path, src);
            else if (name.equals("requirements.txt") || (name.startsWith("requirements-") && name.endsWith(".txt")))
                ManifestWeb.extractRequirements(f, path, src);
            else if (name.equals("go.mod")) ManifestWeb.extractGoMod(f, path, src);
        } catch (RuntimeException e) {
            f.error = "extract failed: " + e;
        }
        return f;
    }

    // ---------- Maven pom.xml ----------

    // ---------- Gradle (Groovy + Kotlin DSL) ----------

    // ---------- package.json ----------

    /** 1-based line of the JSON object key `"name"`, or 1 if not found. */
    // ---------- pyproject.toml ----------

    /**
     * Read a TOML string array starting at {@code startLine} (1-based). String bodies are
     * taken from RAW lines; array brackets that only appear inside a masked comment are
     * ignored because the scan walks the masked line for structure and the raw line for
     * quoted content at matching offsets.
     */
    // ---------- requirements.txt ----------

    // ---------- go.mod ----------

    // ---------- shared helpers ----------

    private static String fileName(String path) {
        if (path == null) return "";
        int s = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return s < 0 ? path : path.substring(s + 1);
    }

    static int indexOfIgnoreCase(String s, String needle, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }
}
