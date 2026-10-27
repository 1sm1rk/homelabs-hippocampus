package de.homelabs.hippocampus.ltm;

import java.time.Instant;
import java.util.List;

/**
 * The {@code ltm_get} result in one of two shapes: the full entry ({@code content}
 * set, {@code chunks} null) or a chunk slice ({@code chunks} set, {@code content}
 * null). {@code totalChunks} always reports the entry's chunk count so callers
 * can page further.
 */
public record LtmGetResult(
        long ltmId,
        String title,
        String kind,
        String originType,
        String originRef,
        Instant createdAt,
        int totalChunks,
        String content,
        List<LtmChunkSlice> chunks) {
}
