package de.homelabs.hippocampus.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness of the service, in two shapes:
 *
 * <ul>
 *   <li>{@code GET /api/ping} — the authenticated probe MCP callers reach through the
 *       adapter; answering it proves the whole authenticated API path (token
 *       validation, scoping context, controller) works.</li>
 *   <li>{@code GET /health} — the open probe for container healthchecks; it returns
 *       no memory data, so it needs no credentials.</li>
 * </ul>
 */
@RestController
public class OpsController {

    private static final String PONG = "pong: hippocampus alive";

    @GetMapping("/api/ping")
    public PingResponse ping() {
        return new PingResponse(PONG);
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "hippocampus");
    }

    public record PingResponse(String reply) {
    }

    public record HealthResponse(String status, String service) {
    }
}
