package com.saathi.shared.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A rate in basis points: 1 bp = 0.01%, so 12% is {@code 1200}.
 *
 * <p>Design §3 keeps rates as integers because a rate stored as a double is a rate that
 * drifts. Every rule row in {@code commission_rule} / {@code ward_fee_rule} carries one of
 * these, and the value that mattered is frozen onto the order as {@code commission_bp_snapshot}.
 */
public record Rate(int basisPoints) {

    public static final int MAX_BASIS_POINTS = 10_000;
    private static final BigDecimal BASIS_POINTS_PER_PERCENT = BigDecimal.valueOf(100);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Rate {
        if (basisPoints < 0 || basisPoints > MAX_BASIS_POINTS) {
            throw new IllegalArgumentException(
                "basisPoints must be within 0.." + MAX_BASIS_POINTS + " (0-100%), was " + basisPoints);
        }
    }

    public static Rate ofBasisPoints(int basisPoints) {
        return new Rate(basisPoints);
    }

    /** {@code Rate.ofPercent("12")} is 1200 bp. Rounds HALF_UP; a 0.005% remainder is not representable. */
    public static Rate ofPercent(BigDecimal percent) {
        Objects.requireNonNull(percent, "percent");
        int bp = percent.multiply(BASIS_POINTS_PER_PERCENT).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return new Rate(bp);
    }

    public BigDecimal percent() {
        return BigDecimal.valueOf(basisPoints).divide(BASIS_POINTS_PER_PERCENT, MathContext.DECIMAL128);
    }

    /** {@code amount * bp / 10000}, rounded HALF_UP to a whole paisa. */
    public Money applyTo(Money amount) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal product = BigDecimal.valueOf(amount.paise()).multiply(BigDecimal.valueOf(basisPoints));
        long paise = product.divide(BigDecimal.valueOf(MAX_BASIS_POINTS), 0, RoundingMode.HALF_UP).longValueExact();
        return Money.ofPaise(paise);
    }

    public boolean isZero() {
        return basisPoints == 0;
    }

    public String display() {
        return percent().stripTrailingZeros().toPlainString() + "%";
    }
}
