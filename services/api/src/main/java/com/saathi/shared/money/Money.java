package com.saathi.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Integer paise. The only representation of currency in this system.
 *
 * <p>PRD §10 and System Design §3: {@code double} and {@code float} are banned from the
 * money path by an architecture test, and every rounding decision is named here rather
 * than left to a caller.
 */
public record Money(long paise) implements Comparable<Money> {

    public static final String CURRENCY = "INR";
    private static final int PAISE_SCALE = 2;

    public Money {
        if (paise == Long.MIN_VALUE) {
            throw new ArithmeticException("Money cannot represent " + Long.MIN_VALUE + " paise: negation would overflow");
        }
    }

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    /** Rupees as written in a mock or a fixture, e.g. {@code 467.00}. Rounds HALF_UP to paise. */
    public static Money ofRupees(BigDecimal rupees) {
        Objects.requireNonNull(rupees, "rupees");
        return new Money(rupees.movePointRight(PAISE_SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    public static Money zero() {
        return new Money(0);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    public Money negate() {
        return new Money(Math.negateExact(paise));
    }

    public Money abs() {
        return paise < 0 ? negate() : this;
    }

    /** Exact scaling — for quantities, never for a rate. Use {@link #applyRate} for percentages. */
    public Money multipliedBy(int factor) {
        return new Money(Math.multiplyExact(paise, factor));
    }

    /**
     * §9.7 I-2: {@code line_paise = round(qty_charged x unit_price_paise)}. This is the only
     * place a non-integer factor enters the money path, and it is the by-weight line — 0.98 kg
     * of chicken. Rounding is HALF_UP to a whole paisa and happens once, per line.
     */
    public Money multipliedBy(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        long product = BigDecimal.valueOf(paise)
            .multiply(factor)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
        return new Money(product);
    }

    /** A percentage of this amount, rounded HALF_UP to a whole paisa. */
    public Money applyRate(Rate rate) {
        return rate.applyTo(this);
    }

    public Money requireNonNegative(String subject) {
        if (paise < 0) {
            throw new ArithmeticException(subject + " must not be negative, was " + display());
        }
        return this;
    }

    public boolean isNegative() {
        return paise < 0;
    }

    public boolean isZero() {
        return paise == 0;
    }

    public boolean isPositive() {
        return paise > 0;
    }

    public BigDecimal rupees() {
        return BigDecimal.valueOf(paise).movePointLeft(PAISE_SCALE);
    }

    /** Presentation form used by every API response, e.g. {@code ₹467.00}. */
    public String display() {
        return "₹" + rupees().setScale(PAISE_SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(paise, other.paise);
    }
}
