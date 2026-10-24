package de.homelabs.hippocampus.ltm;

import java.time.Instant;

/**
 * One chunk of a long-term memory entry. {@code seq} is 1-based within the entry
 * and is the number {@code ltm_find}/ltm_get surface to callers. The embedding
 * is write-only from the Java side — semantic search runs inside Postgres via
 * the HNSW {@code <=>} operator, so the vector never needs to round-trip into a
 * Java array.
 */
public record LtmChunk(
        long id,
        long ltmId,
        int seq,
        String chunkText,
        int tokens,
        Instant createdAt) {
}
