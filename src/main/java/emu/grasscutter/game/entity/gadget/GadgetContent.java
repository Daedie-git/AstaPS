package emu.grasscutter.game.entity.gadget;

import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.proto.GadgetInteractReqOuterClass.GadgetInteractReq;
import emu.grasscutter.net.proto.SceneGadgetInfoOuterClass.SceneGadgetInfo;

public abstract class GadgetContent {
    private final EntityGadget gadget;

    public GadgetContent(EntityGadget gadget) {
        this.gadget = gadget;
    }

    public EntityGadget getGadget() {
        return gadget;
    }

    /** Admission is distinct from onInteract's entity-deletion result. */
    public boolean canInteract(Player player, GadgetInteractReq req) {
        return true;
    }

    /** Called after the owning entity is admitted to its scene. */
    public void onCreate() {}

    public abstract boolean onInteract(Player player, GadgetInteractReq req);

    public abstract void onBuildProto(SceneGadgetInfo.Builder gadgetInfo);
}
