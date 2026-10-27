package de.homelabs.hippocampus.mcp;

import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * Derives the memory's {@code source} — the client identity — from the MCP initialize
 * handshake.
 *
 * <p>{@code clientInfo} is information the client sent during the handshake, not a
 * tool argument: tool arguments are client-declared and spoofable, so {@code source}
 * must never come from them (V004). The adapter forwards the value to the API in the
 * {@code X-Mcp-Source} header, which is the only place the API accepts it from.
 */
final class McpClientIdentity {

    /** Falls back to 'unknown' when the exchange or its clientInfo is absent (defensive; a SYNC server always provides it). */
    static String source(McpSyncServerExchange exchange) {
        if (exchange != null && exchange.getClientInfo() != null && exchange.getClientInfo().name() != null
                && !exchange.getClientInfo().name().isBlank()) {
            return exchange.getClientInfo().name();
        }
        return "unknown";
    }

    private McpClientIdentity() {
    }
}
