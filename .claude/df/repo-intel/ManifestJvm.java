import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JVM-ecosystem manifests: Maven POMs and both Gradle DSLs.
 *
 * Deliberately a tag/line scanner rather than an XML parser: a host repository is untrusted
 * input, and a real XML parser is a door to external entities and remote DTD fetches that this
 * subsystem must never open.
 */
final class ManifestJvm {

    private ManifestJvm() {}

    static final Set<String> GRADLE_CONFIGS = Set.of(
            "implementation", "testImplementation", "api", "compileOnly", "runtimeOnly",
            "annotationProcessor", "testCompileOnly", "testRuntimeOnly", "compile", "testCompile",
            "runtime", "testRuntime", "providedCompile", "providedRuntime", "classpath");

    static void extractPom(Extractor.FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskCLike(src, false, false);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        blankXmlComments(mask);
        int[] li = Lex.lineIndex(mask);
        char[] raw = src.toCharArray();

        Map<String, String> props = new LinkedHashMap<>();
        int propOpen = indexOfTag(mask, "properties", 0, true);
        if (propOpen >= 0) {
            int propBody = skipTag(mask, propOpen);
            int propClose = indexOfTag(mask, "properties", propBody, false);
            if (propClose > propBody) {
                int i = propBody;
                while (i < propClose) {
                    Tag t = readOpenTag(mask, i);
                    if (t == null) { i++; continue; }
                    if (t.close || t.selfClose) { i = t.end; continue; }
                    int bodyStart = t.end;
                    int close = indexOfTag(mask, t.name, bodyStart, false);
                    if (close < 0 || close > propClose) break;
                    String val = textBetween(raw, bodyStart, close).trim();
                    props.put(t.name, val);
                    i = skipTag(mask, close);
                }
            }
        }

        int depMgmt = 0;
        int i = 0;
        while (i < mask.length) {
            if (mask[i] != '<') { i++; continue; }
            Tag t = readOpenTag(mask, i);
            if (t == null) { i++; continue; }
            i = t.end;
            if ("dependencyManagement".equals(t.name)) {
                if (t.close) depMgmt = Math.max(0, depMgmt - 1);
                else if (!t.selfClose) depMgmt++;
                continue;
            }
            if (!"dependency".equals(t.name) || t.close || t.selfClose) continue;
            if (depMgmt > 0) {
                // still consume the block so nesting stays correct; skip emit
                int close = indexOfTag(mask, "dependency", t.end, false);
                if (close >= 0) i = skipTag(mask, close);
                continue;
            }
            int close = indexOfTag(mask, "dependency", t.end, false);
            if (close < 0) break;
            String body = textBetween(raw, t.end, close);
            int line = Lex.lineOf(li, t.start);
            String groupId = childText(body, "groupId");
            String artifactId = childText(body, "artifactId");
            String version = childText(body, "version");
            String scope = childText(body, "scope");
            if (groupId == null || artifactId == null) {
                i = skipTag(mask, close);
                continue;
            }
            version = resolveProp(version, props);
            if (scope == null || scope.isBlank()) scope = "compile";
            f.deps.add(new Extractor.DepRef("maven", groupId + ":" + artifactId, version, scope.trim(), false, line));
            i = skipTag(mask, close);
        }
    }

    static String resolveProp(String version, Map<String, String> props) {
        if (version == null) return null;
        String v = version.trim();
        if (v.startsWith("${") && v.endsWith("}") && v.length() > 3) {
            String key = v.substring(2, v.length() - 1).trim();
            String resolved = props.get(key);
            if (resolved != null) return resolved;
        }
        return v;
    }

    static String childText(String body, String tag) {
        String open = "<" + tag;
        int from = 0;
        while (true) {
            int i = ManifestExtractor.indexOfIgnoreCase(body, open, from);
            if (i < 0) return null;
            int after = i + open.length();
            if (after < body.length() && (body.charAt(after) == '>' || Character.isWhitespace(body.charAt(after))
                    || body.charAt(after) == '/')) {
                int gt = body.indexOf('>', after);
                if (gt < 0) return null;
                if (body.charAt(gt - 1) == '/') return "";
                int close = ManifestExtractor.indexOfIgnoreCase(body, "</" + tag + ">", gt + 1);
                if (close < 0) return null;
                return body.substring(gt + 1, close).trim();
            }
            from = after;
        }
    }

    static void extractGradle(Extractor.FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskCLike(src, false, false);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        char[] raw = src.toCharArray();
        int[] li = Lex.lineIndex(mask);
        int n = mask.length;
        int i = 0;
        while (i < n) {
            if (!Lex.isIdentStart(mask[i])) { i++; continue; }
            int start = i;
            while (i < n && Lex.isIdentPart(mask[i])) i++;
            String conf = new String(mask, start, i - start);
            if (!GRADLE_CONFIGS.contains(conf)) continue;
            // Skip horizontal/vertical whitespace on RAW, not mask: mask blanks string
            // bodies to spaces, so walking mask whitespace would jump past the coordinate.
            int j = i;
            while (j < n && Character.isWhitespace(raw[j])) j++;
            if (j >= n) continue;
            char next = raw[j];
            if (next != '(' && next != '"' && next != '\''
                    && !looksLikeMapForm(raw, j)) {
                continue; // bare configuration name, not a dependency declaration
            }

            String scope = conf.regionMatches(true, 0, "test", 0, 4) ? "test" : "compile";
            int line = Lex.lineOf(li, start);

            // map form: implementation group: "g", name: "a", version: "v"
            if (looksLikeMapForm(raw, j)) {
                String group = mapValue(raw, j, "group");
                String art = mapValue(raw, j, "name");
                String ver = mapValue(raw, j, "version");
                if (group != null && art != null) {
                    f.deps.add(new Extractor.DepRef("maven", group + ":" + art, ver, scope, false, line));
                }
                continue;
            }

            // string form: conf "g:a:v"  or conf("g:a:v")  or conf 'g:a:v'
            String coord = firstStringLiteral(raw, j);
            if (coord == null) continue;
            addMavenCoord(f, coord, scope, line);
        }
    }

    static boolean looksLikeMapForm(char[] raw, int from) {
        int j = from;
        if (j < raw.length && raw[j] == '(') {
            j++;
            while (j < raw.length && Character.isWhitespace(raw[j])) j++;
        }
        return matchWord(raw, j, "group") || matchWord(raw, j, "name") || matchWord(raw, j, "version");
    }

    static boolean matchWord(char[] s, int i, String w) {
        if (i + w.length() > s.length) return false;
        for (int k = 0; k < w.length(); k++) if (s[i + k] != w.charAt(k)) return false;
        int e = i + w.length();
        return e >= s.length || !Lex.isIdentPart(s[e]);
    }

    static String mapValue(char[] raw, int from, String key) {
        int n = raw.length;
        int i = from;
        int limit = Math.min(n, from + 400);
        while (i < limit) {
            if (matchWord(raw, i, key)) {
                int j = i + key.length();
                while (j < n && Character.isWhitespace(raw[j])) j++;
                if (j < n && raw[j] == ':') {
                    j++;
                    while (j < n && Character.isWhitespace(raw[j])) j++;
                    return readQuoted(raw, j);
                }
            }
            i++;
        }
        return null;
    }

    static String firstStringLiteral(char[] raw, int from) {
        int n = raw.length;
        int i = from;
        // optional opening paren for Kotlin/Groovy call form
        while (i < n && Character.isWhitespace(raw[i])) i++;
        if (i < n && raw[i] == '(') {
            i++;
            while (i < n && Character.isWhitespace(raw[i])) i++;
        }
        return readQuoted(raw, i);
    }

    static String readQuoted(char[] raw, int i) {
        if (i >= raw.length) return null;
        char q = raw[i];
        if (q != '"' && q != '\'') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < raw.length && raw[i] != q) {
            if (raw[i] == '\\' && i + 1 < raw.length) { sb.append(raw[i + 1]); i += 2; continue; }
            if (raw[i] == '\n') break;
            sb.append(raw[i++]);
        }
        return sb.toString();
    }

    static void addMavenCoord(Extractor.FileFacts f, String coord, String scope, int line) {
        String c = coord.trim();
        if (c.isEmpty()) return;
        // GAV: group:artifact:version — take first two segments as name
        int c1 = c.indexOf(':');
        if (c1 <= 0) return;
        int c2 = c.indexOf(':', c1 + 1);
        String name;
        String version = null;
        if (c2 < 0) {
            name = c;
        } else {
            name = c.substring(0, c2);
            int c3 = c.indexOf(':', c2 + 1);
            version = c3 < 0 ? c.substring(c2 + 1) : c.substring(c2 + 1, c3);
            if (version.isEmpty()) version = null;
        }
        if (name.isEmpty()) return;
        f.deps.add(new Extractor.DepRef("maven", name, version, scope, false, line));
    }

    static void blankXmlComments(char[] a) {
        int i = 0;
        while (i < a.length) {
            if (a[i] == '<' && i + 3 < a.length && a[i + 1] == '!' && a[i + 2] == '-' && a[i + 3] == '-') {
                a[i++] = ' '; a[i++] = ' '; a[i++] = ' '; a[i++] = ' ';
                while (i < a.length) {
                    if (a[i] == '-' && i + 2 < a.length && a[i + 1] == '-' && a[i + 2] == '>') {
                        a[i++] = ' '; a[i++] = ' '; a[i++] = ' ';
                        break;
                    }
                    if (a[i] != '\n' && a[i] != '\r') a[i] = ' ';
                    i++;
                }
            } else {
                i++;
            }
        }
    }

    static int indexOfTag(char[] s, String name, int from, boolean open) {
        String needle = open ? "<" + name : "</" + name;
        int n = s.length;
        for (int i = from; i < n; i++) {
            if (s[i] != '<') continue;
            if (matchesTagAt(s, i, name, open)) return i;
        }
        return -1;
    }

    static Tag readOpenTag(char[] s, int i) {
        if (i >= s.length || s[i] != '<') return null;
        Tag t = new Tag();
        t.start = i;
        i++;
        if (i < s.length && s[i] == '/') { t.close = true; i++; }
        while (i < s.length && Character.isWhitespace(s[i])) i++;
        int ns = i;
        while (i < s.length && (Character.isLetterOrDigit(s[i]) || s[i] == ':' || s[i] == '_' || s[i] == '-' || s[i] == '.')) i++;
        if (i == ns) return null;
        t.name = new String(s, ns, i - ns);
        // strip namespace prefix
        int col = t.name.indexOf(':');
        if (col >= 0) t.name = t.name.substring(col + 1);
        while (i < s.length && s[i] != '>') {
            if (s[i] == '"' || s[i] == '\'') {
                char q = s[i++];
                while (i < s.length && s[i] != q) i++;
                if (i < s.length) i++;
            } else {
                i++;
            }
        }
        if (i < s.length && s[i] == '>') {
            if (i > 0 && s[i - 1] == '/') t.selfClose = true;
            i++;
        }
        t.end = i;
        return t;
    }

    static int skipTag(char[] s, int tagStart) {
        int i = tagStart;
        while (i < s.length && s[i] != '>') i++;
        return i < s.length ? i + 1 : i;
    }

    static String textBetween(char[] raw, int start, int end) {
        if (start < 0) start = 0;
        if (end > raw.length) end = raw.length;
        if (start >= end) return "";
        return new String(raw, start, end - start);
    }

    static final class Tag {
        String name;
        int start, end;
        boolean close, selfClose;
    }

    static boolean matchesTagAt(char[] s, int i, String name, boolean open) {
        if (s[i] != '<') return false;
        int j = i + 1;
        if (!open) {
            if (j >= s.length || s[j] != '/') return false;
            j++;
        }
        while (j < s.length && Character.isWhitespace(s[j])) j++;
        // optional namespace
        int nameStart = j;
        while (j < s.length && (Character.isLetterOrDigit(s[j]) || s[j] == ':' || s[j] == '_' || s[j] == '-' || s[j] == '.')) j++;
        String raw = new String(s, nameStart, j - nameStart);
        int col = raw.indexOf(':');
        String local = col >= 0 ? raw.substring(col + 1) : raw;
        if (!local.equals(name)) return false;
        if (j < s.length && (s[j] == '>' || Character.isWhitespace(s[j]) || s[j] == '/')) return true;
        return false;
    }
}
