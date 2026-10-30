package com.saathi.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

/**
 * ULID: 26 Crockford-base32 characters, 48-bit millisecond timestamp followed by 80 random
 * bits, so identifiers sort by creation time and can be keyset-paginated without an index
 * on a separate column.
 *
 * <p>Design §4.3-6: ULIDs are internal primary keys. The customer-facing identifier is the
 * opaque public code ({@code SAATH-…}); these never appear in a URL or a response body.
 */
public final class Ulid {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int[] DECODING = new int[128];
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TIMESTAMP_BITS = 48;
    private static final int RANDOM_BITS = 80;
    private static final int RANDOM_BYTES = RANDOM_BITS / Byte.SIZE;
    private static final int TIMESTAMP_CHARS = 10;
    private static final int RANDOM_CHARS = 16;
    /** A ULID is always 26 characters, so callers that parse one can check the length first. */
    public static final int LENGTH = TIMESTAMP_CHARS + RANDOM_CHARS;

    static {
        java.util.Arrays.fill(DECODING, -1);
        for (int i = 0; i < ENCODING.length; i++) {
            DECODING[ENCODING[i]] = i;
        }
    }

    private Ulid() {
    }

    /**
     * There is deliberately no no-arg overload: an id generator that reads the wall clock itself
     * would put a second clock source outside {@code ClockConfig}, and the ULID's timestamp is a
     * fact the audit trail relies on ({@link #instant(String)}), not an implementation detail.
     */
    public static String next(Clock clock) {
        byte[] randomness = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(randomness);
        return encode(clock.millis(), randomness);
    }

    /** Millisecond timestamp carried by the id; {@link java.time.Instant} for audit and log joins. */
    public static Instant instant(String ulid) {
        return Instant.ofEpochMilli(decodeTimestamp(ulid));
    }

    /**
     * Canonical form only: 26 uppercase Crockford characters. Lower-case is rejected because
     * {@code audit_event}'s CHECK rejects it too, and a second spelling of the same id would
     * break both the sort order and the "same key means the same row" assumption.
     */
    public static boolean isValid(String ulid) {
        if (ulid == null || ulid.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = ulid.charAt(i);
            // I, L, O and U are not in Crockford's alphabet, so they never reach DECODING.
            if (c >= DECODING.length || DECODING[c] < 0) {
                return false;
            }
        }
        return true;
    }

    private static String encode(long timestampMillis, byte[] randomness) {
        if (timestampMillis < 0 || timestampMillis >= 1L << TIMESTAMP_BITS) {
            throw new IllegalArgumentException(
                "timestamp must fit 48 bits of milliseconds, was " + timestampMillis);
        }
        char[] out = new char[LENGTH];
        // The timestamp is 48 bits written as 10 base-32 digits, most significant first, so
        // the leading digit is always 0-7 and the whole string sorts by creation time.
        for (int i = 0; i < TIMESTAMP_CHARS; i++) {
            int shift = 5 * (TIMESTAMP_CHARS - 1 - i);
            out[i] = ENCODING[(int) ((timestampMillis >>> shift) & 31)];
        }
        for (int i = 0; i < RANDOM_CHARS; i++) {
            out[TIMESTAMP_CHARS + i] = ENCODING[readBits(randomness, i * 5, 5)];
        }
        return new String(out);
    }

    private static long decodeTimestamp(String ulid) {
        if (!isValid(ulid)) {
            throw new IllegalArgumentException("not a ULID: " + ulid);
        }
        long value = 0;
        for (int i = 0; i < TIMESTAMP_CHARS; i++) {
            value = (value << 5) | DECODING[ulid.charAt(i)];
        }
        return value;
    }

    private static int readBits(byte[] source, int bitOffset, int bitCount) {
        int value = 0;
        for (int i = 0; i < bitCount; i++) {
            int bit = bitOffset + i;
            int byteIndex = bit >>> 3;
            int bitInByte = 7 - (bit & 7);
            value = (value << 1) | ((source[byteIndex] >>> bitInByte) & 1);
        }
        return value;
    }
}
