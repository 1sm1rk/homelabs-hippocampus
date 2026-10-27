package de.homelabs.hippocampus.ltm;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast unit tests for the eager {@link OnnxEmbeddingProvider#init()}.
 * Only bogus paths are used: the file existence checks throw BEFORE any native
 * library load, so these tests are safe in-process — a test that reached an
 * actual native load would segfault the JVM on the exploded classpath (see the
 * repo gotchas).
 */
class OnnxEmbeddingProviderTest {

    private static final int DIM = 384;

    @TempDir
    Path tempDir;

    @Test
    void initFailsFastWhenModelFileIsMissing() {
        OnnxEmbeddingProvider provider = provider(
                tempDir.resolve("missing.onnx").toString(),
                tempDir.resolve("missing-tokenizer.json").toString());

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding model not found")
                .hasMessageContaining("run scripts/install-minilm.sh");
    }

    @Test
    void initFailsFastWhenTokenizerIsMissing() throws Exception {
        Path dummyModel = tempDir.resolve("model.onnx");
        Files.writeString(dummyModel, "not a real model — only the existence check runs");

        OnnxEmbeddingProvider provider = provider(
                dummyModel.toString(),
                tempDir.resolve("missing-tokenizer.json").toString());

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tokenizer not found")
                .hasMessageContaining("run scripts/install-minilm.sh");
    }

    private static OnnxEmbeddingProvider provider(String modelPath, String tokenizerPath) {
        return new OnnxEmbeddingProvider(new LtmProperties.Embedding(modelPath, tokenizerPath, DIM));
    }
}
