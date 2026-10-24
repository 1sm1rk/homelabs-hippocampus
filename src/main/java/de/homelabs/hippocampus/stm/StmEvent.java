package de.homelabs.hippocampus.stm;

import java.time.Instant;

/**
 * One short-term memory event, stored as it was received (ADR 0002).
 * Meta is the raw JSONB text of the meta column.
 * {@code supersededBy}/{@code supersededAt} are NULL for an active event and set
 * once a later event supersedes it (mechanized supersedes, V006): the content is
 * never mutated or deleted, only marked hard-to-find.
 */
public record StmEvent(
        long id,
        String source,
        String actor,
        String kind,
        String content,
        String meta,
        Instant createdAt,
        Long supersededBy,
        Instant supersededAt) {
}
