package com.saathi.shared.paging;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Cursor rules from §10.3. A cursor is an opaque base64url tuple, never a decoded offset:
 * {@code OFFSET} paging is banned because deep offsets are the classic way a list endpoint
 * quietly becomes a full table scan.
 *
 * <p>Every sort carries the stable secondary key {@code id}, which is what makes a cursor
 * unable to skip or repeat a row when two rows share a sort value.
 */
public final class CursorPager {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    private static final String SEPARATOR = "\u001f";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorPager() {
    }

    public static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 100",
                Map.of("field_errors", List.of(Map.of("field", "limit", "message", "must be 1..100"))));
        }
        return requested;
    }

    public static String encode(List<String> keyParts) {
        if (keyParts == null || keyParts.isEmpty()) {
            return null;
        }
        for (String part : keyParts) {
            if (part != null && (part.contains(SEPARATOR) || part.contains("\n"))) {
                throw new IllegalArgumentException("cursor component is not encodable");
            }
        }
        String joined = String.join(SEPARATOR, keyParts.stream().map(CursorPager::nullToMarker).toList());
        return ENCODER.encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return List.of();
        }
        String joined;
        try {
            joined = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw badCursor();
        }
        String[] parts = joined.split(SEPARATOR, -1);
        if (parts.length == 0) {
            throw badCursor();
        }
        return java.util.Arrays.stream(parts).map(CursorPager::markerToNull).toList();
    }

    /** A page is {@code limit + 1} rows wide internally, so {@code has_more} costs no second query. */
    public static int fetchSize(int limit) {
        return limit + 1;
    }

    private static String nullToMarker(String value) {
        return value == null ? "~null~" : value;
    }

    private static String markerToNull(String value) {
        return "~null~".equals(value) ? null : value;
    }

    /**
     * The refusal every cursor reader raises, so a list endpoint that decodes its own key cannot
     * invent a fourth wording for "that value is not one of mine". Public because a caller that
     * pages over rows already in memory has to parse a cursor without {@link #decode} deciding what
     * a legal key is.
     */
    public static ApiException badCursor() {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "cursor is not recognised",
            Map.of("field_errors", List.of(Map.of("field", "cursor", "message", "opaque value; do not construct"))));
    }
}
