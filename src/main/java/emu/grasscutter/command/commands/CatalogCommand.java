package emu.grasscutter.command.commands;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.game.gacha.CatalogRotation;
import emu.grasscutter.game.player.Player;
import java.util.List;
import picocli.CommandLine;

@Command(label = "catalog", permission = "server.catalog", targetRequirement = Command.TargetRequirement.NONE)
public final class CatalogCommand implements CommandHandler {
    @Override
    public CommandLine createCommandLine(Player sender, Player targetPlayer) {
        return new CommandLine(new Invocation(sender, targetPlayer));
    }

    @CommandLine.Command(name = "catalog", mixinStandardHelpOptions = true,
            description = "Show the banner catalog or advance it to the next cycle.")
    private final class Invocation implements Runnable {
        private final Player sender;
        private final Player target;

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "next")
        private String action;

        private Invocation(Player sender, Player target) {
            this.sender = sender;
            this.target = target;
        }

        @Override
        public void run() {
            execute(sender, target, action == null ? List.of() : List.of(action));
        }
    }

    public void execute(Player sender, Player targetPlayer, List<String> args) {
        if (args.size() > 1 || (!args.isEmpty() && !args.get(0).equalsIgnoreCase("next"))) {
            CommandHandler.sendMessage(sender, "Usage: /catalog [next]");
            return;
        }
        try {
            CommandHandler.sendMessage(sender, CatalogRotation.command(
                    Grasscutter.getGameServer().getGachaSystem(), !args.isEmpty()));
        } catch (Exception e) {
            Grasscutter.getLogger().error("[Catalog] Command failed", e);
            CommandHandler.sendMessage(sender, "Catalog change failed; active banners retained. Check server logs.");
        }
    }
}
