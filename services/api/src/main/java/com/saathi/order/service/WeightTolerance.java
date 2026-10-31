package com.saathi.order.service;

import com.saathi.tenancy.domain.Tenant;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * §7.2's weight tolerance, in the one place both of its uses can read it.
 *
 * <p>A by-weight line reserves more than the customer asked for, because the shop has to be allowed
 * to put a little more in the bag than the estimate before the goods are actually gone. That same
 * ceiling is then the gate at weighing: a bag over it is not a price the customer consented to and
 * is not stock the platform holds, so §7.2 refuses the silent oversell rather than re-pricing it.
 * Two numbers that must agree are one function.
 *
 * <p><b>{@code weight_tolerance_bp} is the band over the estimate, not the multiple of it.</b> §7.2
 * words the result as "125% of the estimate" while {@code tenant_weight_tolerance} caps the column at
 * 10000 bp, and the pilot seed stores 250 — a reading of the column as a multiple makes the cap
 * reject the documented default and the seed hold 2.5% of the quantity the customer asked for. As a
 * band, both are coherent: the ceiling is {@code qty × (1 + bp/10000)}, the seed allows 2.5% over,
 * and a city that stores nothing gets §7.2's 125%.
 *
 * <p>Rounding is to three decimals, ceiling, because the ceiling is a promise of stock and rounding
 * down would promise less than the tolerance allows; the column is {@code numeric(10,3)}.
 */
public final class WeightTolerance {

    /** §7.2's default band: the 25% that makes an unset city mean "up to 125% of the estimate". */
    public static final int DEFAULT_BASIS_POINTS = 2_500;

    private static final BigDecimal FULL = BigDecimal.valueOf(10_000);

    private WeightTolerance() {
    }

    public static int basisPoints(Tenant tenant) {
        Integer configured = tenant.getWeightToleranceBp();
        return configured == null ? DEFAULT_BASIS_POINTS : configured;
    }

    /** The most a by-weight line may be weighed — and the quantity its reservation was sized for. */
    public static BigDecimal ceilingFor(BigDecimal qtyOrdered, int basisPoints) {
        return qtyOrdered.multiply(FULL.add(BigDecimal.valueOf(basisPoints)))
            .divide(FULL, 3, RoundingMode.CEILING);
    }

    /** Whether the shop's bag is inside the tolerance the platform reserved room for. */
    public static boolean isWithin(BigDecimal qtyOrdered, BigDecimal qtyWeighed, int basisPoints) {
        return qtyWeighed.compareTo(ceilingFor(qtyOrdered, basisPoints)) <= 0;
    }
}
