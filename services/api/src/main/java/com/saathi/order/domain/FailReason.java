package com.saathi.order.domain;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * §6.4's closed delivery-failure list, enforced by {@code order_fail_reason} in
 * {@code V7__order_lifecycle.sql}.
 *
 * <p>T17 (a shop's own delivery attempt failing) is the only edge a merchant reaches with this in
 * Day 2A; the rider's T15 and the operator's T20 name the same members, so the vocabulary is
 * written once here rather than per actor.
 */
public enum FailReason {
    CUSTOMER_UNREACHABLE,
    WRONG_ADDRESS,
    SHOP_CLOSED,
    CUSTOMER_REFUSED,
    DAMAGED_IN_TRANSIT,
    CASH_DISPUTE,
    SAFETY,
    OTHER;

    public static FailReason parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "reason",
                    "message", "is required; a failed delivery must say why"))));
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
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
