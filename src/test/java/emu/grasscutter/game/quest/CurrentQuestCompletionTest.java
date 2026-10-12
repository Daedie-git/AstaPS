package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestState;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Simulates same-parent steps unlocked by finish hooks, without player records or rewards. */
final class CurrentQuestCompletionTest {
    @Test
    void newlyStartedRequiredStepKeepsItsParentOpen() {
        var parent = new Parent(358);
        var finalStep = new Step(3, parent);
        finalStep.callback = parent::finish;
        var entry = new Step(2, parent);
        entry.callback = () -> parent.steps.add(finalStep);
        parent.steps.add(entry);

        assertEquals(1, CurrentQuestCompletion.complete(parent));
        assertFalse(parent.done, "Finishing 35801 must not close the parent before 35802");
        assertEquals(0, parent.rewardCalls);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, finalStep.getState());

        assertEquals(1, CurrentQuestCompletion.complete(parent));
        assertTrue(parent.done);
        assertEquals(1, parent.rewardCalls);
        assertThrows(IllegalArgumentException.class, () -> CurrentQuestCompletion.complete(parent));
        assertEquals(1, parent.rewardCalls);
    }

    @Test
    void queuedUnlockIsNotMistakenForAnExhaustedParent() {
        var parent = new Parent(358);
        var events = new ArrayDeque<Runnable>();
        var finalStep = new Step(3, parent);
        finalStep.callback = parent::finish;
        var entry = new Step(2, parent);
        entry.callback = () -> events.add(() -> parent.steps.add(finalStep));
        parent.steps.add(entry);

        CurrentQuestCompletion.complete(parent);
        assertTrue(parent.getActiveQuests().isEmpty());
        assertFalse(parent.done, "Queued quest events have not started the final step yet");
        events.remove().run();
        assertEquals(List.of(finalStep), List.copyOf(parent.getActiveQuests()));
        assertFalse(parent.done);
    }

    @Test
    void noFinalHookNeverForcesAParentReward() {
        var parent = new Parent(800);
        parent.steps.add(new Step(1, parent));
        assertEquals(1, CurrentQuestCompletion.complete(parent));
        assertFalse(parent.done);
        assertEquals(0, parent.rewardCalls);
    }

    @Test
    void naturalParentCompletionStopsRemainingOptionalStepsAndSuccessors() {
        var parent = new Parent(358);
        var successor = new Parent(306);
        var next = new Step(1, successor);
        var ending = new Step(1, parent);
        ending.callback = () -> { parent.finish(); successor.steps.add(next); };
        var optional = new Step(2, parent);
        parent.steps.addAll(List.of(optional, ending));
        assertEquals(1, CurrentQuestCompletion.complete(parent));
        assertEquals(List.of(1), parent.calls);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, optional.getState());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, next.getState());
        assertEquals(1, parent.rewardCalls);
    }

    @Test
    void activeStepsAreFinishedInOrderWithoutTouchingOtherParents() {
        var parent = new Parent(358);
        parent.steps.addAll(List.of(new Step(3,parent),new Step(2,parent)));
        var unrelated = new Parent(306);
        unrelated.steps.add(new Step(1,unrelated));
        assertEquals(parent, CurrentQuestCompletion.select(List.of(parent,unrelated),358));
        assertEquals(2, CurrentQuestCompletion.complete(parent));
        assertEquals(List.of(2,3), parent.calls);
        assertTrue(unrelated.calls.isEmpty());
        assertFalse(parent.done);
    }

    @Test
    void singletonSelectionAndAmbiguousSelectionAreSafe() {
        var parent = new Parent(358); parent.steps.add(new Step(1,parent));
        var dormant = new Parent(306);
        var finished = new Parent(354); finished.done=true; finished.steps.add(new Step(1,finished));
        assertEquals(parent, CurrentQuestCompletion.select(List.of(parent,dormant,finished),null));
        dormant.steps.add(new Step(1,dormant));
        assertThrows(IllegalArgumentException.class, () -> CurrentQuestCompletion.select(List.of(parent,dormant),null));
        assertThrows(IllegalArgumentException.class, () -> CurrentQuestCompletion.select(List.of(parent),999));
        assertThrows(IllegalArgumentException.class, () -> CurrentQuestCompletion.select(List.of(finished),null));
    }

    @Test
    void cancelledHookStopsWithoutClosingOrRewardingTheParent() {
        var parent = new Parent(358);
        var denied = new Step(1,parent); denied.cancel=true;
        parent.steps.add(denied);
        assertThrows(IllegalStateException.class, () -> CurrentQuestCompletion.complete(parent));
        assertFalse(parent.done);
        assertEquals(0, parent.rewardCalls);
        assertTrue(parent.calls.isEmpty());
    }

    @Test
    void inactiveParentIsNotForceFinished() {
        var parent = new Parent(358);
        assertThrows(IllegalArgumentException.class, () -> CurrentQuestCompletion.complete(parent));
        assertEquals(0, parent.rewardCalls);
    }

    private static final class Parent extends GameMainQuest {
        final int id;
        boolean done;
        int rewardCalls;
        final List<GameQuest> steps = new ArrayList<>();
        final List<Integer> calls = new ArrayList<>();
        Parent(int id) { this.id=id; }
        @Override public int getParentQuestId() { return id; }
        @Override public boolean isFinished() { return done; }
        @Override public ParentQuestState getState() {
            return done ? ParentQuestState.PARENT_QUEST_STATE_FINISHED : ParentQuestState.PARENT_QUEST_STATE_NONE;
        }
        @Override public Collection<GameQuest> getActiveQuests() {
            return steps.stream().filter(q -> q.getState()==QuestState.QUEST_STATE_UNFINISHED).toList();
        }
        @Override public void finish() { if (!done) { done=true; rewardCalls++; } }
    }

    private static final class Step extends GameQuest {
        final int order;
        final Parent parent;
        Runnable callback=()->{};
        boolean cancel;
        Step(int order,Parent parent) { this.order=order; this.parent=parent; state=QuestState.QUEST_STATE_UNFINISHED; }
        @Override public QuestData getQuestData() { return new QuestData() { @Override public int getOrder() { return order; } }; }
        @Override public void finish() {
            if (cancel) return;
            state=QuestState.QUEST_STATE_FINISHED;
            parent.calls.add(order);
            callback.run();
        }
    }
}
