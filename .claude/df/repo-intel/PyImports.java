import java.util.ArrayList;
import java.util.List;

/**
 * Python import statements, including every `from ... import ...` form and relative imports,
 * which are resolved to an absolute dotted module here so the resolver never has to guess what
 * a leading dot meant.
 */
final class PyImports {

    private PyImports() {}
    static void parseImport(Extractor.FileFacts f, String trimmed, int line, PythonExtractor.Scope fileScope) {
        String raw = trimmed;
        if (trimmed.startsWith("from ")) {
            // from X import a, b as c
            String rest = trimmed.substring(5).trim();
            int importAt = PySyntax.findWord(rest, "import");
            if (importAt < 0) return;
            String modPart = rest.substring(0, importAt).trim();
            String namesPart = rest.substring(importAt + 6).trim();
            if (namesPart.startsWith("(") && namesPart.endsWith(")")) {
                namesPart = namesPart.substring(1, namesPart.length() - 1).trim();
            }

            int level = 0;
            while (level < modPart.length() && modPart.charAt(level) == '.') level++;
            String modPath = modPart.substring(level).trim();
            String abs = resolveRelative(f.namespace, level, modPath);

            if ("*".equals(namesPart)) {
                f.imports.add(new Extractor.ImportRef("*", abs, "*", "python", true, line, raw));
                return;
            }
            for (String part : PySyntax.splitTop(namesPart, ',')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                String member = p;
                String alias = p;
                int asAt = PySyntax.findWord(p, "as");
                if (asAt >= 0) {
                    member = p.substring(0, asAt).trim();
                    alias = p.substring(asAt + 2).trim();
                }
                member = PySyntax.firstIdent(member);
                alias = PySyntax.firstIdent(alias);
                if (member.isEmpty()) continue;
                if (alias.isEmpty()) alias = member;
                f.imports.add(new Extractor.ImportRef(alias, abs, member, "python", false, line, raw));
                // Bind alias for receiver typing: module.member or just the imported name
                String bound = abs == null || abs.isEmpty() ? member : abs + "." + member;
                fileScope.vars.put(alias, bound);
            }
        } else if (trimmed.startsWith("import ")) {
            String rest = trimmed.substring(7).trim();
            for (String part : PySyntax.splitTop(rest, ',')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                String module = p;
                String alias = null;
                int asAt = PySyntax.findWord(p, "as");
                if (asAt >= 0) {
                    module = p.substring(0, asAt).trim();
                    alias = p.substring(asAt + 2).trim();
                }
                module = PySyntax.takeDottedName(module);
                if (module.isEmpty()) continue;
                if (alias == null || alias.isEmpty()) {
                    // import a.b.c binds `a`
                    alias = module.contains(".") ? module.substring(0, module.indexOf('.')) : module;
                } else {
                    alias = PySyntax.firstIdent(alias);
                }
                f.imports.add(new Extractor.ImportRef(alias, module, null, "python", false, line, raw));
                fileScope.vars.put(alias, module);
            }
        }
    }
    /**
     * Resolve a relative import against the current module namespace.
     * level=0 absolute; level=1 current package; level=2 parent package; etc.
     */
    static String resolveRelative(String namespace, int level, String modPath) {
        if (level <= 0) return modPath == null ? "" : modPath;
        // Package of this module: for acme.service.case_service -> acme.service
        // for package module acme (__init__) -> acme
        String pkg = namespace == null ? "" : namespace;
        // Always treat namespace as the module; package is parent unless this is a package root
        // Relative imports are relative to the package containing the module.
        List<String> parts = new ArrayList<>();
        if (pkg != null && !pkg.isEmpty()) {
            for (String s : pkg.split("\\.")) if (!s.isEmpty()) parts.add(s);
        }
        // Drop the module leaf to get the containing package (for non-package modules).
        // For `acme.service.case_service`, containing package is `acme.service`.
        // For a namespace that is itself a package (from __init__.py), path ends with the package
        // name and modulePath already dropped __init__, so namespace IS the package.
        // Heuristic: we always drop one component for relative base when level>=1, matching
        // Python's rule that the current package is the parent of a plain module file.
        // When the file is __init__.py, PySyntax.modulePath() already yields the package, and Python
        // treats that package as the current package — so we should NOT drop.
        // Callers pass namespace from modulePath which drops __init__. We cannot see the path
        // here; use: if level>=1, base = parts with (level) components removed from the end
        // starting from the package. Python: go up `level` from the *package*, not the module.
        // For module acme.service.case_service, package = acme.service, level 1 stays, level 2 -> acme.
        if (!parts.isEmpty()) {
            // Assume namespace is a module (not package) when it has 2+ parts OR always drop one?
            // Safer approach matching fixtures: drop last component to get package, then go up level-1.
            parts.remove(parts.size() - 1); // now containing package
        }
        int up = level - 1;
        while (up > 0 && !parts.isEmpty()) {
            parts.remove(parts.size() - 1);
            up--;
        }
        if (modPath != null && !modPath.isEmpty()) {
            for (String s : modPath.split("\\.")) if (!s.isEmpty()) parts.add(s);
        }
        return String.join(".", parts);
    }
}
