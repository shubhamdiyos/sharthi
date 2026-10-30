package com.saathi.shared.contact;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Staff sign in with an email (§12.1), so this is the value a lookup runs on.
 *
 * <p>{@code user_account_email_lower} refuses a mixed-case address at write time and the unique
 * index is exact, so a stored {@code Meena@Saathi.Example} would be found by nobody and a login
 * for it would answer "invalid credentials" to the person who owns the account. Lower-casing at
 * the boundary is what makes the column's own CHECK true rather than a trap.
 *
 * <p>The shape is the same pattern the migration asserts, so a request that would fail in the
 * database fails one layer earlier with a field error the client can show.
 */
public final class EmailNormaliser {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

    private EmailNormaliser() {
    }

    public static Optional<String> normalise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }

    public static String required(String raw, String field) {
        return normalise(raw).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
            "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field, "message", "Enter a valid email address.")))));
    }

    /**
     * {@code me********@saathi.example}: first and last character of the local part, domain kept.
     * The domain is not the secret; the mailbox is, and a support agent reading a masked address
     * still needs to recognise which organisation it belongs to.
     */
    public static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return null;
        }
        String local = email.substring(0, at);
        String masked = local.length() <= 2
            ? "*".repeat(local.length())
            : local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1);
        return masked + email.substring(at);
    }
}
