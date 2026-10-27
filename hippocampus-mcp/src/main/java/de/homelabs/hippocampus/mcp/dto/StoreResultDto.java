package de.homelabs.hippocampus.mcp.dto;

import java.util.List;

/** Wire shape of a store reply: the new event id plus non-fatal meta-key warnings. */
public record StoreResultDto(long id, List<String> warnings) {

    public StoreResultDto {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
