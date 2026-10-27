package de.homelabs.hippocampus.mcp.dto;

import java.time.Instant;
import java.util.List;

/** Wire shape of one long-term search hit: an excerpt with provenance, never whole content. */
public record LtmFindHitDto(
        long ltmId,
        String title,
        String originType,
        String originRef,
        String excerpt,
        int matchedSeq,
        double score,
        List<String> matchedArms,
        Instant createdAt) {

    public LtmFindHitDto {
        matchedArms = matchedArms == null ? List.of() : List.copyOf(matchedArms);
    }
}
