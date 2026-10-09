package emu.grasscutter.game.quest;

import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestState;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Select one active parent quest; never guess between concurrent quests or complete successors. */
public final class CurrentQuestCompletion {
    private CurrentQuestCompletion() {}

    public static List<GameMainQuest> active(Collection<GameMainQuest> quests) {
        return quests.stream()
                .filter(q -> !q.isFinished() && q.getState() != ParentQuestState.PARENT_QUEST_STATE_FINISHED)
                .filter(q -> !q.getActiveQuests().isEmpty())
                .sorted(Comparator.comparingInt(GameMainQuest::getParentQuestId)).toList();
    }

    public static GameMainQuest select(Collection<GameMainQuest> quests, Integer id) {
        var candidates = active(quests);
        if (id != null) return candidates.stream().filter(q -> q.getParentQuestId() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Quest " + id + " is not active."));
        if (candidates.isEmpty()) throw new IllegalArgumentException("No active quest to finish.");
        if (candidates.size() > 1) throw new IllegalArgumentException("Several quests are active: "
                + candidates.stream().map(q -> String.valueOf(q.getParentQuestId())).collect(java.util.stream.Collectors.joining(", "))
                + ". Use /finishquest <quest ID>.");
        return candidates.get(0);
    }

    public static int complete(GameMainQuest quest) {
        if (quest.isFinished() || quest.getState() == ParentQuestState.PARENT_QUEST_STATE_FINISHED)
            throw new IllegalArgumentException("Quest is already finished.");
        // Snapshot this parent's active steps only. New quests started by completion are out of scope.
        var steps = quest.getActiveQuests().stream()
                .sorted(Comparator.comparingInt(q -> q.getQuestData().getOrder())).toList();
        if (steps.isEmpty()) throw new IllegalArgumentException("Quest has no active steps.");
        int completed = 0;
        for (var step : steps) {
            if (step.getState().getValue() != QuestState.QUEST_STATE_UNFINISHED.getValue()) continue;
            step.finish();
            if (step.getState().getValue() != QuestState.QUEST_STATE_FINISHED.getValue())
                throw new IllegalStateException("A quest hook refused completion; stopped without finishing the parent.");
            completed++;
        }
        if (!quest.isFinished() && quest.getState() != ParentQuestState.PARENT_QUEST_STATE_FINISHED) quest.finish();
        if (!quest.isFinished()) throw new IllegalStateException("Parent quest did not finish.");
        return completed;
    }
}
