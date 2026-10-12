package emu.grasscutter.game.battlepass;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.GameConstants;

import org.junit.jupiter.api.Test;

class BattlePassPointsTest {
    @Test
    void largeEarnedAwardReachesFiftyRatherThanZero() {
        var manager = new BattlePassManager();
        manager.addPointsDirectly(50 * GameConstants.BATTLE_PASS_POINT_PER_LEVEL, false);
        assertEquals(50, manager.getLevel());
        assertEquals(0, manager.getPoint());
    }

    @Test
    void nonWeeklyAwardDoesNotConsumeWeeklyCapacity() {
        var manager = new BattlePassManager();
        manager.addPointsDirectly(1000, false);
        assertEquals(0, manager.getCyclePoints());
    }
}
