package me.niteshh.redcake.support;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.command.commands.reads.*;
import me.niteshh.redcake.command.commands.types.*;
import me.niteshh.redcake.command.commands.write.*;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.store.ExpirationManager;
import me.niteshh.redcake.store.InMemoryKeyValueStore;
import me.niteshh.redcake.store.KeyValueStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Shared helpers for tests: wiring without Spring and a tiny polling "await". */
public final class TestSupport {

    private TestSupport() {
    }

    /** A store with its own running expiration worker. Call {@link #stop} when done. */
    public record Stack(ExpirationManager expirationManager, InMemoryKeyValueStore store) {
        public void stop() {
            expirationManager.stop();
        }
    }

    public static Stack newStack() {
        ExpirationManager manager = new ExpirationManager();
        InMemoryKeyValueStore store = new InMemoryKeyValueStore(manager);
        store.initializeExpirationHandler();
        manager.start();
        return new Stack(manager, store);
    }

    /** Every stateless command, wired against {@code store} (everything except those needing server context). */
    public static List<RedCakeCommand> baseCommands(KeyValueStore store) {
        return new ArrayList<>(List.of(
                new PingCommand(), new EchoCommand(), new GetCommand(store), new MGetCommand(store),
                new ExistsCommand(store), new TtlCommand(store), new PttlCommand(store),
                new StrlenCommand(store), new TypeCommand(store), new DbSizeCommand(store),
                new KeysCommand(store), new TimeCommand(), new SelectCommand(),
                new HelloCommand(), new RandomKeyCommand(store),
                new SetCommand(store), new SetNxCommand(store), new SetExCommand(store),
                new PSetExCommand(store), new GetSetCommand(store), new GetDelCommand(store),
                new MSetCommand(store), new AppendCommand(store), new DelCommand(store),
                new UnlinkCommand(store), new RenameCommand(store), new PersistCommand(store),
                new FlushAllCommand(store), new FlushDbCommand(store),
                new ExpireCommand(store), new PExpireCommand(store),
                new ExpireAtCommand(store), new PExpireAtCommand(store),
                new IncrCommand(store), new DecrCommand(store),
                new IncrByCommand(store), new DecrByCommand(store)
        ));
    }

    /** The data-type command families (hash, list, set, zset, extra string commands). */
    public static List<CommandProvider> providers(KeyValueStore store) {
        return List.of(
                new HashCommands(store), new ListCommands(store),
                new SetCommands(store), new SortedSetCommands(store),
                new StringExtraCommands(store));
    }

    /** Every command RedCake ships that needs no server context, with a standalone INFO. */
    public static CommandHandler allCommands(KeyValueStore store, ReplicationRole role) {
        List<RedCakeCommand> commands = baseCommands(store);
        commands.add(new InfoCommand(store, new ReplicationConfig(role, "127.0.0.1", 6379)));
        return new CommandHandler(commands, providers(store));
    }

    /** Polls {@code condition} every 10 ms until it is true or {@code timeoutMillis} passes. */
    public static boolean await(long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
