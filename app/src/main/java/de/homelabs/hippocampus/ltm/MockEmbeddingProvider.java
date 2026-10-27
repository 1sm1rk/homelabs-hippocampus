package de.homelabs.hippocampus.ltm;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic stand-in for the embedding backend, used by unit and integration
 * tests so nothing depends on the ~449 MB model file. The vector is derived from
 * the text via a stable FNV-1a hash (no {@code String.hashCode} coupling, no
 * randomness across runs), then L2-normalized so it behaves like a real embedding.
 */
public class MockEmbeddingProvider implements EmbeddingProvider {

    public static final int DIMENSION = 384;

    @Override
    public float[] embed(String text) {
        long seed = fnv1a(text == null ? "" : text);
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            // SplitMix64-style deterministic float sequence from the seed + index.
            long z = seed + 0x9E3779B97F4A7C15L * (i + 1);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            // Map 32 low bits to [-1, 1].
            vector[i] = ((z & 0xFFFFFFFFL) / 2147483647.0f) - 1.0f;
        }
        return l2Normalize(vector);
    }

    private static long fnv1a(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static float[] l2Normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }
}
