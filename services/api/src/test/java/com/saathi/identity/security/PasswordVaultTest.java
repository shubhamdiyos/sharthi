package com.saathi.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §12.2's password rule — at least twelve characters, Argon2id, no composition theatre — and the
 * {@code user_account_argon2_shape} CHECK that makes a bcrypt string or a plaintext password
 * uncommittable. Two things are protected here and both are testable without a database or a
 * request: the write path refuses what the design says it refuses, and the read path verifies what
 * the column says is a credential — including hashes this class did not write, which is the
 * explicit contract on {@code PasswordVault.matches}.
 *
 * <p>The negative vectors below were produced by BouncyCastle's Argon2 directly rather than through
 * Spring's encoder, so "a hash this class did not write still verifies" is a checked statement about
 * the standard PHC wire format, not about one library reading its own output.
 *
 * <p>§12.2's breach-list half is deliberately not asserted: the class javadoc records that it is
 * not implemented, and a test must not imply a control that does not exist.
 */
class PasswordVaultTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String MINIMUM = "aaaaaaaaaaaa";
    private static final String SHAPED =
        "^\\$argon2id\\$v=19\\$m=16384,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$";

    /** A valid Argon2id PHC string for PASSWORD, built by BouncyCastle with Spring's own parameters. */
    private static final String FOREIGN_HASH =
        "$argon2id$v=19$m=16384,t=2,p=1$AREhMUFRYXGBkaGxwdHh8Q$R+UcNXz/zmbQvGESeiSyyEyxrFe8Wyf1ofydvvr/vhQ";
    /** The same construction for "hunter2", which is below MIN_LENGTH: read-side-only material. */
    private static final String FOREIGN_SHORT_HASH =
        "$argon2id$v=19$m=16384,t=2,p=1$AREhMUFRYXGBkaGxwdHh8Q$O1PtpkntfZgXAKJbSgLwBC7gbAqUoYGGnodPzhmwXZ0";

    private final PasswordVault vault = new PasswordVault();

    @Test
    @DisplayName("twelve characters is the rule and it is the documented number")
    void theMinimumLengthIsTheDocumentedOne() {
        // §12.2 says "Passwords: >= 12 characters". This constant is the only place that number
        // lives, and it is the same one the field error's message is built from.
        assertThat(PasswordVault.MIN_LENGTH).isEqualTo(12);
    }

    @Test
    @DisplayName("a password shorter than twelve characters is a field error on password")
    void aShortPasswordIsRefused() {
        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(() -> vault.hash("aaaaaaaaaaa"))
            .satisfies(failure -> {
                assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                // 422: the request parsed and the value is simply too short, so it is a field error
                // and not the §10.2 code the enum flags as unassignable.
                assertThat(failure.code().statusCode()).isEqualTo(422);
                assertThat(failure.details()).isEqualTo(Map.of("field_errors", List.of(
                    Map.of("field", "password", "message", "Use at least 12 characters."))));
            });
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> vault.hash("hunter2"));
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> vault.hash(""));
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> vault.hash(null));
        // Eleven fails and twelve does not: the rule is length, applied at the boundary exactly.
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> vault.hash(MINIMUM.substring(0, 11)));
        assertThat(vault.hash(MINIMUM)).startsWith("$argon2id$v=");
    }

    @Test
    @DisplayName("length is the whole rule, so a composition-free password is accepted")
    void noCompositionTheatre() {
        // §12.2's "no composition theatre" is a promise that nothing else is enforced: twelve
        // lowercase letters and no digits is a valid password here, and refusing it would be the
        // weak-password rule the design declined to write.
        String hash = vault.hash(MINIMUM);
        assertThat(vault.matches(MINIMUM, hash)).isTrue();
        assertThat(vault.matches("aaaaaaaaaaab", hash)).isFalse();
    }

    @Test
    @DisplayName("the same password hashes differently every time")
    void hashingIsSaltedPerCall() {
        // Random salt per hash: two accounts that share a password must not share a column value,
        // or a leak of one row is a leak of both credentials and the work factor is paid once for
        // the whole table.
        assertThat(vault.hash(PASSWORD)).isNotEqualTo(vault.hash(PASSWORD));
        // Both still verify, which is the point: the difference is the salt, not a broken encode.
        assertThat(vault.matches(PASSWORD, vault.hash(PASSWORD))).isTrue();
        assertThat(vault.matches(PASSWORD, vault.hash(PASSWORD))).isTrue();
    }

    @Test
    @DisplayName("the hash is Argon2id in the form the column asserts, with the documented parameters")
    void theStoredShapeIsThePhcForm() {
        String hash = vault.hash(PASSWORD);
        // user_account_argon2_shape is CHECK (password_hash LIKE '$argon2id$v=%').
        assertThat(hash).startsWith("$argon2id$v=");
        assertThat(hash).matches(SHAPED);
        // The class javadoc's "Spring's current defaults (16 MiB, 2 iterations, 1 lane)" — a change
        // to these numbers changes every login's cost, so it has to show up here first.
        assertThat(hash).contains("$m=16384,t=2,p=1$");
        // §12.2's whole premise: a plaintext password cannot be committed by accident and still look
        // like a credential.
        assertThat(hash).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a bare argon2id hash verifies")
    void matchesAcceptsTheBarePhcForm() {
        assertThat(vault.matches(PASSWORD, vault.hash(PASSWORD))).isTrue();
    }

    @Test
    @DisplayName("a hash carrying the old id prefix verifies too")
    void matchesAcceptsThePrefixedForm() {
        // {argon2id} is what Spring's older delegating encoder wrote into a column. The class
        // javadoc says nothing here has to add or strip it; this is that claim checked instead of
        // assumed, because a row migrated from that encoder has to keep signing people in.
        assertThat(vault.matches(PASSWORD, "{argon2id}" + vault.hash(PASSWORD))).isTrue();
        assertThat(vault.matches("wrong password entirely", "{argon2id}" + vault.hash(PASSWORD))).isFalse();
    }

    @Test
    @DisplayName("a wrong password never matches")
    void matchesRejectsAWrongPassword() {
        String hash = vault.hash(PASSWORD);
        assertThat(vault.matches("wrong password entirely", hash)).isFalse();
        // Adjacent to the real one, so the assertion is about Argon2 and not about string length.
        assertThat(vault.matches(PASSWORD + "!", hash)).isFalse();
        assertThat(vault.matches(PASSWORD.substring(0, PASSWORD.length() - 1), hash)).isFalse();
        assertThat(vault.matches(PASSWORD.toUpperCase(), hash)).isFalse();
    }

    @Test
    @DisplayName("a stored hash this class did not write is still verified")
    void verifiesAHashFromOutsideTheClass() {
        // The vector was produced by BouncyCastle's Argon2 generator directly. So the value in the
        // column is the standard PHC encoding and not a library-private format — which is what makes
        // §12.2's hash auditable by any later module that reads the column.
        assertThat(vault.matches(PASSWORD, FOREIGN_HASH)).isTrue();
        assertThat(vault.matches("correct horse battery stapled", FOREIGN_HASH)).isFalse();
        // The prefixed form of the same foreign row verifies identically.
        assertThat(vault.matches(PASSWORD, "{argon2id}" + FOREIGN_HASH)).isTrue();
    }

    @Test
    @DisplayName("the read path is not gated by the write path's minimum length")
    void aLegacyShortPasswordStillVerifies() {
        // The other half of "a stored hash this class did not write is still verified": a row written
        // before the rule, or by another tool, must not become an account nobody can sign into.
        // Enforcing MIN_LENGTH on the read side would be a silent permanent lockout, which §12.8
        // explicitly refuses.
        assertThat(vault.matches("hunter2", FOREIGN_SHORT_HASH)).isTrue();
    }

    @Test
    @DisplayName("anything that is not an argon2id credential fails closed")
    void matchesFailsClosedOnSomethingThatIsNotAHash() {
        assertThat(vault.matches(PASSWORD, null)).isFalse();
        assertThat(vault.matches(null, vault.hash(PASSWORD))).isFalse();
        // A bcrypt string is what an accidental write of the wrong encoder would leave behind. It
        // fails, and it fails as false rather than as an exception escaping the login path.
        assertThat(vault.matches(PASSWORD, "$2a$10$abcdefghijklmnopqrstuv")).isFalse();
        assertThat(vault.matches(PASSWORD, "$argon2i$v=19$m=16384,t=2,p=1$AREhMUFRYXGBkaGxwdHh8Q$"
            + "R+UcNXz/zmbQvGESeiSyyEyxrFe8Wyf1ofydvvr/vhQ")).isFalse();
        assertThat(vault.matches(PASSWORD, PASSWORD)).isFalse();
        assertThat(vault.matches(PASSWORD, "")).isFalse();
    }
}
