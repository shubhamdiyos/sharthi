package com.saathi.shared.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §10.3's cursor contract. The point of these tests is not that base64 works — it is that a
 * cursor is opaque, that a limit outside 1..100 is refused rather than quietly clamped, and
 * that {@code has_more} comes from fetching one extra row instead of from a COUNT(*).
 */
class CursorPagerTest {

    @Test
    void limitDefaultsToTwentyAndIsNeverSilentlyClamped() {
        assertEquals(20, CursorPager.clampLimit(null));
        assertEquals(1, CursorPager.clampLimit(1));
        assertEquals(100, CursorPager.clampLimit(100));
        for (int rejected : new int[] {0, -1, 101, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            ApiException e = assertThrows(ApiException.class, () -> CursorPager.clampLimit(rejected));
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
            assertEquals(422, e.code().statusCode());
        }
    }

    @Test
    void aBadLimitNamesTheFieldInTheSameShapeAsAValidationError() {
        ApiException e = assertThrows(ApiException.class, () -> CursorPager.clampLimit(0));
        assertTrue(e.details().containsKey("field_errors"), "the envelope's details.field_errors[]");
        assertEquals(List.of("limit"),
            ((List<?>) e.details().get("field_errors")).stream()
                .map(entry -> ((java.util.Map<?, ?>) entry).get("field")).toList(),
            "a client can highlight the field it got wrong");
    }

    @Test
    void aCursorIsAnOpaqueTupleAndRoundTrips() {
        List<String> key = List.of("2026-10-30T09:15:42.710Z", "SAATH-4821");
        String cursor = CursorPager.encode(key);
        assertEquals(key, CursorPager.decode(cursor));
        assertTrue(cursor.matches("^[A-Za-z0-9_-]+$"), "base64url without padding: " + cursor);
    }

    @Test
    void aNullableSortColumnSurvivesTheRoundTrip() {
        // The order list sorts by delivered_at, which is null until delivery. A cursor over a
        // nullable column has to distinguish "null" from "empty string" or page two repeats a row.
        List<String> key = java.util.Arrays.asList("2026-10-30", null);
        String cursor = CursorPager.encode(key);
        assertEquals(2, CursorPager.decode(cursor).size());
        assertEquals("2026-10-30", CursorPager.decode(cursor).get(0));
        assertNull(CursorPager.decode(cursor).get(1));
    }

    @Test
    void anEmptyOrMissingCursorMeansFirstPage() {
        assertNull(CursorPager.encode(List.of()));
        assertNull(CursorPager.encode(null));
        assertEquals(List.of(), CursorPager.decode(null));
        assertEquals(List.of(), CursorPager.decode(""));
        assertEquals(List.of(), CursorPager.decode("   "));
    }

    @Test
    void aGarbageCursorIsRefusedRatherThanTreatedAsFirstPage() {
        // Silent fallback to page 1 would make an invalid cursor look like a fresh listing and
        // hide a client bug; §10.3 says a cursor is opaque and must not be constructed.
        for (String bad : new String[] {"!!!", "not base64 at all", "aGc\u0000", "%s"}) {
            ApiException e = assertThrows(ApiException.class, () -> CursorPager.decode(bad), bad);
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        }
    }

    @Test
    void aComponentThatWouldForgeTheTupleIsRefusedAtBuildTime() {
        assertThrows(IllegalArgumentException.class,
            () -> CursorPager.encode(List.of("a\u001fb")));
        assertThrows(IllegalArgumentException.class,
            () -> CursorPager.encode(List.of("two\nlines")));
    }

    @Test
    void fetchingOneExtraRowIsWhatRemovesTheCountQuery() {
        assertEquals(21, CursorPager.fetchSize(20));
        assertEquals(101, CursorPager.fetchSize(100));
    }

    @Test
    void aPageCarriesTheEnvelopeEveryCollectionEndpointReturns() {
        Page<String> page = Page.of(List.of("a", "b"), "YWJj", true, 20);
        assertEquals(List.of("a", "b"), page.data());
        assertEquals("YWJj", page.page().nextCursor());
        assertTrue(page.page().hasMore());
        assertEquals(20, page.page().limit());

        Page<String> last = Page.of(List.of("c"), null, false, 20);
        assertFalse(last.page().hasMore());
        assertNull(last.page().nextCursor());

        Page<String> none = Page.empty(20);
        assertEquals(List.of(), none.data());
        assertFalse(none.page().hasMore());
        assertEquals(20, none.page().limit());
    }

    @Test
    void aPageCannotBeMutatedByTheCallerThatBuiltIt() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        Page<String> page = Page.of(mutable, null, false, 20);
        mutable.add("b");
        assertEquals(1, page.data().size(), "the response is frozen at construction");
    }
}
