package com.saathi.shared.money;

/**
 * §10.4's Money object as it goes over the wire.
 *
 * <p>{@code Money} itself serialises as one number, which would make every price on the API a bare
 * integer a client has to know is paise and has to format itself. §10.4's shape is the answer to
 * that: {@code paise} stays the authoritative value, {@code display} is what a screen prints, and
 * {@code approx} is how a by-weight line says "this is an estimate of 1 kg, not a claim about the
 * 0.94 kg that will actually arrive".
 *
 * <p>Field order matters less than field names: the wire is snake_case, so this serialises as
 * {@code paise}, {@code currency}, {@code display}, {@code approx}.
 */
public record MoneyView(long paise, String currency, String display, boolean approx) {

    public static MoneyView of(Money money) {
        return of(money, false);
    }

    /** An amount a customer is told is not final — the by-weight line, never a settled total. */
    public static MoneyView of(Money money, boolean approximate) {
        return new MoneyView(money.paise(), Money.CURRENCY, money.display(), approximate);
    }
}
