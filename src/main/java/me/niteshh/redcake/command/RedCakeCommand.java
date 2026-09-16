package me.niteshh.redcake.command;
import me.niteshh.redcake.resp.RespValue;
import java.util.List;

public interface RedCakeCommand {

    String name();

    RespValue execute(List<String> arguments);
}
