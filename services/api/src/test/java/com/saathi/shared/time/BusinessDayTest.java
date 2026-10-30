package com.saathi.shared.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saathi.shared.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * A business day is a settlement fact, not a display detail: {@code job_run} is unique on
 * {@code (job_key, tenant_id, business_day)} (§4.6) and a settlement batch's period is cut on
 * it. India has one zone and no DST, which is exactly why the zone must be named rather than
 * inherited from the server's locale.
 */
class BusinessDayTest {

    private static final ZoneId IST = ClockConfig.OPERATIONS_ZONE;

    @Test
    void theOperationsZoneIsKolkata() {
        assertEquals(ZoneId.of("Asia/Kolkata"), IST);
    }

    @Test
    void lateUtcEveningIsAlreadyTomorrowInSiwan() {
        Instant eveningUtc = Instant.parse("2026-10-30T18:35:00Z");
        assertEquals(LocalDate.of(2026, 10, 30), BusinessDay.of(eveningUtc.minusSeconds(1), ZoneId.of("UTC")),
            "the same instant is still yesterday on a UTC calendar");
        assertEquals(LocalDate.of(2026, 10, 31), BusinessDay.of(eveningUtc, IST));
    }

    @Test
    void theLastHoursOfTheUtcDayAlreadyBelongToTomorrowsBusinessDay() {
        // IST is UTC+05:30, so everything from 18:30 UTC onward is tomorrow in Siwan. A nightly
        // reconciliation keyed on the UTC date would assert the wrong day's identities (§4.6).
        Instant lateUtc = Instant.parse("2026-10-30T20:00:00Z");
        assertEquals(LocalDate.of(2026, 10, 30), BusinessDay.of(lateUtc, ZoneId.of("UTC")));
        assertEquals(LocalDate.of(2026, 10, 31), BusinessDay.of(lateUtc, IST));
    }

    @Test
    void aDayRollsAtTheZoneMidnightNotAtUtcMidnight() {
        Instant before = Instant.parse("2026-10-30T18:29:59Z");
        Instant after = Instant.parse("2026-10-30T18:30:01Z");
        assertEquals(LocalDate.of(2026, 10, 30), BusinessDay.of(before, IST));
        assertEquals(LocalDate.of(2026, 10, 31), BusinessDay.of(after, IST));
    }

    @Test
    void todayReadsTheInjectedClockNotTheWall() {
        Clock fixed = Clock.fixed(Instant.parse("2026-04-14T06:00:00Z"), IST);
        assertEquals(LocalDate.of(2026, 4, 14), BusinessDay.today(fixed));
        assertEquals(LocalDate.of(2026, 4, 14), BusinessDay.of(fixed.instant(), IST));
    }

    @Test
    void theProductionClockIsTheKolkataClock() {
        assertEquals(IST, new ClockConfig().clock().getZone());
    }

    @Test
    void aLeapDayAndAMonthEdgeBothResolve() {
        assertEquals(LocalDate.of(2028, 2, 29), BusinessDay.of(Instant.parse("2028-02-28T18:31:00Z"), IST));
        assertEquals(LocalDate.of(2026, 12, 31), BusinessDay.of(Instant.parse("2026-12-31T06:00:00Z"), IST));
        assertEquals(LocalDate.of(2027, 1, 1), BusinessDay.of(Instant.parse("2027-01-01T06:00:00Z"), IST));
    }
}
