package de.homelabs.hippocampus.ltm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmbeddingProviderTest {

    private final MockEmbeddingProvider provider = new MockEmbeddingProvider();

    @Test
    void producesVectorsOfTheModelDimension() {
        assertThat(provider.embed("hello world")).hasSize(MockEmbeddingProvider.DIMENSION);
        assertThat(provider.embed("")).hasSize(MockEmbeddingProvider.DIMENSION);
    }

    @Test
    void isDeterministicAcrossCallsAndInstances() {
        float[] first = provider.embed("paraphrase this");
        float[] second = provider.embed("paraphrase this");
        float[] other = new MockEmbeddingProvider().embed("paraphrase this");

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(other);
    }

    @Test
    void differentTextsYieldDifferentVectors() {
        assertThat(provider.embed("alpha")).isNotEqualTo(provider.embed("beta"));
    }

    @Test
    void vectorsAreL2Normalized() {
        float[] vector = provider.embed("normalize me");
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }
}
