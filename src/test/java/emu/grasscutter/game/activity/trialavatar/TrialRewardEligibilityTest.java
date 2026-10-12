package emu.grasscutter.game.activity.trialavatar;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TrialRewardEligibilityTest {
    @Test
    void rewardRequiresPassAndUnclaimedState() {
        var reward = TrialAvatarPlayerData.RewardInfoItem.create(1, 10);
        assertFalse(TrialAvatarActivityHandler.canClaimReward(reward));
        reward.setPassedDungeon(true);
        assertTrue(TrialAvatarActivityHandler.canClaimReward(reward));
        reward.setReceivedReward(true);
        assertFalse(TrialAvatarActivityHandler.canClaimReward(reward));
    }
}
