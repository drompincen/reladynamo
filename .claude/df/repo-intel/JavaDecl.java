import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Parsing of a Java declaration head -- the text between the last statement boundary and a `{`
 * or `;`. That single string is where modifiers, annotations, type keywords, names, parameters
 * and supertype clauses all live, so it is worth its own file.
 *
 * Annotation arguments are taken from the RAW head, because the masked copy has had every string
 * literal blanked and the route path or topic name would be lost.
 */
final class JavaDecl {

    private JavaDecl() {}

    static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract", "synchronized",
            "native", "default", "strictfp", "transient", "volatile", "sealed", "non-sealed");

    static final class Decl {
        java.util.Set<String> modifiers = new java.util.LinkedHashSet<>();
        Map<String, String> annotations = new LinkedHashMap<>();
        String typeKeyword;
        String name;
        String typeName;
        boolean isMethod;
        List<String> params = new ArrayList<>();
        List<String> extendsNames = new ArrayList<>();
        List<String> implementsNames = new ArrayList<>();
    }

    static Decl parse(String head, String rawHead) {
        Decl d = new Decl();
        String t = head;

        // annotations, with argument text taken from the raw source so literals survive
        int scan = 0;
        while (true) {
            int at = t.indexOf('@', scan);
            if (at < 0) break;
            int e = at + 1;
            while (e < t.length() && (Lex.isIdentPart(t.charAt(e)) || t.charAt(e) == '.')) e++;
            String an = t.substring(at + 1, e);
            if (an.isEmpty() || "interface".equals(an)) { scan = at + 1; continue; }
            String simple = an.contains(".") ? an.substring(an.lastIndexOf('.') + 1) : an;
            String args = "";
            int close = e;
            int ws = e;
            while (ws < t.length() && Character.isWhitespace(t.charAt(ws))) ws++;
            if (ws < t.length() && t.charAt(ws) == '(') {
                int dep = 0;
                for (int i = ws; i < t.length(); i++) {
                    if (t.charAt(i) == '(') dep++;
                    else if (t.charAt(i) == ')') { dep--; if (dep == 0) { close = i + 1; break; } }
                }
                if (close > ws && close <= rawHead.length()) args = rawHead.substring(ws, Math.min(close, rawHead.length()));
            }
            d.annotations.put(simple, args);
            t = t.substring(0, at) + " ".repeat(Math.max(0, close - at)) + t.substring(close);
            scan = close;
        }

        t = t.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return null;

        // type declarations
        String[] kws = {"class", "interface", "enum", "record"};
        for (String kw : kws) {
            int p = Syntax.findWord(t, kw);
            if (p < 0) continue;
            String before = t.substring(0, p);
            if (before.contains("=") || before.contains("(")) continue;
            for (String m : before.trim().split("\\s+")) if (MODIFIERS.contains(m)) d.modifiers.add(m);
            String after = t.substring(p + kw.length()).trim();
            String name = Syntax.firstIdent(after);
            if (name.isEmpty()) continue;
            d.typeKeyword = kw;
            d.name = name;
            String rest = after.substring(after.indexOf(name) + name.length());
            rest = Syntax.stripGenerics(rest);
            if ("record".equals(kw)) {
                int lp = rest.indexOf('(');
                if (lp >= 0) {
                    int rp = Syntax.matching(rest, lp);
                    if (rp > lp) {
                        for (String p2 : Syntax.splitTop(rest.substring(lp + 1, rp), ',')) {
                            if (!p2.isBlank()) d.params.add(p2.trim());
                        }
                        rest = rest.substring(Math.min(rp + 1, rest.length()));
                    }
                }
            }
            int ext = Syntax.findWord(rest, "extends");
            int imp = Syntax.findWord(rest, "implements");
            if (ext >= 0) {
                String seg = imp > ext ? rest.substring(ext + 7, imp) : rest.substring(ext + 7);
                for (String s : Syntax.splitTop(seg, ',')) if (!s.isBlank()) d.extendsNames.add(Syntax.baseType(s.trim()));
            }
            if (imp >= 0) {
                String seg = ext > imp ? rest.substring(imp + 10, ext) : rest.substring(imp + 10);
                for (String s : Syntax.splitTop(seg, ',')) if (!s.isBlank()) d.implementsNames.add(Syntax.baseType(s.trim()));
            }
            return d;
        }

        // method or field
        int lp = t.indexOf('(');
        if (lp > 0 && Syntax.indexOfTop(t, '=') < 0) {
            // `Case c = repo.find(id);` is an assignment, not a method declaration. Without this
            // guard the local's declared type is lost, and with it every call made through it.
            String before = Syntax.stripGenerics(t.substring(0, lp)).trim();
            String[] toks = before.split("\\s+");
            if (toks.length == 0) return null;
            String name = toks[toks.length - 1];
            if (name.isEmpty() || JavaExtractor.NOT_DECL.contains(name) || name.indexOf('.') >= 0
                    || !Lex.isIdentStart(name.charAt(0))) return null;
            for (int i = 0; i < toks.length - 1; i++) if (MODIFIERS.contains(toks[i])) d.modifiers.add(toks[i]);
            String rt = null;
            for (int i = toks.length - 2; i >= 0; i--) {
                if (!MODIFIERS.contains(toks[i])) { rt = toks[i]; break; }
            }
            int rp = Syntax.matching(t, lp);
            if (rp < 0) return null;
            for (String p : Syntax.splitTop(t.substring(lp + 1, rp), ',')) if (!p.isBlank()) d.params.add(p.trim());
            d.name = name;
            d.typeName = rt;
            d.isMethod = true;
            return d;
        }

        // field / local variable: `Type name` or `Type name = ...`
        String lhs = t;
        int eq = Syntax.indexOfTop(t, '=');
        if (eq > 0) lhs = t.substring(0, eq);
        lhs = Syntax.stripGenerics(lhs).trim();
        String[] toks = lhs.split("\\s+");
        if (toks.length < 2) return null;
        String name = toks[toks.length - 1].replace("[]", "");
        if (name.isEmpty() || !Lex.isIdentStart(name.charAt(0))) return null;
        for (String m : toks) if (MODIFIERS.contains(m)) d.modifiers.add(m);
        String typeName = null;
        for (int i = toks.length - 2; i >= 0; i--) if (!MODIFIERS.contains(toks[i])) { typeName = toks[i]; break; }
        if (typeName == null) return null;
        d.name = name;
        d.typeName = typeName;
        return d;
    }
}
