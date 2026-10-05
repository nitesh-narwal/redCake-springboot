package me.niteshh.redcake.persistence;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.RespCommandCodec;
import me.niteshh.redcake.resp.ProtocolException;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only file (AOF): a durable log of every write command.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li><b>Startup</b> ({@link #open}): if the file exists, every logged
 *       command is replayed through {@link CommandHandler}, rebuilding the
 *       dataset. A torn last record (crash mid-write) is detected and the file
 *       is truncated back to the last complete command.</li>
 *   <li><b>Runtime</b> ({@link #append}): after a write command succeeds,
 *       {@code ClientHandler} appends the <em>normalized</em> command (absolute
 *       expiry times) in RESP format - the same bytes that are replicated.</li>
 *   <li><b>Durability</b>: governed by {@link PersistenceConfig.Fsync}.</li>
 *   <li><b>Compaction</b> ({@link #rewrite}): replaces the ever-growing log by
 *       a minimal one ({@code SET} per live key) written to a temp file and
 *       atomically renamed over the old one.</li>
 * </ol>
 *
 * <p>Locking: {@link #append} and {@link #rewrite} are called while the caller
 * holds the primary write lock, which guarantees the file order equals the
 * execution order. {@link #ioLock} additionally protects the stream from the
 * once-per-second fsync thread.
 *
 * <p>Only a primary logs. Replicas rebuild state from their primary, so the
 * AOF is ignored (with a warning) when running with {@code --replicaof}.
 */
@Slf4j
@Component
public class AppendOnlyLog {

    private final PersistenceConfig config;
    private final CommandHandler commandHandler;
    private final KeyValueStore store;
    private final ReplicationConfig replicationConfig;

    private final ReentrantLock ioLock = new ReentrantLock();
    private FileOutputStream fileStream;
    private BufferedOutputStream out;

    private volatile boolean active;
    private volatile boolean healthy = true;

    @Autowired
    public AppendOnlyLog(
            PersistenceConfig config,
            CommandHandler commandHandler,
            KeyValueStore store,
            ReplicationConfig replicationConfig
    ) {
        this.config = config;
        this.commandHandler = commandHandler;
        this.store = store;
        this.replicationConfig = replicationConfig;
    }

    /** @return an inert instance for components/tests that run without persistence */
    public static AppendOnlyLog disabled() {
        return new AppendOnlyLog(PersistenceConfig.disabled(), null, null, null);
    }

    /** Replays an existing log, then opens it for appending. Runs before the server accepts clients. */
    @PostConstruct
    public void open() throws IOException {
        if (!config.isEnabled()) {
            return;
        }
        if (replicationConfig != null && replicationConfig.getRole() == ReplicationRole.REPLICA) {
            log.warn("AOF is ignored on replicas; use the primary's AOF for recovery");
            return;
        }

        Path file = config.getFile();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (Files.exists(file)) {
            replay(file);
        }

        openForAppend(file);
        active = true;

        startFsyncThread();
        log.info("AOF enabled: file={} fsync={}", file, config.getFsync());
    }

    /** @return {@code true} if commands are being logged */
    public boolean isActive() {
        return active;
    }

    /** @return {@code false} after a write failure; the server then refuses writes */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Appends one (already normalized) write command. Caller holds the write lock.
     *
     * @throws IOException if the disk write failed; the log is then marked unhealthy
     */
    public void append(List<String> command) throws IOException {
        if (!active) {
            return;
        }
        byte[] bytes = RespCommandCodec.encode(command);
        ioLock.lock();
        try {
            out.write(bytes);
            if (config.getFsync() == PersistenceConfig.Fsync.ALWAYS) {
                out.flush();
                fileStream.getFD().sync();
            }
        } catch (IOException e) {
            healthy = false;
            log.error("AOF write failed - refusing further writes", e);
            throw e;
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Compacts the log: writes one command set per live key to a temp file,
     * fsyncs it and atomically renames it over the AOF. Caller holds the
     * write lock so the snapshot matches the log position exactly.
     *
     * @return number of keys written
     */
    public long rewrite() throws IOException {
        if (!active) {
            throw new IllegalStateException("AOF is not enabled");
        }
        long keys;
        ioLock.lock();
        try {
            out.flush();
            out.close();
            keys = replaceFileWithSnapshot();
            openForAppend(config.getFile());
            healthy = true;
        } finally {
            ioLock.unlock();
        }
        log.info("AOF rewritten: {} keys", keys);
        return keys;
    }

    /**
     * Starts logging on a node that was a replica (and therefore ignored the
     * AOF) and has just been promoted: the current dataset becomes the new
     * log base. Caller holds the write lock.
     */
    public void activateFromSnapshot() throws IOException {
        if (!config.isEnabled() || active) {
            return;
        }
        ioLock.lock();
        try {
            Path parent = config.getFile().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            long keys = replaceFileWithSnapshot();
            openForAppend(config.getFile());
            healthy = true;
            active = true;
            startFsyncThread();
            log.info("AOF activated after promotion: {} keys", keys);
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Stops logging (flush + close) without deleting the file. Used when a
     * primary becomes a replica: from then on its state comes from the new primary.
     */
    public void deactivate() {
        if (!active) {
            return;
        }
        active = false; // also ends the fsync thread
        ioLock.lock();
        try {
            out.flush();
            fileStream.getFD().sync();
            out.close();
        } catch (IOException e) {
            log.error("Failed to flush AOF while deactivating", e);
        } finally {
            ioLock.unlock();
        }
    }

    /** Flushes and closes the file on shutdown. */
    @PreDestroy
    public void close() {
        deactivate();
    }

    // ------------------------------------------------------------ internals

    /**
     * Writes the current dataset to {@code <file>.rewrite}, fsyncs it and
     * atomically renames it over the AOF. Caller holds {@link #ioLock}.
     *
     * @return number of keys written
     */
    private long replaceFileWithSnapshot() throws IOException {
        Path file = config.getFile();
        Path temp = file.resolveSibling(file.getFileName() + ".rewrite");
        long[] count = {0};

        try (FileOutputStream tempStream = new FileOutputStream(temp.toFile());
             BufferedOutputStream tempOut = new BufferedOutputStream(tempStream, 64 * 1024)) {
            IOException[] failure = {null};
            store.forEachSnapshot(entry -> {
                if (failure[0] != null) {
                    return;
                }
                try {
                    for (List<String> command : entry.toCommands()) {
                        tempOut.write(RespCommandCodec.encode(command));
                    }
                    count[0]++;
                } catch (IOException e) {
                    failure[0] = e;
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            tempOut.flush();
            tempStream.getFD().sync();
        }

        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return count[0];
    }

    private void startFsyncThread() {
        if (config.getFsync() == PersistenceConfig.Fsync.EVERYSEC) {
            Thread.ofPlatform()
                    .daemon()
                    .name("RedCake-AofFsync")
                    .start(this::fsyncLoop);
        }
    }

    private void openForAppend(Path file) throws IOException {
        fileStream = new FileOutputStream(file.toFile(), true);
        out = new BufferedOutputStream(fileStream, 64 * 1024);
    }

    /**
     * Executes every command in the file. Stops at the first unreadable
     * record and truncates the file there, so the next append starts at a
     * clean record boundary instead of after garbage.
     */
    private void replay(Path file) throws IOException {
        RespParser parser = new RespParser();
        long goodBytes = 0;
        long commands = 0;
        boolean torn = false;

        try (BufferedInputStream in = new BufferedInputStream(
                new FileInputStream(file.toFile()), 64 * 1024)) {
            while (true) {
                List<String> command;
                try {
                    command = parser.parseCommand(in);
                } catch (ProtocolException e) {
                    torn = true;
                    break;
                }
                if (command == null) {
                    break;
                }
                commandHandler.handle(command);
                // Canonical encoding == bytes written by append(), so lengths agree.
                goodBytes += RespCommandCodec.encode(command).length;
                commands++;
            }
        }

        if (torn) {
            log.warn("AOF has a damaged tail; truncating to {} bytes", goodBytes);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(goodBytes);
            }
        }
        log.info("AOF replayed: {} commands", commands);
    }

    /** Once-per-second flush + fsync for {@code everysec} mode. */
    private void fsyncLoop() {
        while (active) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            ioLock.lock();
            try {
                if (active) {
                    out.flush();
                    fileStream.getFD().sync();
                }
            } catch (IOException e) {
                healthy = false;
                log.error("AOF fsync failed", e);
            } finally {
                ioLock.unlock();
            }
        }
    }
}
