package de.homelabs.hippocampus.ltm;

/**
 * Swap point for the embedding backend (ADR 0005): {@link MockEmbeddingProvider}
 * (deterministic, unit/integration tests) and {@link OnnxEmbeddingProvider}
 * (MiniLM-L12-v2 in-process, production). A later model switch re-embeds through
 * this interface without touching the chunker, repository or tools.
 */
public interface EmbeddingProvider {

    /**
     * Embeds a single text into a normalized {@code float[dimension]} vector
     * (384 for MiniLM-L12-v2).
     */
    float[] embed(String text);
}
