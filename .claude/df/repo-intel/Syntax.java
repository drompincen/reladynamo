import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stateless syntax helpers shared by the extractors and the resolver.
 *
 * These are pure functions over text: no state, no graph, no I/O. They were split out of
 * JavaExtractor when it outgrew the project's 500-line limit, and several of them were already
 * being reached for from other files, which is the usual sign that they never belonged to one
 * extractor in the first place.
 */
final class Syntax {

    private Syntax() {}

    static int arity(char[] s, int lparen) {
        int d = 0, count = 0;
        boolean any = false;
        for (int i = lparen; i < s.length; i++) {
            char c = s[i];
            if (c == '(') d++;
            else if (c == ')') { d--; if (d == 0) return any ? count + 1 : 0; }
            else if (c == ',' && d == 1) count++;
            else if (!Character.isWhitespace(c) && d >= 1) any = true;
        }
        return -1;
    }


    static boolean isType(String t) {
        return "class".equals(t) || "interface".equals(t) || "enum".equals(t) || "record".equals(t) || "type".equals(t);
    }

    static boolean isTestPath(String p) {
        return p.contains("src/test/") || p.contains("/test/") || p.startsWith("test/")
                || p.contains("/tests/") || p.startsWith("tests/");
    }

    static String visibility(Set<String> mods) {
        if (mods.contains("public")) return "public";
        if (mods.contains("private")) return "private";
        if (mods.contains("protected")) return "protected";
        return "package-private";
    }

    static String simple(String qname) {
        int i = qname.lastIndexOf('.');
        return i < 0 ? qname : qname.substring(i + 1);
    }

    static String baseType(String t) {
        String s = stripGenerics(t).trim().replace("[]", "").replace("...", "");
        int sp = s.lastIndexOf(' ');
        if (sp >= 0) s = s.substring(sp + 1);
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length() && Character.isUpperCase(s.charAt(dot + 1))) s = s.substring(dot + 1);
        return s;
    }

    static boolean isJdkType(String t) {
        String b = baseType(t);
        return switch (b) {
            case "String", "int", "long", "double", "float", "boolean", "char", "byte", "short", "void",
                 "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short", "Object",
                 "List", "Map", "Set", "Optional", "Collection", "Iterable", "Stream", "var" -> true;
            default -> false;
        };
    }

    static String[] splitParam(String p) {
        String s = stripGenerics(p).trim();
        // drop annotations on parameters
        while (s.startsWith("@")) {
            int sp = s.indexOf(' ');
            if (sp < 0) return null;
            s = s.substring(sp + 1).trim();
        }
        s = s.replace("final ", "").trim();
        int sp = s.lastIndexOf(' ');
        if (sp <= 0) return null;
        return new String[]{s.substring(0, sp).trim(), s.substring(sp + 1).trim()};
    }

    static String firstIdent(String s) {
        int i = 0;
        while (i < s.length() && !Lex.isIdentStart(s.charAt(i))) {
            if (!Character.isWhitespace(s.charAt(i))) return "";
            i++;
        }
        int j = i;
        while (j < s.length() && Lex.isIdentPart(s.charAt(j))) j++;
        return s.substring(i, j);
    }

    static int findWord(String s, String w) {
        int from = 0;
        while (true) {
            int i = s.indexOf(w, from);
            if (i < 0) return -1;
            boolean lb = i == 0 || !Lex.isIdentPart(s.charAt(i - 1));
            int e = i + w.length();
            boolean rb = e >= s.length() || !Lex.isIdentPart(s.charAt(e));
            if (lb && rb) return i;
            from = i + 1;
        }
    }

    static String stripGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        for (char c : s.toCharArray()) {
            if (c == '<') d++;
            else if (c == '>') { d = Math.max(0, d - 1); sb.append(' '); }
            else if (d == 0) sb.append(c);
        }
        return sb.toString();
    }

    static int matching(String s, int open) {
        char o = s.charAt(open), c = o == '(' ? ')' : o == '[' ? ']' : '}';
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) d++;
            else if (s.charAt(i) == c) { d--; if (d == 0) return i; }
        }
        return -1;
    }

    static int indexOfTop(String s, char target) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '<') d++;
            else if (c == ')' || c == ']' || c == '>') d--;
            else if (c == target && d == 0) return i;
        }
        return -1;
    }

    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int d = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '<' || c == '{') d++;
            else if (c == ')' || c == ']' || c == '>' || c == '}') d--;
            else if (c == sep && d == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    /** First string literal inside annotation arguments, e.g. @GetMapping("/cases/{id}"). */
    static String pathArg(String rawArgs) {
        if (rawArgs == null) return null;
        int q = rawArgs.indexOf('"');
        if (q < 0) return null;
        int e = rawArgs.indexOf('"', q + 1);
        return e < 0 ? null : rawArgs.substring(q + 1, e);
    }

    static String joinRoute(String prefix, String p) {
        String a = prefix == null ? "" : prefix.trim();
        String b = p == null ? "" : p.trim();
        if (a.isEmpty()) return b.isEmpty() ? "/" : b;
        if (b.isEmpty()) return a;
        if (a.endsWith("/") && b.startsWith("/")) return a + b.substring(1);
        if (!a.endsWith("/") && !b.startsWith("/")) return a + "/" + b;
        return a + b;
    }
}
