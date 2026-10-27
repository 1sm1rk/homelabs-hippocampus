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

import de.homelabs.hippocampus.mcp.dto.IngestResultDto;
import de.homelabs.hippocampus.mcp.dto.LtmFindHitDto;
import de.homelabs.hippocampus.mcp.dto.LtmGetResultDto;
import de.homelabs.hippocampus.mcp.dto.StmEventDto;
import de.homelabs.hippocampus.mcp.dto.StmKind;
import de.homelabs.hippocampus.mcp.dto.StoreResultDto;
import de.homelabs.hippocampus.mcp.dto.UpdateResultDto;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter's HTTP contract against a fake API: the incoming token is forwarded on
 * every call, the source travels in the internal header (and never in the body), the
 * request shapes match the API's endpoints, and the API's error message survives as the
 * tool error text.
 */
class HippocampusApiClientIT {

    private static final String INCOMING = "Bearer incoming-harness-token";

    private final FakeHippocampusApi fake = new FakeHippocampusApi();

    private HippocampusApiClient client;

    @BeforeEach
    void setUp() {
        client = new HippocampusApiClient(RestClient.builder(), JsonMapper.builder().build(), fake.baseUrl());
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
    void storeForwardsTheTokenAndSendsTheSourceHeader() {
        fake.on("POST", "/api/stm/events", 200, """
                {"id":42,"warnings":["meta key 'quelle' is deprecated — prefer 'origin'"]}""");

        StoreResultDto result = client.store("opencode", "user", StmKind.fact, "content", Map.of("topic", "x"));

        assertThat(result.id()).isEqualTo(42);
        assertThat(result.warnings()).containsExactly("meta key 'quelle' is deprecated — prefer 'origin'");

        FakeHippocampusApi.Call call = fake.onlyCall();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/stm/events");
        assertThat(call.authorization()).isEqualTo(INCOMING);
        assertThat(call.source()).isEqualTo("opencode");
        assertThat(call.body()).contains("\"actor\":\"user\"").contains("\"kind\":\"fact\"")
                .doesNotContain("source");
    }

    @Test
    void updateTargetsTheEventAndMapsTheSupersedeIds() {
        fake.on("PUT", "/api/stm/events/7", 200, """
                {"id":8,"supersededId":7}""");

        UpdateResultDto result = client.update("cli", 7, "corrected", null, null, null);

        assertThat(result.id()).isEqualTo(8);
        assertThat(result.supersededId()).isEqualTo(7);
        assertThat(fake.onlyCall().authorization()).isEqualTo(INCOMING);
        assertThat(fake.onlyCall().source()).isEqualTo("cli");
    }

    @Test
    void searchSendsTheQueryAndTheOptionalLimit() {
        fake.on("GET", "/api/stm/events", 200, """
                [{"id":1,"source":"cli","actor":"user","kind":"fact","content":"something",
                  "meta":"{}","createdAt":"2026-10-27T10:00:00Z","supersededBy":null,"supersededAt":null}]""");

        List<StmEventDto> events = client.find("something", 5, true);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().content()).isEqualTo("something");
        assertThat(events.getFirst().createdAt()).isEqualTo(java.time.Instant.parse("2026-10-27T10:00:00Z"));
        assertThat(fake.onlyCall().query()).contains("query=something").contains("limit=5")
                .contains("includeSuperseded=true");
    }

    @Test
    void longTermGetMapsChunkSlices() {
        fake.on("GET", "/api/ltm/entries/9", 200, """
                {"ltmId":9,"title":"doc","kind":null,"originType":"import","originRef":"/tmp/doc.md",
                 "createdAt":"2026-10-27T10:00:00Z","totalChunks":2,"content":null,
                 "chunks":[{"seq":1,"text":"first"}]}""");

        LtmGetResultDto result = client.ltmGet(9, 1, 1);

        assertThat(result.ltmId()).isEqualTo(9);
        assertThat(result.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.seq()).isEqualTo(1);
            assertThat(chunk.text()).isEqualTo("first");
        });
        assertThat(fake.onlyCall().query()).contains("fromSeq=1").contains("toSeq=1");
    }

    @Test
    void longTermSearchMapsExcerptsWithProvenance() {
        fake.on("GET", "/api/ltm/search", 200, """
                [{"ltmId":3,"title":"entry","originType":"promote","originRef":"17","excerpt":"…excerpt…",
                  "matchedSeq":2,"score":0.5,"matchedArms":["cosine","fts"],"createdAt":"2026-10-27T10:00:00Z"}]""");

        List<LtmFindHitDto> hits = client.ltmFind("excerpt", null);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.matchedSeq()).isEqualTo(2);
            assertThat(hit.matchedArms()).containsExactly("cosine", "fts");
        });
        assertThat(fake.onlyCall().query()).isEqualTo("query=excerpt");
    }

    @Test
    void promoteReportsANoOpAsNegativeId() {
        fake.on("POST", "/api/ltm/promote", 200, """
                {"id":-1,"chunkCount":0,"warnings":["event #5 is already in long-term memory (no-op)"]}""");

        IngestResultDto result = client.promote("cli", 5, null);

        assertThat(result.id()).isNegative();
        assertThat(result.warnings()).hasSize(1);
        assertThat(fake.onlyCall().body()).contains("\"stmId\":5");
    }

    @Test
    void domainErrorsKeepTheApiMessage() {
        fake.on("POST", "/api/ltm/promote", 400, """
                {"error":"no event #5 to promote"}""");

        assertThatThrownBy(() -> client.promote("cli", 5, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no event #5 to promote");
    }

    @Test
    void callsWithoutAnIncomingTokenAreRefused() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> client.ping())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no incoming Authorization header");
        assertThat(fake.calls()).isEmpty();
    }
}
