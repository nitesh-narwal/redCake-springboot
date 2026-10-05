package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.write;

/**
 * Smaller string/key commands: {@code GETRANGE MSETNX EXPIRETIME PEXPIRETIME}.
 */
@Component
@RequiredArgsConstructor
public class StringExtraCommands implements CommandProvider {

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                read("GETRANGE", 3, 3, a -> {
                    String value = store.get(a.get(0));
                    if (value == null) {
                        return new BulkString("");
                    }
                    int[] r = CommandSupport.range(
                            CommandSupport.parseLong(a.get(1)), CommandSupport.parseLong(a.get(2)), value.length());
                    return new BulkString(r == null ? "" : value.substring(r[0], r[1] + 1));
                }),
                // All writers are serialized by the primary write lock, so "check, then set all" is atomic.
                write("MSETNX", 2, -1, a -> {
                    if (a.size() % 2 != 0) {
                        return CommandSupport.wrongArity("msetnx");
                    }
                    for (int i = 0; i < a.size(); i += 2) {
                        if (store.exists(a.get(i))) {
                            return new IntegerValue(0);
                        }
                    }
                    for (int i = 0; i < a.size(); i += 2) {
                        store.set(a.get(i), a.get(i + 1));
                    }
                    return new IntegerValue(1);
                }),
                read("EXPIRETIME", 1, 1, a -> expireTime(a.get(0), 1000)),
                read("PEXPIRETIME", 1, 1, a -> expireTime(a.get(0), 1))
        );
    }

    /** Absolute expiry as unix time ({@code unit} = ms per result unit); -1 no TTL, -2 no key. */
    private RespValue expireTime(String key, long unit) {
        long remaining = store.pttl(key);
        return new IntegerValue(remaining < 0 ? remaining : (System.currentTimeMillis() + remaining) / unit);
    }
}
