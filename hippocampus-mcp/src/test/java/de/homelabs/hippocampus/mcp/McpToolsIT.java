package de.homelabs.hippocampus.mcp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import de.homelabs.hippocampus.mcp.dto.StmEventDto;
import de.homelabs.hippocampus.mcp.dto.StmKind;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tool → REST chain end to end: the tool classes call the fake API with the
 * harness token and the MCP client identity, and their replies read exactly as they
 * did when they called the domain directly. That reply text is the agent-facing
 * contract, so it is asserted character for character.
 */
class McpToolsIT {

    private static final String INCOMING = "Bearer incoming-harness-token";

    private final FakeHippocampusApi fake = new FakeHippocampusApi();

    private StmTools stmTools;
    private LtmTools ltmTools;
    private PingTool pingTool;

    @BeforeEach
    void setUp() {
        HippocampusApiClient api = new HippocampusApiClient(RestClient.builder(),
                JsonMapper.builder().build(), fake.baseUrl());
        stmTools = new StmTools(api);
        ltmTools = new LtmTools(api);
        pingTool = new PingTool(api);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", INCOMING);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        fake.close();
    }

    @Test
    void storeForwardsTheClientIdentityAndKeepsTheToolReply() {
        fake.on("POST", "/api/stm/events", 200, """
                {"id":42,"warnings":["unregistered meta keys (curation candidates): brandnew"]}""");

        String reply = stmTools.store(exchange("opencode"), "user", StmKind.fact, "hello", Map.of("brandnew", "v"));

        assertThat(reply).isEqualTo(
                "stored event #42\nunregistered meta keys (curation candidates): brandnew");
        assertThat(fake.onlyCall().source()).isEqualTo("opencode");
        assertThat(fake.onlyCall().authorization()).isEqualTo(INCOMING);
        assertThat(fake.onlyCall().body()).doesNotContain("opencode").doesNotContain("source");
    }

    @Test
    void missingClientInfoFallsBackToUnknown() {
        fake.on("POST", "/api/stm/events", 200, """
                {"id":43,"warnings":[]}""");

        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(null);

        assertThat(stmTools.store(exchange, "user", StmKind.note, "no client info", null))
                .isEqualTo("stored event #43");
        assertThat(fake.onlyCall().source()).isEqualTo("unknown");
    }

    @Test
    void updateKeepsTheFormerReplyText() {
        fake.on("PUT", "/api/stm/events/7", 200, """
                {"id":8,"supersededId":7}""");

        assertThat(stmTools.update(exchange("cli"), 7, "corrected", null, null, null))
                .isEqualTo("updated: event #8 supersedes #7");
    }

    @Test
    void promoteKeepsTheFormerReplyText() {
        fake.on("POST", "/api/ltm/promote", 200, """
                {"id":12,"chunkCount":2,"warnings":[]}""");

        assertThat(ltmTools.promote(exchange("cli"), 5, null))
                .isEqualTo("promoted event #5 → ltm entry #12 (2 chunks)");
    }

    @Test
    void promoteReportsANoOpAsBefore() {
        fake.on("POST", "/api/ltm/promote", 200, """
                {"id":-1,"chunkCount":0,"warnings":["event #5 is already in long-term memory (no-op)"]}""");

        assertThat(ltmTools.promote(exchange("cli"), 5, null))
                .isEqualTo("no-op: event #5 is already in long-term memory (no-op)");
    }

    @Test
    void importFileKeepsTheFormerReplyTextWithWarnings() {
        fake.on("POST", "/api/ltm/imports", 200, """
                {"id":3,"chunkCount":1,"warnings":["meta key 'quelle' is deprecated — prefer 'origin'"]}""");

        assertThat(ltmTools.import_file(exchange("cli"), "/tmp/a.md", null, null))
                .isEqualTo("imported /tmp/a.md → ltm entry #3 (1 chunks)\n"
                        + "meta key 'quelle' is deprecated — prefer 'origin'");
    }

    @Test
    void findReturnsTheMappedEvents() {
        fake.on("GET", "/api/stm/events", 200, """
                [{"id":1,"source":"cli","actor":"user","kind":"fact","content":"something",
                  "meta":"{}","createdAt":"2026-10-27T10:00:00Z","supersededBy":null,"supersededAt":null}]""");

        List<StmEventDto> events = stmTools.find("something", 5, null);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo(1);
            assertThat(event.source()).isEqualTo("cli");
            assertThat(event.kind()).isEqualTo("fact");
        });
        assertThat(fake.onlyCall().query()).contains("query=something").contains("limit=5");
    }

    @Test
    void pingAnswersTheServicesPong() {
        fake.on("GET", "/api/ping", 200, """
                {"reply":"pong: hippocampus alive"}""");

        assertThat(pingTool.ping()).isEqualTo("pong: hippocampus alive");
        assertThat(fake.onlyCall().authorization()).isEqualTo(INCOMING);
    }

    @Test
    void domainErrorsSurfaceAsToolErrors() {
        fake.on("PUT", "/api/stm/events/7", 400, """
                {"error":"no event #7 to update"}""");

        assertThatThrownBy(() -> stmTools.update(exchange("cli"), 7, "hijacked", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no event #7 to update");
    }

    private McpSyncServerExchange exchange(String clientName) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation(clientName, "1.0.0"));
        return exchange;
    }
}
