package de.homelabs.hippocampus.api;

/**
 * Shared plumbing of the REST surface: the internal source header, its
 * normalization, the result-limit clamp every list endpoint applies, and the
 * required-field checks that replace the schema validation of the former tool
 * layer.
 */
final class ApiSupport {

    /**
     * Internal header carrying the MCP client identity (the harness name from the MCP
     * initialize handshake).
     *
     * <p>{@code source} stays SERVER-DERIVED (V004): the MCP edge — the adapter module
     * — derives it from {@code clientInfo} and writes it into this header; it never
     * appears in a request body, so no caller can claim an arbitrary source through
     * payload fields. The API accepts it only on authenticated calls, i.e. from the
     * gateway it shares its token contract with.
     */
    static final String SOURCE_HEADER = "X-Mcp-Source";

    /** Missing or blank source (direct API calls, non-MCP callers) is recorded as 'unknown'. */
    static String source(String headerValue) {
        return (headerValue == null || headerValue.isBlank()) ? "unknown" : headerValue;
    }

    /** Required text field: the former tool schema rejected these before binding, the API returns 400. */
    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /** Required non-text field (kind, ids inside a body). */
    static <T> T requireValue(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /** Same window the tool layer used: default when absent, clamped to [1, max]. */
    static int clamp(Integer value, int defaultValue, int maxValue) {
        int limit = (value == null) ? defaultValue : value;
        return Math.min(Math.max(limit, 1), maxValue);
    }

    private ApiSupport() {
    }
}
