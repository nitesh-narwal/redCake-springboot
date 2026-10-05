package me.niteshh.redcake.command.commands.reads;

import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code SHUTDOWN} - stops the server gracefully: the Spring context is
 * closed, which stops accepting clients, lets replica queues drain, flushes
 * and fsyncs the append-only file and ends the process. Administrative.
 *
 * <p>The reply is sent first; the shutdown starts ~100 ms later on another
 * thread so the client sees {@code +OK}.
 */
@Component
public class ShutdownCommand implements RedCakeCommand {

    private final ObjectProvider<ConfigurableApplicationContext> context;

    public ShutdownCommand(ObjectProvider<ConfigurableApplicationContext> context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "SHUTDOWN";
    }

    @Override
    public boolean isAdmin() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        ConfigurableApplicationContext ctx = context.getIfAvailable();
        if (ctx == null) {
            return new ErrorValue("SHUTDOWN is not available in this runtime");
        }
        Thread.ofPlatform().name("RedCake-Shutdown").start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ctx.close();
            System.exit(0);
        });
        return new SimpleString("OK shutting down");
    }
}
