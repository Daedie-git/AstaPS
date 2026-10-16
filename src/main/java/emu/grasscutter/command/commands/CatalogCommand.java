package emu.grasscutter.command.commands;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.game.gacha.CatalogRotation;
import emu.grasscutter.game.player.Player;
import java.util.List;

@Command(label = "catalog", permission = "server.catalog", targetRequirement = Command.TargetRequirement.NONE)
public final class CatalogCommand implements CommandHandler {
    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        if (args.size() > 1) {
            CommandHandler.sendMessage(sender, "Usage: /catalog [next|number]");
            return;
        }
        Integer number = null;
        if (!args.isEmpty() && !args.get(0).equalsIgnoreCase("next")) {
            try {
                number = Integer.parseInt(args.get(0));
            } catch (NumberFormatException e) {
                CommandHandler.sendMessage(sender, "Usage: /catalog [next|number]");
                return;
            }
        }
        try {
            var system = Grasscutter.getGameServer().getGachaSystem();
            CommandHandler.sendMessage(sender, number == null
                    ? CatalogRotation.command(system, !args.isEmpty())
                    : CatalogRotation.command(system, number.intValue()));
        } catch (IllegalArgumentException e) {
            CommandHandler.sendMessage(sender, e.getMessage());
        } catch (Exception e) {
            Grasscutter.getLogger().error("[Catalog] Command failed", e);
            CommandHandler.sendMessage(sender, "Catalog change failed; active banners retained. Check server logs.");
        }
    }
}
