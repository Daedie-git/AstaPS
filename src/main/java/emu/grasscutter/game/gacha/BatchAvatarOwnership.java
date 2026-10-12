package emu.grasscutter.game.gacha;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/** Ownership view used while reward cards have not yet been delivered. */
final class BatchAvatarOwnership {
    private final IntUnaryOperator ownership;

    BatchAvatarOwnership(IntUnaryOperator ownership) {
        this.ownership = ownership;
    }

    private final Map<Integer, Integer> provisional = new HashMap<>();

    int beforeCopy(int avatarId) {
        int before = provisional.computeIfAbsent(avatarId, ownership::applyAsInt);
        if (before >= -1) provisional.put(avatarId, before < 6 ? before + 1 : 6);
        return before;
    }
}
