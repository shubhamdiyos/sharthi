package com.saathi.order.domain;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * T4's closed reject vocabulary (§6.3).
 *
 * <p>§6.3 requires a reject reason "from a closed enum" and enumerates the delivery-failure set in
 * §6.4 but not this one, so {@code V7__order_lifecycle.sql} is where the members are stated — as a
 * database CHECK, so a reason this API does not know is a rejected write rather than a new string
 * in a customer's order history. This enum is that constraint read from the Java side, and the two
 * lists must change together.
 */
public enum RejectReason {
    OUT_OF_STOCK,
    SHOP_CLOSED,
    AREA_NOT_SERVED,
    SUSPICIOUS_ORDER,
    OTHER;

    public static RejectReason parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "reason",
                    "message", "is required; a shop must say why it rejected the order"))));
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "reason",
                    "message", "must be one of " + names()))));
        }
    }

    private static String names() {
        return String.join(", ", java.util.Arrays.stream(values()).map(Enum::name).toList());
    }
}
