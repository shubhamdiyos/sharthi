package com.saathi.shared.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Money arithmetic, and the three accrual cases from System Design §9.4.
 *
 * <p>{@link #recompute()} is §17.2's named test. Its point is that the design's worked
 * figures are <em>produced</em> by the same primitives the ledger will use: asserting
 * {@code 38016} as a literal would only prove that a mock can copy itself (risk D4).
 */
class MoneyTest {

    private static final Rate COMMISSION_12 = Rate.ofPercent(BigDecimal.valueOf(12));
    private static final Money DELIVERY_FEE = Money.ofPaise(2_500);
    private static final Money HANDLING_FEE = Money.ofPaise(1_000);

    @Test
    void recompute() {
        // Case A — SAATH-4821, platform-delivered: atta 5 kg at ₹64.00/kg, salt 4 × ₹28.00.
        Money atta = Money.ofRupees(new BigDecimal("64.00")).multipliedBy(5);
        Money salt = Money.ofRupees(new BigDecimal("28.00")).multipliedBy(4);
        Money goodsA = atta.plus(salt);

        assertEquals(Money.ofPaise(32_000), atta, "atta line");
        assertEquals(Money.ofPaise(11_200), salt, "salt line");
        assertEquals(Money.ofPaise(43_200), goodsA, "case A net goods");
        assertEquals(Money.ofPaise(46_700), goodsA.plus(DELIVERY_FEE).plus(HANDLING_FEE), "case A total");

        Money commissionA = goodsA.applyRate(COMMISSION_12);
        assertEquals(Money.ofPaise(5_184), commissionA, "commission is 12% of goods, never of total");

        Money netGoodsA = goodsA.minus(commissionA);
        Money takeA = commissionA.plus(DELIVERY_FEE).plus(HANDLING_FEE);
        assertEquals(Money.ofPaise(38_016), netGoodsA, "merchant is owed net goods");
        assertEquals(Money.ofPaise(8_684), takeA, "platform take");
        assertIdentityI1(goodsA.plus(DELIVERY_FEE).plus(HANDLING_FEE), netGoodsA, takeA);

        // Case B — SAATH-4849, merchant-delivered: milk 2 × ₹29.00, curd ₹45.00, paneer ₹90.00.
        Money goodsB = Money.ofRupees(new BigDecimal("29.00")).multipliedBy(2)
            .plus(Money.ofRupees(new BigDecimal("45.00")))
            .plus(Money.ofRupees(new BigDecimal("90.00")));
        assertEquals(Money.ofPaise(19_300), goodsB, "case B net goods");

        Money commissionB = goodsB.applyRate(COMMISSION_12);
        assertEquals(Money.ofPaise(2_316), commissionB, "case B commission");
        Money receivableB = commissionB.plus(HANDLING_FEE);
        assertEquals(Money.ofPaise(3_316), receivableB, "case B platform receivable");
        Money collectedB = goodsB.plus(DELIVERY_FEE).plus(HANDLING_FEE);
        assertEquals(Money.ofPaise(19_484), collectedB.minus(receivableB), "case B merchant keeps");

        // Case C — SAATH-4835, weighed. 0.98 kg of chicken at ₹470.00/kg plus 6 eggs at ₹7.00.
        Money chicken = Money.ofRupees(new BigDecimal("470.00")).multipliedBy(new BigDecimal("0.98"));
        Money eggs = Money.ofRupees(new BigDecimal("7.00")).multipliedBy(6);
        Money goodsC = chicken.plus(eggs);
        assertEquals(Money.ofPaise(46_060), chicken, "weighed line rounds HALF_UP to a whole paisa");
        assertEquals(Money.ofPaise(50_260), goodsC, "case C weighed goods");

        Money quoteC = goodsC.plus(DELIVERY_FEE).plus(HANDLING_FEE);
        assertEquals(Money.ofPaise(53_760), quoteC, "the consented figure is in paise, never the screen's ₹538");

        Money commissionC = goodsC.applyRate(COMMISSION_12);
        assertEquals(Money.ofPaise(6_031), commissionC, "50,260 x 1200 / 10,000 = 6,031.2 -> HALF_UP 6,031");
        Money netGoodsC = goodsC.minus(commissionC);
        Money takeC = commissionC.plus(DELIVERY_FEE).plus(HANDLING_FEE);
        assertEquals(Money.ofPaise(44_229), netGoodsC, "case C net payable");
        assertEquals(Money.ofPaise(9_531), takeC, "case C platform take");
        assertIdentityI1(quoteC, netGoodsC, takeC);
    }

    /** I-1 (§9.7): the customer's total is exactly what the merchant is owed plus what the platform took. */
    private static void assertIdentityI1(Money total, Money merchantNet, Money platformTake) {
        assertEquals(total, merchantNet.plus(platformTake), "I-1 must hold to the paisa");
    }

    @Test
    void rupeesRoundHalfUpToWholePaise() {
        assertEquals(Money.ofPaise(1_001), Money.ofRupees(new BigDecimal("10.005")));
        assertEquals(Money.ofPaise(999), Money.ofRupees(new BigDecimal("9.994")));
        assertEquals(Money.ofPaise(1_000), Money.ofRupees(new BigDecimal("10")));
        assertEquals(Money.ofPaise(1), Money.ofRupees(new BigDecimal("0.005")));
    }

    @Test
    void quantitiesScaleExactlyAndRejectFractionalRequests() {
        assertEquals(Money.ofPaise(11_200), Money.ofPaise(2_800).multipliedBy(4));
        // A weighed quantity is the one non-integer factor the platform allows, and it rounds.
        assertEquals(Money.ofPaise(46_061),
            Money.ofPaise(47_000).multipliedBy(new BigDecimal("0.98002")));
    }

    @Test
    void displayIsTwoDecimalRupees() {
        assertEquals("₹467.00", Money.ofPaise(46_700).display());
        assertEquals("₹537.60", Money.ofPaise(53_760).display());
        assertEquals("₹0.00", Money.zero().display());
        assertEquals("₹-5.00", Money.ofPaise(500).negate().display());
    }

    @Test
    void rupeesKeepTheirScaleSoReportsCanSumThem() {
        assertEquals(new BigDecimal("467.00"), Money.ofPaise(46_700).rupees());
    }

    @Test
    void overflowFailsInsteadOfWrapping() {
        assertThrows(ArithmeticException.class, () -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)));
        assertThrows(ArithmeticException.class, () -> Money.ofPaise(Long.MAX_VALUE).multipliedBy(2));
        assertThrows(ArithmeticException.class, () -> Money.ofPaise(Long.MIN_VALUE).negate());
    }

    @Test
    void theValueThatWouldOverflowOnNegationIsRejectedAtConstruction() {
        assertThrows(ArithmeticException.class, () -> Money.ofPaise(Long.MIN_VALUE));
    }

    @Test
    void negativeAmountsAreAllowedWhereTheLedgerNeedsThemAndRefusedWhereItDoesNot() {
        Money shortfall = Money.ofPaise(6_700).negate();
        assertTrue(shortfall.isNegative(), "shortfalls exist");
        assertThrows(ArithmeticException.class, () -> shortfall.requireNonNegative("order total"));
        assertEquals(shortfall.negate(), Money.ofPaise(6_700), "abs is the sign-stripping form");
        assertEquals(Money.ofPaise(6_700), shortfall.abs());
        assertEquals(Money.ofPaise(6_700), Money.ofPaise(6_700).abs());
    }

    @Test
    void orderingIsByPaiseNotByPresentation() {
        assertEquals(0, Money.ofRupees(new BigDecimal("10.00")).compareTo(Money.ofPaise(1_000)));
        assertEquals(-1, Integer.signum(Money.zero().compareTo(Money.ofPaise(1))));
        assertEquals(1, Integer.signum(Money.ofPaise(2).compareTo(Money.zero())));
        assertTrue(Money.ofPaise(5).isPositive(), "sign helpers agree with compareTo");
        assertTrue(Money.zero().isZero(), "zero is zero");
    }

    @Test
    void equalityIsValueEquality() {
        assertEquals(Money.ofRupees(new BigDecimal("467.00")), Money.ofPaise(46_700));
        assertEquals(Money.ofPaise(46_700).hashCode(), Money.ofRupees(new BigDecimal("467.00")).hashCode());
    }
}
