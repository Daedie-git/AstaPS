package emu.grasscutter.game.battlepass;

import static emu.grasscutter.net.proto.BattlePassUnlockStatusOuterClass.BattlePassUnlockStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.GameConstants;
import org.junit.jupiter.api.Test;

class BattlePassPurchaseTest {
    @Test
    void actualPurchaseChargesOnlyRemainingLevelsAndRejectsRetry() throws Exception {
        var player = fakePlayer(100000);
        var bp = player.manager;
        var level = BattlePassManager.class.getDeclaredField("level");
        level.setAccessible(true);
        level.setInt(bp, 49);
        assertEquals(1, bp.buyLevels(40));
        assertEquals(50, bp.getLevel());
        assertEquals(100000 - GameConstants.BATTLE_PASS_LEVEL_PRICE, player.gems);
        assertEquals(0, bp.buyLevels(1));
    }

    @Test
    void actualPurchaseRejectsInsufficientAndCancelledDebit() throws Exception {
        var player = fakePlayer(0);
        assertEquals(0, player.manager.buyLevels(1));
        player.gems = 100000;
        player.rejectDebit = true;
        assertEquals(0, player.manager.buyLevels(1));
        assertEquals(0, player.manager.getLevel());
        assertEquals(100000, player.gems);
    }

    private static FakePlayer fakePlayer(int gems) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var player = (FakePlayer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(FakePlayer.class);
        player.gems = gems;
        player.manager = new FakeManager(player);
        return player;
    }

    private static class FakeManager extends BattlePassManager {
        FakeManager(emu.grasscutter.game.player.Player player) { super(player); }
        @Override public void save() {}
    }

    private static class FakePlayer extends emu.grasscutter.game.player.Player {
        int gems;
        boolean rejectDebit;
        FakeManager manager;
        @Override public int getPrimogems() { return gems; }
        @Override public boolean setPrimogems(int gems) {
            if (rejectDebit) return false;
            this.gems = gems;
            return true;
        }
        @Override public BattlePassManager getBattlePassManager() { return manager; }
        @Override public void save() {}
        @Override public void sendPacket(emu.grasscutter.net.packet.BasePacket packet) {}
    }

    @Test
    void purchaseQuotesUseCurrentLevelRatherThanRequestedLevel() {
        assertEquals(
                new BattlePassManager.LevelPurchase(5, 5 * GameConstants.BATTLE_PASS_LEVEL_PRICE),
                BattlePassManager.quoteLevelPurchase(10, 5));
        assertEquals(
                new BattlePassManager.LevelPurchase(2, 2 * GameConstants.BATTLE_PASS_LEVEL_PRICE),
                BattlePassManager.quoteLevelPurchase(48, 10));
        assertEquals(
                new BattlePassManager.LevelPurchase(50, 50 * GameConstants.BATTLE_PASS_LEVEL_PRICE),
                BattlePassManager.quoteLevelPurchase(0, Integer.MAX_VALUE));
    }

    @Test
    void invalidAndMaximumLevelPurchasesAreRejected() {
        var zero = new BattlePassManager.LevelPurchase(0, 0);
        assertEquals(zero, BattlePassManager.quoteLevelPurchase(50, 5));
        assertEquals(zero, BattlePassManager.quoteLevelPurchase(51, 5));
        assertEquals(zero, BattlePassManager.quoteLevelPurchase(-1, 5));
        assertEquals(zero, BattlePassManager.quoteLevelPurchase(10, 0));
        assertEquals(zero, BattlePassManager.quoteLevelPurchase(10, -10));
    }

    @Test
    void paidFlagAffectsActualSevenPointOneSchedule() {
        var bp = new BattlePassManager();
        assertFalse(bp.isPaid());

        var freeSchedule = SafeBattlePassSchedule.build(bp);
        assertEquals(BattlePassUnlockStatus_BATTLE_PASS_UNLOCK_FREE, freeSchedule.getUnlockStatus());
        assertEquals(0, freeSchedule.getPaidPlatformFlags());

        bp.setPaid(true);
        assertTrue(bp.isPaid());
        var paidSchedule = SafeBattlePassSchedule.build(bp);
        assertEquals(BattlePassUnlockStatus_BATTLE_PASS_UNLOCK_PAID, paidSchedule.getUnlockStatus());
        assertEquals(3, paidSchedule.getPaidPlatformFlags());

        assertTrue(BattlePassCompatHelper.setPaidFlag(bp, false));
        assertFalse(bp.isPaid());
        assertFalse(BattlePassCompatHelper.setPaidFlag(null, true));
        assertEquals(
                BattlePassUnlockStatus_BATTLE_PASS_UNLOCK_FREE,
                SafeBattlePassSchedule.build(bp).getUnlockStatus());
    }
}
