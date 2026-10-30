package com.saathi.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The business day a job or a settlement line belongs to, computed in the operations zone.
 *
 * <p>{@code job_run} is unique on {@code (job_key, tenant_id, business_day)} (§4.6), so the
 * day a run belongs to is part of the money-correctness guarantee, not a reporting detail.
 */
public final class BusinessDay {

    private BusinessDay() {
    }

    public static LocalDate of(Instant instant, ZoneId zone) {
        return instant.atZone(Objects.requireNonNull(zone, "zone")).toLocalDate();
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock);
    }
}
