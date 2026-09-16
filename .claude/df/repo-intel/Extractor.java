import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One extractor per language. Pass 1 only: everything here is derivable from a single file
 * in isolation. Anything that needs to look at another file is left as an unresolved
 * candidate for {@link Resolver} to decide on in pass 2.
 *
 * Extractors never execute, import, load or evaluate the source they read.
 */
interface Extractor {

    /** Language key recorded on every node this extractor produces. */
    String language();

    boolean supports(String relPath);

    /** Parse one file. Must not throw for malformed input -- record the problem and return what it got. */
    FileFacts extract(String relPath, String source);

    // ---------- shared pass-1 result types ----------

    /**
     * An import/include as written. `module` and `member` are language-specific but the
     * resolver only needs: what name did this bind locally, and what does it point at.
     */
    final class ImportRef {
        String alias;      // local binding name, or null for side-effect-only imports
        String module;     // package / module path / relative specifier as written
        String member;     // imported member within the module, or null
        String kind;       // java | java-static | python | es | cjs | shell
        boolean wildcard;
        int line;
        String raw;

        ImportRef(String alias, String module, String member, String kind, boolean wildcard, int line, String raw) {
            this.alias = alias; this.module = module; this.member = member;
            this.kind = kind; this.wildcard = wildcard; this.line = line; this.raw = raw;
        }
    }

    /** A call or reference candidate. Resolution happens in pass 2. */
    final class Ref {
        String from;          // enclosing symbol node id
        String receiver;      // text before the dot, or null
        String receiverType;  // declared type of the receiver when the extractor knew it
        String name;          // called member / referenced type
        int arity;            // -1 when unknown
        int line;
        String kind;          // call | new | type

        Ref(String from, String receiver, String receiverType, String name, int arity, int line, String kind) {
            this.from = from; this.receiver = receiver; this.receiverType = receiverType;
            this.name = name; this.arity = arity; this.line = line; this.kind = kind;
        }
    }

    /** extends / implements, by written name. */
    final class TypeRef {
        String from;       // node id of the declaring type
        String name;       // supertype as written
        String relation;   // EXTENDS | IMPLEMENTS
        int line;

        TypeRef(String from, String name, String relation, int line) {
            this.from = from; this.name = name; this.relation = relation; this.line = line;
        }
    }

    /** An external package dependency declared by a manifest file. */
    final class DepRef {
        String ecosystem;  // maven | npm | pypi | go
        String name;
        String version;
        String scope;      // compile | test | dev | optional
        boolean indirect;
        int line;

        DepRef(String ecosystem, String name, String version, String scope, boolean indirect, int line) {
            this.ecosystem = ecosystem; this.name = name; this.version = version;
            this.scope = scope; this.indirect = indirect; this.line = line;
        }
    }

    /** Everything one file yields in pass 1. */
    final class FileFacts {
        String path;
        String language;
        String namespace = "";               // java package / python module / ts dir
        String summary;                      // the file's header-comment description, for search
        List<GraphModel.Node> nodes = new ArrayList<>();
        List<GraphModel.Edge> edges = new ArrayList<>();   // containment only: always EXTRACTED
        List<ImportRef> imports = new ArrayList<>();
        List<Ref> refs = new ArrayList<>();
        List<TypeRef> supers = new ArrayList<>();
        List<DepRef> deps = new ArrayList<>();
        List<String> includes = new ArrayList<>();          // shell: sourced paths as written
        Map<String, String> exports = new LinkedHashMap<>(); // exported name -> node id (js/ts)
        String error;

        FileFacts(String path, String language) { this.path = path; this.language = language; }

        void add(GraphModel.Node n) { nodes.add(n); }

        void contains(String parent, String child, int line) {
            edges.add(new GraphModel.Edge(parent, child, "CONTAINS", GraphModel.EXTRACTED, path, line, "syntax"));
        }

        void defines(String file, String child, int line) {
            edges.add(new GraphModel.Edge(file, child, "DEFINES", GraphModel.EXTRACTED, path, line, "syntax"));
        }
    }
}
