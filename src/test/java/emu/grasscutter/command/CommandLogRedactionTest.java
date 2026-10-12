package emu.grasscutter.command;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.config.Configuration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;

class CommandLogRedactionTest {
    @Command(label = "account", targetRequirement = Command.TargetRequirement.NONE)
    static class CredentialCommand implements CommandHandler {
        @Override public picocli.CommandLine createCommandLine(emu.grasscutter.game.player.Player sender,
                emu.grasscutter.game.player.Player target) { return new picocli.CommandLine(new CredentialArgs()); }
    }
    @picocli.CommandLine.Command(name = "account")
    static class CredentialArgs implements Runnable {
        @picocli.CommandLine.Parameters(index = "0") String secret;
        @Override public void run() { throw new IllegalStateException(secret); }
    }
    @Test void credentialParserAndExecutionFailuresDoNotEchoSecrets() throws Exception {
        var logger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var method = CommandMap.class.getDeclaredMethod("configureCommandLine", picocli.CommandLine.class,
                    emu.grasscutter.game.player.Player.class, CommandHandler.class);
            method.setAccessible(true);
            var cli = (picocli.CommandLine) method.invoke(null, new picocli.CommandLine(new CredentialArgs()), null, new CredentialCommand());
            cli.execute("synthetic-password-991");
            cli.execute("synthetic-password-991", "synthetic-extra-secret");
            assertFalse(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("synthetic-password-991")
                    || e.getFormattedMessage().contains("synthetic-extra-secret") || e.getThrowableProxy() != null));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void invocationLogsNeverContainArgumentsEvenForFailedCommands() {
        var logger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        boolean old = Configuration.SERVER.logCommands;
        try {
            Configuration.SERVER.logCommands = true;
            var commands = new CommandMap(false);
            commands.invoke(null, null, "unknowncommand synthetic-password-991");
            assertFalse(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("synthetic-password-991")));
        } finally { Configuration.SERVER.logCommands = old; logger.detachAppender(appender); appender.stop(); }
    }
}
