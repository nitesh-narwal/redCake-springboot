package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.pubsub.PubSubBroker;
import me.niteshh.redcake.resp.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code PUBSUB CHANNELS [pattern] | NUMSUB [channel ...] | NUMPAT} - introspection of Pub/Sub state. */
@Component
@RequiredArgsConstructor
public class PubSubCommand implements RedCakeCommand {
    private final PubSubBroker broker;

    @Override
    public String name() {
        return "PUBSUB";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.isEmpty()) {
            return CommandSupport.wrongArity("pubsub");
        }
        return switch (arguments.getFirst().toUpperCase(Locale.ROOT)) {
            case "CHANNELS" -> ArrayValue.ofBulkStrings(
                    broker.channelNames(arguments.size() > 1 ? arguments.get(1) : null));
            case "NUMSUB" -> {
                List<RespValue> out = new ArrayList<>();
                for (String channel : arguments.subList(1, arguments.size())) {
                    out.add(new BulkString(channel));
                    out.add(new IntegerValue(broker.subscriberCount(channel)));
                }
                yield new ArrayValue(out);
            }
            case "NUMPAT" -> new IntegerValue(broker.patternCount());
            default -> new ErrorValue("unknown subcommand '" + arguments.getFirst() + "' for 'pubsub' command");
        };
    }
}
