package de.homelabs.hippocampus.ltm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 1 LTM configuration. The {@code provider} key is not bound here — it is
 * read directly by {@code @ConditionalOnProperty} in {@link LtmConfig} to select
 * the active {@link EmbeddingProvider} (mock vs onnx).
 *
 * @param chunking  MiniLM-tuned chunking (ADR 0005): ~115 tokens, ~24 overlap,
 *                  hard cap 128 (the model's max sequence length).
 * @param embedding model + tokenizer paths and the vector dimension.
 */
@ConfigurationProperties(prefix = "hippocampus.ltm")
public record LtmProperties(Chunking chunking, Embedding embedding) {

    public record Chunking(int targetTokens, int overlapTokens, int hardCapTokens) {
    }

    public record Embedding(String modelPath, String tokenizerPath, int dimension) {
    }
}
