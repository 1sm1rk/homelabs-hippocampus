package de.homelabs.hippocampus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
 * Ownership scoping over the real REST surface (strict isolation): two token subjects
 * and one {@code owner='unknown'} legacy row. Every read path (STM find/recent, LTM
 * search/get/list incl. the chunk slices) must hide another owner's rows, foreign ids
 * must surface as the plain "not found" error, and the meta registry must stay global
 * (curation data, not user data).
 *
 * <p>All calls go through HTTP with a Bearer JWT, so the controllers' owner resolution
 * is part of what is proven here — not only the repository WHERE clauses. Assertions
 * are by unique marker content / id (contains / doesNotContain), never containsExactly:
 * the Testcontainers DB is shared across the methods of this context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
class OwnershipScopingIT {

    /** Token subjects: two humans sharing one service, strict isolation between them. */
    private static final String OWNER_A = "scoping-sub-a";
    private static final String OWNER_B = "scoping-sub-b";
    /** Pre-auth rows (backfill not executed): visible to NO token. */
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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void stmReadsSeeOnlyTheOwnersEvents() throws Exception {
        String a = bearerToken(OWNER_A);
        String b = bearerToken(OWNER_B);

        long id = store(a, "vetinari alpha scoping marker");

        // regression: the owner still sees everything it created
        assertThat(get("/api/stm/events?query=vetinari", a).body()).contains("vetinari alpha scoping marker");
        assertThat(get("/api/stm/events/recent?limit=100", a).body()).contains("vetinari alpha scoping marker");

        // the second human sees none of it — neither by keyword nor by listing
        assertThat(get("/api/stm/events?query=vetinari", b).body()).doesNotContain("vetinari alpha scoping marker");
        assertThat(get("/api/stm/events/recent?limit=100", b).body()).doesNotContain("vetinari alpha scoping marker");
        assertThat(get("/api/stm/events?query=scoping", b).body()).doesNotContain("vetinari alpha scoping marker");

        assertThat(id).isPositive();
    }

    @Test
    void ltmReadsSeeOnlyTheOwnersEntriesIncludingChunks() throws Exception {
        String a = bearerToken(OWNER_A);
        String b = bearerToken(OWNER_B);

        long stmId = store(a, "grimaldi beta scoping marker");
        long ltmId = promote(a, stmId);

        // owner regression: search excerpt, index line and both get variants resolve
        assertThat(get("/api/ltm/search?query=grimaldi", a).body()).contains("grimaldi beta scoping marker");
        assertThat(get("/api/ltm/entries?limit=100", a).body()).contains("grimaldi beta scoping marker");
        assertThat(get("/api/ltm/entries/" + ltmId, a).body()).contains("grimaldi beta scoping marker");
        assertThat(get("/api/ltm/entries/%d?fromSeq=1&toSeq=1".formatted(ltmId), a).body())
                .contains("grimaldi beta scoping marker");

        // foreign reads: no hit, no index line, and the chunk queries resolve through
        // the owner-scoped entry — the plain "not found", no existence leak
        assertThat(get("/api/ltm/search?query=grimaldi", b).body()).doesNotContain("grimaldi beta scoping marker");
        assertThat(get("/api/ltm/entries?limit=100", b).body()).doesNotContain("grimaldi beta scoping marker");

        Response full = get("/api/ltm/entries/" + ltmId, b);
        assertThat(full.status()).isEqualTo(400);
        assertThat(full.body()).contains("no long-term entry #" + ltmId).doesNotContain("grimaldi beta scoping marker");

        Response slice = get("/api/ltm/entries/%d?fromSeq=1&toSeq=1".formatted(ltmId), b);
        assertThat(slice.status()).isEqualTo(400);
        assertThat(slice.body()).contains("no long-term entry #" + ltmId).doesNotContain("grimaldi beta scoping marker");
    }

    @Test
    void foreignUpdateIsNotFoundAndLeavesTheRowUntouched() throws Exception {
        String a = bearerToken(OWNER_A);
        String b = bearerToken(OWNER_B);

        long stmId = store(a, "havelock gamma scoping marker");

        // B tries to supersede A's event by raw id
        Response foreign = put("/api/stm/events/" + stmId, """
                {"content":"hijacked delta overwrite"}""", b);
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.body()).contains("no event #" + stmId + " to update");

        // no supersede marker, no new content, and A still sees the original as active
        Long supersededBy = jdbc.sql("SELECT superseded_by FROM stm_event WHERE id = ?")
                .param(stmId).query(Long.class).optional().orElse(null);
        assertThat(supersededBy).isNull();
        String owner = jdbc.sql("SELECT owner FROM stm_event WHERE id = ?")
                .param(stmId).query(String.class).single();
        assertThat(owner).isEqualTo(OWNER_A);

        assertThat(get("/api/stm/events?query=havelock", a).body()).contains("havelock gamma scoping marker");
        assertThat(get("/api/stm/events?query=hijacked", a).body()).doesNotContain("hijacked delta overwrite");
        assertThat(get("/api/stm/events?query=hijacked", b).body()).doesNotContain("hijacked delta overwrite");
    }

    @Test
    void foreignPromoteIsNotFound() throws Exception {
        String a = bearerToken(OWNER_A);
        String b = bearerToken(OWNER_B);

        long stmId = store(a, "wintermute epsilon scoping marker");

        Response foreign = post("/api/ltm/promote", """
                {"stmId":%d}""".formatted(stmId), b);
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.body()).contains("no event #" + stmId + " to promote");

        Integer foreignEntries = jdbc.sql("SELECT count(*) FROM ltm_entry WHERE origin_ref = ? AND owner = ?")
                .param(String.valueOf(stmId)).param(OWNER_B)
                .query(Integer.class).single();
        assertThat(foreignEntries).isZero();

        // the owner's own promote still works (regression)
        assertThat(promote(a, stmId)).isPositive();
    }

    @Test
    void unknownOwnerRowsAreInvisibleToEveryToken() throws Exception {
        // pre-auth leftovers: STM + LTM rows on the 'unknown' owner
        long unknownStm = stmRepository.save(OWNER_UNKNOWN, "test", "user", "fact",
                "legacy zeta unknown marker", null);
        long unknownLtm = ltmRepository.insertEntry(OWNER_UNKNOWN, "test", "user", "legacy unknown entry",
                "legacy eta unknown content", null, null, "import", "/tmp/legacy-unknown.md", "hash-legacy-eta");
        ltmRepository.insertChunk(unknownLtm, 1, "legacy eta unknown content",
                embeddingProvider.embed("legacy eta unknown content"), 5);

        for (String token : List.of(bearerToken(OWNER_A), bearerToken(OWNER_B))) {
            assertThat(get("/api/stm/events?query=zeta", token).body()).doesNotContain("legacy zeta unknown marker");
            assertThat(get("/api/stm/events/recent?limit=100", token).body())
                    .doesNotContain("legacy zeta unknown marker");
            // includeSuperseded is no owner-agnostic escape hatch
            assertThat(get("/api/stm/events?query=zeta&includeSuperseded=true", token).body())
                    .doesNotContain("legacy zeta unknown marker");
            assertThat(get("/api/stm/events/recent?limit=100&includeSuperseded=true", token).body())
                    .doesNotContain("legacy zeta unknown marker");

            assertThat(get("/api/ltm/search?query=legacy", token).body()).doesNotContain("legacy eta unknown content");
            assertThat(get("/api/ltm/entries?limit=100", token).body()).doesNotContain("legacy unknown entry");

            Response foreignGet = get("/api/ltm/entries/" + unknownLtm, token);
            assertThat(foreignGet.status()).isEqualTo(400);
            assertThat(foreignGet.body()).contains("no long-term entry #" + unknownLtm)
                    .doesNotContain("legacy eta unknown content");
        }

        assertThat(unknownStm).isPositive();
    }

    @Test
    void metaKeyRegistryStaysGlobalForAFreshUser() throws Exception {
        String b = bearerToken(OWNER_B);

        // a deprecated registry key still warns (registry row consulted) and a
        // canonical key produces no unregistered-key warning — on a fresh subject
        Response deprecated = post("/api/stm/events",
                """
                {"actor":"user","kind":"fact","content":"theta scoping marker","meta":{"quelle":"some doc"}}""", b);
        assertThat(deprecated.status()).isEqualTo(200);
        assertThat(deprecated.body()).contains("\"id\":").contains("deprecated").contains("origin");

        Response canonical = post("/api/stm/events",
                """
                {"actor":"user","kind":"fact","content":"iota scoping marker","meta":{"topic":"scoping"}}""", b);
        assertThat(canonical.status()).isEqualTo(200);
        assertThat(canonical.body()).contains("\"id\":").doesNotContain("unregistered meta keys");
    }

    // --- harness -----------------------------------------------------------------

    record Response(int status, String body) {
    }

    private long store(String token, String content) throws Exception {
        Response stored = post("/api/stm/events", """
                {"actor":"user","kind":"fact","content":"%s"}""".formatted(content), token);
        assertThat(stored.status()).isEqualTo(200);
        return idOf(stored.body());
    }

    private long promote(String token, long stmId) throws Exception {
        Response promoted = post("/api/ltm/promote", """
                {"stmId":%d}""".formatted(stmId), token);
        assertThat(promoted.status()).isEqualTo(200);
        return idOf(promoted.body());
    }

    private long idOf(String body) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("no \"id\" in: " + body);
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

    private Response get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30)).GET().build());
    }

    private Response post(String path, String json, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private Response put(String path, String json, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:%d%s".formatted(port, path));
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
