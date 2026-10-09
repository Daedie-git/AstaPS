package emu.grasscutter.command.commands;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.CurrentQuestCompletion;
import emu.grasscutter.game.quest.QuestManager;
import java.util.List;

@Command(label = "finishquest", usage = {"[<questId>]"}, permission = "player.quest", permissionTargeted = "player.quest.others")
public final class FinishQuestCommand implements CommandHandler {
    @Override
    public void execute(Player sender, Player target, List<String> args) {
        if (args.size() > 1) {
            CommandHandler.sendMessage(sender, "Usage: /finishquest [quest ID]");
            return;
        }
        final Integer id;
        try {
            id = args.isEmpty() ? null : Integer.valueOf(args.get(0));
            if (id != null && id <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            CommandHandler.sendMessage(sender, "Use a positive quest ID, or /finishquest alone.");
            return;
        }
        // Use the quest event executor so completion cannot race another quest event.
        QuestManager.eventExecutor.execute(() -> {
            try {
                if (target == null || !target.isOnline()) {
                    CommandHandler.sendMessage(sender, "The character must be online.");
                    return;
                }
                var quest = CurrentQuestCompletion.select(target.getQuestManager().getMainQuests().values(), id);
                if (!GameData.getMainQuestDataMap().containsKey(quest.getParentQuestId()))
                    throw new IllegalArgumentException("Quest resource data is missing; nothing completed.");
                int count = CurrentQuestCompletion.complete(quest);
                CommandHandler.sendMessage(sender, "Finished quest " + quest.getParentQuestId() + " (" + count
                        + " active steps). Normal rewards and successor startup applied.");
            } catch (IllegalArgumentException e) {
                CommandHandler.sendMessage(sender, e.getMessage());
            } catch (Exception e) {
                Grasscutter.getLogger().error("[FinishQuest] Completion failed", e);
                CommandHandler.sendMessage(sender, "Quest completion stopped; check server logs before retrying.");
            }
        });
    }
}
