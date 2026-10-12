package emu.grasscutter.server.packet.recv;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.RewardData;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.InvestigationHandbookHelper;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.PlayerProgress;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.TakeInvestigationRewardRspOuterClass.TakeInvestigationRewardRsp;
import emu.grasscutter.server.game.GameSession;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

/** Actual chapter claim/parser and reward delivery, isolated from account storage and resources. */
@ExtendWith(ServerResourceFixture.class)
final class InvestigationChapterRewardClaimTest {
    private static final int CHAPTER = 10001, TARGET = 60001, REWARD = 991002;
    private RecordingPlayer player;
    private CaptureSession session;
    private Map<Integer, Object> chapters;
    private Map<Integer, List<Object>> targets;
    private Map<Integer, Object> targetsById;
    private Object oldTarget;
    private Object oldChapter;
    private List<Object> oldTargets;
    private boolean oldLoaded;
    private RewardData oldReward;
    private emu.grasscutter.data.excels.ItemData oldItem;

    private static Field helperField(String name) throws Exception {
        var field = InvestigationHandbookHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        chapters = (Map<Integer, Object>) helperField("CHAPTERS_BY_ID").get(null);
        targets = (Map<Integer, List<Object>>) helperField("TARGETS_BY_CHAPTER").get(null);
        oldChapter = chapters.get(CHAPTER);
        oldTargets = targets.get(CHAPTER);
        targetsById = (Map<Integer, Object>) helperField("TARGETS_BY_ID").get(null);
        oldTarget = targetsById.get(TARGET);
        oldLoaded = helperField("loaded").getBoolean(null);
        helperField("loaded").setBoolean(null, true);
        setChapter(1);
        var targetClass = Class.forName(InvestigationHandbookHelper.class.getName() + "$TargetDef");
        var target = new Gson().fromJson(
                "{\"id\":60001,\"investigationId\":10001,\"progress\":1,\"rewardId\":991002}", targetClass);
        targets.put(CHAPTER, List.of(target));
        targetsById.put(TARGET, target);
        oldItem = GameData.getItemDataMap().put(201, new Gson().fromJson(
                "{\"id\":201,\"itemType\":\"ITEM_VIRTUAL\"}", emu.grasscutter.data.excels.ItemData.class));
        oldReward = GameData.getRewardDataMap().get(REWARD);
        var reward = new RewardData();
        reward.rewardId = REWARD;
        reward.rewardItemList = List.of(new ItemParamData(201, 20));
        GameData.getRewardDataMap().put(REWARD, reward);
        player = allocate(RecordingPlayer.class);
        player.progress = new PlayerProgress();
        player.inventory = allocate(RecordingInventory.class);
        player.inventory.grants = new ArrayList<>();
        session = new CaptureSession();
        session.player = player;
        player.setSession(session);
    }

    private void setChapter(int unlockLevel) throws Exception {
        var type = Class.forName(InvestigationHandbookHelper.class.getName() + "$ChapterDef");
        chapters.put(CHAPTER, new Gson().fromJson("{\"id\":10001,\"rewardId\":991002,\"unlockLevel\":"
                + unlockLevel + "}", type));
    }

    @AfterEach
    void restore() throws Exception {
        if (oldChapter == null) chapters.remove(CHAPTER); else chapters.put(CHAPTER, oldChapter);
        if (oldTargets == null) targets.remove(CHAPTER); else targets.put(CHAPTER, oldTargets);
        if (oldTarget == null) targetsById.remove(TARGET); else targetsById.put(TARGET, oldTarget);
        helperField("loaded").setBoolean(null, oldLoaded);
        if (oldItem == null) GameData.getItemDataMap().remove(201);
        else GameData.getItemDataMap().put(201, oldItem);
        if (oldReward == null) GameData.getRewardDataMap().remove(REWARD);
        else GameData.getRewardDataMap().put(REWARD, oldReward);
    }

    @Test
    void completedChapterPaysOnceAndRetryIsAnswered() throws Exception {
        player.progress.setInvestigationTargetProgress(TARGET, 1);
        assertEquals(0, claim().getRetcode());
        assertEquals(3, player.progress.getInvestigationChapterState(CHAPTER));
        assertEquals(1, player.inventory.grants.size());
        assertEquals(60, player.inventory.grants.get(0).get(0).getCount());
        assertEquals(1, player.saves);
        session.sent.clear();
        assertEquals(0, claim().getRetcode());
        assertEquals(1, player.inventory.grants.size());
        assertEquals(1, player.saves);
    }

    @Test
    void incompleteChapterIsRejectedWithoutPaying() throws Exception {
        assertNotEquals(0, claim().getRetcode());
        assertTrue(player.inventory.grants.isEmpty());
        assertEquals(0, player.progress.getInvestigationChapterState(CHAPTER));
    }

    @Test
    void refusedChapterGrantDoesNotConsumeTheClaim() throws Exception {
        player.progress.setInvestigationTargetProgress(TARGET, 1);
        player.inventory.refuse = true;
        assertNotEquals(0, claim().getRetcode());
        assertEquals(0, player.progress.getInvestigationChapterState(CHAPTER));
        assertEquals(0, player.saves);
        player.inventory.refuse = false;
        session.sent.clear();
        assertEquals(0, claim().getRetcode());
        assertEquals(3, player.progress.getInvestigationChapterState(CHAPTER));
    }

    @Test
    void refusedTargetGrantDoesNotRecordRewardTaken() {
        player.progress.setInvestigationTargetProgress(TARGET, 1);
        player.inventory.refuse = true;
        assertNotEquals(0, InvestigationHandbookHelper.claimTargetReward(player, TARGET));
        assertEquals(0, player.progress.getInvestigationTargetState(TARGET));
        assertEquals(0, player.saves);
    }

    @Test
    void lockedChapterIsRejectedDespiteCompletedTargets() throws Exception {
        setChapter(10);
        player.progress.setInvestigationTargetProgress(TARGET, 1);
        assertNotEquals(0, claim().getRetcode());
        assertTrue(player.inventory.grants.isEmpty());
    }

    @Test
    void missingRewardDataDoesNotConsumeTheClaim() throws Exception {
        player.progress.setInvestigationTargetProgress(TARGET, 1);
        GameData.getRewardDataMap().remove(REWARD);
        assertNotEquals(0, claim().getRetcode());
        assertEquals(0, player.progress.getInvestigationChapterState(CHAPTER));
        assertEquals(0, player.saves);
    }

    @Test
    void missingObjectiveRewardDataDoesNotConsumeTheClaim() {
        player.progress.setInvestigationTargetProgress(TARGET, 1);
        GameData.getRewardDataMap().remove(REWARD);
        assertNotEquals(0, InvestigationHandbookHelper.claimTargetReward(player, TARGET));
        assertEquals(0, player.progress.getInvestigationTargetState(TARGET));
        assertEquals(0, player.saves);
    }

    @Test
    void nullPlayerIsRejectedAndAnswered() throws Exception {
        session.player = null;
        assertNotEquals(0, claim().getRetcode());
    }

    private TakeInvestigationRewardRsp claim() throws Exception {
        // Field 5, chapter ID 10001: 28 91 4e.
        new HandlerTakeInvestigationRewardReq().handle(session, new byte[0],
                HexFormat.of().parseHex("28914e"));
        var replies = session.sent.stream().filter(packet -> packet.getOpcode()
                == emu.grasscutter.net.packet.PacketOpcodes.TakeInvestigationRewardRsp).toList();
        assertEquals(1, replies.size());
        var reply = TakeInvestigationRewardRsp.parseFrom(replies.get(0).getData());
        assertEquals(CHAPTER, reply.getId());
        return reply;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class RecordingPlayer extends Player {
        RecordingInventory inventory;
        PlayerProgress progress;
        int saves;
        @Override public int getLevel() { return 5; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public PlayerProgress getPlayerProgress() { return progress; }
        @Override public void save() { saves++; }
    }

    private static final class RecordingInventory extends Inventory {
        List<List<ItemParamData>> grants;
        boolean refuse;
        private RecordingInventory() { super(null); }
        @Override public boolean tryAddItemParamDatas(Collection<ItemParamData> items, ActionReason reason) {
            if (refuse) return false;
            grants.add(List.copyOf(items));
            return true;
        }
    }

    private static final class CaptureSession extends GameSession {
        final List<BasePacket> sent = new ArrayList<>();
        Player player;
        private CaptureSession() { super(null); }
        @Override public Player getPlayer() { return player; }
        @Override public void send(BasePacket packet) { sent.add(packet); }
    }
}
