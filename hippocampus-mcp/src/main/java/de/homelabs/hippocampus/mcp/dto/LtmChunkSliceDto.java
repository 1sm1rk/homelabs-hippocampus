package de.homelabs.hippocampus.mcp.dto;

/** Wire shape of one chunk slice: the 1-based {@code seq} and its text. */
public record LtmChunkSliceDto(int seq, String text) {
}
