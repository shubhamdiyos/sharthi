package com.saathi.shared.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §12.1: the phone <em>is</em> the customer's factor, and §12.8 hashes it into an OTP target. Both
 * only work if one person types one number, so this class's job — turn the spellings a keyboard
 * actually produces into a single E.164 — is the line between "a second account for the same
 * person" and one account. The assertions are taken from the class javadoc's own three examples,
 * which are the forms {@code user_account_phone_e164} would otherwise have stored as three people.
 *
 * <p>Masking is checked here too because §11.4 prohibits a full number in a response, a log or an
 * export, and the two callers ({@code AuthenticatedPrincipal.display()}, {@code LoggingOtpProvider})
 * have nothing else between them and the log line.
 *
 * <p>Indian mobiles only, and a foreign number is refused rather than passed through: OD-8's
 * provider is unselected and an account whose OTP can never arrive is worse than an error message,
 * so refusal is the documented outcome and is asserted as such.
 */
class PhoneNormaliserTest {

    private static final String E164 = "+919800003421";

    @Test
    @DisplayName("the three spellings the column would have stored as three people are one number")
    void theDocumentedSpellingsCollapse() {
        // Verbatim from the class javadoc: bare ten digits, the trunk-code form, and the form the
        // sign-in screen's placeholder shows. If any of these drifted, the same person would get a
        // second account and an OTP that never reaches the phone they expect.
        assertThat(PhoneNormaliser.normalise("9800003421")).contains(E164);
        assertThat(PhoneNormaliser.normalise("09800003421")).contains(E164);
        assertThat(PhoneNormaliser.normalise("+91 98000 03421")).contains(E164);
    }

    @Test
    @DisplayName("the punctuation a keypad produces is noise, not a different number")
    void groupingIsIgnored() {
        List<String> spellings = List.of("98000 03421", "(98000) 03421", "(98000)-03421",
            "+91-98000-03421", "+91 98000 03421", "  +91 98000 03421  ", "098000 03421",
            "98000.03421", "98-000-034-21");
        assertThat(spellings).allSatisfy(spelling ->
            assertThat(PhoneNormaliser.normalise(spelling)).as(spelling).contains(E164));
    }

    @Test
    @DisplayName("the country code typed without its plus means the same ten digits")
    void theCountryCodeWithoutItsPlusIsStripped() {
        assertThat(PhoneNormaliser.normalise("919800003421")).contains(E164);
        assertThat(PhoneNormaliser.normalise("91 98000 03421")).contains(E164);
        assertThat(PhoneNormaliser.normalise("+919800003421")).contains(E164);
        assertThat(PhoneNormaliser.normalise("+91 9800003421")).contains(E164);
    }

    @Test
    @DisplayName("normalising a number that is already normalised changes nothing")
    void normalisingIsIdempotent() {
        // The value is hashed into otp_challenge.target_hash and looked up again on verify; if a
        // second pass moved it, the row written at request time would be unreachable.
        for (String spelling : List.of("9800003421", "09800003421", "+91 98000 03421", "919800003421")) {
            String once = PhoneNormaliser.normalise(spelling).orElseThrow();
            assertThat(PhoneNormaliser.normalise(once)).as(spelling).contains(once);
        }
        assertThat(PhoneNormaliser.normalise("+919876543210")).contains("+919876543210");
    }

    @Test
    @DisplayName("a number that is not ten mobile digits is refused")
    void wrongLengthIsRefused() {
        assertThat(PhoneNormaliser.normalise("980000342")).isEmpty();
        assertThat(PhoneNormaliser.normalise("98000034210")).isEmpty();
        assertThat(PhoneNormaliser.normalise("+91980000342")).isEmpty();
        assertThat(PhoneNormaliser.normalise("447911123456")).isEmpty();
        assertThat(PhoneNormaliser.normalise("")).isEmpty();
        assertThat(PhoneNormaliser.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("an Indian mobile starts with 6-9 and nothing else does")
    void wrongPrefixIsRefused() {
        // The 6-9 range is the TRAI mobile numbering plan; landlines (011..., 080...) cannot
        // receive an OTP, so accepting them would create an account that cannot be signed into.
        assertThat(PhoneNormaliser.normalise("5876543210")).isEmpty();
        assertThat(PhoneNormaliser.normalise("4800003421")).isEmpty();
        assertThat(PhoneNormaliser.normalise("1234567890")).isEmpty();
        // A leading zero only means the trunk code on eleven digits. On ten it is part of the
        // number and there is no eleven-digit interpretation left, so this is refused — the class
        // javadoc's trunk example is "09800003421", which is tested above.
        assertThat(PhoneNormaliser.normalise("09800 03421")).isEmpty();
        assertThat(PhoneNormaliser.normalise("0980003421")).isEmpty();
        // The same stray trunk code behind the country code reduces to the same ten digits with a
        // leading zero, so it is refused by this rule and not by the foreign-number one.
        assertThat(PhoneNormaliser.normalise("+91 0 98000 03421")).isEmpty();
    }

    @Test
    @DisplayName("a foreign number is refused rather than passed through")
    void foreignNumbersAreRefused() {
        assertThat(PhoneNormaliser.normalise("+1 555 867 5309")).isEmpty();
        assertThat(PhoneNormaliser.normalise("+44 7911 123456")).isEmpty();
        assertThat(PhoneNormaliser.normalise("+92 300 1234567")).isEmpty();
        // The same ten digits under a different country code is still not an Indian mobile.
        assertThat(PhoneNormaliser.normalise("+961 9800003421")).isEmpty();
    }

    @Test
    @DisplayName("a character that is not keypad grouping refuses the whole number")
    void anythingElseIsRefused() {
        assertThat(PhoneNormaliser.normalise("abcdefghij")).isEmpty();
        assertThat(PhoneNormaliser.normalise("9800003421!")).isEmpty();
        assertThat(PhoneNormaliser.normalise("9800 03421x")).isEmpty();
        assertThat(PhoneNormaliser.normalise("+91_98000_03421")).isEmpty();
        // A plus in the middle is not a country code, so it is dropped as noise rather than
        // starting an international prefix: only a leading plus is read as one.
        assertThat(PhoneNormaliser.normalise("98+00003421")).contains(E164);
        assertThat(PhoneNormaliser.normalise("+91 98000 03421+")).contains(E164);
    }

    @Test
    @DisplayName("required answers §10.2's field error on the field its caller named")
    void requiredProducesTheDocumentedFieldError() {
        assertThat(PhoneNormaliser.required("(98000) 03421", "phone")).isEqualTo(E164);
        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(() -> PhoneNormaliser.required("5876543210", "phone"))
            .satisfies(failure -> {
                assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                // 422, not 400: the body parsed and the value is simply not an Indian mobile.
                assertThat(failure.code().statusCode()).isEqualTo(422);
                assertThat(failure.details()).isEqualTo(Map.of("field_errors", List.of(
                    Map.of("field", "phone", "message", "Enter a 10-digit Indian mobile number."))));
            });
        // SignInService passes "phone"; the field is the caller's, because §10.2's field_errors name
        // the wire field the client sent, not the Java parameter.
        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(() -> PhoneNormaliser.required(null, "recovery_phone"))
            .satisfies(failure -> assertThat(failure.details()).isEqualTo(Map.of("field_errors", List.of(
                Map.of("field", "recovery_phone", "message", "Enter a 10-digit Indian mobile number.")))));
        // The required() path is normalise() with the failure already mapped, so the two cannot
        // disagree about what a valid number is.
        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(() -> PhoneNormaliser.required("+1 555 867 5309", "phone"))
            .satisfies(failure -> assertThat(failure.getMessage())
                .isEqualTo("One or more fields failed validation."));
    }

    @Test
    @DisplayName("a masked number is recognisable to its owner and useless to a stranger")
    void maskKeepsTwoDigitsEachEnd() {
        String masked = PhoneNormaliser.mask(E164);
        assertThat(masked).isEqualTo("+91 98XXXXXX21");
        assertThat(masked).doesNotContain("9800003421").doesNotContain("00003421");
        assertThat(masked).endsWith("21").startsWith("+91 98");
        // The middle six digits of the ten are what a contact list needs; they are gone for every
        // number, not just this one.
        for (String e164 : List.of("+919876543210", "+917000000000", "+919123456789")) {
            String value = PhoneNormaliser.mask(e164);
            assertThat(value).as(e164)
                .isEqualTo("+91 " + e164.substring(3, 5) + "XXXXXX" + e164.substring(11));
            assertThat(value).as(e164).doesNotContain(e164.substring(3));
            assertThat(value).as(e164).containsOnlyOnce("XXXXXX");
        }
    }

    @Test
    @DisplayName("mask prints nothing for a value that is not the stored form")
    void maskRefusesAnythingItDidNotRecognise() {
        // The two callers put this straight into a response and a log line, so an input that is not
        // a normalised E.164 has to answer null rather than a plausible-looking partial number —
        // §11.4 prohibits a full number in a log, and a mask that guesses is how one escapes.
        assertThat(PhoneNormaliser.mask(null)).isNull();
        assertThat(PhoneNormaliser.mask("9800003421")).isNull();
        assertThat(PhoneNormaliser.mask("09800003421")).isNull();
        assertThat(PhoneNormaliser.mask("+91980000342")).isNull();
        assertThat(PhoneNormaliser.mask("+1 555 867 5309")).isNull();
        // A masked value is not a number, so it does not mask again: display code masks the stored
        // value once and never re-feeds a mask.
        assertThat(PhoneNormaliser.mask("+91 98XXXXXX21")).isNull();
    }
}
