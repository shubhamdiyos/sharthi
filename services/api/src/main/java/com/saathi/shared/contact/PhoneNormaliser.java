package com.saathi.shared.contact;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place a phone number becomes the value this platform stores.
 *
 * <p>{@code user_account_phone_e164} would accept "9800003421", "09800003421" and
 * "+91 98000 03421" as three different people, and phone is the customer's only factor (§12.1) —
 * so a second spelling of the same number is a second account for the same person, an OTP that
 * never reaches the phone its owner expects, and a login that fails for someone who has never
 * mistyped anything. Normalising here, before the number is hashed into an OTP target or looked
 * up, is what makes those three impossible.
 *
 * <p>Indian mobiles only. A foreign number is refused rather than passed through: OD-8's provider
 * is unselected, sender-ID registration is India-specific, and an account whose OTP can never be
 * delivered is worse than an error message.
 */
public final class PhoneNormaliser {

    private static final String INDIA = "+91";
    private static final int MOBILE_DIGITS = 10;

    private PhoneNormaliser() {
    }

    /** E.164 for an Indian mobile, or empty for anything that is not one. */
    public static Optional<String> normalise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        // Only a leading + survives the strip: "09800 03421" and "(98000)-03421" are the same
        // number typed into two different forms, and a + anywhere else is not a country code.
        boolean international = raw.stripLeading().startsWith("+");
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != '+' && c != '-' && c != ' ' && c != '.' && c != '(' && c != ')') {
                return Optional.empty();
            }
        }
        if (international && !raw.trim().startsWith(INDIA)) {
            return Optional.empty();
        }
        String number = digits.toString();
        // Indian mobiles are 10 digits starting 6-9; a leading 0 is the trunk code and a leading
        // 91 is the country code typed without the +, both of which mean the same ten digits.
        if (number.length() == MOBILE_DIGITS + 1 && number.startsWith("0")) {
            number = number.substring(1);
        } else if (number.length() == MOBILE_DIGITS + 2 && number.startsWith("91")) {
            number = number.substring(2);
        }
        if (number.length() != MOBILE_DIGITS || number.charAt(0) < '6' || number.charAt(0) > '9') {
            return Optional.empty();
        }
        return Optional.of(INDIA + number);
    }

    /**
     * {@link #normalise} with the failure already mapped to §10.2's field error, because every
     * endpoint that takes a phone answers the same question about it.
     */
    public static String required(String raw, String field) {
        return normalise(raw).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
            "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field,
                "message", "Enter a 10-digit Indian mobile number.")))));
    }

    /**
     * {@code +91 98XXXXXX21}: the first two and last two digits of the ten. Enough for a person
     * to recognise their own number on a screen, not enough to be a contact list — which is the
     * line §10 draws about phone numbers in responses, logs and exports.
     */
    public static String mask(String e164) {
        if (e164 == null || !e164.startsWith(INDIA) || e164.length() != INDIA.length() + MOBILE_DIGITS) {
            return null;
        }
        String number = e164.substring(INDIA.length());
        return INDIA + " " + number.substring(0, 2) + "XXXXXX" + number.substring(8);
    }
}
