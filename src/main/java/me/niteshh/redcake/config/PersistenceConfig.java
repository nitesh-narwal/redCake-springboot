package me.niteshh.redcake.config;

import lombok.Getter;

import java.nio.file.Path;

/**
 * Append-only-file (AOF) settings parsed from the command line.
 *
 * <p>Persistence is <b>off</b> unless {@code --aof-file <path>} is given.
 * {@code --aof-fsync} controls the durability/latency trade-off:
 * <ul>
 *   <li>{@code always} - fsync after every write command: no acknowledged write
 *       is lost on crash, slowest.</li>
 *   <li>{@code everysec} (default) - fsync once per second: lose at most ~1 s
 *       of writes, fast.</li>
 *   <li>{@code no} - leave flushing to the OS: fastest, loses whatever the OS
 *       had not written yet.</li>
 * </ul>
 */
@Getter
public final class PersistenceConfig {

    /** When the AOF is forced to stable storage. */
    public enum Fsync { ALWAYS, EVERYSEC, NO }

    private final Path file;
    private final Fsync fsync;

    public PersistenceConfig(Path file, Fsync fsync) {
        this.file = file;
        this.fsync = fsync == null ? Fsync.EVERYSEC : fsync;
    }

    /** @return a config with persistence turned off */
    public static PersistenceConfig disabled() {
        return new PersistenceConfig(null, Fsync.EVERYSEC);
    }

    public boolean isEnabled() {
        return file != null;
    }
}
