package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.scripts.*;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;

class LuaTeleportAuditTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void nonSettledLuaTeleportReportsTransferOutcome(boolean accepted) {
        var player = allocate(Player.class);
        set(player, "id", 42);
        var world = allocate(TransferWorld.class); world.accepted = accepted;
        set(player, "world", world);
        var scene = allocate(NoDirectRemovalScene.class);
        set(scene, "players", List.of(player));
        var manager = allocate(SceneScriptManager.class);
        set(manager, "scene", scene);
        var script = new ScriptLib();
        script.setSceneScriptManager(manager);
        var args = new LuaTable();
        var ids = new LuaTable();
        ids.set(1, org.luaj.vm2.LuaValue.valueOf(42));
        args.set("uid_list", ids);
        var pos = new LuaTable();
        pos.set("x", 10); pos.set("y", 20); pos.set("z", 30);
        args.set("pos", pos);
        try {
            assertEquals(accepted ? 0 : 3, script.TransPlayerToPos(args));
            assertNotNull(world.transfer);
            assertEquals(TeleportType.SCRIPT, world.transfer.getTeleportType());
            assertEquals(3, world.transfer.getSceneId());
            assertEquals(10, world.transfer.getTeleportTo().getX());
        } finally { script.removeSceneScriptManager(); }
    }
    public static class NoDirectRemovalScene extends Scene {
        public NoDirectRemovalScene() { super(null, null); }
        @Override public int getId() { return 3; }
        @Override public synchronized void removePlayer(Player player) { fail("Lua must not remove its sole player directly"); }
    }
    public static class TransferWorld extends World {
        public TransferWorld() { super((Player)null); }
        TeleportProperties transfer; boolean accepted;
        @Override public boolean transferPlayerToScene(Player player, TeleportProperties props) { transfer = props; return accepted; }
    }
}
