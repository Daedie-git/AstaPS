package emu.grasscutter.game.dungeons;

import emu.grasscutter.game.inventory.GameItem;

import java.util.List;
import java.util.function.*;

/** Ordering boundary for statue generation, capacity, payment and supported delivery. */
final class DomainRewardDelivery {
    static List<GameItem> grant(
            Supplier<List<GameItem>> generate,
            Predicate<List<GameItem>> capacity,
            BooleanSupplier pay,
            Predicate<List<GameItem>> deliver) {
        final List<GameItem> rewards;
        try {
            rewards = generate.get();
        } catch (RuntimeException failure) {
            return List.of();
        }
        if (rewards == null || rewards.isEmpty() || !capacity.test(rewards)) return List.of();
        if (!pay.getAsBoolean() || !deliver.test(rewards)) return List.of();
        return rewards;
    }
}
