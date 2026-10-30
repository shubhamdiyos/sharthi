package com.saathi.identity.security;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id, in the one form the database will accept.
 *
 * <p>{@code user_account_argon2_shape} rejects any stored hash that does not begin
 * {@code $argon2id$v=}, so a bcrypt string or a plaintext password cannot be committed by accident
 * and still look like a credential. Verified against the shipped encoder rather than assumed:
 * Spring Security 7.1.2 writes the bare PHC form, which is why nothing here has to add or strip the
 * {@code {argon2id}} id prefix the older delegating encoder used — and why a hash written by this
 * class inserts cleanly into the column every later module will read.
 *
 * <p>Parameters are Spring's current defaults (16 MiB, 2 iterations, 1 lane), which are §12.2's
 * "Argon2id" with the numbers chosen by people who benchmark it for a living.
 */
@Component
public class PasswordVault {

    /** §12.2: length is the rule. No composition theatre. */
    static final int MIN_LENGTH = 12;

    private static final Argon2PasswordEncoder ENCODER = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String hash(String rawPassword) {
        requireAcceptable(rawPassword);
        return ENCODER.encode(rawPassword);
    }

    /** A stored hash this class did not write is still verified, because the CHECK is the contract. */
    public boolean matches(String rawPassword, String storedHash) {
        return storedHash != null && ENCODER.matches(rawPassword, storedHash);
    }

    /**
     * The breach-list half of §12.2 is not here: it needs a corpus, and sending a password a user has
     * just typed to an outside service is a disclosure decision the founder has not made. Raised
     * rather than quietly replaced by a length check.
     */
    private static void requireAcceptable(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "password",
                    "message", "Use at least " + MIN_LENGTH + " characters."))));
        }
    }
}
