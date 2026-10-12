package emu.grasscutter.command.commands;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.CurrentQuestCompletion;
import emu.grasscutter.game.quest.QuestManager;
import java.util.List;
import picocli.CommandLine;

@Command(label = "finishquest", permission = "player.quest", permissionTargeted = "player.quest.others")
public final class FinishQuestCommand implements CommandHandler {
    @Override
    public CommandLine createCommandLine(Player sender, Player target) {
        return new CommandLine(new Invocation(sender, target));
    }

    @CommandLine.Command(name = "finishquest", mixinStandardHelpOptions = true,
            description = "Finish the current active quest step through normal quest hooks.")
    private final class Invocation implements Runnable {
        private final Player sender;
        private final Player target;

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "QUEST_ID")
        private Integer questId;

        private Invocation(Player sender, Player target) {
            this.sender = sender;
            this.target = target;
        }

        @Override
        public void run() {
            execute(sender, target, questId == null ? List.of() : List.of(questId.toString()));
        }
    }

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
                CommandHandler.sendMessage(sender, "Finished " + count + " active step(s) of quest "
                        + quest.getParentQuestId() + ". " + (quest.isFinished()
                        ? "Its final step completed the chain; normal rewards and successor startup applied."
                        : "The chain stays open. Newly unlocked steps are not skipped; use /finishquest again if needed."));
            } catch (IllegalArgumentException e) {
                CommandHandler.sendMessage(sender, e.getMessage());
            } catch (Exception e) {
                Grasscutter.getLogger().error("[FinishQuest] Completion failed", e);
                CommandHandler.sendMessage(sender, "Quest completion stopped; check server logs before retrying.");
            }
        });
    }
}
