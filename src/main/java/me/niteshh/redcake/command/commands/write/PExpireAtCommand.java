package me.niteshh.redcake.command.commands.write;

import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

/** {@code PEXPIREAT key unix-milliseconds [NX|XX|GT|LT]}. See {@link ExpireCommandBase}. */
@Component
public class PExpireAtCommand extends ExpireCommandBase {
    public PExpireAtCommand(KeyValueStore store) {
        super(store, "PEXPIREAT", 1L, false);
    }
}
