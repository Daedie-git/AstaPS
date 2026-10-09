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
