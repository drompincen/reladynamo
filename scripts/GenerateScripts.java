import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates this repository's scripts from the markdown files in {@code script-instructions/}.
 *
 * <p>The repository tracks no scripts, not even as text files: every script lives as a fenced code
 * block under a {@code ## <path>} heading in a {@code script-instructions/*.md} file, and the
 * generated files are gitignored build output. This program writes each block to its path — no bash,
 * Python or Maven needed, only a JDK 11+ in single-file source mode. See {@code start-here.md}.
 *
 * <pre>
 *   java scripts/GenerateScripts.java            generate every script from script-instructions/
 *   java scripts/GenerateScripts.java --check    exit 1 if any script is missing or differs
 *   java scripts/GenerateScripts.java --adopt    copy edited scripts back into their markdown blocks
 * </pre>
 *
 * The markdown is the source of truth. {@code --adopt} exists for the case where a script was edited
 * in place: run it before committing, or the edit is silently left out of git.
 */
public class GenerateScripts {

    private static final String INSTRUCTIONS_DIR = "script-instructions";

    /** Script extensions a section heading may name. */
    private static final List<String> SCRIPT_EXTENSIONS = Arrays.asList(".sh", ".cmd", ".ps1");

    private static final Pattern HEADING = Pattern.compile("^## (\\S+)\\s*$");
    private static final Pattern FENCE_OPEN = Pattern.compile("^(`{3,})[A-Za-z0-9_-]*\\s*$");

    /** One {@code ## <path>} section: where its code block sits in the markdown, and what it holds. */
    private static final class Section {
        final Path markdown;
        final Path script;
        final int firstLine;   // index of the first content line (just after the opening fence)
        final int endLine;     // index of the closing fence
        final String fence;
        final String content;

        Section(Path markdown, Path script, int firstLine, int endLine, String fence, String content) {
            this.markdown = markdown;
            this.script = script;
            this.firstLine = firstLine;
            this.endLine = endLine;
            this.fence = fence;
            this.content = content;
        }
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length == 0 ? "--generate" : args[0];
        if (!Arrays.asList("--generate", "--check", "--adopt").contains(mode)) {
            System.err.println("usage: java scripts/GenerateScripts.java [--check | --adopt]");
            System.exit(2);
        }
        Path root = Paths.get("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml")) || !Files.isDirectory(root.resolve(INSTRUCTIONS_DIR))) {
            System.err.println("run from the repository root (no pom.xml and " + INSTRUCTIONS_DIR + "/ in " + root + ")");
            System.exit(2);
        }

        List<Section> sections = parseAll(root);
        List<String> changed = new ArrayList<>();
        Map<Path, List<Section>> adopted = new LinkedHashMap<>();
        for (Section section : sections) {
            Path script = section.script;
            byte[] expected = section.content.getBytes(StandardCharsets.UTF_8);
            boolean same = Files.isRegularFile(script) && Arrays.equals(expected, Files.readAllBytes(script));
            String name = root.relativize(script).toString().replace('\\', '/');

            if ("--check".equals(mode)) {
                if (!same) {
                    changed.add((Files.exists(script) ? "differs  " : "missing  ") + name);
                }
            } else if ("--adopt".equals(mode)) {
                if (Files.isRegularFile(script) && !same) {
                    adopted.computeIfAbsent(section.markdown, k -> new ArrayList<>()).add(section);
                    changed.add("adopted  " + name + " -> " + root.relativize(section.markdown).toString().replace('\\', '/'));
                }
            } else {
                if (!same) {
                    Files.createDirectories(script.getParent());
                    Files.write(script, expected);
                    changed.add("wrote    " + name);
                }
                makeExecutable(script);
            }
        }
        for (Map.Entry<Path, List<Section>> entry : adopted.entrySet()) {
            adopt(entry.getKey(), entry.getValue());
        }

        changed.forEach(System.out::println);
        System.out.printf("%s: %d scripts, %d %s%n", mode.substring(2), sections.size(), changed.size(),
                "--check".equals(mode) ? "out of date" : "changed");
        if ("--check".equals(mode) && !changed.isEmpty()) {
            System.out.println("run: java scripts/GenerateScripts.java   (or --adopt if you edited a script in place)");
            System.exit(1);
        }
    }

    private static List<Section> parseAll(Path root) throws IOException {
        List<Path> markdowns;
        try (Stream<Path> list = Files.list(root.resolve(INSTRUCTIONS_DIR))) {
            markdowns = list.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        List<Section> sections = new ArrayList<>();
        Map<Path, Path> seen = new LinkedHashMap<>();
        for (Path markdown : markdowns) {
            for (Section section : parse(root, markdown)) {
                Path previous = seen.putIfAbsent(section.script, markdown);
                if (previous != null) {
                    fail(markdown, "script " + root.relativize(section.script).toString().replace('\\', '/') + " is also defined in " + previous.getFileName());
                }
                sections.add(section);
            }
        }
        return sections;
    }

    /** Reads every {@code ## <path>} heading and the first fenced block after it. */
    private static List<Section> parse(Path root, Path markdown) throws IOException {
        List<String> lines = readLines(markdown);
        List<Section> sections = new ArrayList<>();
        String fence = null;
        Path pending = null;
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (fence != null) {
                if (line.equals(fence)) {
                    if (pending != null) {
                        String content = lines.subList(start, i).stream().map(l -> l + "\n").collect(Collectors.joining());
                        sections.add(new Section(markdown, pending, start, i, fence, content));
                        pending = null;
                    }
                    fence = null;
                }
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (pending != null) {
                    fail(markdown, "section before line " + (i + 1) + " has no code block");
                }
                pending = scriptPath(root, markdown, heading.group(1), i + 1);
                continue;
            }
            Matcher open = FENCE_OPEN.matcher(line);
            if (open.matches()) {
                fence = open.group(1);
                start = i + 1;
            }
        }
        if (fence != null) {
            fail(markdown, "unclosed code block starting at line " + start);
        }
        if (pending != null) {
            fail(markdown, "last section has no code block");
        }
        return sections;
    }

    /** Resolves a heading to a script path, refusing anything outside the repository or not a script. */
    private static Path scriptPath(Path root, Path markdown, String heading, int lineNumber) {
        if (SCRIPT_EXTENSIONS.stream().noneMatch(heading::endsWith)) {
            fail(markdown, "line " + lineNumber + ": '" + heading + "' does not name a " + SCRIPT_EXTENSIONS + " script");
        }
        Path relative = Paths.get(heading);
        Path script = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !script.startsWith(root) || script.startsWith(root.resolve(".git"))) {
            fail(markdown, "line " + lineNumber + ": '" + heading + "' is outside the repository");
        }
        return script;
    }

    /** Replaces each adopted section's block with the script as it now stands on disk. */
    private static void adopt(Path markdown, List<Section> sections) throws IOException {
        List<String> lines = readLines(markdown);
        List<Section> bottomUp = new ArrayList<>(sections);
        bottomUp.sort((a, b) -> Integer.compare(b.firstLine, a.firstLine));
        for (Section section : bottomUp) {
            List<String> body = readLines(section.script);
            if (body.contains(section.fence)) {
                fail(markdown, section.script.getFileName() + " contains a line equal to its fence " + section.fence + "; widen the fence");
            }
            lines.subList(section.firstLine, section.endLine).clear();
            lines.addAll(section.firstLine, body);
        }
        String joined = lines.stream().map(l -> l + "\n").collect(Collectors.joining());
        Files.write(markdown, joined.getBytes(StandardCharsets.UTF_8));
    }

    /** Lines without terminators; CRLF is folded to LF so scripts always run under bash. */
    private static List<String> readLines(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return new ArrayList<>(text.isEmpty() ? List.of() : Arrays.asList(text.split("\n", -1)));
    }

    private static void fail(Path markdown, String message) {
        System.err.println(markdown.getFileName() + ": " + message);
        System.exit(2);
    }

    private static void makeExecutable(Path script) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            perms.addAll(Files.getPosixFilePermissions(script));
            perms.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
            Files.setPosixFilePermissions(script, perms);
        } catch (UnsupportedOperationException windowsFileSystem) {
            // No POSIX permissions here; every script in this repository is invoked as `bash <file>`.
        }
    }
}
