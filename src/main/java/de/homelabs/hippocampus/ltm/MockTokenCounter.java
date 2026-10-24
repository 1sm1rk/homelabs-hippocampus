package de.homelabs.hippocampus.ltm;

/**
 * Deterministic stand-in for the tokenizer-based {@link TokenCounter}, wired
 * alongside {@link MockEmbeddingProvider} when the mock provider is selected
 * (tests), so the chunker runs without the ~449 MB model files. Counts
 * whitespace-delimited words, which is monotonic in text length as the
 * chunker's binary search requires.
 */
public class MockTokenCounter implements TokenCounter {

    @Override
    public int count(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
