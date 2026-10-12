package emu.grasscutter.game.dungeons;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.inventory.GameItem;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import sun.misc.Unsafe;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class DomainRewardDeliveryTest {
    @ParameterizedTest
    @EnumSource(DomainStatueClaimHelper.ClaimMode.class)
    void emptyGenerationDoesNotPay(DomainStatueClaimHelper.ClaimMode mode) {
        var paid = new AtomicInteger();
        var result =
                DomainRewardDelivery.grant(
                        List::of,
                        items -> true,
                        () -> {
                            paid.incrementAndGet();
                            return true;
                        },
                        items -> true);
        assertTrue(result.isEmpty());
        assertEquals(0, paid.get());
    }

    @ParameterizedTest
    @EnumSource(DomainStatueClaimHelper.ClaimMode.class)
    void fullCapacityDoesNotPay(DomainStatueClaimHelper.ClaimMode mode) throws Exception {
        var u = Unsafe.class.getDeclaredField("theUnsafe");
        u.setAccessible(true);
        var item = (GameItem) ((Unsafe) u.get(null)).allocateInstance(GameItem.class);
        var paid = new AtomicInteger();
        var result =
                DomainRewardDelivery.grant(
                        () -> List.of(item),
                        items -> false,
                        () -> {
                            paid.incrementAndGet();
                            return true;
                        },
                        items -> true);
        assertTrue(result.isEmpty());
        assertEquals(0, paid.get());
    }

    @ParameterizedTest
    @EnumSource(DomainStatueClaimHelper.ClaimMode.class)
    void throwingGenerationDoesNotPay(DomainStatueClaimHelper.ClaimMode mode) {
        var paid = new AtomicInteger();
        assertDoesNotThrow(
                () ->
                        DomainRewardDelivery.grant(
                                () -> {
                                    throw new IllegalStateException("fixture generation failed");
                                },
                                items -> true,
                                () -> {
                                    paid.incrementAndGet();
                                    return true;
                                },
                                items -> true));
        assertEquals(0, paid.get());
    }
}
