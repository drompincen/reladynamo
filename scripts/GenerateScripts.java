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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates this repository's scripts from their text sources.
 *
 * <p>The repository tracks no executable scripts: every script is stored as {@code <name>.sh.txt}
 * next to where it runs, and the {@code .sh} files are gitignored build output. This program turns
 * each source into its script — no bash, Python or Maven needed, only a JDK 11+ in single-file
 * source mode. See {@code start-here.md}.
 *
 * <pre>
 *   java scripts/GenerateScripts.java            generate every script from its .txt source
 *   java scripts/GenerateScripts.java --check    exit 1 if any script is missing or differs
 *   java scripts/GenerateScripts.java --adopt    copy edited scripts back over their .txt sources
 * </pre>
 *
 * The {@code .txt} file is the source of truth. {@code --adopt} exists for the case where a script
 * was edited in place: run it before committing, or the edit is silently left out of git.
 */
public class GenerateScripts {

    /** Script extensions a {@code .txt} source may carry. */
    private static final List<String> SCRIPT_EXTENSIONS = Arrays.asList(".sh", ".cmd", ".ps1");

    /** Build output, VCS metadata and agent scratch space — never script sources. */
    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", "target", "node_modules", ".idea", ".state", ".grok-fleet", ".fleet");

    public static void main(String[] args) throws IOException {
        String mode = args.length == 0 ? "--generate" : args[0];
        if (!Arrays.asList("--generate", "--check", "--adopt").contains(mode)) {
            System.err.println("usage: java scripts/GenerateScripts.java [--check | --adopt]");
            System.exit(2);
        }
        Path root = Paths.get("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("pom.xml")) || !Files.isDirectory(root.resolve("scripts"))) {
            System.err.println("run from the repository root (no pom.xml and scripts/ in " + root + ")");
            System.exit(2);
        }

        List<Path> sources = findSources(root);
        List<String> changed = new ArrayList<>();
        for (Path source : sources) {
            Path script = scriptFor(source);
            byte[] expected = render(script, Files.readAllBytes(source));
            boolean same = Files.isRegularFile(script) && Arrays.equals(expected, Files.readAllBytes(script));
            String name = root.relativize(script).toString().replace('\\', '/');

            if ("--check".equals(mode)) {
                if (!same) {
                    changed.add((Files.exists(script) ? "differs  " : "missing  ") + name);
                }
            } else if ("--adopt".equals(mode)) {
                if (Files.isRegularFile(script) && !same) {
                    Files.write(source, render(script, Files.readAllBytes(script)));
                    changed.add("adopted  " + name + " -> " + source.getFileName());
                }
            } else {
                if (!same) {
                    Files.write(script, expected);
                    changed.add("wrote    " + name);
                }
                makeExecutable(script);
            }
        }

        changed.forEach(System.out::println);
        System.out.printf("%s: %d script sources, %d %s%n", mode.substring(2), sources.size(), changed.size(),
                "--check".equals(mode) ? "out of date" : "changed");
        if ("--check".equals(mode) && !changed.isEmpty()) {
            System.out.println("run: java scripts/GenerateScripts.java   (or --adopt if you edited a script in place)");
            System.exit(1);
        }
    }

    private static List<Path> findSources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> SCRIPT_EXTENSIONS.stream().anyMatch(ext -> stripTxt(p).endsWith(ext)))
                    .filter(p -> !inSkippedDir(root.relativize(p)))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static boolean inSkippedDir(Path relative) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (SKIPPED_DIRS.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    private static String stripTxt(Path source) {
        String name = source.getFileName().toString();
        return name.substring(0, name.length() - ".txt".length());
    }

    private static Path scriptFor(Path source) {
        return source.resolveSibling(stripTxt(source));
    }

    /** Shell scripts must have LF endings to run under bash, whatever the checkout or editor did. */
    private static byte[] render(Path script, byte[] content) {
        if (!script.getFileName().toString().endsWith(".sh")) {
            return content;
        }
        return new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
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
