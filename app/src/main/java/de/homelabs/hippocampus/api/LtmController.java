package de.homelabs.hippocampus.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.homelabs.hippocampus.ltm.LtmFindHit;
import de.homelabs.hippocampus.ltm.LtmGetResult;
import de.homelabs.hippocampus.ltm.LtmService;
import de.homelabs.hippocampus.ltm.LtmSummary;

/**
 * Long-term memory endpoints: promote an STM event, import a document, then search
 * (hybrid), fetch and list. Same semantics as the former MCP tools.
 *
 * <p>Both ingestion endpoints reply with an entry id and a chunk count; a warned
 * no-op (already promoted, unchanged re-import) reports {@code id = -1} so the MCP
 * edge can keep its "no-op: ..." answer. {@code owner} is the authenticated
 * principal's {@code sub} on every path — a foreign id answers exactly like an
 * absent one.
 */
@RestController
@RequestMapping("/api/ltm")
public class LtmController {

    private final LtmService service;

    public LtmController(LtmService service) {
        this.service = service;
    }

    /** Copy a short-term event into long-term memory (provenance pointer, STM row untouched). */
    @PostMapping("/promote")
    public IngestResponse promote(@RequestBody PromoteRequest request,
            @RequestHeader(value = ApiSupport.SOURCE_HEADER, required = false) String source,
            @AuthenticationPrincipal Jwt jwt) {
        LtmService.PromoteOutcome outcome = service.promote(jwt.getSubject(), ApiSupport.source(source),
                ApiSupport.requireValue(request.stmId(), "stmId"), request.title());
        return new IngestResponse(outcome.id(), outcome.chunkCount(), outcome.warnings());
    }

    /** Import a txt/md/PDF file the service process can read (chunked + embedded). */
    @PostMapping("/imports")
    public IngestResponse importFile(@RequestBody ImportRequest request,
            @RequestHeader(value = ApiSupport.SOURCE_HEADER, required = false) String source,
            @AuthenticationPrincipal Jwt jwt) {
        LtmService.ImportOutcome outcome = service.importFile(jwt.getSubject(), ApiSupport.source(source),
                ApiSupport.requireText(request.path(), "path"), request.title(), request.meta());
        return new IngestResponse(outcome.id(), outcome.chunkCount(), outcome.warnings());
    }

    /** Hybrid search: excerpts with provenance, never whole entries. */
    @GetMapping("/search")
    public List<LtmFindHit> search(@RequestParam String query,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        return service.find(jwt.getSubject(), query, ApiSupport.clamp(limit, 5, 50));
    }

    /** The long-term memory index, newest first — no content. */
    @GetMapping("/entries")
    public List<LtmSummary> list(@RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject(), ApiSupport.clamp(limit, 20, 100));
    }

    /** One entry: full content when no chunk range is given, a 1-based chunk slice otherwise. */
    @GetMapping("/entries/{id}")
    public LtmGetResult get(@PathVariable long id,
            @RequestParam(required = false) Integer fromSeq,
            @RequestParam(required = false) Integer toSeq,
            @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), id, fromSeq, toSeq);
    }

    /** Body of {@code POST /api/ltm/promote}. */
    public record PromoteRequest(Long stmId, String title) {
    }

    /** Body of {@code POST /api/ltm/imports}; the path is read by the service process itself. */
    public record ImportRequest(String path, String title, Map<String, String> meta) {
    }

    /** {@code id = -1} means a warned no-op; otherwise the new entry id and its chunk count. */
    public record IngestResponse(long id, int chunkCount, List<String> warnings) {
        public IngestResponse {
            warnings = List.copyOf(warnings);
        }
    }
}
