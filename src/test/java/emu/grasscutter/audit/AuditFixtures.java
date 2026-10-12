package emu.grasscutter.audit;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Constructor-free fixtures avoid server, resource and database startup. */
public final class AuditFixtures {
    private static final Unsafe UNSAFE;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    public static <T> T allocate(Class<T> type) {
        try {
            T value = type.cast(UNSAFE.allocateInstance(type));
            if (value instanceof emu.grasscutter.game.player.Player) {
                set(value, "sceneTags", new java.util.HashMap<>());
            }
            if (value instanceof emu.grasscutter.game.world.Scene) {
                var data = new emu.grasscutter.data.excels.scene.SceneData();
                set(data, "id", 3);
                set(data, "sceneType", emu.grasscutter.game.props.SceneType.SCENE_WORLD);
                set(value, "sceneData", data);
            }
            if (value instanceof emu.grasscutter.scripts.SceneScriptManager manager) {
                set(value, "liyueDungeonTimers", new emu.grasscutter.scripts.LiyueCloudDungeonTimers(manager));
            }
            if (value instanceof emu.grasscutter.game.entity.EntityGadget) {
                set(value, "extraGlobalValues", new java.util.HashMap<>());
                set(value, "bornPos", new emu.grasscutter.game.world.Position());
                set(value, "bornRot", new emu.grasscutter.game.world.Position());
                set(value, "position", new emu.grasscutter.game.world.Position());
                set(value, "rotation", new emu.grasscutter.game.world.Position());
            }
            return value;
        }
        catch (InstantiationException e) { throw new AssertionError(e); }
    }
    public static void set(Object target, String name, Object value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) { /* Search parent. */ }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        }
        throw new AssertionError(name);
    }
    private AuditFixtures() {}
}
