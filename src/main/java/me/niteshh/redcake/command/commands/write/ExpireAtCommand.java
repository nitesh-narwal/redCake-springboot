package me.niteshh.redcake.command.commands.write;

import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

/** {@code EXPIREAT key unix-seconds [NX|XX|GT|LT]}. See {@link ExpireCommandBase}. */
@Component
public class ExpireAtCommand extends ExpireCommandBase {
    public ExpireAtCommand(KeyValueStore store) {
        super(store, "EXPIREAT", 1000L, false);
    }
}
