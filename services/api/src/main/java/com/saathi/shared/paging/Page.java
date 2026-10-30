package com.saathi.shared.paging;

import java.util.List;

/**
 * The response envelope every collection endpoint returns (§10.3):
 * {@code { "data": [...], "page": { "next_cursor", "has_more", "limit" } }}.
 */
public record Page<T>(List<T> data, PageInfo page) {

    public Page {
        data = List.copyOf(data);
    }

    public record PageInfo(String nextCursor, boolean hasMore, int limit) {
    }

    public static <T> Page<T> of(List<T> data, String nextCursor, boolean hasMore, int limit) {
        return new Page<>(data, new PageInfo(nextCursor, hasMore, limit));
    }

    public static <T> Page<T> empty(int limit) {
        return new Page<>(List.of(), new PageInfo(null, false, limit));
    }
}
