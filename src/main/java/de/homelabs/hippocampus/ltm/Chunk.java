package de.homelabs.hippocampus.ltm;

/**
 * One chunk produced by the {@link Chunker}: the chunk text plus its token count
 * (as measured by the embedding tokenizer, special tokens included). The list
 * order is the sequence; the caller assigns the 1-based {@code seq} column.
 */
public record Chunk(String text, int tokenCount) {
}
