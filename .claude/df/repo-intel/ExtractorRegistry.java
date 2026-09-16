import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Language dispatch. One extractor per language, chosen by path; a shebang is the fallback for
 * extensionless scripts. An unsupported file is not an error -- it simply contributes a file
 * node and nothing else, which is still useful for orientation.
 */
final class ExtractorRegistry {

    private final BashExtractor bash = new BashExtractor();
    private final List<Extractor> all = new ArrayList<>();

    ExtractorRegistry() {
        all.add(new JavaExtractor());
        all.add(new PythonExtractor());
        all.add(new JsTsExtractor());
        all.add(new ManifestExtractor());
        all.add(bash);
    }

    Extractor forPath(String rel, String source) {
        for (Extractor e : all) if (e.supports(rel)) return e;
        if (source != null && source.startsWith("#!")) {
            int nl = source.indexOf('\n');
            String first = nl < 0 ? source : source.substring(0, nl);
            if (first.contains("bash") || first.contains("/sh") || first.endsWith("sh")) return bash;
        }
        return null;
    }

    static String languageOf(String rel) {
        String p = rel.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".java")) return "java";
        if (p.endsWith(".py") || p.endsWith(".pyi")) return "python";
        if (p.endsWith(".ts") || p.endsWith(".tsx")) return "typescript";
        if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs")) return "javascript";
        if (p.endsWith(".sh") || p.endsWith(".bash")) return "bash";
        if (p.endsWith(".json") || p.endsWith(".xml") || p.endsWith(".toml") || p.endsWith(".gradle")
                || p.endsWith(".gradle.kts") || p.endsWith(".txt") || p.endsWith(".mod")) return "config";
        return "other";
    }

    Map<String, Object> capabilities() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("java", true);
        m.put("python", true);
        m.put("typescript", true);
        m.put("javascript", true);
        m.put("bash", true);
        m.put("config", true);
        return m;
    }
}
