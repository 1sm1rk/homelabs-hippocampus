package de.homelabs.hippocampus.ltm;

import java.time.Instant;

/**
 * One long-term memory entry: a promoted STM event or an imported document,
 * stored as it arrived (append-only, ADR 0002). {@code meta} is the raw JSONB
 * text of the meta column. {@code kind} is NULL for imported documents and
 * carries the promoted event's StmKind otherwise. {@code originRef} is the STM
 * event id for {@code promote} and the file path for {@code import};
 * {@code originHash} is the content hash used for unchanged re-import detection.
 */
public record LtmEntry(
        long id,
        String title,
        String content,
        String kind,
        String meta,
        String originType,
        String originRef,
        String originHash,
        String owner,
        String source,
        String actor,
        Instant createdAt) {
}
