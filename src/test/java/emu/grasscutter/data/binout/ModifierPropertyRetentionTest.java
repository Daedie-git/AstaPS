package emu.grasscutter.data.binout;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.common.DynamicFloat;
import emu.grasscutter.utils.JsonUtils;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModifierPropertyRetentionTest {
    @SuppressWarnings("unchecked")
    private static Map<String, DynamicFloat> properties(AbilityModifier modifier) throws Exception {
        return (Map<String, DynamicFloat>) modifier.properties.getClass()
                .getMethod("values").invoke(modifier.properties);
    }

    @Test
    void installedNahidaConstellationPropertyRetainsItsFormula() throws Exception {
        // ConfigAbility_Avatar_Nahida.json, [7].Default.modifiers.
        // UNIQUE_Avatar_Nahida_Constellation_2_DefenseDown.properties, installed 7.1 resources.
        var modifier = JsonUtils.decode("{\"properties\":{\"Actor_DefenceRatio\":[0,1,\"DefenseDown_Ratio\",\"MUL\",\"SUB\"]}}", AbilityModifier.class);
        var specials = new Object2FloatOpenHashMap<String>();
        specials.put("DefenseDown_Ratio", .3f);
        assertEquals(-.3f, properties(modifier).get("Actor_DefenceRatio").get(specials, 0), 1e-6f);
    }

    @Test
    void malformedUnknownPropertyCannotDiscardValidModifier() throws Exception {
        var modifier = JsonUtils.decode("{\"properties\":{\"Future_Property\":{\"unexpected\":true},\"Actor_MaxHPRatio\":0.2}}", AbilityModifier.class);
        assertEquals(.2f, modifier.properties.Actor_MaxHPRatio.get());
        assertFalse(properties(modifier).containsKey("Future_Property"));
        assertNotNull(JsonUtils.decode("{\"properties\":[]}", AbilityModifier.class));
    }

    @Test
    void unknownPropertiesAndExistingTypedConsumersBothSurvive() throws Exception {
        var modifier = JsonUtils.decode("{\"properties\":{\"Actor_MaxHPRatio\":0.2,\"Actor_HpThresholdRatio\":0.4,\"Actor_CostStaminaRatio\":-2,\"Future_Property\":5}}", AbilityModifier.class);
        assertEquals(.2f, modifier.properties.Actor_MaxHPRatio.get());
        assertEquals(.4f, modifier.properties.Actor_HpThresholdRatio.get());
        assertEquals(-2f, modifier.properties.Actor_CostStaminaRatio.get());
        assertEquals(5f, properties(modifier).get("Future_Property").get());
        modifier.properties.Actor_MaxHPRatio = new DynamicFloat(.5f);
        assertEquals(.5f, properties(modifier).get("Actor_MaxHPRatio").get());
        assertThrows(UnsupportedOperationException.class,
                () -> properties(modifier).put("Other", DynamicFloat.ONE));
    }
}
