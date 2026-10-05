package me.niteshh.redcake.command.commands.write;

import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

/** {@code PSETEX key milliseconds value}. See {@link SetExpireCommandBase}. */
@Component
public class PSetExCommand extends SetExpireCommandBase {
    public PSetExCommand(KeyValueStore store) {
        super(store, "PSETEX", 1L);
    }
}
