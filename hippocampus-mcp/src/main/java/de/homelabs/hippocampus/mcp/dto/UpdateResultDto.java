package de.homelabs.hippocampus.mcp.dto;

/** Wire shape of an update reply: {@code id} is the new event, {@code supersededId} the corrected one. */
public record UpdateResultDto(long id, long supersededId) {
}
