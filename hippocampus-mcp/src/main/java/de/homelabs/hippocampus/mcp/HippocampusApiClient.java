package de.homelabs.hippocampus.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import de.homelabs.hippocampus.mcp.dto.IngestResultDto;
import de.homelabs.hippocampus.mcp.dto.LtmFindHitDto;
import de.homelabs.hippocampus.mcp.dto.LtmGetResultDto;
import de.homelabs.hippocampus.mcp.dto.LtmSummaryDto;
import de.homelabs.hippocampus.mcp.dto.PingDto;
import de.homelabs.hippocampus.mcp.dto.StmEventDto;
import de.homelabs.hippocampus.mcp.dto.StmKind;
import de.homelabs.hippocampus.mcp.dto.StoreResultDto;
import de.homelabs.hippocampus.mcp.dto.UpdateResultDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Typed client for the hippocampus REST API: one method per memory operation, one
 * HTTP call per method. It compiles against the JSON contract only — no service
 * classes are on its classpath, which is what keeps the adapter a separate artifact.
 *
 * <p><strong>Token forwarding.</strong> Every call copies the INCOMING harness token
 * — the {@code Authorization} header of the MCP request being served — verbatim onto
 * the API call. The gateway validated that token before invoking the tool; the API
 * validates it again (issuer + audience) and derives {@code owner} from its
 * {@code sub}. The subject is never sent as a parameter, so an agent cannot act as
 * another user. The token is read per call (no caching) and never logged.
 *
 * <p><strong>Source header.</strong> Write calls carry the MCP client identity in the
 * {@code X-Mcp-Source} header. The value is derived in the tool layer from the MCP
 * initialize handshake ({@code clientInfo}), never from tool arguments — the V004
 * rule, now crossing a process boundary: the API accepts {@code source} only from this
 * header on authenticated calls and never from a request body field.
 */
@Component
public class HippocampusApiClient {

    /**
     * Internal header carrying the MCP client identity from the adapter to the API.
     * The API side reads it as the only accepted {@code source} input.
     */
    static final String SOURCE_HEADER = "X-Mcp-Source";

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public HippocampusApiClient(RestClient.Builder builder, ObjectMapper objectMapper,
            @Value("${hippocampus.mcp.api.base-url:http://localhost:8092}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    StoreResultDto store(String source, String actor, StmKind kind, String content, Map<String, String> meta) {
        return body(write(HttpMethod.POST, source, "/api/stm/events")
                .body(new StoreCommand(actor, kind, content, meta)), StoreResultDto.class);
    }

    UpdateResultDto update(String source, long id, String content, StmKind kind, String actor,
            Map<String, String> meta) {
        return body(write(HttpMethod.PUT, source, "/api/stm/events/" + id)
                .body(new UpdateCommand(content, kind, actor, meta)), UpdateResultDto.class);
    }

    List<StmEventDto> find(String query, Integer limit, Boolean includeSuperseded) {
        return body(client.get()
                .uri(uri -> uri.path("/api/stm/events")
                        .queryParamIfPresent("query", Optional.ofNullable(query))
                        .queryParamIfPresent("limit", Optional.ofNullable(limit))
                        .queryParamIfPresent("includeSuperseded", Optional.ofNullable(includeSuperseded))
                        .build()), new ParameterizedTypeReference<List<StmEventDto>>() {
                });
    }

    List<StmEventDto> recent(Integer limit, Boolean includeSuperseded) {
        return body(client.get()
                .uri(uri -> uri.path("/api/stm/events/recent")
                        .queryParamIfPresent("limit", Optional.ofNullable(limit))
                        .queryParamIfPresent("includeSuperseded", Optional.ofNullable(includeSuperseded))
                        .build()), new ParameterizedTypeReference<List<StmEventDto>>() {
                });
    }

    IngestResultDto promote(String source, long stmId, String title) {
        return body(write(HttpMethod.POST, source, "/api/ltm/promote").body(new PromoteCommand(stmId, title)),
                IngestResultDto.class);
    }

    IngestResultDto importFile(String source, String path, String title, Map<String, String> meta) {
        return body(write(HttpMethod.POST, source, "/api/ltm/imports").body(new ImportCommand(path, title, meta)),
                IngestResultDto.class);
    }

    List<LtmFindHitDto> ltmFind(String query, Integer limit) {
        return body(client.get()
                .uri(uri -> uri.path("/api/ltm/search")
                        .queryParam("query", query)
                        .queryParamIfPresent("limit", Optional.ofNullable(limit))
                        .build()), new ParameterizedTypeReference<List<LtmFindHitDto>>() {
                });
    }

    LtmGetResultDto ltmGet(long ltmId, Integer fromSeq, Integer toSeq) {
        return body(client.get()
                .uri(uri -> uri.path("/api/ltm/entries/{id}")
                        .queryParamIfPresent("fromSeq", Optional.ofNullable(fromSeq))
                        .queryParamIfPresent("toSeq", Optional.ofNullable(toSeq))
                        .build(ltmId)), LtmGetResultDto.class);
    }

    List<LtmSummaryDto> ltmList(Integer limit) {
        return body(client.get()
                .uri(uri -> uri.path("/api/ltm/entries")
                        .queryParamIfPresent("limit", Optional.ofNullable(limit))
                        .build()), new ParameterizedTypeReference<List<LtmSummaryDto>>() {
                });
    }

    PingDto ping() {
        return body(client.get().uri("/api/ping"), PingDto.class);
    }

    // --- plumbing --------------------------------------------------------------

    /** Write call with the source header; the token is added by {@link #forwardToken}. */
    private RestClient.RequestBodySpec write(HttpMethod method, String source, String path) {
        return client.method(method).uri(path)
                .header(SOURCE_HEADER, source == null || source.isBlank() ? "unknown" : source);
    }

    /**
     * Copies the incoming {@code Authorization} header onto the outgoing call. Reading
     * the raw header (instead of a security-context object) keeps the adapter free of
     * any auth framework: whoever authorized the MCP request already validated the
     * token, and the API validates it again.
     */
    private void forwardToken(HttpHeaders headers) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String authorization = servletAttributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
                return;
            }
        }
        throw new IllegalStateException("no incoming Authorization header to forward to the hippocampus API");
    }

    private <T> T body(RestClient.RequestHeadersSpec<?> spec, Class<T> type) {
        return errorAware(spec.headers(this::forwardToken).retrieve()).body(type);
    }

    private <T> T body(RestClient.RequestHeadersSpec<?> spec, ParameterizedTypeReference<T> type) {
        return errorAware(spec.headers(this::forwardToken).retrieve()).body(type);
    }

    private RestClient.ResponseSpec errorAware(RestClient.ResponseSpec spec) {
        return spec.onStatus(status -> status.isError(), (request, response) -> {
            throw new IllegalArgumentException(
                    errorMessage(response.getStatusCode().value(), readBody(response)));
        });
    }

    private String readBody(ClientHttpResponse response) {
        try (InputStream stream = response.getBody()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The API answers domain errors as {@code {"error":"<message>"}} with 400 — the same
     * message text the tool layer used to raise. Unwrapping it keeps the tool error
     * wording stable for the agent.
     */
    private String errorMessage(int status, String body) {
        try {
            JsonNode error = objectMapper.readTree(body).get("error");
            if (error != null && !error.asString().isBlank()) {
                return error.asString();
            }
        } catch (RuntimeException ignored) {
            // an unparseable error body is reported as-is below
        }
        return "hippocampus API answered " + status + ": " + body;
    }

    // --- outbound payloads (the API's request contract) -------------------------

    record StoreCommand(String actor, StmKind kind, String content, Map<String, String> meta) {
    }

    record UpdateCommand(String content, StmKind kind, String actor, Map<String, String> meta) {
    }

    record PromoteCommand(long stmId, String title) {
    }

    record ImportCommand(String path, String title, Map<String, String> meta) {
    }
}
