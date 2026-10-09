package emu.grasscutter.game.tower;

import static org.junit.jupiter.api.Assertions.assertEquals;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.tower.TowerFloorData;
import emu.grasscutter.data.excels.tower.TowerLevelData;
import emu.grasscutter.data.excels.tower.TowerRewardData;
import emu.grasscutter.data.excels.tower.TowerScheduleData;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins where Spiral Abyss rewards come from, using the 7.1 rows for floor 9 (floorId 1044).
 *
 * <p>Rotation 124 uses reward group 15, whose nine-star chest (610053) pays 100 Primogems; the
 * floor row's own chest (610049) pays 50.
 */
public final class TowerRewardsTest {
    private static final TowerFloorData FLOOR =
            JsonUtils.decode(
                    "{\"floorId\":1044,\"floorIndex\":9,\"rewardIdThreeStars\":610009,"
                            + "\"rewardIdSixStars\":610031,\"rewardIdNineStars\":610049}",
                    TowerFloorData.class);
    private static final TowerLevelData SECOND_CHAMBER =
            JsonUtils.decode(
                    "{\"levelId\":131,\"levelIndex\":2,\"firstPassRewardId\":621026}",
                    TowerLevelData.class);

    private static TowerScheduleData schedule(int rewardGroup) {
        return JsonUtils.decode(
                "{\"scheduleId\":124,\"rewardGroup\":" + rewardGroup + "}", TowerScheduleData.class);
    }

    @BeforeEach
    public void loadGroup15() {
        var row =
                JsonUtils.decode(
                        "{\"rewardGroup\":15,\"floor\":9,\"rewardId3Stars\":610009,"
                                + "\"rewardId6Stars\":610029,\"rewardId9Stars\":610053,"
                                + "\"rewardIdRoom\":[626301,626302,626303]}",
                        TowerRewardData.class);
        GameData.getTowerRewardDataMap().put(row.getId(), row);
    }

    @AfterEach
    public void clear() {
        GameData.getTowerRewardDataMap().clear();
    }

    @Test
    @DisplayName("star chests come from the rotation's reward group")
    public void starFromGroup() {
        assertEquals(610053, TowerRewards.starRewardId(schedule(15), FLOOR, 9));
        assertEquals(610029, TowerRewards.starRewardId(schedule(15), FLOOR, 6));
    }

    @Test
    @DisplayName("chamber rewards come from the rotation's reward group by chamber index")
    public void chamberFromGroup() {
        assertEquals(626302, TowerRewards.chamberRewardId(schedule(15), FLOOR, SECOND_CHAMBER));
    }

    @Test
    @DisplayName("a rotation without a matching group falls back to the floor and chamber rows")
    public void fallback() {
        assertEquals(610049, TowerRewards.starRewardId(schedule(4), FLOOR, 9));
        assertEquals(610049, TowerRewards.starRewardId(null, FLOOR, 9));
        assertEquals(621026, TowerRewards.chamberRewardId(schedule(4), FLOOR, SECOND_CHAMBER));
    }
}
