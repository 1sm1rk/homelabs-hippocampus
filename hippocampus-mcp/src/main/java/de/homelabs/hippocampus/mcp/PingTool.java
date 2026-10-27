package de.homelabs.hippocampus.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Liveness check: proves the connection to the memory service works end-to-end
 * (used by README verification across harnesses). Since the tools live outside the
 * service, the check travels the full path — MCP call, authenticated API call, answer
 * — so a green ping means the whole chain is intact, not just the MCP edge.
 */
@Component
public class PingTool {

    private final HippocampusApiClient api;

    public PingTool(HippocampusApiClient api) {
        this.api = api;
    }

    @McpTool(description = "Hippocampus liveness check. "
            + "Use to verify the MCP connection to the memory service. "
            + "Returns a fixed pong reply.")
    public String ping() {
        return api.ping().reply();
    }
}
