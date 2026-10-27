package de.homelabs.hippocampus.mcp.dto;

/**
 * Wire vocabulary of the {@code kind} field: the closed set the service stores.
 *
 * <p>The constants ARE the wire values (lowercase) so the generated MCP tool schema,
 * the HTTP JSON and the database share one vocabulary — the same convention the
 * service's domain enum follows. Keeping the copy here is what makes the adapter
 * independent of the service's classes: it compiles against the JSON contract only.
 */
public enum StmKind {
    fact,
    note,
    task,
    decision
}
