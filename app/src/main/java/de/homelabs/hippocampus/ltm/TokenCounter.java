package de.homelabs.hippocampus.ltm;

/**
 * Abstraction over a text tokenizer so the {@link Chunker} can be unit-tested
 * without loading the native HuggingFace tokenizer. Production wiring is
 * {@link DjlTokenCounter} (tokenizer.json via DJL, ADR 0005).
 */
public interface TokenCounter {

    /**
     * Number of tokens the tokenizer produces for the given text, special tokens
     * included. Monotonic in text length: appending characters never decreases
     * the count, which the chunker's binary search relies on.
     */
    int count(String text);
}
