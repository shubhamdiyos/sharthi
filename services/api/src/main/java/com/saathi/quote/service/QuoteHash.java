package com.saathi.quote.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * §8.1's {@code quote_hash}: SHA-256 over the exact lines, fees, total, currency, mode and
 * {@code issued_at}.
 *
 * <p>The hash is what {@code POST /orders} compares. Recomputing "the current quote" therefore
 * uses the <em>stored</em> {@code issued_at}: a fresh timestamp would make every recomputation a
 * different hash and turn every placement into a price change. The canonical form is a line-oriented
 * text rather than JSON so that key order, whitespace and number formatting cannot make two
 * identical quotes hash differently.
 */
public final class QuoteHash {

    public static final String PREFIX = "sha256:";

    private QuoteHash() {
    }

    /** One line's canonical contribution; {@code qty} is the charged quantity. */
    public record Line(String productId, BigDecimal qty, long unitPricePaise, long linePaise) {
    }

    public static String compute(Instant issuedAt, String mode, long goodsPaise, long deliveryFeePaise,
                                 long handlingFeePaise, long totalPaise, List<Line> lines) {
        StringBuilder canonical = new StringBuilder(160 + lines.size() * 80);
        canonical.append("v1\n")
            .append(issuedAt.toEpochMilli()).append('\n')
            .append(mode).append('\n')
            .append(goodsPaise).append('\n')
            .append(deliveryFeePaise).append('\n')
            .append(handlingFeePaise).append('\n')
            .append(totalPaise).append('\n')
            .append("INR\n");
        lines.stream()
            .sorted(Comparator.comparing(Line::productId))
            .forEach(line -> canonical.append(line.productId()).append('|')
                .append(line.qty().stripTrailingZeros().toPlainString()).append('|')
                .append(line.unitPricePaise()).append('|')
                .append(line.linePaise()).append('\n'));
        return sha256(canonical.toString());
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }

    /** The wire form, and the form a client echoes back. A bare digest is accepted by {@link #bare}. */
    public static String display(String hex) {
        return PREFIX + hex;
    }

    public static String bare(String displayedOrHex) {
        if (displayedOrHex == null) {
            return "";
        }
        String trimmed = displayedOrHex.trim();
        return trimmed.startsWith(PREFIX) ? trimmed.substring(PREFIX.length()) : trimmed;
    }

    /** Tests and logs can show which fields were hashed without re-implementing the format. */
    public static String describe(Instant issuedAt, String mode, List<Line> lines) {
        return lines.stream().map(line -> line.productId() + ":" + line.qty().toPlainString())
            .collect(Collectors.joining(",", "issued=" + issuedAt + ";mode=" + mode + ";", ""));
    }
}
