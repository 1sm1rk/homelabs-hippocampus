package de.homelabs.hippocampus.ltm;

import java.time.Instant;
import java.util.List;

/**
 * One {@code ltm_find} result: a short excerpt with provenance, never the whole
 * entry (#13). {@code matchedSeq} is the 1-based chunk number the excerpt came
 * from (the same number {@code ltm_get} slices by); {@code matchedArms} names
 * the search arms that contributed to the fused score.
 */
public record LtmFindHit(
        long ltmId,
        String title,
        String originType,
        String originRef,
        String excerpt,
        int matchedSeq,
        double score,
        List<String> matchedArms,
        Instant createdAt) {

    public LtmFindHit {
        matchedArms = List.copyOf(matchedArms);
    }
}
