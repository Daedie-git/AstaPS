package emu.grasscutter.game.entity;

import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.EvtBeingHitInfoOuterClass.EvtBeingHitInfo;
import java.util.HashSet;

/** Final-hit scaling; ownership comes from the attacker, never the player's active character. */
public final class CharacterDamageTuning {
    private static final int KEQING = 10000042;
    private static final int DILUC = 10000016;

    private CharacterDamageTuning() {}

    public static EvtBeingHitInfo apply(Scene scene, EvtBeingHitInfo hit, float keqingMultiplier) {
        return apply(scene, hit, keqingMultiplier, 1f);
    }

    public static EvtBeingHitInfo apply(
            Scene scene, EvtBeingHitInfo hit, float keqingMultiplier, float dilucMultiplier) {
        if (scene == null || !hit.hasAttackResult()) return hit;

        var attack = hit.getAttackResult();
        if (!Float.isFinite(attack.getDamage()) || attack.getDamage() <= 0f
                || !(scene.getEntityById(attack.getDefenseId()) instanceof EntityMonster)) return hit;

        var owner = resolveAvatar(scene, attack.getAttackerId());
        if (owner == null || owner.getAvatar() == null) return hit;
        float multiplier = switch (owner.getAvatar().getAvatarId()) {
            case DILUC -> dilucMultiplier;
            case KEQING -> attack.getElementType() == ElementType.Electric.getValue()
                    ? keqingMultiplier : 1f;
            default -> 1f;
        };
        if (!Float.isFinite(multiplier) || multiplier <= 0f || multiplier == 1f) return hit;

        float damage = attack.getDamage() * multiplier;
        if (!Float.isFinite(damage) || damage <= 0f) return hit;
        return hit.toBuilder()
                .setAttackResult(attack.toBuilder().setDamage(damage))
                .build();
    }

    private static EntityAvatar resolveAvatar(Scene scene, int attackerId) {
        var visited = new HashSet<Integer>();
        GameEntity entity = scene.getEntityById(attackerId);
        for (int depth = 0; depth < 8 && entity != null && visited.add(entity.getId()); depth++) {
            if (entity instanceof EntityAvatar avatar) return avatar;
            if (!(entity instanceof EntityClientGadget gadget)) return null;
            // A known immediate owner takes precedence over a stale original-owner field.
            GameEntity owner = scene.getEntityById(gadget.getOwnerEntityId());
            if (owner == null && gadget.getOriginalOwnerEntityId() > 0)
                owner = scene.getEntityById(gadget.getOriginalOwnerEntityId());
            entity = owner;
        }
        return null;
    }
}
