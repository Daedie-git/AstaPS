package emu.grasscutter.game.inventory;

import emu.grasscutter.data.excels.reliquary.ReliquaryAffixData;

/**
 * Multiplier applied to a substat's excel weight while a relic rolls its stats.
 *
 * <p>{@link #NONE} adds no multiplier. Ordinary new-substat weights come from
 * {@link ArtifactDropWeights}; upgrade weights remain unchanged. The artifact shop supplies its
 * own multiplier and retains the Excel base weights.
 */
@FunctionalInterface
public interface ArtifactRollBias {
    ArtifactRollBias NONE = affix -> 1;

    double weigh(ReliquaryAffixData affix);
}
