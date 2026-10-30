package com.saathi.shared.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one property that makes ULIDs worth their complexity: the string itself sorts by
 * creation time, so §10.3's keyset pagination needs no second index and a cursor over
 * {@code id} can never skip a row.
 */
class UlidTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Clock REAL = Clock.system(UTC);
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    @Test
    void theTimestampRoundTripsToTheMillisecond() {
        Instant now = Instant.parse("2026-10-30T09:15:42.710Z");
        String id = Ulid.next(Clock.fixed(now, UTC));
        assertEquals(now, Ulid.instant(id), "an audit row must be datable from its id alone");
    }

    @Test
    void idsFromDifferentClocksCarryDifferentTimestamps() {
        Instant first = Instant.parse("2026-04-14T09:30:00.000Z");
        Instant second = first.plusMillis(1);
        String a = Ulid.next(Clock.fixed(first, UTC));
        String b = Ulid.next(Clock.fixed(second, UTC));
        assertEquals(first, Ulid.instant(a));
        assertEquals(second, Ulid.instant(b));
        assertNotEquals(a, b);
    }

    @Test
    void lexicographicOrderIsChronologicalOrder() {
        List<String> ids = java.util.stream.IntStream.range(0, 40)
            .mapToObj(i -> Ulid.next(Clock.fixed(Instant.EPOCH.plusMillis(i * 37L), UTC)))
            .toList();
        assertEquals(ids.stream().sorted().toList(), ids,
            "generated in time order, so sorted() must not move anything");
        assertEquals(Instant.EPOCH, Ulid.instant(ids.get(0)));
        assertEquals(Instant.EPOCH.plusMillis(39 * 37L), Ulid.instant(ids.get(39)));
    }

    @Test
    void everyIdIsTwentySixCanonicalCharacters() {
        String id = Ulid.next(REAL);
        assertEquals(26, id.length());
        assertTrue(Ulid.isValid(id), id);
        for (int i = 0; i < id.length(); i++) {
            assertTrue(CROCKFORD.indexOf(id.charAt(i)) >= 0, "character " + i + " of " + id);
        }
    }

    @Test
    void theAmbiguousLettersNeverAppear() {
        for (int i = 0; i < 2_000; i++) {
            String id = Ulid.next(REAL);
            for (char banned : new char[] {'I', 'L', 'O', 'U'}) {
                assertFalse(id.indexOf(banned) >= 0, banned + " in " + id);
            }
        }
    }

    @Test
    void randomnessIsTheFullEightyBitsWide() {
        // Two ids minted in the same millisecond differ in the trailing 16 characters, which is
        // what makes (id) a safe secondary sort key and a safe primary key under a burst.
        Clock sameInstant = Clock.fixed(Instant.parse("2026-10-30T09:15:42.710Z"), UTC);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            ids.add(Ulid.next(sameInstant));
        }
        assertEquals(5_000, ids.size(), "a collision inside one millisecond would be a duplicate key");
        assertEquals(26, ids.stream().findFirst().orElseThrow().length());
    }

    @Test
    void theLeadingDigitStaysWithinAFortyEightBitTimestamp() {
        // 48 bits in ten base-32 digits leaves two unused bits, so the first character is 0-7
        // until the year 10889. Sorting a column of these is only time-ordered while that holds.
        String id = Ulid.next(REAL);
        assertTrue("01234567".indexOf(id.charAt(0)) >= 0, "first character was " + id.charAt(0));
    }

    @Test
    void theLargestRepresentableTimestampStillRoundTrips() {
        Instant max = Instant.ofEpochMilli((1L << 48) - 1);
        String id = Ulid.next(Clock.fixed(max, UTC));
        assertEquals(max, Ulid.instant(id));
    }

    @Test
    void aTimestampThatDoesNotFitIsRefusedRatherThanTruncated() {
        Instant overflow = Instant.ofEpochMilli(1L << 48);
        assertThrows(IllegalArgumentException.class, () -> Ulid.next(Clock.fixed(overflow, UTC)));
        assertThrows(IllegalArgumentException.class, () -> Ulid.next(Clock.fixed(Instant.ofEpochMilli(-1), UTC)));
    }

    @Test
    void malformedIdentifiersAreRejected() {
        assertFalse(Ulid.isValid(null));
        assertFalse(Ulid.isValid(""));
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAV".substring(1)), "25 characters");
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAVV"), "27 characters");
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAU"), "U is not in the alphabet");
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAI"), "I is not in the alphabet");
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAL"), "L is not in the alphabet");
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAO"), "O is not in the alphabet");
        assertFalse(Ulid.isValid("01arz3ndektsv4rrffq69g5fav"), "lower case is not the canonical form");
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FA-"), "punctuation");
        assertFalse(Ulid.isValid("😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"));
        assertThrows(IllegalArgumentException.class, () -> Ulid.instant("not-a-ulid"));
    }

    @Test
    void aGeneratedIdSatisfiesTheDatabasesOwnCheck() {
        // V1__foundation_infrastructure.sql refuses anything that does not match this pattern,
        // so the two must never disagree — a fixture row inserted by hand included.
        for (int i = 0; i < 500; i++) {
            assertTrue(Ulid.next(REAL).matches("^[0-9A-HJKMNP-TV-Z]{26}$"));
        }
    }
}
