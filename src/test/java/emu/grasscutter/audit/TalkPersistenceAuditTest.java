package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.talk.TalkManager;
import emu.grasscutter.game.talk.exec.ExecSetQuestVar;
import emu.grasscutter.data.excels.TalkConfigData.TalkExecParam;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import org.junit.jupiter.api.Test;

class TalkPersistenceAuditTest {
    @Test void dialogueOnlyHistoryAndCrossParentVariablesSaveTheirOwnDocuments() {
        var player = allocate(Player.class);
        var manager = allocate(QuestManager.class);
        set(player, "questManager", manager);
        var parents = new Int2ObjectOpenHashMap<GameMainQuest>();
        set(manager, "mainQuests", parents);
        var history = new SavedQuest();
        var variable = new SavedQuest();
        set(history, "talks", new HashMap<>());
        set(variable, "questVars", new int[5]);
        parents.put(1, history);
        parents.put(2, variable);
        new TalkManager(player).saveTalkToQuest(101, 1);
        var exec = new TalkExecParam();
        exec.setParam(new String[]{"0", "42", "2"});
        new ExecSetQuestVar().execute(player, null, exec);
        assertEquals(Set.of(101), history.savedTalks);
        assertEquals(42, variable.savedVars[0]);
        assertEquals(1, history.saves);
        assertEquals(1, variable.saves);
        exec.setParam(new String[]{"0", "5", "2"});
        new emu.grasscutter.game.talk.exec.ExecIncQuestVar().execute(player, null, exec);
        assertEquals(47, variable.savedVars[0]);
        new emu.grasscutter.game.talk.exec.ExecDecQuestVar().execute(player, null, exec);
        assertEquals(42, variable.savedVars[0]);
        assertEquals(3, variable.saves);
    }
    public static class SavedQuest extends GameMainQuest {
        Set<Integer> savedTalks;
        int[] savedVars;
        int saves;
        @Override public void triggerQuestVarAction(int index, int value) {}
        @Override public void save() {
            saves++;
            if (getTalks() != null) savedTalks = new HashSet<>(getTalks().keySet());
            if (getQuestVars() != null) savedVars = getQuestVars().clone();
        }
    }
}
