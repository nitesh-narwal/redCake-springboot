package me.niteshh.redcake.command.commands.write;

import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

/** {@code PEXPIRE key milliseconds [NX|XX|GT|LT]}. See {@link ExpireCommandBase}. */
@Component
public class PExpireCommand extends ExpireCommandBase {
    public PExpireCommand(KeyValueStore store) {
        super(store, "PEXPIRE", 1L, true);
    }
}
