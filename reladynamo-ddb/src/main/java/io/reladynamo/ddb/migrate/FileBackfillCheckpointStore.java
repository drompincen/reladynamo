package io.reladynamo.ddb.migrate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Line-oriented checkpoint file. Written to a sibling temp path then renamed so a kill
 * cannot leave a half-written record as the live checkpoint.
 *
 * <p>Java 11 baseline. No JSON library — keep the ddb module's dependency set unchanged.
 */
public final class FileBackfillCheckpointStore implements BackfillCheckpointStore {

    private static final String MAGIC = "RDCHK1";

    private final Path path;

    public FileBackfillCheckpointStore(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("checkpoint path is required");
        }
        this.path = path;
    }

    @Override
    public void save(BackfillCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint is required");
        }
        rejectNewlines("snapshotId", checkpoint.snapshotId());
        rejectNewlines("watermark", checkpoint.watermark());
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(MAGIC);
                w.newLine();
                w.write("snapshotId=");
                w.write(checkpoint.snapshotId());
                w.newLine();
                w.write("watermark=");
                w.write(checkpoint.watermark());
                w.newLine();
                w.write("rowsRead=");
                w.write(Integer.toString(checkpoint.rowsRead()));
                w.newLine();
                w.write("rowsWritten=");
                w.write(Integer.toString(checkpoint.rowsWritten()));
                w.newLine();
                w.write("--partitions--");
                w.newLine();
                for (String p : checkpoint.completedPartitions()) {
                    rejectNewlines("partition", p);
                    w.write(p);
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not write checkpoint " + path, e);
        }
    }

    @Override
    public BackfillCheckpoint load() {
        if (!Files.exists(path)) {
            return null;
        }
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String magic = r.readLine();
            if (!MAGIC.equals(magic)) {
                throw new IllegalStateException("checkpoint " + path + " is not a RDCHK1 file");
            }
            String snapshotId = requiredValue(r.readLine(), "snapshotId");
            String watermark = requiredValue(r.readLine(), "watermark");
            int rowsRead = Integer.parseInt(requiredValue(r.readLine(), "rowsRead"));
            int rowsWritten = Integer.parseInt(requiredValue(r.readLine(), "rowsWritten"));
            String marker = r.readLine();
            if (!"--partitions--".equals(marker)) {
                throw new IllegalStateException("checkpoint " + path + " is missing the partitions marker");
            }
            Set<String> partitions = new LinkedHashSet<String>();
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    partitions.add(line);
                }
            }
            return new BackfillCheckpoint(snapshotId, watermark, partitions, rowsRead, rowsWritten);
        } catch (IOException e) {
            throw new IllegalStateException("could not read checkpoint " + path, e);
        }
    }

    private static String requiredValue(String line, String key) {
        if (line == null || !line.startsWith(key + "=")) {
            throw new IllegalStateException("checkpoint is missing field " + key);
        }
        return line.substring(key.length() + 1);
    }

    private static void rejectNewlines(String field, String value) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException(field + " must not contain a newline");
        }
    }
}
