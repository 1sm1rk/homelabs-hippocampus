package de.homelabs.hippocampus.mcp.dto;

import java.time.Instant;

/** Wire shape of one long-term index line: id, title, origin, chunk count — no content. */
public record LtmSummaryDto(
        long id,
        String title,
        String originType,
        String originRef,
        int chunkCount,
        Instant createdAt) {
}
