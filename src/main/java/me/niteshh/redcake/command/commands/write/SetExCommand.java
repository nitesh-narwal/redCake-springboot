package me.niteshh.redcake.command.commands.write;

import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

/** {@code SETEX key seconds value}. See {@link SetExpireCommandBase}. */
@Component
public class SetExCommand extends SetExpireCommandBase {
    public SetExCommand(KeyValueStore store) {
        super(store, "SETEX", 1000L);
    }
}
