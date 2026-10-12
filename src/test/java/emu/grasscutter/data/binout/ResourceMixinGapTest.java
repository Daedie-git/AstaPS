package emu.grasscutter.data.binout;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.utils.JsonUtils;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import org.junit.jupiter.api.Test;

final class ResourceMixinGapTest {
    @Test
    void albedoWatcherPreservesTeamThresholdAndDetachPolicy() {
        var mixin = JsonUtils.decode("{\"$type\":\"AttachModifierToGlobalValueMixin\",\"valueSteps\":[0.5,1.5],\"globalValueTarget\":\"Team\",\"modifierNameSteps\":[\"UNIQUE_Albedo_AlchemyTrap_BloomAttackUp_Hexenzirkel\"],\"removeAppliedModifier\":true,\"globalValueKey\":\"_ABILITY_Albedo_Hexenzirkel_Gadget_IsExist\"}", AbilityMixinData.class);
        assertEquals(AbilityMixinData.Type.AttachModifierToGlobalValueMixin, mixin.type);
        assertEquals("Team", mixin.globalValueTarget);
        assertEquals(0.5f, mixin.valueSteps.get(0).get());
        assertEquals(1.5f, mixin.valueSteps.get(1).get());
        assertTrue(mixin.removeAppliedModifier);
        assertEquals(1, mixin.modifierNameSteps.size());
    }

    @Test
    void mavuikaPhlogistonIdentityAndDynamicSpeedAreNotLostOrRenamed() {
        var mixin = JsonUtils.decode("{\"$type\":\"ChangePhlogistonMixin\",\"targetType\":\"Team\",\"speed\":[4,\"NyxFreeze\",\"MUL\"],\"LANIGKLMJHP\":0.30000001192092896}", AbilityMixinData.class);
        assertEquals(AbilityMixinData.Type.ChangePhlogistonMixin, mixin.type);
        assertEquals("Team", mixin.targetType);
        var values = new Object2FloatOpenHashMap<String>();
        values.put("NyxFreeze", 1f);
        assertEquals(4f, mixin.speed.get(values, 0));
        values.put("NyxFreeze", 0f);
        assertEquals(0f, mixin.speed.get(values, 0));
        assertEquals(0.3f, mixin.LANIGKLMJHP.get(), 1e-6f);
        var local = JsonUtils.decode("{\"$type\":\"CurLocalAvatarMixinV2\",\"modifierName\":\"UNIQUE_Nahida_ElementalBurst_Hollow_PermanentSkill_Handler\"}", AbilityMixinData.class);
        assertEquals(AbilityMixinData.Type.CurLocalAvatarMixinV2, local.type);
    }

    @Test
    void phlogistonPickupAddIsNotConfusedWithLegacyDetermineType() {
        var action = JsonUtils.decode("{\"$type\":\"ChangePhlogiston\",\"value\":16,\"costType\":\"Add\"}", AbilityModifier.AbilityModifierAction.class);
        assertEquals("Add", action.costType);
        assertNull(action.determineType);
        assertEquals(16f, action.writtenValue().get());
    }
}
