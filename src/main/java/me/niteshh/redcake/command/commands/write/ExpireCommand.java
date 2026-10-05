package me.niteshh.redcake.command.commands.write;

import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

/** {@code EXPIRE key seconds [NX|XX|GT|LT]}. See {@link ExpireCommandBase}. */
@Component
public class ExpireCommand extends ExpireCommandBase {
    public ExpireCommand(KeyValueStore store) {
        super(store, "EXPIRE", 1000L, true);
    }
}
