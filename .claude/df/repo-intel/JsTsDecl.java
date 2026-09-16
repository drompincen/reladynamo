import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Declaration-head parsing for TypeScript and JavaScript: classes, interfaces, enums, type
 * aliases, functions, arrow consts and class members, plus the parameter binding that gives a
 * receiver its declared type.
 */
final class JsTsDecl {

    private JsTsDecl() {}
    static JsTsExtractor.Decl parseBraceDecl(String head, String rawHead) {
        JsTsExtractor.Decl d = new JsTsExtractor.Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return null;

        // strip leading export / default / declare / async
        t = JsTsScan.stripExportPrefix(t, d);

        // class / interface / enum
        for (String kw : new String[]{"class", "interface", "enum"}) {
            int p = JsTsSyntax.findWord(t, kw);
            if (p < 0) continue;
            String before = t.substring(0, p).trim();
            if (before.contains("=") && !before.endsWith("=")) {
                // e.g. const x = class — still ok if = is last meaningful
            }
            for (String m : before.split("\\s+")) if (JsTsExtractor.MODIFIERS.contains(m)) d.modifiers.add(m);
            String after = t.substring(p + kw.length()).trim();
            String name = JsTsSyntax.firstIdent(after);
            if (name.isEmpty()) continue;
            d.typeKeyword = kw;
            d.name = name;
            String rest = after.substring(after.indexOf(name) + name.length());
            rest = JsTsSyntax.stripGenerics(rest);
            int ext = JsTsSyntax.findWord(rest, "extends");
            int imp = JsTsSyntax.findWord(rest, "implements");
            if (ext >= 0) {
                String seg = imp > ext ? rest.substring(ext + 7, imp) : rest.substring(ext + 7);
                for (String s : JsTsSyntax.splitTop(seg, ',')) {
                    String b = JsTsSyntax.baseType(s.trim());
                    if (!b.isEmpty()) {
                        if ("interface".equals(kw)) d.extendsNames.add(b);
                        else d.extendsNames.add(b);
                    }
                }
            }
            if (imp >= 0 && "class".equals(kw)) {
                String seg = rest.substring(imp + 10);
                for (String s : JsTsSyntax.splitTop(seg, ',')) {
                    String b = JsTsSyntax.baseType(s.trim());
                    if (!b.isEmpty()) d.implementsNames.add(b);
                }
            }
            return d;
        }

        // function name(...) {
        int fk = JsTsSyntax.findWord(t, "function");
        if (fk >= 0) {
            String after = t.substring(fk + 8).trim();
            // skip generics on function
            if (after.startsWith("<")) after = JsTsSyntax.stripLeadingGenerics(after).trim();
            String name = JsTsSyntax.firstIdent(after);
            if (name.isEmpty()) {
                // export default function(
                if (d.isDefaultExport) name = "default";
                else return null;
            }
            int lp = after.indexOf('(');
            if (lp < 0) return null;
            int rp = JsTsSyntax.matching(after, lp);
            if (rp < 0) return null;
            for (String p : JsTsSyntax.splitTop(after.substring(lp + 1, rp), ',')) {
                if (!p.isBlank()) d.params.add(p.trim());
            }
            d.name = name;
            d.isMethod = true;
            return d;
        }

        // class member: name(...) {  or  async name(...) {  or  constructor(
        // or get/set name(
        JsTsExtractor.Decl mem = parseMember(t, rawHead);
        if (mem != null && mem.isMethod) return mem;

        return null;
    }
    static JsTsExtractor.Decl parseMember(String head, String rawHead) {
        JsTsExtractor.Decl d = new JsTsExtractor.Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return null;
        t = JsTsScan.stripExportPrefix(t, d);

        // field: [mods] name: Type = ...   or  name: Type
        // method: [mods] name<G>(params): Ret
        // constructor(params)
        int lp = JsTsSyntax.indexOfTop(t, '(');
        if (lp > 0) {
            String before = t.substring(0, lp).trim();
            before = JsTsSyntax.stripGenerics(before).trim();
            String[] toks = before.split("\\s+");
            if (toks.length == 0) return null;
            String name = toks[toks.length - 1];
            if (name.isEmpty() || !Lex.isIdentStart(name.charAt(0))) return null;
            if (JsTsExtractor.NOT_CALL.contains(name) && !"constructor".equals(name)) return null;
            for (int i = 0; i < toks.length - 1; i++) {
                if (JsTsExtractor.MODIFIERS.contains(toks[i])) d.modifiers.add(toks[i]);
            }
            int rp = JsTsSyntax.matching(t, lp);
            if (rp < 0) return null;
            String paramSrc = t.substring(lp + 1, rp);
            for (String p : JsTsSyntax.splitTop(paramSrc, ',')) if (!p.isBlank()) d.params.add(p.trim());
            d.name = name;
            d.isMethod = true;
            // return type after ):
            String after = t.substring(rp + 1).trim();
            if (after.startsWith(":")) {
                String rt = after.substring(1).trim();
                int brace = JsTsSyntax.indexOfTop(rt, '{');
                int arrow = rt.indexOf("=>");
                if (arrow >= 0 && (brace < 0 || arrow < brace)) rt = rt.substring(0, arrow).trim();
                d.typeName = JsTsSyntax.baseType(rt);
            }
            return d;
        }

        // field without (
        String lhs = t;
        int eq = JsTsSyntax.indexOfTop(t, '=');
        if (eq > 0) lhs = t.substring(0, eq).trim();
        // strip definite assignment !
        lhs = lhs.replace("!", " ").trim();
        int colon = JsTsSyntax.indexOfTop(lhs, ':');
        String namePart = colon >= 0 ? lhs.substring(0, colon).trim() : lhs;
        String typePart = colon >= 0 ? lhs.substring(colon + 1).trim() : null;
        String[] toks = namePart.split("\\s+");
        if (toks.length == 0) return null;
        String name = toks[toks.length - 1];
        if (name.isEmpty() || !Lex.isIdentStart(name.charAt(0))) return null;
        if (JsTsExtractor.TYPE_KW.contains(name) || JsTsExtractor.NOT_CALL.contains(name)) return null;
        for (int i = 0; i < toks.length - 1; i++) {
            if (JsTsExtractor.MODIFIERS.contains(toks[i])) d.modifiers.add(toks[i]);
        }
        // must look like a field: has modifier or type annotation or is simple name in class
        d.name = name;
        if (typePart != null) d.typeName = JsTsSyntax.baseType(typePart);
        d.isMethod = false;
        return d;
    }
    static JsTsExtractor.Decl parseVarOrFunction(String head, String rawHead) {
        JsTsExtractor.Decl d = new JsTsExtractor.Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return null;
        t = JsTsScan.stripExportPrefix(t, d);

        // function foo(...)  (no brace — shouldn't happen often)
        int fk = JsTsSyntax.findWord(t, "function");
        if (fk >= 0 && JsTsSyntax.indexOfTop(t, '=') < 0) {
            String after = t.substring(fk + 8).trim();
            if (after.startsWith("<")) after = JsTsSyntax.stripLeadingGenerics(after).trim();
            String name = JsTsSyntax.firstIdent(after);
            int lp = after.indexOf('(');
            if (name.isEmpty() || lp < 0) return null;
            int rp = JsTsSyntax.matching(after, lp);
            if (rp < 0) return null;
            for (String p : JsTsSyntax.splitTop(after.substring(lp + 1, rp), ',')) {
                if (!p.isBlank()) d.params.add(p.trim());
            }
            d.name = name;
            d.isMethod = true;
            return d;
        }

        // const/let/var name: Type = ...
        int kwPos = -1;
        String kw = null;
        for (String k : new String[]{"const", "let", "var"}) {
            int p = JsTsSyntax.findWord(t, k);
            if (p >= 0 && (kwPos < 0 || p < kwPos)) { kwPos = p; kw = k; }
        }
        if (kwPos < 0) return null;
        String after = t.substring(kwPos + kw.length()).trim();
        int eq = JsTsSyntax.indexOfTop(after, '=');
        String lhs = eq >= 0 ? after.substring(0, eq).trim() : after;
        String rhs = eq >= 0 ? after.substring(eq + 1).trim() : "";

        // name: Type
        int colon = JsTsSyntax.indexOfTop(lhs, ':');
        String nameStr = colon >= 0 ? lhs.substring(0, colon).trim() : lhs;
        String typeStr = colon >= 0 ? lhs.substring(colon + 1).trim() : null;
        // destructuring skip
        if (nameStr.startsWith("{") || nameStr.startsWith("[")) return null;
        String name = JsTsSyntax.firstIdent(nameStr);
        if (name.isEmpty()) return null;
        d.name = name;
        if (typeStr != null) d.typeName = JsTsSyntax.baseType(typeStr);

        // arrow or function expression on RHS?
        if (JsTsScan.isFunctionExpr(rhs)) {
            d.isMethod = true;
            d.params.addAll(JsTsScan.extractParamsFromFn(rhs));
        } else {
            d.isMethod = false;
        }
        return d;
    }
    static JsTsExtractor.Decl parseTypeAlias(String head) {
        JsTsExtractor.Decl d = new JsTsExtractor.Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        t = JsTsScan.stripExportPrefix(t, d);
        int p = JsTsSyntax.findWord(t, "type");
        if (p < 0) return null;
        // avoid `import type` already handled; here `type Name =`
        String before = t.substring(0, p).trim();
        if (before.contains("import")) return null;
        String after = t.substring(p + 4).trim();
        String name = JsTsSyntax.firstIdent(after);
        if (name.isEmpty()) return null;
        // must have = somewhere for alias
        if (JsTsSyntax.indexOfTop(after, '=') < 0) return null;
        d.typeKeyword = "type";
        d.name = name;
        return d;
    }
    static void bindParams(JsTsExtractor.Scope sc, JsTsExtractor.Decl d) {
        for (String p : d.params) {
            String[] kv = JsTsScan.splitTsParam(p);
            if (kv == null) continue;
            if (kv[0] != null && !"any".equals(kv[0]) && !"unknown".equals(kv[0])) {
                sc.vars.put(kv[1], JsTsSyntax.baseType(kv[0]));
            }
        }
    }
}
