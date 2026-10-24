package de.homelabs.hippocampus.ltm;

/**
 * One chunk of an {@code ltm_get} result: the 1-based {@code seq} and its text.
 */
public record LtmChunkSlice(int seq, String text) {
}
