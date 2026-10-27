package de.homelabs.hippocampus.ltm;

import java.time.Instant;

/**
 * One {@code ltm_list} index line: id, title, origin and chunk count — no content.
 */
public record LtmSummary(
        long id,
        String title,
        String originType,
        String originRef,
        int chunkCount,
        Instant createdAt) {
}
