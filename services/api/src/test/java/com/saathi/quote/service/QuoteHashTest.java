package com.saathi.quote.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §8.1's hash: the same quote always hashes the same, and every field the customer consented to
 * changes it. A hash that ignored the mode or the fees would let a placement accept a total the
 * customer never saw.
 */
class QuoteHashTest {

    private static final Instant ISSUED = Instant.parse("2026-10-30T10:15:30.123Z");
    private static final String MODE = "PLATFORM";

    private static List<QuoteHash.Line> lines() {
        return List.of(
            new QuoteHash.Line("01WEIGHT", new BigDecimal("0.985"), 47000, 46295),
            new QuoteHash.Line("01SALT", new BigDecimal("2"), 2800, 5600));
    }

    private static String hash(Instant issuedAt, String mode, long delivery, long handling,
                               List<QuoteHash.Line> lines) {
        long goods = lines.stream().mapToLong(QuoteHash.Line::linePaise).sum();
        return QuoteHash.compute(issuedAt, mode, goods, delivery, handling, goods + delivery + handling,
            lines);
    }

    @Test
    @DisplayName("line order on the wire does not change the digest")
    void lineOrderIsCanonicalised() {
        List<QuoteHash.Line> reversed = List.of(lines().get(1), lines().get(0));

        assertThat(hash(ISSUED, MODE, 2500, 1000, reversed))
            .isEqualTo(hash(ISSUED, MODE, 2500, 1000, lines()));
    }

    @Test
    @DisplayName("issued_at, mode, fees and quantities are all part of what was consented to")
    void everyConsentedFieldChangesTheDigest() {
        String base = hash(ISSUED, MODE, 2500, 1000, lines());

        assertThat(hash(ISSUED.plusMillis(1), MODE, 2500, 1000, lines())).isNotEqualTo(base);
        assertThat(hash(ISSUED, "MERCHANT", 2500, 1000, lines())).isNotEqualTo(base);
        assertThat(hash(ISSUED, MODE, 2600, 1000, lines())).isNotEqualTo(base);
        assertThat(hash(ISSUED, MODE, 2500, 1100, lines())).isNotEqualTo(base);
        assertThat(hash(ISSUED, MODE, 2500, 1000, List.of(
            new QuoteHash.Line("01WEIGHT", new BigDecimal("0.990"), 47000, 46530),
            new QuoteHash.Line("01SALT", new BigDecimal("2"), 2800, 5600))))
            .isNotEqualTo(base);
    }

    @Test
    @DisplayName("a quantity is canonicalised, so 2.000 and 2 are one line")
    void quantityTrailingZerosAreNotAFakeChange() {
        String canonical = hash(ISSUED, MODE, 2500, 1000, List.of(
            new QuoteHash.Line("01SALT", new BigDecimal("2"), 2800, 5600)));
        String padded = hash(ISSUED, MODE, 2500, 1000, List.of(
            new QuoteHash.Line("01SALT", new BigDecimal("2.000"), 2800, 5600)));

        assertThat(padded).isEqualTo(canonical);
    }

    @Test
    @DisplayName("the wire form carries sha256: and the bare form is what the database stores")
    void displayAndBareRoundTrip() {
        String hex = hash(ISSUED, MODE, 2500, 1000, lines());

        assertThat(hex).matches("^[0-9a-f]{64}$");
        assertThat(QuoteHash.display(hex)).isEqualTo("sha256:" + hex);
        assertThat(QuoteHash.bare("sha256:" + hex)).isEqualTo(hex);
        assertThat(QuoteHash.bare(hex)).isEqualTo(hex);
        assertThat(QuoteHash.bare("  sha256:" + hex + " ")).isEqualTo(hex);
        assertThat(QuoteHash.bare(null)).isEmpty();
    }
}
