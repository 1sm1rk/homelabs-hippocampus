package de.homelabs.hippocampus.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Liveness check for the MCP endpoint: proves the connection to the
 * memory service works end-to-end (used by README verification across
 * harnesses). Kept since Stage 0 as the universal connection proof.
 */
@Component
public class PingTool {

    @McpTool(description = "Hippocampus liveness check. "
            + "Use to verify the MCP connection to the memory service. "
            + "Returns a fixed pong reply.")
    public String ping() {
        return "pong: hippocampus alive";
    }
}