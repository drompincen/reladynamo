import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ESM and CommonJS import/export handling: named, default, namespace and type-only imports,
 * re-exports, and `require`. Each one records what name it bound locally and what specifier it
 * bound it from; resolving the specifier to a file is the resolver's job, not this one's.
 */
final class JsTsImports {

    private JsTsImports() {}
    static boolean handleImportOrExport(Extractor.FileFacts f, String path, String fileLang, String t,
                                         String raw, int line, JsTsExtractor.Scope cur) {
        String s = JsTsSyntax.collapseWs(t);
        String rawS = JsTsSyntax.collapseWs(raw);

        // re-export: export { a, b as c } from "m"  /  export type { T } from "m"
        // NB: the masked head ends at `from` because the module literal has been blanked, so a
        // `contains(" from ")` test misses every re-export. Match the word, not the spacing.
        if (s.startsWith("export ") && JsTsSyntax.findWord(s, "from") >= 0) {
            int from = JsTsSyntax.findWord(s, "from");
            if (from >= 0) {
                // NB: `s` is masked and `rawS` is not, and collapsing whitespace shortens them
                // by different amounts -- so an offset taken from one cannot index the other.
                String mod = JsTsSyntax.lastStringLit(rawS);
                if (mod == null) mod = JsTsSyntax.stringLit(rawS, from);
                String between = s.substring(0, from).trim();
                // export { ... } from
                int lb = between.indexOf('{');
                int rb = between.lastIndexOf('}');
                if (lb >= 0 && rb > lb && mod != null) {
                    String body = between.substring(lb + 1, rb);
                    for (String part : JsTsSyntax.splitTop(body, ',')) {
                        String p = part.trim();
                        if (p.isEmpty()) continue;
                        String[] ab = JsTsSyntax.parseAs(p);
                        if (ab[0] == null || ab[0].isEmpty()) continue;
                        String member = ab[0];
                        String alias = ab[1] != null && !ab[1].isEmpty() ? ab[1] : ab[0];
                        f.imports.add(new Extractor.ImportRef(alias, mod, member, "es", false, line, rawS));
                        f.exports.put(alias, ""); // re-export: no local node
                    }
                    return true;
                }
                // export * from "m" / export * as ns from "m"
                if (between.contains("*") && mod != null) {
                    f.imports.add(new Extractor.ImportRef(null, mod, null, "es", true, line, rawS));
                    return true;
                }
            }
        }

        // export { a, b as c }  (local)
        if (s.startsWith("export ") && s.contains("{") && JsTsSyntax.findWord(s, "from") < 0) {
            int lb = s.indexOf('{');
            int rb = s.lastIndexOf('}');
            if (lb >= 0 && rb > lb) {
                for (String part : JsTsSyntax.splitTop(s.substring(lb + 1, rb), ',')) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    String[] ab = JsTsSyntax.parseAs(p);
                    String name = ab[0];
                    String as = ab[1] != null ? ab[1] : ab[0];
                    // map export name -> existing node id if we know it
                    String id = JsTsScan.findNodeIdByName(f, name);
                    f.exports.put(as, id != null ? id : "");
                }
                // bare export { } may also wrap a default-less list after other decls
                if (!s.contains("function") && !s.contains("class") && !s.contains("const")
                        && !s.contains("let") && !s.contains("var") && !s.contains("interface")
                        && !s.contains("enum") && !s.contains("type ") && JsTsSyntax.findWord(s, "type") < 0) {
                    return true;
                }
            }
        }

        // export default <name>;
        if (s.startsWith("export default ") || s.equals("export default")) {
            String rest = s.substring("export default".length()).trim();
            if (!rest.isEmpty() && !rest.startsWith("function") && !rest.startsWith("class")
                    && !rest.startsWith("abstract") && !rest.startsWith("async")) {
                String name = JsTsSyntax.firstIdent(rest);
                if (!name.isEmpty()) {
                    String id = JsTsScan.findNodeIdByName(f, name);
                    f.exports.put("default", id != null ? id : "");
                } else {
                    f.exports.put("default", "");
                }
                return true;
            }
            // export default function/class falls through to declaration parsing
        }

        // import forms
        if (s.startsWith("import ") || s.startsWith("import{")) {
            parseEsImport(f, s, rawS, line, cur);
            return true;
        }

        // export const/let/function/class handled as declarations with isExport flag in parse*
        // Fall through for `export const x = ...` etc.
        if (s.startsWith("export ")) return false;

        return false;
    }
    static void parseEsImport(Extractor.FileFacts f, String s, String rawS, int line, JsTsExtractor.Scope cur) {
        // strip leading import / import type
        String rest = s.substring(6).trim(); // after "import"
        boolean typeOnly = false;
        if (rest.startsWith("type ") || rest.equals("type") || rest.startsWith("type{")) {
            typeOnly = true;
            if (rest.startsWith("type")) rest = rest.substring(4).trim();
        }

        // bare: import "m"
        if (rest.startsWith("\"") || rest.startsWith("'") || rest.startsWith("`")) {
            String mod = JsTsSyntax.stringLit(rawS, 0);
            if (mod == null) mod = JsTsSyntax.unquote(rest);
            f.imports.add(new Extractor.ImportRef(null, mod, null, "es", false, line, rawS));
            return;
        }

        int from = JsTsSyntax.findWord(rest, "from");
        String mod = null;
        String clause = rest;
        if (from >= 0) {
            mod = JsTsSyntax.stringLit(rawS.substring(rawS.toLowerCase(Locale.ROOT).lastIndexOf("from") >= 0
                    ? Math.max(0, rawS.toLowerCase(Locale.ROOT).lastIndexOf("from")) : 0), 0);
            // more reliable: string after from in rawS
            int rawFrom = JsTsSyntax.findWord(rawS, "from");
            if (rawFrom < 0) rawFrom = JsTsSyntax.findWord(s, "from");
            mod = JsTsSyntax.stringLit(rawS, rawFrom >= 0 ? rawFrom : 0);
            if (mod == null) mod = JsTsSyntax.stringLit(s, from);
            clause = rest.substring(0, from).trim();
        }
        if (mod == null) return;

        // import * as ns from "m"
        if (clause.startsWith("*")) {
            String alias = null;
            int as = JsTsSyntax.findWord(clause, "as");
            if (as >= 0) alias = JsTsSyntax.firstIdent(clause.substring(as + 2).trim());
            f.imports.add(new Extractor.ImportRef(alias, mod, null, "es", true, line, rawS));
            if (alias != null && cur != null) {
                // module namespace alias — record module specifier as type hint for resolver
                cur.vars.put(alias, mod);
            }
            return;
        }

        // default + optional named: import X from "m"  /  import X, { a } from "m"
        String defaultAlias = null;
        int brace = clause.indexOf('{');
        if (brace < 0) {
            // import X from "m" only
            defaultAlias = JsTsSyntax.firstIdent(clause);
            if (!defaultAlias.isEmpty()) {
                f.imports.add(new Extractor.ImportRef(defaultAlias, mod, "default", "es", false, line, rawS));
                if (cur != null) cur.vars.put(defaultAlias, defaultAlias);
            }
            return;
        }
        // possible default before brace
        String before = clause.substring(0, brace).trim();
        if (before.endsWith(",")) before = before.substring(0, before.length() - 1).trim();
        if (!before.isEmpty() && !before.equals("type")) {
            defaultAlias = JsTsSyntax.firstIdent(before);
            if (!defaultAlias.isEmpty() && !"type".equals(defaultAlias)) {
                f.imports.add(new Extractor.ImportRef(defaultAlias, mod, "default", "es", false, line, rawS));
                if (cur != null) cur.vars.put(defaultAlias, defaultAlias);
            }
        }
        int rb = clause.lastIndexOf('}');
        if (rb > brace) {
            String body = clause.substring(brace + 1, rb);
            // drop leading type keyword inside: import { type T, a }
            for (String part : JsTsSyntax.splitTop(body, ',')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (p.startsWith("type ")) p = p.substring(5).trim();
                String[] ab = JsTsSyntax.parseAs(p);
                if (ab[0] == null || ab[0].isEmpty()) continue;
                String member = ab[0];
                String alias = ab[1] != null ? ab[1] : ab[0];
                f.imports.add(new Extractor.ImportRef(alias, mod, member, "es", false, line, rawS));
                if (cur != null) cur.vars.put(alias, member);
            }
        }
    }
    static boolean handleRequire(Extractor.FileFacts f, String t, String raw, int line, JsTsExtractor.Scope cur) {
        String s = JsTsSyntax.collapseWs(t);
        // const x = require("m")  /  let x = require('m')
        int req = JsTsSyntax.findWord(s, "require");
        if (req < 0) return false;
        int lp = s.indexOf('(', req);
        if (lp < 0) return false;
        String mod = JsTsSyntax.stringLit(raw, raw.indexOf('(', Math.max(0, JsTsSyntax.findWord(raw, "require"))));
        if (mod == null) mod = JsTsSyntax.stringLit(s, lp);
        if (mod == null) return false;

        String alias = null;
        // leading const/let/var name =
        String before = s.substring(0, req).trim();
        if (before.startsWith("const ") || before.startsWith("let ") || before.startsWith("var ")) {
            String lhs = before;
            int sp = lhs.indexOf(' ');
            lhs = lhs.substring(sp + 1).trim();
            int eq = lhs.indexOf('=');
            if (eq >= 0) lhs = lhs.substring(0, eq).trim();
            // strip type annotation
            int colon = JsTsSyntax.indexOfTop(lhs, ':');
            if (colon >= 0) lhs = lhs.substring(0, colon).trim();
            alias = JsTsSyntax.firstIdent(lhs);
        }
        f.imports.add(new Extractor.ImportRef(alias, mod, null, "cjs", false, line, raw));
        if (alias != null && cur != null) cur.vars.put(alias, mod);
        return true;
    }
}
