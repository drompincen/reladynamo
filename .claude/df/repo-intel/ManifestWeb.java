import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * npm, PyPI and Go manifests: package.json, pyproject.toml, requirements.txt and go.mod.
 *
 * package.json goes through the engine's own JSON reader; the rest are line scanners, which is
 * all that is needed to list declared dependencies and is the safest thing to point at a file
 * we did not write.
 */
final class ManifestWeb {

    private ManifestWeb() {}


    static void extractPackageJson(Extractor.FileFacts f, String path, String src) {
        Object rootObj;
        try {
            rootObj = Json.parse(src);
        } catch (RuntimeException e) {
            f.error = "json parse failed: " + e;
            return;
        }
        Map<String, Object> root = Json.obj(rootObj);
        collectNpmDeps(f, src, Json.obj(root.get("dependencies")), "compile");
        collectNpmDeps(f, src, Json.obj(root.get("devDependencies")), "dev");
        collectNpmDeps(f, src, Json.obj(root.get("peerDependencies")), "optional");
    }

    static void collectNpmDeps(Extractor.FileFacts f, String src, Map<String, Object> map, String scope) {
        if (map == null || map.isEmpty()) return;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String name = e.getKey();
            if (name == null || name.isEmpty()) continue;
            String version = e.getValue() == null ? null : String.valueOf(e.getValue());
            int line = lineOfJsonKey(src, name);
            f.deps.add(new Extractor.DepRef("npm", name, version, scope, false, line));
        }
    }

    static int lineOfJsonKey(String src, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int i = src.indexOf(needle, from);
            if (i < 0) return 1;
            int j = i + needle.length();
            while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
            if (j < src.length() && src.charAt(j) == ':') {
                int line = 1;
                for (int k = 0; k < i; k++) if (src.charAt(k) == '\n') line++;
                return line;
            }
            from = i + 1;
        }
    }

    static void extractPyproject(Extractor.FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskPython(src);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        List<String> mLines = Lex.lines(mask);
        List<String> rLines = Lex.lines(src.toCharArray());

        String section = "";
        for (int ln = 1; ln < mLines.size(); ln++) {
            String ml = mLines.get(ln).trim();
            String rl = ln < rLines.size() ? rLines.get(ln) : "";
            if (ml.startsWith("[") && ml.endsWith("]")) {
                section = ml.substring(1, ml.length() - 1).trim();
                continue;
            }
            if (section.equals("project") && startsWithKey(ml, "dependencies")) {
                for (TomlItem it : readTomlStringArray(rLines, mLines, ln)) {
                    String pkg = pypiName(it.spec);
                    if (pkg.isEmpty()) continue;
                    f.deps.add(new Extractor.DepRef("pypi", pkg, pypiVersion(it.spec), "compile", false, it.line));
                }
            } else if (section.equals("project.optional-dependencies")
                    || section.startsWith("project.optional-dependencies.")) {
                if (ml.contains("=")) {
                    for (TomlItem it : readTomlStringArray(rLines, mLines, ln)) {
                        String pkg = pypiName(it.spec);
                        if (pkg.isEmpty()) continue;
                        f.deps.add(new Extractor.DepRef("pypi", pkg, pypiVersion(it.spec), "optional", false, it.line));
                    }
                }
            } else if (section.equals("tool.poetry.dependencies")
                    || section.equals("tool.poetry.group.dev.dependencies")
                    || (section.startsWith("tool.poetry.group.") && section.endsWith(".dependencies"))) {
                if (ml.isEmpty()) continue;
                int eq = ml.indexOf('=');
                if (eq <= 0) continue;
                String key = ml.substring(0, eq).trim();
                if (key.equals("python")) continue; // interpreter constraint, not a package
                String scope = section.contains(".group.") ? "dev" : "compile";
                String rhs = rl.trim();
                int req = rhs.indexOf('=');
                String ver = null;
                if (req >= 0) ver = unquote(rhs.substring(req + 1).trim());
                f.deps.add(new Extractor.DepRef("pypi", key, ver, scope, false, ln));
            }
        }
    }

    static boolean startsWithKey(String line, String key) {
        if (!line.startsWith(key)) return false;
        int i = key.length();
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        return i < line.length() && line.charAt(i) == '=';
    }

    static List<TomlItem> readTomlStringArray(List<String> rawLines, List<String> maskLines, int startLine) {
        List<TomlItem> out = new ArrayList<>();
        boolean inArray = false;
        for (int ln = startLine; ln < rawLines.size(); ln++) {
            String raw = rawLines.get(ln);
            String mask = ln < maskLines.size() ? maskLines.get(ln) : raw;
            int n = Math.max(raw.length(), mask.length());
            for (int i = 0; i < n; i++) {
                char mc = i < mask.length() ? mask.charAt(i) : ' ';
                char rc = i < raw.length() ? raw.charAt(i) : ' ';
                if (!inArray) {
                    if (mc == '[') inArray = true;
                    continue;
                }
                if (mc == ']') return out;
                // quoted element: quote char survives masking as space, so detect from raw
                // only when the mask still has non-comment content around this column
                if ((rc == '"' || rc == '\'') && (mc == ' ' || mc == rc)) {
                    // skip if this column is inside a fully blanked comment run with no string
                    // (maskPython blanks string bodies too — opening quote becomes space)
                    char q = rc;
                    StringBuilder sb = new StringBuilder();
                    int j = i + 1;
                    while (j < raw.length() && raw.charAt(j) != q) {
                        if (raw.charAt(j) == '\\' && j + 1 < raw.length()) {
                            sb.append(raw.charAt(j + 1));
                            j += 2;
                            continue;
                        }
                        sb.append(raw.charAt(j++));
                    }
                    out.add(new TomlItem(sb.toString(), ln));
                    i = j; // for-loop will +1
                }
            }
        }
        return out;
    }

    static String pypiName(String spec) {
        if (spec == null) return "";
        String s = spec.trim();
        if (s.isEmpty()) return "";
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi).trim();
        // name runs until version op, extra bracket, or whitespace
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '[' || c == '(' || c == ' ' || c == '\t') break;
            if (c == '<' || c == '>' || c == '=' || c == '!' || c == '~') break;
            if (c == ',') break;
            i++;
        }
        return s.substring(0, i).trim();
    }

    static String pypiVersion(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi).trim();
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            int close = s.indexOf(']', bracket);
            if (close >= 0) s = (s.substring(0, bracket) + s.substring(close + 1)).trim();
        }
        // find first version operator
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '>' || c == '=' || c == '!' || c == '~') {
                return s.substring(i).trim();
            }
        }
        return null;
    }

    static String unquote(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static void extractRequirements(Extractor.FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskPython(src);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        List<String> mLines = Lex.lines(mask);
        List<String> rLines = Lex.lines(src.toCharArray());
        for (int ln = 1; ln < mLines.size(); ln++) {
            String ml = mLines.get(ln).trim();
            if (ml.isEmpty()) continue; // blank or fully commented
            String rl = ln < rLines.size() ? rLines.get(ln).trim() : ml;
            if (rl.isEmpty() || rl.startsWith("#")) continue;
            if (rl.startsWith("-r") || rl.startsWith("--requirement")) continue;
            if (rl.startsWith("-") && !rl.startsWith("-e")) {
                // other pip options / includes: not package deps
                if (rl.startsWith("--")) continue;
            }
            // editable installs: -e path/url — skip as non-package name when no egg fragment
            if (rl.startsWith("-e ") || rl.startsWith("--editable")) continue;
            String pkg = pypiName(rl);
            if (pkg.isEmpty() || pkg.startsWith("-")) continue;
            f.deps.add(new Extractor.DepRef("pypi", pkg, pypiVersion(rl), "compile", false, ln));
        }
    }

    static void extractGoMod(Extractor.FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskCLike(src, false, false);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        List<String> mLines = Lex.lines(mask);
        List<String> rLines = Lex.lines(src.toCharArray());
        boolean inRequire = false;

        for (int ln = 1; ln < mLines.size(); ln++) {
            String ml = mLines.get(ln).trim();
            String rl = ln < rLines.size() ? rLines.get(ln).trim() : "";

            if (!inRequire) {
                if (ml.startsWith("module ") || (ml.equals("module") && rl.startsWith("module "))) {
                    String mod = rl.substring("module".length()).trim();
                    if (!mod.isEmpty()) {
                        f.namespace = mod;
                        String simple = mod;
                        int slash = mod.lastIndexOf('/');
                        if (slash >= 0 && slash + 1 < mod.length()) simple = mod.substring(slash + 1);
                        GraphModel.Node node = new GraphModel.Node(
                                GraphModel.symbolId("config", "module", path, mod, -1),
                                "module", simple, mod, "config", path);
                        node.startLine = ln;
                        node.endLine = ln;
                        f.add(node);
                        f.defines(GraphModel.fileId(path), node.id, ln);
                    }
                    continue;
                }
                if (ml.startsWith("require")) {
                    String rest = ml.substring("require".length()).trim();
                    String rawRest = rl.length() > "require".length() ? rl.substring("require".length()).trim() : rest;
                    if (rest.equals("(") || rawRest.startsWith("(")) {
                        inRequire = true;
                        continue;
                    }
                    // single-line: require path v1.2.3
                    parseGoRequireLine(f, rawRest.isEmpty() ? rest : rawRest, ln);
                }
                continue;
            }

            if (ml.equals(")") || rl.equals(")")) {
                inRequire = false;
                continue;
            }
            if (ml.isEmpty() && (rl.isEmpty() || rl.startsWith("//"))) continue;
            parseGoRequireLine(f, rl, ln);
        }
    }

    static final class TomlItem {
        final String spec;
        final int line;
        TomlItem(String spec, int line) { this.spec = spec; this.line = line; }
    }

    static void parseGoRequireLine(Extractor.FileFacts f, String line, int ln) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("//")) return;
        boolean indirect = s.contains("//") && s.substring(s.indexOf("//") + 2).trim().startsWith("indirect");
        int comment = s.indexOf("//");
        if (comment >= 0) s = s.substring(0, comment).trim();
        if (s.isEmpty()) return;
        // path version
        int sp = -1;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) { sp = i; break; }
        }
        String name;
        String version = null;
        if (sp < 0) {
            name = s;
        } else {
            name = s.substring(0, sp).trim();
            version = s.substring(sp).trim();
            if (version.isEmpty()) version = null;
        }
        if (name.isEmpty()) return;
        f.deps.add(new Extractor.DepRef("go", name, version, "compile", indirect, ln));
    }
}
