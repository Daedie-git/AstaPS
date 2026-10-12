package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TpsDungeonResourceGapTest {
    @ParameterizedTest
    @ValueSource(strings = {"DUNGEON_TPS_DEFENSE", "DUNGEON_TPS_MERCENARIES"})
    void installedActivityIdentitiesDoNotCollapseToNone(String type) {
        var dungeon = JsonUtils.decode("{\"id\":10955,\"type\":\"" + type + "\"}", DungeonData.class);
        assertEquals(type, dungeon.getType().name());
        assertFalse(dungeon.getType().isCountsToBattlepass());
    }
}
