package com.saathi.shared.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Rates are basis points, so a rule written as "12%" and a rule written as "12.005%" resolve
 * to different integers and nothing in between drifts (§3, §14.7's RATE_NOT_APPROVED guard).
 */
class RateTest {

    @Test
    void onePercentIsOneHundredBasisPoints() {
        assertEquals(1_200, Rate.ofPercent(BigDecimal.valueOf(12)).basisPoints());
        assertEquals(1, Rate.ofPercent(new BigDecimal("0.01")).basisPoints());
        assertEquals(10_000, Rate.ofPercent(BigDecimal.valueOf(100)).basisPoints());
        assertEquals(0, Rate.ofPercent(BigDecimal.ZERO).basisPoints());
    }

    @Test
    void aSubBasisPointRemainderIsResolvedByHalfUpAndNotBySilence() {
        assertEquals(1, Rate.ofPercent(new BigDecimal("0.005")).basisPoints(), "0.5 bp -> 1");
        assertEquals(0, Rate.ofPercent(new BigDecimal("0.004")).basisPoints(), "0.4 bp -> 0");
    }

    @Test
    void ratesBeyondZeroToOneHundredPercentAreNotRepresentable() {
        assertThrows(IllegalArgumentException.class, () -> Rate.ofBasisPoints(-1));
        assertThrows(IllegalArgumentException.class, () -> Rate.ofBasisPoints(10_001));
        assertThrows(IllegalArgumentException.class, () -> Rate.ofPercent(BigDecimal.valueOf(100.01)));
        assertEquals(Rate.MAX_BASIS_POINTS, Rate.ofBasisPoints(10_000).basisPoints());
    }

    @Test
    void applyingARateRoundsHalfUpToWholePaise() {
        Rate twelve = Rate.ofBasisPoints(1_200);
        assertEquals(Money.ofPaise(5_184), twelve.applyTo(Money.ofPaise(43_200)));
        assertEquals(Money.ofPaise(6_031), twelve.applyTo(Money.ofPaise(50_260)), "6,031.2 -> 6,031");
        assertEquals(Money.zero(), twelve.applyTo(Money.ofPaise(1)), "0.12 rounds down to nothing");
        assertEquals(Money.ofPaise(1), Rate.ofBasisPoints(2_000).applyTo(Money.ofPaise(3)), "0.6 -> 1");
        assertEquals(Money.zero(), Rate.ofBasisPoints(200).applyTo(Money.ofPaise(3)), "0.06 -> 0");
        assertEquals(Money.ofPaise(1), Rate.ofBasisPoints(6_000).applyTo(Money.ofPaise(1)), "0.6 -> 1");
    }

    @Test
    void aZeroRateIsARealConfigurationNotAMissingOne() {
        org.junit.jupiter.api.Assertions.assertTrue(Rate.ofBasisPoints(0).isZero(), "OD-7's pilot rate is 0");
        assertEquals(Money.zero(), Rate.ofBasisPoints(0).applyTo(Money.ofPaise(46_700)));
        assertEquals("0%", Rate.ofBasisPoints(0).display());
    }

    @Test
    void displayNeverCarriesALostDecimal() {
        assertEquals("12%", Rate.ofBasisPoints(1_200).display());
        assertEquals("0.01%", Rate.ofBasisPoints(1).display());
        assertEquals("12.5%", Rate.ofBasisPoints(1_250).display());
        assertEquals("100%", Rate.ofBasisPoints(10_000).display());
    }

    @Test
    void percentRoundTripsThroughBasisPoints() {
        for (int bp : new int[] {0, 1, 250, 1_200, 3_333, 9_999, 10_000}) {
            BigDecimal percent = Rate.ofBasisPoints(bp).percent();
            assertEquals(bp, Rate.ofPercent(percent).basisPoints(), bp + " bp");
        }
    }

    @Test
    void moneyAndRateAgreeOnTheSameOperation() {
        Money goods = Money.ofPaise(50_260);
        Rate twelve = Rate.ofBasisPoints(1_200);
        assertEquals(twelve.applyTo(goods), goods.applyRate(twelve));
    }
}
