// Isolated actual-method regression. No server startup, player, scheduler, DB, or sockets.
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.platform.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.*;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.data.*;
import org.luaj.vm2.LuaTable;
import java.lang.reflect.Field;
import java.util.*;
import sun.misc.Unsafe;

public final class CheckPlatformPointArrayProtocol {
    static final Unsafe U;
    static { try { Field f=Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); U=(Unsafe)f.get(null); }
        catch(Exception e) { throw new RuntimeException(e); } }
    static <T> T empty(Class<T> c) throws Exception { return c.cast(U.allocateInstance(c)); }
    static void field(Object o,String name,Object value) throws Exception {
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass()) {
            try { Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);return; }
            catch(NoSuchFieldException ignored) { }
        } throw new NoSuchFieldException(name);
    }
    static void check(boolean condition,String name) {
        if(!condition) throw new AssertionError(name);
        System.out.println("PASS "+name);
    }
    static class TestScene extends Scene {
        GameEntity entity; List<BasePacket> attempted; List<BasePacket> deliverable; int lookups;
        TestScene() { super(null,null); }
        @Override public int getId() { return 3; }
        @Override public int getSceneTime() { return 1000; }
        @Override public GameEntity getEntityByConfigId(int config,int group) {
            lookups++;return config==174002 && group==133003174 ? entity:null;
        }
        @Override public void broadcastPacket(BasePacket packet) {
            attempted.add(packet);
            // Reproduce only the independently bytecode-verified GameSession nonpositive-ID boundary.
            // Never invoke positive GameSession.send, encryption, sockets, or server transport.
            if(packet.getOpcode()>0) deliverable.add(packet);
        }
        void clear() { attempted.clear();deliverable.clear(); }
    }
    static class TestManager extends SceneScriptManager {
        TestScene scene; TestManager() { super(null); }
        @Override public Scene getScene() { return scene; }
    }
    static class TestGadget extends EntityGadget {
        TestGadget() { super(null,0,null); }
        @Override public SceneEntityInfoOuterClass.SceneEntityInfo toProto() {
            return SceneEntityInfoOuterClass.SceneEntityInfo.newBuilder().setEntityId(getId())
                .setGadget(SceneGadgetInfoOuterClass.SceneGadgetInfo.newBuilder().setPlatform(getPlatformInfo())).build();
        }
    }
    public static void main(String[] args) throws Exception {
        int expected=args.length==0 ? 0:Integer.parseInt(args[0]);
        TestScene scene=empty(TestScene.class);scene.attempted=new ArrayList<>();scene.deliverable=new ArrayList<>();
        TestManager manager=empty(TestManager.class);manager.scene=scene;
        TestGadget gadget=empty(TestGadget.class);field(gadget,"scene",scene);field(gadget,"position",new Position(1,2,3));
        gadget.setId(0x04000001);gadget.setConfigId(174002);gadget.setGroupId(133003174);scene.entity=gadget;
        SceneGadget meta=new SceneGadget();meta.rot=new Position(10,20,30);meta.start_route=true;meta.is_use_point_array=true;
        gadget.setMetaGadget(meta);
        PointArrayRoute route=new PointArrayRoute(meta);route.setPointArrayId(40);route.setCurrentPoint(9);
        route.setStartSceneTime(777);route.setStopSceneTime(888);gadget.setRouteConfig(route);
        byte[] before=gadget.getPlatformInfo().build().toByteArray();
        ScriptLib lib=new ScriptLib();lib.setSceneScriptManager(manager);SceneGroup group=new SceneGroup();group.id=133003174;lib.setCurrentGroup(group);
        int[] points={3,8,12};int[] snapshot=points.clone();LuaTable options=new LuaTable();options.set("route_type",3);options.set("sentinel",73);
        check(lib.SetPlatformPointArray(999,41,points,options)==1 && scene.attempted.isEmpty(),"unknown entity returns 1 without publishing");
        check(route.getPointArrayId()==40 && route.getCurrentPoint()==9,"unknown entity leaves point-array state unchanged");
        scene.entity=empty(EntityMonster.class);
        check(lib.SetPlatformPointArray(174002,41,points,options)==2 && scene.attempted.isEmpty(),"non-gadget returns 2 without publishing");scene.entity=gadget;
        check(lib.SetPlatformPointArray(174002,40,points,options)==-1 && scene.attempted.isEmpty(),"same ID retains -1 return and no-op notification behavior");
        check(gadget.getRouteConfig()==route && Arrays.equals(before,gadget.getPlatformInfo().build().toByteArray()),"same ID leaves route identity and serialization unchanged");
        check(lib.SetPlatformPointArray(174002,41,points,options)==-1,"changed ID retains existing -1 return");
        check(gadget.getRouteConfig()==route && route.getPointArrayId()==41 && route.getCurrentPoint()==9,"changed ID preserves route object and current point");
        check(route.isStarted() && route.isActive() && route.getStartRot()==meta.rot && route.getStartSceneTime()==777 && route.getStopSceneTime()==888,"changed ID preserves active/started/rotation/timing state");
        check(Arrays.equals(points,snapshot) && options.length()==0 && options.keys().length==2 && options.get("route_type").toint()==3 && options.get("sentinel").toint()==73,"point list and Lua options are not mutated or newly interpreted");
        check(Arrays.equals(before,gadget.getPlatformInfo().build().toByteArray()),"existing platform serialization is preserved (no new point-array field mapping)");
        List<Integer> opcodes=scene.attempted.stream().map(BasePacket::getOpcode).toList();
        System.out.println("OBSERVED attempted="+opcodes+" deliverable="+scene.deliverable.stream().map(BasePacket::getOpcode).toList());
        boolean visible;
        if(expected>0) {
            visible=opcodes.equals(List.of(expected)) && scene.deliverable.size()==1;
            if(visible) {
                var change=PlatformChangeRouteNotifyOuterClass.PlatformChangeRouteNotify.parseFrom(scene.deliverable.get(0).getData());
                check(change.getEntityId()==gadget.getId() && change.getSceneTime()==1000 && Arrays.equals(change.getPlatform().toByteArray(),gadget.getPlatformInfo().build().toByteArray()),"positive synthetic opcode branch keeps the existing change-envelope payload");
            }
        } else {
            visible=opcodes.equals(List.of(6253,27685)) && scene.deliverable.size()==2;
            if(visible) {
                var disappear=SceneEntityDisappearNotifyOuterClass.SceneEntityDisappearNotify.parseFrom(scene.deliverable.get(0).getData());
                var appear=SceneEntityAppearNotifyOuterClass.SceneEntityAppearNotify.parseFrom(scene.deliverable.get(1).getData());
                check(disappear.getDisappearType()==VisionTypeOuterClass.VisionType.VisionType_VISION_REFRESH && disappear.getEntityListList().equals(List.of(gadget.getId())),"disappear targets only this gadget with VISION_REFRESH");
                check(appear.getAppearType()==VisionTypeOuterClass.VisionType.VisionType_VISION_REFRESH && appear.getParam()==0 && appear.getEntityListCount()==1 && appear.getEntityList(0).getEntityId()==gadget.getId(),"appearance follows disappearance with VISION_REFRESH and param zero");
                check(Arrays.equals(appear.getEntityList(0).getGadget().getPlatform().toByteArray(),gadget.getPlatformInfo().build().toByteArray()),"appearance carries the unchanged actual platform DTO");
            }
        }
        scene.clear();check(lib.SetPlatformPointArray(174002,41,points,options)==-1 && scene.attempted.isEmpty(),"same ID after transition remains silent");
        gadget.setRouteConfig(null);scene.clear();check(lib.SetPlatformPointArray(174002,42,points,options)==-1 && gadget.getRouteConfig() instanceof PointArrayRoute && ((PointArrayRoute)gadget.getRouteConfig()).getPointArrayId()==42,"missing route retains existing point-array conversion and return");
        check(gadget.getRouteConfig().isStarted() && gadget.getRouteConfig().isActive() && gadget.getRouteConfig().getStartRot()==meta.rot,"route conversion preserves metadata defaults");
        check(visible,"changed point-array notification must survive the nonpositive-opcode discard boundary");
        System.out.println("RESULT PASS actual SetPlatformPointArray notification and unchanged state/return contracts");
    }
}
