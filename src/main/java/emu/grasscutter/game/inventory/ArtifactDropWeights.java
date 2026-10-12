package emu.grasscutter.game.inventory;

import emu.grasscutter.data.excels.reliquary.ReliquaryAffixData;
import emu.grasscutter.game.props.FightProperty;

/** Ordinary five-star substats: flat ATK/DEF weigh 7; every other stat weighs 8. */
public final class ArtifactDropWeights {
    private ArtifactDropWeights() {}

    public static int weight(ReliquaryAffixData affix, ArtifactRollBias bias) {
        // Explicit shop bias keeps the original Excel weights. Other rarities stay unchanged.
        if (bias != ArtifactRollBias.NONE || affix.getDepotId() != 501) {
            return affix.getWeight();
        }
        FightProperty stat = affix.getFightProp();
        return stat == FightProperty.FIGHT_PROP_ATTACK || stat == FightProperty.FIGHT_PROP_DEFENSE
                ? 7
                : 8;
    }
}
