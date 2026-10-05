package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.pubsub.PubSubBroker;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code PUBLISH channel message} - delivers to all subscribers of the
 * channel (and matching patterns) on <em>this</em> node; replies with the
 * number of receivers. Pub/Sub messages are transient, so this is not a write
 * command: it is neither logged nor replicated.
 */
@Component
@RequiredArgsConstructor
public class PublishCommand implements RedCakeCommand {
    private final PubSubBroker broker;

    @Override
    public String name() {
        return "PUBLISH";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return CommandSupport.wrongArity("publish");
        }
        return new IntegerValue(broker.publish(arguments.get(0), arguments.get(1)));
    }
}
