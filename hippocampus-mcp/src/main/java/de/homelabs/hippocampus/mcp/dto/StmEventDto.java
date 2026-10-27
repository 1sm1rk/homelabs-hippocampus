package de.homelabs.hippocampus.mcp.dto;

import java.time.Instant;

/**
 * Wire shape of one short-term memory event as the REST API returns it. Field names
 * mirror the API's {@code StmEvent} record exactly, so the MCP tool results keep the
 * shape they had when the tools called the domain directly.
 *
 * <p>The adapter owns its own copy of the contract on purpose: it compiles against
 * HTTP and JSON only, never against the service's classes.
 */
public record StmEventDto(
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
