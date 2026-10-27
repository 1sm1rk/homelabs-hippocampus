package de.homelabs.hippocampus.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.homelabs.hippocampus.stm.StmEvent;
import de.homelabs.hippocampus.stm.StmKind;
import de.homelabs.hippocampus.stm.StmService;

/**
 * Short-term memory endpoints: store events as they are, correct them via update,
 * find by keyword, list the most recent. Requests and responses carry exactly the
 * semantics of the former MCP tools — the entry point moved from MCP to HTTP, the
 * behavior did not change.
 *
 * <p>{@code owner} is always the authenticated principal's {@code sub}; {@code source}
 * comes only from the internal {@link ApiSupport#SOURCE_HEADER} header (never from a
 * body field). Strict isolation: reads see the caller's rows only, and a foreign id
 * surfaces as the same "not found" answer an absent id gets.
 */
@RestController
@RequestMapping("/api/stm/events")
public class StmEventsController {

    private final StmService service;

    public StmEventsController(StmService service) {
        this.service = service;
    }

    /** Store one event; the reply carries the new id plus any non-fatal meta-key warnings. */
    @PostMapping
    public StoreResponse store(@RequestBody StoreRequest request,
            @RequestHeader(value = ApiSupport.SOURCE_HEADER, required = false) String source,
            @AuthenticationPrincipal Jwt jwt) {
        StmService.StoreOutcome outcome = service.store(jwt.getSubject(), ApiSupport.source(source),
                ApiSupport.requireText(request.actor(), "actor"),
                ApiSupport.requireValue(request.kind(), "kind").value(),
                ApiSupport.requireText(request.content(), "content"),
                request.meta());
        return new StoreResponse(outcome.id(), outcome.warnings());
    }

    /**
     * Correct an event: writes a new event and marks the old one superseded (never
     * overwrites). Absent fields are inherited, meta is merged per key.
     */
    @PutMapping("/{id}")
    public UpdateResponse update(@PathVariable long id, @RequestBody UpdateRequest request,
            @RequestHeader(value = ApiSupport.SOURCE_HEADER, required = false) String source,
            @AuthenticationPrincipal Jwt jwt) {
        long newId = service.update(jwt.getSubject(), ApiSupport.source(source), id, request.content(),
                request.kind(), request.actor(), request.meta());
        return new UpdateResponse(newId, id);
    }

    /** Keyword search, newest first; superseded events are excluded unless asked for. */
    @GetMapping
    public List<StmEvent> find(@RequestParam String query,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Boolean includeSuperseded,
            @AuthenticationPrincipal Jwt jwt) {
        return service.find(jwt.getSubject(), query, ApiSupport.clamp(limit, 10, 50),
                Boolean.TRUE.equals(includeSuperseded));
    }

    /** The most recent events, newest first. */
    @GetMapping("/recent")
    public List<StmEvent> recent(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Boolean includeSuperseded,
            @AuthenticationPrincipal Jwt jwt) {
        return service.recent(jwt.getSubject(), ApiSupport.clamp(limit, 20, 100),
                Boolean.TRUE.equals(includeSuperseded));
    }

    /** Body of {@code POST /api/stm/events}; {@code source} is deliberately absent (see the class javadoc). */
    public record StoreRequest(String actor, StmKind kind, String content, Map<String, String> meta) {
    }

    public record StoreResponse(long id, List<String> warnings) {
        public StoreResponse {
            warnings = List.copyOf(warnings);
        }
    }

    /** Body of {@code PUT /api/stm/events/{id}}; every field is optional (inherited when absent). */
    public record UpdateRequest(String content, StmKind kind, String actor, Map<String, String> meta) {
    }

    /** {@code id} is the new (corrected) event, {@code supersededId} the event it replaces. */
    public record UpdateResponse(long id, long supersededId) {
    }
}
