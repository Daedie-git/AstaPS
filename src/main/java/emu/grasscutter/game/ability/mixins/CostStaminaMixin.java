package emu.grasscutter.game.ability.mixins;

import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityMixinData;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.ability.NightsoulStaminaExempt;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.managers.stamina.Consumption;
import emu.grasscutter.game.managers.stamina.ConsumptionType;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

@AbilityMixin(AbilityMixinData.Type.CostStaminaMixin)
public class CostStaminaMixin extends AbilityMixinHandler {
    // Bound catch-up after a pause; slower executions still pay a positive interval.
    private static final long MAX_CHARGE_INTERVAL_MS = 1000;
    private final Map<Ability, Map<AbilityMixinData, Timing>> streams = new WeakHashMap<>();

    protected long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    @Override
    public synchronized boolean execute(Ability ability, AbilityMixinData data,
            ByteString abilityData, GameEntity target) {
        var player = ability.getPlayerOwner();
        if (player == null) return false;
        if (NightsoulStaminaExempt.shouldSkipCharacterStamina(player)) {
            streams.remove(ability);
            return true;
        }
        float ratio = data.costStaminaDelta.get(ability);
        if (ratio == 0f) ratio = 1f;
        if (!Float.isFinite(ratio) || target == null) return false;
        long now = monotonicMillis();
        var mixins = streams.computeIfAbsent(ability, ignored -> new WeakHashMap<>());
        var timing = mixins.get(data);
        if (timing == null || timing.target.get() != target
                || timing.scene.get() != target.getScene()) {
            mixins.put(data, new Timing(target, now));
            return true;
        }
        long elapsed = Math.max(0, Math.min(MAX_CHARGE_INTERVAL_MS, now - timing.at));
        timing.at = now;
        double charge = timing.remainder + ratio * 100d * elapsed / 1000d;
        int cost = (int) Math.max(-Integer.MAX_VALUE, Math.min(Integer.MAX_VALUE, charge));
        timing.remainder = charge - cost;
        if (cost != 0) {
            var stamina = player.getStaminaManager();
            stamina.updateStaminaRelative(player.getSession(),
                    new Consumption(ConsumptionType.FIGHT, -cost), true);
            if (cost > 0) stamina.staminaRecoverDelay = 0;
        }
        return true;
    }

    private static final class Timing {
        final WeakReference<GameEntity> target;
        final WeakReference<emu.grasscutter.game.world.Scene> scene;
        long at;
        double remainder;
        Timing(GameEntity target, long at) {
            this.target = new WeakReference<>(target);
            this.scene = new WeakReference<>(target.getScene());
            this.at = at;
        }
    }
}
