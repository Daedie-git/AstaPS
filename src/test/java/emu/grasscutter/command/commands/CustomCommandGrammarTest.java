package emu.grasscutter.command.commands;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.command.Command;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CustomCommandGrammarTest {
    @Test
    void finishQuestHasIndependentParsersAndAnOptionalIntegerId() {
        var handler = new FinishQuestCommand();
        var first = handler.createCommandLine(null, null);
        var second = handler.createCommandLine(null, null);
        assertNotSame(first, second);
        assertEquals("finishquest", first.getCommandName());
        assertDoesNotThrow(() -> first.parseArgs());
        assertDoesNotThrow(() -> second.parseArgs("358"));
        assertThrows(CommandLine.ParameterException.class,
                () -> handler.createCommandLine(null, null).parseArgs("not-a-quest"));
        assertThrows(CommandLine.ParameterException.class,
                () -> handler.createCommandLine(null, null).parseArgs("358", "359"));
        var permissions = FinishQuestCommand.class.getAnnotation(Command.class);
        assertEquals("player.quest", permissions.permission());
        assertEquals("player.quest.others", permissions.permissionTargeted());
        assertEquals(Command.TargetRequirement.ONLINE, permissions.targetRequirement());
    }

    @Test
    void catalogRetainsConsoleGrammarAndPermission() {
        var handler = new CatalogCommand();
        var parser = handler.createCommandLine(null, null);
        assertEquals("catalog", parser.getCommandName());
        assertDoesNotThrow(() -> parser.parseArgs());
        assertDoesNotThrow(() -> handler.createCommandLine(null, null).parseArgs("next"));
        assertThrows(CommandLine.ParameterException.class,
                () -> handler.createCommandLine(null, null).parseArgs("next", "next"));
        var permissions = CatalogCommand.class.getAnnotation(Command.class);
        assertEquals("server.catalog", permissions.permission());
        assertEquals(Command.TargetRequirement.NONE, permissions.targetRequirement());
    }
}
