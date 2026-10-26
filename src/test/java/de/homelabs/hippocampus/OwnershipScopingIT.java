package de.homelabs.hippocampus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import de.homelabs.hippocampus.ltm.EmbeddingProvider;
import de.homelabs.hippocampus.ltm.LtmRepository;
import de.homelabs.hippocampus.stm.StmRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-side ownership scoping over the real MCP endpoint (strict isolation, ADR 0007
 * / AP-10). Two token subjects and one {@code owner='unknown'} legacy row: every read
 * path (STM find/recent/update, LTM find/get/list incl. the chunk slices) must hide
 * another owner's rows, foreign ids must surface as the plain "not found" error, and
 * the meta registry must stay global (curation data, not user data).
 *
 * <p>All calls go through the HTTP/JSON-RPC surface with a Bearer JWT, so the tool
 * layer's {@code owner()} resolution is part of what is proven here — not only the
 * repository WHERE clauses. Assertions are by unique marker content / id
 * (contains / doesNotContain), never containsExactly: the Testcontainers DB is
 * shared across the methods of this context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
class OwnershipScopingIT {

    /** Token subjects: two humans sharing one service, strict isolation between them. */
    private static final String OWNER_A = "scoping-sub-a";
    private static final String OWNER_B = "scoping-sub-b";
    /** Pre-auth rows (V007/V008 backfill not executed): visible to NO token. */
    private static final String OWNER_UNKNOWN = "unknown";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("hippocampus.ltm.embedding.provider", () -> "mock");
    }

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StmRepository stmRepository;

    @Autowired
    LtmRepository ltmRepository;

    @Autowired
    EmbeddingProvider embeddingProvider;

    @Value("${local.server.port}")
    int port;

    @Test
    void stmReadsSeeOnlyTheOwnersEvents() throws Exception {
        Session a = openSession(OWNER_A);
        Session b = openSession(OWNER_B);

        long id = store(a, "vetinari alpha scoping marker");

        // regression: the owner still sees everything it created
        assertThat(call(a, toolCall("find", "{\"query\":\"vetinari\",\"limit\":50}")))
                .contains("vetinari alpha scoping marker");
        assertThat(call(a, toolCall("list", "{\"limit\":100}")))
                .contains("vetinari alpha scoping marker");

        // the second human sees none of it — neither by keyword nor by listing
        assertThat(call(b, toolCall("find", "{\"query\":\"vetinari\",\"limit\":50}")))
                .doesNotContain("vetinari alpha scoping marker");
        assertThat(call(b, toolCall("list", "{\"limit\":100}")))
                .doesNotContain("vetinari alpha scoping marker");
        assertThat(call(b, toolCall("find", "{\"query\":\"scoping\",\"limit\":50}")))
                .doesNotContain("vetinari alpha scoping marker");

        assertThat(id).isPositive();
    }

    @Test
    void ltmReadsSeeOnlyTheOwnersEntriesIncludingChunks() throws Exception {
        Session a = openSession(OWNER_A);
        Session b = openSession(OWNER_B);

        long stmId = store(a, "grimaldi beta scoping marker");
        long ltmId = promote(a, stmId);

        // owner regression: find excerpt, index line and both get variants resolve
        assertThat(call(a, toolCall("ltm_find", "{\"query\":\"grimaldi\",\"limit\":50}")))
                .contains("grimaldi beta scoping marker");
        assertThat(call(a, toolCall("ltm_list", "{\"limit\":100}")))
                .contains("grimaldi beta scoping marker");
        assertThat(call(a, toolCall("ltm_get", "{\"ltm_id\":%d}".formatted(ltmId))))
                .contains("grimaldi beta scoping marker");
        assertThat(call(a, toolCall("ltm_get", "{\"ltm_id\":%d,\"from_seq\":1,\"to_seq\":1}".formatted(ltmId))))
                .contains("grimaldi beta scoping marker");

        // foreign reads: no hit, no index line, and the chunk queries resolve through
        // the owner-scoped entry — the plain "not found", no existence leak
        assertThat(call(b, toolCall("ltm_find", "{\"query\":\"grimaldi\",\"limit\":50}")))
                .doesNotContain("grimaldi beta scoping marker");
        assertThat(call(b, toolCall("ltm_list", "{\"limit\":100}")))
                .doesNotContain("grimaldi beta scoping marker");
        assertThat(call(b, toolCall("ltm_get", "{\"ltm_id\":%d}".formatted(ltmId))))
                .contains("no long-term entry #" + ltmId)
                .doesNotContain("grimaldi beta scoping marker");
        assertThat(call(b, toolCall("ltm_get", "{\"ltm_id\":%d,\"from_seq\":1,\"to_seq\":1}".formatted(ltmId))))
                .contains("no long-term entry #" + ltmId)
                .doesNotContain("grimaldi beta scoping marker");
    }

    @Test
    void foreignUpdateIsNotFoundAndLeavesTheRowUntouched() throws Exception {
        Session a = openSession(OWNER_A);
        Session b = openSession(OWNER_B);

        long stmId = store(a, "havelock gamma scoping marker");

        // B tries to supersede A's event by raw id
        assertThat(call(b, toolCall("update", "{\"id\":%d,\"content\":\"hijacked delta overwrite\"}".formatted(stmId))))
                .contains("no event #" + stmId + " to update");

        // no supersede marker, no new content, and A still sees the original as active
        Long supersededBy = jdbc.sql("SELECT superseded_by FROM stm_event WHERE id = ?")
                .param(stmId).query(Long.class).optional().orElse(null);
        assertThat(supersededBy).isNull();
        String owner = jdbc.sql("SELECT owner FROM stm_event WHERE id = ?")
                .param(stmId).query(String.class).single();
        assertThat(owner).isEqualTo(OWNER_A);

        assertThat(call(a, toolCall("find", "{\"query\":\"havelock\",\"limit\":50}")))
                .contains("havelock gamma scoping marker");
        assertThat(call(a, toolCall("find", "{\"query\":\"hijacked\",\"limit\":50}")))
                .doesNotContain("hijacked delta overwrite");
        assertThat(call(b, toolCall("find", "{\"query\":\"hijacked\",\"limit\":50}")))
                .doesNotContain("hijacked delta overwrite");
    }

    @Test
    void foreignPromoteIsNotFound() throws Exception {
        Session a = openSession(OWNER_A);
        Session b = openSession(OWNER_B);

        long stmId = store(a, "wintermute epsilon scoping marker");

        assertThat(call(b, toolCall("promote", "{\"stm_id\":%d}".formatted(stmId))))
                .contains("no event #" + stmId + " to promote");

        Integer foreignEntries = jdbc.sql("SELECT count(*) FROM ltm_entry WHERE origin_ref = ? AND owner = ?")
                .param(String.valueOf(stmId)).param(OWNER_B)
                .query(Integer.class).single();
        assertThat(foreignEntries).isZero();

        // the owner's own promote still works (regression)
        long ltmId = promote(a, stmId);
        assertThat(ltmId).isPositive();
    }

    @Test
    void unknownOwnerRowsAreInvisibleToEveryToken() throws Exception {
        Session a = openSession(OWNER_A);
        Session b = openSession(OWNER_B);

        // pre-auth leftovers: STM + LTM rows on the 'unknown' owner
        long unknownStm = stmRepository.save(OWNER_UNKNOWN, "test", "user", "fact",
                "legacy zeta unknown marker", null);
        long unknownLtm = ltmRepository.insertEntry(OWNER_UNKNOWN, "test", "user", "legacy unknown entry",
                "legacy eta unknown content", null, null, "import", "/tmp/legacy-unknown.md", "hash-legacy-eta");
        ltmRepository.insertChunk(unknownLtm, 1, "legacy eta unknown content",
                embeddingProvider.embed("legacy eta unknown content"), 5);

        for (Session session : List.of(a, b)) {
            assertThat(call(session, toolCall("find", "{\"query\":\"zeta\",\"limit\":50}")))
                    .doesNotContain("legacy zeta unknown marker");
            assertThat(call(session, toolCall("list", "{\"limit\":100}")))
                    .doesNotContain("legacy zeta unknown marker");
            // includeSuperseded is no owner-agnostic escape hatch
            assertThat(call(session, toolCall("find", "{\"query\":\"zeta\",\"limit\":50,\"includeSuperseded\":true}")))
                    .doesNotContain("legacy zeta unknown marker");
            assertThat(call(session, toolCall("list", "{\"limit\":100,\"includeSuperseded\":true}")))
                    .doesNotContain("legacy zeta unknown marker");

            assertThat(call(session, toolCall("ltm_find", "{\"query\":\"legacy\",\"limit\":50}")))
                    .doesNotContain("legacy eta unknown content");
            assertThat(call(session, toolCall("ltm_list", "{\"limit\":100}")))
                    .doesNotContain("legacy unknown entry");
            assertThat(call(session, toolCall("ltm_get", "{\"ltm_id\":%d}".formatted(unknownLtm))))
                    .contains("no long-term entry #" + unknownLtm)
                    .doesNotContain("legacy eta unknown content");
        }

        assertThat(unknownStm).isPositive();
    }

    @Test
    void metaKeyRegistryStaysGlobalForAFreshUser() throws Exception {
        Session b = openSession(OWNER_B);

        // a deprecated registry key still warns (registry row consulted) and a
        // canonical key produces no unregistered-key warning — on a fresh subject
        String deprecated = call(b, toolCall("store",
                "{\"actor\":\"user\",\"kind\":\"fact\",\"content\":\"theta scoping marker\",\"meta\":{\"quelle\":\"some doc\"}}"));
        assertThat(deprecated).contains("stored event #").contains("deprecated").contains("origin");

        String canonical = call(b, toolCall("store",
                "{\"actor\":\"user\",\"kind\":\"fact\",\"content\":\"iota scoping marker\",\"meta\":{\"topic\":\"scoping\"}}"));
        assertThat(canonical).contains("stored event #").doesNotContain("unregistered meta keys");
    }

    // --- harness -----------------------------------------------------------------

    private record Session(String token, String sessionId) {}

    private final AtomicInteger requestIds = new AtomicInteger(1);

    private Session openSession(String sub) throws Exception {
        String token = bearerToken(sub);
        var init = post("""
                {"jsonrpc":"2.0","id":%d,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it-scoping","version":"0"}}}
                """.formatted(requestIds.getAndIncrement()), null, token);
        assertThat(init.body()).contains("hippocampus");
        return new Session(token, init.sessionId());
    }

    /** A single tools/call wrapper with its own JSON-RPC id. */
    private String toolCall(String name, String argumentsJson) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(requestIds.getAndIncrement(), name, argumentsJson);
    }

    private String call(Session session, String json) throws Exception {
        return post(json, session.sessionId(), session.token()).body();
    }

    private long store(Session session, String content) throws Exception {
        String body = call(session, toolCall("store",
                "{\"actor\":\"user\",\"kind\":\"fact\",\"content\":\"%s\"}".formatted(content)));
        return extractId(body, "stored event #");
    }

    private long promote(Session session, long stmId) throws Exception {
        String body = call(session, toolCall("promote", "{\"stm_id\":%d}".formatted(stmId)));
        return extractId(body, "ltm entry #");
    }

    private long extractId(String body, String marker) {
        var matcher = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(marker) + "(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("no '" + marker + "<id>' in: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }

    private String bearerToken(String sub) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject(sub)
                .audience(List.of(TestSecurityConfig.AUDIENCE))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private Response post(String json, String sessionId, String token) throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);
        var connection = java.net.HttpURLConnection.class.cast(java.net.URI.create(url).toURL().openConnection());
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            connection.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        try (var out = connection.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        var stream = (status >= 400) ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(body, connection.getHeaderField("Mcp-Session-Id"), status);
    }

    record Response(String body, String sessionId, int status) {}
}
