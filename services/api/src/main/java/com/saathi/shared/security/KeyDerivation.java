package com.saathi.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Every signing key this platform uses is derived from the one configured secret
 * ({@code saathi.session-secret}) with HKDF-SHA256, RFC 5869 §2.2-2.3.
 *
 * <p>Why derive instead of sharing: the OTP target hash, the OTP code hash, the session cookie
 * MAC and the CSRF token would otherwise all be keyed by the same secret, so a leak of any one
 * of them — and the target hashes are the column most likely to end up in a support tool — is a
 * leak of all of them, including the ability to mint sessions. HKDF makes each label's key
 * independent of the others' while keeping the deployment to a single secret, which is what
 * §2.2's "one secret per process" and §12.4's cookie design both assume.
 *
 * <p>Salt and info are both the label. The label is public and stable — it is code, not a
 * secret — and using it in both positions means a derived key is bound to its purpose without
 * anyone having to generate and store a second per-purpose secret.
 */
public final class KeyDerivation {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] EMPTY = new byte[0];

    /** OTP lookup key for a normalised phone (§12.8: the column is a hash, never the number). */
    public static final String OTP_TARGET = "otp-target";
    /** OTP verification key for the six digits. */
    public static final String OTP_CODE = "otp-code";
    /** Session cookie MAC (§12.4: the row, not the cookie, decides access). */
    public static final String SESSION_TOKEN = "session-token";
    /** CSRF token bound to a session (§12.4's synchroniser pattern). */
    public static final String CSRF_TOKEN = "csrf-token";

    private KeyDerivation() {
    }

    /** {@code PRK = HMAC(salt=label, IKM=master)} then {@code OKM = HMAC(PRK, label || 0x01)}. */
    public static byte[] subkey(String masterSecret, String label) {
        require(masterSecret, "masterSecret");
        require(label, "label");
        byte[] prk = mac(label.getBytes(StandardCharsets.UTF_8), masterSecret.getBytes(StandardCharsets.UTF_8));
        byte[] info = label.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[info.length + 1];
        System.arraycopy(info, 0, input, 0, info.length);
        input[info.length] = 0x01;
        return mac(prk, input);
    }

    /** Lower-case hex, the same form {@code IpHasher} and the {@code char(64)} columns use. */
    public static String hmacHex(byte[] key, String message) {
        return HexFormat.of().formatHex(mac(key, message.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Constant-time comparison of a recomputed MAC against the value that arrived.
     *
     * <p>{@link MessageDigest#isEqual} is the JDK's own constant-time compare; a {@code String.equals}
     * here would answer "wrong at character 1" faster than "wrong at character 63", which is
     * enough to forge a cookie one character at a time.
     */
    public static boolean matches(byte[] key, String message, String expectedHex) {
        if (expectedHex == null) {
            return false;
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(expectedHex);
        } catch (IllegalArgumentException e) {
            // Not hex at all: still compared, so the time does not depend on the caller's input.
            expected = EMPTY;
        }
        return MessageDigest.isEqual(mac(key, message.getBytes(StandardCharsets.UTF_8)), expected);
    }

    private static byte[] mac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("Unable to derive a signing key", e);
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
