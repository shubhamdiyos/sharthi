package com.saathi.identity.security;

import com.saathi.shared.config.SaathiProperties;
import com.saathi.shared.security.KeyDerivation;
import org.springframework.stereotype.Component;

/**
 * The four keys identity signs with, each one an HKDF subkey of the single configured secret.
 *
 * <p>They exist as named methods so that "which key signs this?" is a call site rather than a
 * convention. The alternative — one HMAC used for OTP targets, codes, session cookies and CSRF
 * tokens — is a deployment with one secret and therefore looks like it has one key, when the whole
 * point of {@link KeyDerivation} is that a leaked {@code otp_challenge.target_hash} table does not
 * let anybody mint a session.
 *
 * <p>Derived once at startup: HKDF over a secret that does not change while the process is running,
 * so recomputing it per request would be a per-request cost for a per-deployment fact.
 */
@Component
public class IdentityKeys {

    private final byte[] otpTarget;
    private final byte[] otpCode;
    private final byte[] sessionToken;
    private final byte[] csrfToken;

    public IdentityKeys(SaathiProperties properties) {
        String master = properties.sessionSecret();
        this.otpTarget = KeyDerivation.subkey(master, KeyDerivation.OTP_TARGET);
        this.otpCode = KeyDerivation.subkey(master, KeyDerivation.OTP_CODE);
        this.sessionToken = KeyDerivation.subkey(master, KeyDerivation.SESSION_TOKEN);
        this.csrfToken = KeyDerivation.subkey(master, KeyDerivation.CSRF_TOKEN);
    }

    /**
     * The lookup and throttle key for a phone (§12.8). Deterministic, because the same person must
     * find their own open challenge — and keyed, because ten digits is a small enough space to
     * enumerate if the hash is not.
     */
    public String otpTargetHash(String e164) {
        return KeyDerivation.hmacHex(otpTarget, e164);
    }

    /**
     * Bound to the challenge id, not just the six digits. A hash of the digits alone would be one
     * value per code, so a leaked table would be a precomputed map of the whole 10^6 space that
     * applies to every row in it.
     */
    public String otpCodeHash(String challengeId, String code) {
        return KeyDerivation.hmacHex(otpCode, challengeId + "|" + code);
    }

    /**
     * Constant-time, because this is the comparison that decides a login. A
     * {@code String.equals} on a hash returns as soon as a character differs, and six digits with
     * a per-attempt oracle is a small enough space to make that difference worth measuring.
     */
    public boolean otpCodeMatches(String challengeId, String code, String storedHash) {
        return KeyDerivation.matches(otpCode, challengeId + "|" + code, storedHash);
    }

    public String sessionMac(String sessionId) {
        return KeyDerivation.hmacHex(sessionToken, sessionId);
    }

    public boolean sessionMacMatches(String sessionId, String mac) {
        return KeyDerivation.matches(sessionToken, sessionId, mac);
    }

    /** A CSRF token is a session MAC under a different key, so it cannot be replayed as one. */
    public String csrfToken(String sessionId) {
        return KeyDerivation.hmacHex(csrfToken, sessionId);
    }

    public boolean csrfTokenMatches(String sessionId, String token) {
        return KeyDerivation.matches(csrfToken, sessionId, token);
    }

}