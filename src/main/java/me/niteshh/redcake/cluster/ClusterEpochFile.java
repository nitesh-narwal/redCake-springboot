package me.niteshh.redcake.cluster;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Remembers, next to the append-only file, which leader epoch that file's data belongs to ("this AOF was
 * written by the primary of epoch 7").
 *
 * <p>Replicas do not write an AOF, so after a power failure of the whole cluster the AOFs on disk have
 * different ages. The election compares this number with the cluster's current epoch and refuses to promote a
 * node whose file is older - without it a node with a stale AOF could win and overwrite everybody with
 * month-old data.
 *
 * <p>Written when a node becomes primary, <em>before</em> it accepts its first write: a crash in between
 * leaves a number that is too old, which only makes the node more cautious, never less.
 */
public final class ClusterEpochFile {

    private ClusterEpochFile() {
    }

    /** {@code redcake.aof} becomes {@code redcake.aof.epoch}. */
    public static Path forAof(Path aofFile) {
        return aofFile.resolveSibling(aofFile.getFileName() + ".epoch");
    }

    /** @return the stored epoch, or {@code -1} if there is no (readable) file */
    public static long read(Path file) {
        try {
            if (!Files.exists(file)) {
                return -1;
            }
            return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (IOException | NumberFormatException e) {
            return -1; // unreadable means unknown, which the election treats as "old"
        }
    }

    /** Atomically replaces the file and forces it to disk. */
    public static void write(Path file, long epoch) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
            out.write((epoch + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
