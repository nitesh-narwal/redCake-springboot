package me.niteshh.redcake.command;

import java.util.List;

/**
 * A bean that contributes several commands at once. Used by the data-type
 * families (hash, list, set, sorted set) whose commands are defined as small
 * lambdas via {@link LambdaCommand} rather than one class each.
 * {@link CommandHandler} registers every provider's commands next to the
 * individually declared {@link RedCakeCommand} beans.
 */
public interface CommandProvider {

    /** @return the commands this provider contributes */
    List<RedCakeCommand> commands();
}
