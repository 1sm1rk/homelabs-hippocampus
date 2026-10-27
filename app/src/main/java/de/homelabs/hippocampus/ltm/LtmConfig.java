package de.homelabs.hippocampus.ltm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Stage 1 LTM building blocks: the chunker, the token counter, and the
 * active embedding provider. Provider selection is config-driven
 * ({@code hippocampus.ltm.embedding.provider=mock|onnx}); the default is onnx.
 * The onnx beans load EAGERLY at startup: {@link DjlTokenCounter#init()} and
 * {@link OnnxEmbeddingProvider#init()} are called inside the bean factories, so a
 * missing or broken model/tokenizer aborts the context refresh instead of
 * surfacing at the first embedding call.
 */
@Configuration
public class LtmConfig {

    @Bean
    Chunker chunker(LtmProperties properties) {
        LtmProperties.Chunking c = properties.chunking();
        return new Chunker(c.targetTokens(), c.overlapTokens(), c.hardCapTokens());
    }

    @Bean
    @ConditionalOnProperty(name = "hippocampus.ltm.embedding.provider", havingValue = "mock")
    TokenCounter mockTokenCounter() {
        return new MockTokenCounter();
    }

    @Bean
    @ConditionalOnProperty(name = "hippocampus.ltm.embedding.provider", havingValue = "onnx", matchIfMissing = true)
    TokenCounter djlTokenCounter(LtmProperties properties) {
        DjlTokenCounter counter = new DjlTokenCounter(properties.embedding().tokenizerPath());
        counter.init();
        return counter;
    }

    @Bean
    @ConditionalOnProperty(name = "hippocampus.ltm.embedding.provider", havingValue = "mock")
    EmbeddingProvider mockEmbeddingProvider() {
        return new MockEmbeddingProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "hippocampus.ltm.embedding.provider", havingValue = "onnx", matchIfMissing = true)
    EmbeddingProvider onnxEmbeddingProvider(LtmProperties properties) {
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider(properties.embedding());
        provider.init();
        return provider;
    }
}
