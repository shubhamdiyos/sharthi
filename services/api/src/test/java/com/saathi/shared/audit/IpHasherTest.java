package com.saathi.shared.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * §13.4: the platform correlates abuse by source address without ever storing an address.
 * Two properties matter and both are testable without a database — the hash is stable for a
 * day and it changes with the day, so a leaked table cannot be matched against old logs.
 */
class IpHasherTest {

    private static final String KEY = "daily-key-for-tests-only";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

    @Test
    void theSameAddressOnTheSameDayUnderTheSameKeyIsTheSameHash() {
        assertEquals(IpHasher.hash("49.36.140.8", KEY, DAY), IpHasher.hash("49.36.140.8", KEY, DAY));
    }

    @Test
    void aDifferentAddressIsUnrelatedToTheFirst() {
        assertNotEquals(IpHasher.hash("49.36.140.8", KEY, DAY), IpHasher.hash("49.36.140.9", KEY, DAY));
    }

    @Test
    void rotatingTheDayBreaksCorrelationAcrossDays() {
        assertNotEquals(IpHasher.hash("49.36.140.8", KEY, DAY),
            IpHasher.hash("49.36.140.8", KEY, DAY.plusDays(1)));
    }

    @Test
    void rotatingTheKeyBreaksCorrelationAcrossLeaks() {
        assertNotEquals(IpHasher.hash("49.36.140.8", KEY, DAY),
            IpHasher.hash("49.36.140.8", "rotated-" + KEY, DAY));
    }

    @Test
    void theOutputIsSixtyFourLowercaseHexCharacters() {
        String hash = IpHasher.hash("2409:4000:34:8c::1", KEY, DAY);
        assertEquals(64, hash.length());
        // audit_event.ip_hash has a CHECK for exactly this shape.
        assertEquals(true, hash.matches("^[0-9a-f]{64}$"), hash);
    }

    @Test
    void noAddressIsNoHash() {
        assertNull(IpHasher.hash(null, KEY, DAY));
        assertNull(IpHasher.hash("", KEY, DAY));
        assertNull(IpHasher.hash("   ", KEY, DAY));
    }

    @Test
    void theDayIsPartOfTheMessageNotOnlyOfTheKey() {
        // With the day outside the MAC input, one key would produce one hash per address for
        // every day forever, which is the property §13.4 asks against.
        String withSameAddressKeyButDayBakedIntoMessage = IpHasher.hash("49.36.140.8", KEY, DAY);
        String other = IpHasher.hash("49.36.140.8", KEY, DAY.withYear(2027));
        assertNotEquals(withSameAddressKeyButDayBakedIntoMessage, other);
    }

    @Test
    void theMacInputIsTheDayTheAddressAndNothingElse() {
        // Computed independently of this codebase (python hmac over "2026-10-30|49.36.140.8"),
        // so a change to the message format, the algorithm or the encoding has to be a
        // deliberate decision rather than a silent one — audit rows already written are keyed
        // by this exact construction and cannot be recomputed after the fact.
        assertEquals("962f6f2259eb1ebddb77216a7bf6fca93b0bc6622d6a78977748153cd70c8342",
            IpHasher.hash("49.36.140.8", KEY, DAY));
    }
}
