package com.saathi.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §12.4's cookie design and §12.8's hashed OTP targets both rest on one deployment secret fanning
 * out into four independent keys (HKDF-SHA256, RFC 5869 §2.2-2.3). What is under test here is the
 * <em>independence</em>, not the arithmetic: if two labels derived the same bytes, or if a derived
 * key were the secret itself, then {@code otp_challenge.target_hash} — the column most likely to
 * end up in a support tool — would also be a key that mints sessions, which is the whole thing the
 * derivation exists to prevent.
 *
 * <p>No Spring context is needed: {@link KeyDerivation} is a pure function of its two arguments,
 * and the four keys are only ever reached through it ({@code IdentityKeys}, {@code RequestTags}).
 * The known-answer tests are computed independently of this codebase, the same way
 * {@code IpHasherTest}'s are, because rows already written are keyed by this exact construction and
 * cannot be recomputed after the fact.
 */
class KeyDerivationTest {

    private static final String MASTER = "session-secret-for-tests-only";
    private static final String ROTATED = "rotated-session-secret-for-tests-only";
    private static final String E164 = "+919800003421";

    /** hex() is the printable form of the same bytes the columns store, so failures read as data. */
    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    @DisplayName("the same secret and the same label always derive the same key")
    void derivingIsDeterministic() {
        // IdentityKeys derives once at startup and RequestTags derives once per day; both rely on the
        // derivation being a fact of the deployment rather than a per-call coin flip, or a phone
        // hashed on an OTP request would not be found by the verify that follows it.
        assertThat(hex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_TARGET)))
            .isEqualTo(hex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_TARGET)));
    }

    @Test
    @DisplayName("each purpose gets its own key even though all four come from one secret")
    void everyLabelDerivesADifferentKey() {
        Set<String> keys = new HashSet<>();
        keys.add(hex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_TARGET)));
        keys.add(hex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_CODE)));
        keys.add(hex(KeyDerivation.subkey(MASTER, KeyDerivation.SESSION_TOKEN)));
        keys.add(hex(KeyDerivation.subkey(MASTER, KeyDerivation.CSRF_TOKEN)));
        // Four labels, four distinct keys: this assertion is the entire security argument of the
        // class javadoc. A collision would make a leaked target hash a leaked session key.
        assertThat(keys).as("the four signing purposes must not share a key").hasSize(4);
    }

    @Test
    @DisplayName("rotating the secret rotates every derived key")
    void aDifferentSecretDerivesDifferentKeys() {
        assertThat(hex(KeyDerivation.subkey(ROTATED, KeyDerivation.SESSION_TOKEN)))
            .isNotEqualTo(hex(KeyDerivation.subkey(MASTER, KeyDerivation.SESSION_TOKEN)));
        // And it rotates all of them at once, which is what §2.2's single-secret deployment means:
        // one value in the platform store is the whole key hierarchy.
        assertThat(hex(KeyDerivation.subkey(ROTATED, KeyDerivation.OTP_TARGET)))
            .isNotEqualTo(hex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_TARGET)));
    }

    @Test
    @DisplayName("a subkey is thirty-two bytes and is not the secret it came from")
    void aSubkeyIsNotTheMasterSecret() {
        byte[] subkey = KeyDerivation.subkey(MASTER, KeyDerivation.SESSION_TOKEN);
        // One HMAC-SHA256 block, which is the length hmacHex's char(64) columns are written for.
        assertThat(subkey).hasSize(32);
        byte[] master = MASTER.getBytes(StandardCharsets.UTF_8);
        assertThat(subkey).isNotEqualTo(master);
        // If the derived key equalled the secret, the derivation would be decoration. Compared as
        // MACs rather than as bytes so a reader sees the consequence, not just the difference.
        assertThat(KeyDerivation.hmacHex(subkey, E164))
            .isNotEqualTo(KeyDerivation.hmacHex(master, E164));
        assertThat(hex(subkey)).doesNotContain(hex(master));
    }

    @Test
    @DisplayName("the derivation matches HKDF as computed independently of this codebase")
    void theConstructionIsLocked() {
        // Computed outside Java (python hmac: PRK = HMAC(label, master), OKM = HMAC(PRK, label ||
        // 0x01)) and pinned here, so changing the algorithm, the label placement or the encoding is
        // a deliberate decision. Sessions and OTP rows already in a database are keyed by exactly
        // these four values and cannot be re-derived after a silent change.
        assertThat(hex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_TARGET)))
            .isEqualTo("02f8324afb97159ad9bd6b4e5b58e325f51689f1be3fb1b3d1e9b43637a90c46");
        assertThat(hex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_CODE)))
            .isEqualTo("b91e351f782d8a9193b791794e99b1dc93fa7484b38953df67d5fa469b20d63e");
        assertThat(hex(KeyDerivation.subkey(MASTER, KeyDerivation.SESSION_TOKEN)))
            .isEqualTo("6f726cb956f94a705052462b5fe59402768c25b3accaf7bc02b298798c74d708");
        assertThat(hex(KeyDerivation.subkey(MASTER, KeyDerivation.CSRF_TOKEN)))
            .isEqualTo("668723ee0f57641a50b1afd2c3495fbf76ee8525783095a252b5d852c3c6a48c");
    }

    @Test
    @DisplayName("the labels the kernel publishes are the four documented purposes")
    void theLabelConstantsAreTheWireContract() {
        // A label is not an internal name: it is baked into every derived key, so renaming one
        // silently invalidates every stored target hash, code hash, cookie MAC and CSRF token.
        assertThat(KeyDerivation.OTP_TARGET).isEqualTo("otp-target");
        assertThat(KeyDerivation.OTP_CODE).isEqualTo("otp-code");
        assertThat(KeyDerivation.SESSION_TOKEN).isEqualTo("session-token");
        assertThat(KeyDerivation.CSRF_TOKEN).isEqualTo("csrf-token");
    }

    @Test
    @DisplayName("a mac is lowercase hex in the shape the char(64) columns assert")
    void theEncodingIsTheStoredShape() {
        String digest = KeyDerivation.hmacHex(KeyDerivation.subkey(MASTER, KeyDerivation.OTP_TARGET), E164);
        assertThat(digest).hasSize(64);
        // otp_challenge's CHECK is exactly this regex; a digest that does not fit it fails at the
        // database rather than at the boundary that produced it.
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(digest).isEqualTo("fe6d2f647b98676d63d3a7cf9fe82620161cdb71c305829ef26d62ccdb87f0a8");
    }

    @Test
    @DisplayName("a mac covers the whole message and the key it was taken under")
    void aMacIsBoundToItsMessageAndItsKey() {
        byte[] key = KeyDerivation.subkey(MASTER, KeyDerivation.OTP_CODE);
        assertThat(KeyDerivation.hmacHex(key, "01ARZ|123456"))
            .isEqualTo(KeyDerivation.hmacHex(key, "01ARZ|123456"))
            .isNotEqualTo(KeyDerivation.hmacHex(key, "01ARZ|123457"));
        // IdentityKeys concatenates challengeId + "|" + code. The delimiter has to stay where the
        // caller put it, or ("1","23") and ("12","3") would be one digest and one code would be
        // replayable against another challenge.
        assertThat(KeyDerivation.hmacHex(key, "1|23")).isNotEqualTo(KeyDerivation.hmacHex(key, "12|3"));
        // Same message, different purpose: this is why a CSRF token cannot be replayed as a session
        // MAC even though both are built over a session id.
        assertThat(KeyDerivation.hmacHex(KeyDerivation.subkey(MASTER, KeyDerivation.SESSION_TOKEN), "01ARZ"))
            .isNotEqualTo(KeyDerivation.hmacHex(KeyDerivation.subkey(MASTER, KeyDerivation.CSRF_TOKEN), "01ARZ"));
    }

    @Test
    @DisplayName("matches accepts only the digest that this key and this message produce")
    void matchesVerifiesTheRecomputedDigest() {
        byte[] sessionKey = KeyDerivation.subkey(MASTER, KeyDerivation.SESSION_TOKEN);
        String good = KeyDerivation.hmacHex(sessionKey, "01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertThat(KeyDerivation.matches(sessionKey, "01ARZ3NDEKTSV4RRFFQ69G5FAV", good)).isTrue();
        // A different session id under the same key, and the same session id under a different key:
        // both are the cookie an attacker would try to forge.
        assertThat(KeyDerivation.matches(sessionKey, "01ARZ3NDEKTSV4RRFFQ69G5FAW", good)).isFalse();
        byte[] csrfKey = KeyDerivation.subkey(MASTER, KeyDerivation.CSRF_TOKEN);
        assertThat(KeyDerivation.matches(csrfKey, "01ARZ3NDEKTSV4RRFFQ69G5FAV", good)).isFalse();
    }

    @Test
    @DisplayName("matches fails closed on anything that is not a digest, without throwing")
    void matchesFailsClosedOnMalformedInput() {
        byte[] key = KeyDerivation.subkey(MASTER, KeyDerivation.SESSION_TOKEN);
        String good = KeyDerivation.hmacHex(key, "session-id");
        assertThat(KeyDerivation.matches(key, "session-id", null)).isFalse();
        assertThat(KeyDerivation.matches(key, "session-id", "")).isFalse();
        assertThat(KeyDerivation.matches(key, "session-id", "not-hex-at-all")).isFalse();
        assertThat(KeyDerivation.matches(key, "session-id", good.substring(1))).isFalse();
        assertThat(KeyDerivation.matches(key, "session-id", good.substring(0, 63))).isFalse();
        // A correctly shaped digest of something else is still just wrong.
        assertThat(KeyDerivation.matches(key, "session-id", KeyDerivation.hmacHex(key, "other-session")))
            .isFalse();
        assertThat(good).isNotEqualTo(KeyDerivation.hmacHex(key, "other-session"));
    }

    @Test
    @DisplayName("a blank secret or label is refused before it is used as a key")
    void blankInputsAreRefused() {
        // SaathiProperties already @NotBlanks both secrets at boot; this is the second gate, for a
        // caller that builds a subkey out of a value it read itself. It throws IllegalArgumentException
        // rather than ApiException because it is a programming fault, not a client's field error.
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> KeyDerivation.subkey(null, KeyDerivation.OTP_TARGET))
            .withMessage("masterSecret must not be blank");
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> KeyDerivation.subkey("  ", KeyDerivation.OTP_TARGET))
            .withMessage("masterSecret must not be blank");
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> KeyDerivation.subkey(MASTER, null))
            .withMessage("label must not be blank");
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> KeyDerivation.subkey(MASTER, ""))
            .withMessage("label must not be blank");
    }
}
