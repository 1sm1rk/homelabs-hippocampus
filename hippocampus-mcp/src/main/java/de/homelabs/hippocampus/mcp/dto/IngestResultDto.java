package de.homelabs.hippocampus.mcp.dto;

import java.util.List;

/**
 * Wire shape of a promote/import reply: the new long-term entry id and its chunk
 * count, or {@code id = -1} for a warned no-op (already promoted / unchanged
 * re-import).
 */
public record IngestResultDto(long id, int chunkCount, List<String> warnings) {

    public IngestResultDto {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
