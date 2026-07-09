package br.com.carvoeiromanager.common;

import java.util.List;

/**
 * A 1-based page of results plus the totals needed to render pagination controls. Built with
 * {@link #of} from an already-filtered/sorted in-memory list — pages large-but-bounded
 * collections (a save's transfer market, at most a few hundred players) without a database
 * {@code LIMIT/OFFSET}.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    /**
     * Slices {@code all} into the requested page. {@code page} is clamped to at least 1;
     * {@code size} is clamped to [1, 100]. A page past the end of the data yields an empty
     * {@code content} with the totals still reflecting the full collection.
     */
    public static <T> PageResponse<T> of(List<T> all, int page, int size) {
        int clampedSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        int clampedPage = Math.max(1, page);
        long totalElements = all.size();
        int totalPages = (int) Math.ceil(totalElements / (double) clampedSize);

        int fromIndex = (clampedPage - 1) * clampedSize;
        List<T> content = fromIndex >= all.size()
                ? List.of()
                : all.subList(fromIndex, Math.min(fromIndex + clampedSize, all.size()));
        return new PageResponse<>(content, clampedPage, clampedSize, totalElements, totalPages);
    }
}
