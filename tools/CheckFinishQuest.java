import emu.grasscutter.command.Command;
import emu.grasscutter.command.commands.FinishQuestCommand;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import java.util.*;
import javassist.ClassPool;
import javassist.expr.*;

/** Selection and completion delegation tests with fake quest hooks; no live player data. */
public final class CheckFinishQuest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void refuses(Runnable call) { try { call.run(); throw new AssertionError("Unsafe operation accepted"); } catch (IllegalArgumentException | IllegalStateException expected) {} }
    static class Parent extends GameMainQuest {
        final int id; boolean done; int rewardCalls; final List<GameQuest> steps = new ArrayList<>();
        Parent(int id) { this.id=id; }
        public int getParentQuestId() { return id; }
        public boolean isFinished() { return done; }
        public ParentQuestState getState() { return done ? ParentQuestState.PARENT_QUEST_STATE_FINISHED : ParentQuestState.PARENT_QUEST_STATE_NONE; }
        public Collection<GameQuest> getActiveQuests() { return steps.stream().filter(q -> q.getState()==QuestState.QUEST_STATE_UNFINISHED).toList(); }
        public void finish() { if (!done) { done=true; rewardCalls++; } }
    }
    static class Step extends GameQuest {
        final int order; final List<Integer> calls; Runnable callback=()->{}; boolean cancel;
        Step(int order, List<Integer> calls) { this.order=order; this.calls=calls; state=QuestState.QUEST_STATE_UNFINISHED; }
        public QuestData getQuestData() { return new QuestData() { public int getOrder() { return order; } }; }
        public void finish() { if (!cancel) { state=QuestState.QUEST_STATE_FINISHED; calls.add(order); callback.run(); } }
    }
    public static void main(String[] args) throws Exception {
        var calls = new ArrayList<Integer>();
        var a = new Parent(356); a.steps.add(new Step(3,calls)); a.steps.add(new Step(2,calls));
        var dormant = new Parent(500); var finished = new Parent(354); finished.done=true; finished.steps.add(new Step(1,calls));
        check(CurrentQuestCompletion.select(List.of(dormant,a,finished),null)==a);
        var b = new Parent(357); b.steps.add(new Step(1,new ArrayList<>()));
        refuses(()->CurrentQuestCompletion.select(List.of(a,b),null));
        check(CurrentQuestCompletion.select(List.of(a,b),357)==b);
        refuses(()->CurrentQuestCompletion.select(List.of(a,b),999));
        refuses(()->CurrentQuestCompletion.select(List.of(dormant,finished),null));
        check(CurrentQuestCompletion.complete(a)==2 && calls.equals(List.of(2,3)) && a.rewardCalls==0 && !a.done);
        check(!b.done && b.getActiveQuests().size()==1);
        refuses(()->CurrentQuestCompletion.complete(a)); check(a.rewardCalls==0);
        var auto = new Parent(700); var ending = new Step(1,new ArrayList<>()); ending.callback=auto::finish; auto.steps.add(ending);
        CurrentQuestCompletion.complete(auto); check(auto.rewardCalls==1);
        refuses(()->CurrentQuestCompletion.complete(auto)); check(auto.rewardCalls==1);
        var chain = new Parent(358); var first = new Step(2,new ArrayList<>());
        var last = new Step(3,new ArrayList<>()); last.callback=chain::finish;
        first.callback=()->chain.steps.add(last); chain.steps.add(first);
        check(CurrentQuestCompletion.complete(chain)==1 && !chain.done && chain.rewardCalls==0);
        check(last.getState()==QuestState.QUEST_STATE_UNFINISHED);
        check(CurrentQuestCompletion.complete(chain)==1 && chain.done && chain.rewardCalls==1);
        var cancelled = new Parent(800); var denied = new Step(1,new ArrayList<>()); denied.cancel=true; cancelled.steps.add(denied);
        refuses(()->CurrentQuestCompletion.complete(cancelled)); check(!cancelled.done && cancelled.rewardCalls==0);
        var annotation=FinishQuestCommand.class.getAnnotation(Command.class);
        check(annotation.label().equals("finishquest") && annotation.permission().equals("player.quest") && annotation.permissionTargeted().equals("player.quest.others"));
        System.out.println("PASS: safe selection, ordered active steps, newly unlocked same-parent steps preserved, no forced parent reward, natural final-step completion and refusal safety.");
        if (args.length > 1 && args[1].equals("--logic-only")) return;
        var pool = new ClassPool(false); pool.appendSystemPath(); pool.appendClassPath(args[0]);
        var found = new boolean[]{false};
        pool.get("emu.grasscutter.command.CommandMap").getDeclaredMethod("scan").instrument(new ExprEditor() {
            public void edit(NewExpr expression) { if (expression.getClassName().equals(FinishQuestCommand.class.getName())) found[0]=true; }
        });
        check(found[0]);
        System.out.println("PASS: deployed registry explicitly registers finishquest.");
    }
}
