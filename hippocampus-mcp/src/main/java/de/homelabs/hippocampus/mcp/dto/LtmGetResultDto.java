package de.homelabs.hippocampus.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Wire shape of one long-term entry: full {@code content} (no chunk range asked for)
 * or a 1-based chunk slice ({@code content} null).
 */
public record LtmGetResultDto(
        long ltmId,
        String title,
        String kind,
        String originType,
        String originRef,
        Instant createdAt,
        int totalChunks,
        String content,
        List<LtmChunkSliceDto> chunks) {
}
