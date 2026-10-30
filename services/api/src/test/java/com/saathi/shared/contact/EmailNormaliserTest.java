package com.saathi.shared.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §12.1: staff sign in with an email, so this value is the one a lookup runs on. The class javadoc
 * ties it to two columns — {@code user_account_email_lower} (which makes a mixed-case address
 * unfindable) and {@code user_account_email_shape} — and the promise is that a request the database
 * would have refused fails one layer earlier with a field error a client can show. That promise is
 * checked here against the migration's own pattern, written out again below as an independent
 * oracle: if the two ever disagree, the test says so rather than trusting either alone.
 *
 * <p>Masking is §11.4's rule that a contact in a response or a log is never the full one. The
 * mailbox is the secret and the domain is not, so the assertions go the other way round from what
 * one might expect: the domain must survive, the local part must not.
 */
class EmailNormaliserTest {

    private static final String STORED = "meena@saathi.example";

    /**
     * V2__identity_and_tenancy.sql's {@code user_account_email_shape}, copied deliberately: the
     * normaliser's javadoc claims this is the same pattern, and a copy is how that claim stays true.
     */
    private static final String MIGRATION_SHAPE = "^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$";

    @Test
    @DisplayName("the stored form is lowercase because the unique index is exact")
    void theAddressIsLowerCased() {
        // The class javadoc's own example: a stored "Meena@Saathi.Example" would be found by nobody
        // and answered "invalid credentials" to the person who owns the account.
        assertThat(EmailNormaliser.normalise("Meena@Saathi.Example")).contains(STORED);
        assertThat(EmailNormaliser.normalise("MEENA@SAATHI.EXAMPLE")).contains(STORED);
    }

    @Test
    @DisplayName("whitespace around an address is not part of it")
    void surroundingWhitespaceIsTrimmed() {
        assertThat(EmailNormaliser.normalise("  " + STORED + "  ")).contains(STORED);
        assertThat(EmailNormaliser.normalise("\tMeena@Saathi.Example\n")).contains(STORED);
    }

    @Test
    @DisplayName("normalising an address that is already normalised changes nothing")
    void normalisingIsIdempotent() {
        // PasswordVerifier normalises the login address and then looks the row up, which was itself
        // normalised on the way in; a second pass that moved the value would break every sign-in.
        for (String raw : List.of("Meena@Saathi.Example", " meena@saathi.example ", "meena@saathi.co.in")) {
            String once = EmailNormaliser.normalise(raw).orElseThrow();
            assertThat(EmailNormaliser.normalise(once)).as(raw).contains(once);
        }
    }

    @Test
    @DisplayName("everything the normaliser accepts satisfies both email CHECKs")
    void theAcceptedListIsWhatTheMigrationWouldStore() {
        for (String raw : List.of("Meena@Saathi.Example", "  MEENA@SAATHI.EXAMPLE  ", "meena@saathi.example",
            "Meena.Sharma@Sub.Saathi.Example", "meena+work@saathi.example", "meena@saathi.co.in",
            "meena@saathi_exam.ple", "a@b.co")) {
            String stored = EmailNormaliser.normalise(raw)
                .orElseThrow(() -> new AssertionError(raw + " is storable, so it must normalise"));
            // user_account_email_lower: no uppercase survives, whatever the caller typed.
            assertThat(stored).as(raw).doesNotMatch(".*[A-Z].*");
            // user_account_email_shape: the migration's pattern accepts exactly this too.
            assertThat(stored).as(raw).matches(MIGRATION_SHAPE);
        }
    }

    @Test
    @DisplayName("everything the migration would reject is rejected one layer earlier")
    void theRefusedListIsWhatTheMigrationWouldRefuse() {
        List<String> unusable = List.of("meena", "", "meena@saathi", "meena@saathi.c", "@saathi.example",
            "meena@", "meena@.example", "meena@saathi.", "meena@saathi.example.", "meena@@saathi.example",
            "meena saathi@example.com", "meena@127.0.0.1", "meena@saathi.example!", "meena@saathi_exam");
        for (String raw : unusable) {
            // The oracle first: this is the migration's verdict on the value as it would be stored.
            assertThat(raw.trim().toLowerCase(Locale.ROOT)).as(raw).doesNotMatch(MIGRATION_SHAPE);
            // Then the normaliser's, which must agree — §10.2's field error, not a constraint
            // violation surfacing as a 500 from the repository.
            assertThat(EmailNormaliser.normalise(raw)).as(raw).isEmpty();
        }
        assertThat(EmailNormaliser.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("plus tags, dots and subdomains survive untouched")
    void nothingIsInventedOutOfTheAddress() {
        // The pattern is the migration's and it allows all three, so the normaliser must lower-case
        // and trim and do nothing else: "meena+work" and "meena" are different mailboxes, and
        // silently stripping a tag or a subdomain would send a password reset to the wrong person.
        assertThat(EmailNormaliser.normalise("Meena+Work@Sub.Saathi.Example"))
            .contains("meena+work@sub.saathi.example");
        assertThat(EmailNormaliser.normalise("first.last@saathi.example")).contains("first.last@saathi.example");
    }

    @Test
    @DisplayName("required answers §10.2's field error on the field its caller named")
    void requiredProducesTheDocumentedFieldError() {
        // PasswordVerifier's staff login path: EmailNormaliser.required(rawEmail, "email").
        assertThat(EmailNormaliser.required("Meena@Saathi.Example", "email")).isEqualTo(STORED);
        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(() -> EmailNormaliser.required("meena@saathi", "email"))
            .satisfies(failure -> {
                assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(failure.code().statusCode()).isEqualTo(422);
                assertThat(failure.getMessage()).isEqualTo("One or more fields failed validation.");
                assertThat(failure.details()).isEqualTo(Map.of("field_errors", List.of(
                    Map.of("field", "email", "message", "Enter a valid email address."))));
            });
        // The field name is the caller's, because field_errors names the wire field the client sent;
        // invitations use the same normaliser with a different field.
        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(() -> EmailNormaliser.required(null, "recovery_email"))
            .satisfies(failure -> assertThat(failure.details()).isEqualTo(Map.of("field_errors", List.of(
                Map.of("field", "recovery_email", "message", "Enter a valid email address.")))));
    }

    @Test
    @DisplayName("a masked address keeps the domain and loses the mailbox")
    void maskKeepsTheDomain() {
        String masked = EmailNormaliser.mask(STORED);
        assertThat(masked).isEqualTo("m***a@saathi.example");
        // §11.4's reason for keeping the domain: a support agent must still see which organisation
        // an address belongs to.
        assertThat(masked).endsWith("@saathi.example");
        for (String stored : List.of(STORED, "meena.sharma@saathi.example", "first.last@sub.domain.co.in")) {
            String local = stored.substring(0, stored.indexOf('@'));
            assertThat(EmailNormaliser.mask(stored)).as(stored)
                // Everything between the first and last character of the local part is gone.
                .doesNotContain(local.substring(1, local.length() - 1))
                .doesNotContain(local)
                .endsWith(stored.substring(stored.indexOf('@')));
        }
    }

    @Test
    @DisplayName("a one or two character mailbox is masked in full")
    void shortMailboxesAreMaskedCompletely() {
        // The first-and-last rule would print the whole mailbox at these lengths, so the code
        // replaces every character instead: "me" must not come back as "me".
        assertThat(EmailNormaliser.mask("me@saathi.example")).isEqualTo("**@saathi.example");
        assertThat(EmailNormaliser.mask("m@saathi.example")).isEqualTo("*@saathi.example");
        assertThat(EmailNormaliser.mask("me@saathi.example")).doesNotContain("me@");
    }

    @Test
    @DisplayName("a masked address is still masked when it is masked again")
    void maskingIsStable() {
        String once = EmailNormaliser.mask(STORED);
        assertThat(EmailNormaliser.mask(once)).isEqualTo(once);
        assertThat(EmailNormaliser.mask("m@saathi.example")).isEqualTo(EmailNormaliser.mask("*@saathi.example"));
    }

    @Test
    @DisplayName("mask answers null for a value that has no mailbox in it")
    void maskRefusesNothingToMask() {
        // Same shape as the phone mask: AuthenticatedPrincipal.display() puts this straight into a
        // response, so a value with no addressable local part is reported as unmaskable rather than
        // echoed back with stars bolted onto it.
        assertThat(EmailNormaliser.mask(null)).isNull();
        assertThat(EmailNormaliser.mask("meena")).isNull();
        assertThat(EmailNormaliser.mask("@saathi.example")).isNull();
    }

    @Test
    @DisplayName("mask normalises nothing, so it is given the stored value")
    void maskIsNotANormaliser() {
        // Masking "Meena@Saathi.Example" keeps both characters as typed, which is why the display
        // path masks what came out of the row: user_account_email_lower has already made it
        // lowercase, and the mask must not be relied on to do that job as well.
        assertThat(EmailNormaliser.mask("Meena@Saathi.Example")).isEqualTo("M***a@Saathi.Example");
        assertThat(EmailNormaliser.mask(EmailNormaliser.normalise("Meena@Saathi.Example").orElseThrow()))
            .isEqualTo("m***a@saathi.example");
    }
}
