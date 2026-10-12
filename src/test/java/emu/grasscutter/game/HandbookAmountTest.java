package emu.grasscutter.game;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.utils.objects.HandbookBody.GiveItem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HandbookAmountTest {
    @ParameterizedTest
    @ValueSource(longs = {0, -1, 1001, Long.MAX_VALUE})
    void invalidAmountsRejectBeforePlayerLookup(long amount) {
        var request = new Gson().fromJson("{\"player\":\"1\",\"item\":\"1\",\"amount\":" + amount + "}", GiveItem.class);
        assertEquals(400, HandbookActions.giveItem(request).getStatus());
    }
}
