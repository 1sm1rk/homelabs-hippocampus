package de.homelabs.hippocampus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot smoke test: the context boots against a real Postgres, Flyway migrates, and
 * the REST API answers behind the OAuth 2.0 resource server — the liveness probe, the
 * STM and LTM endpoints, the source header, supersedes and owner provenance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
class HippocampusBootIT {

    private static final String SOURCE_HEADER = "X-Mcp-Source";

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
    ObjectMapper objectMapper;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcClient jdbc;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void bootsPingsAndOpensTheHealthProbe() throws Exception {
        Response ping = get("/api/ping", bearerToken("it-user-sub"));
        assertThat(ping.status()).isEqualTo(200);
        assertThat(ping.body()).contains("pong: hippocampus alive");

        Response health = get("/health", null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void storeDerivesSourceFromTheRequestHeader() throws Exception {
        String token = bearerToken("it-source-sub");

        Response stored = post("/api/stm/events",
                """
                {"actor":"user","kind":"fact","content":"source header check"}""", token, "it-boot-client");
        assertThat(stored.status()).isEqualTo(200);
        assertThat(stored.body()).contains("\"id\":");

        Response recent = get("/api/stm/events/recent?limit=100", token);
        assertThat(recent.body()).contains("source header check");
        assertThat(recent.body()).contains("\"source\":\"it-boot-client\"");
        assertThat(recent.body()).contains("\"actor\":\"user\"");
    }

    @Test
    void storeRejectsAnUnknownKind() throws Exception {
        String token = bearerToken("it-kind-sub");

        Response rejected = post("/api/stm/events",
                """
                {"actor":"user","kind":"gibberish","content":"must not be stored"}""", token, null);
        assertThat(rejected.status()).isEqualTo(400);

        Response recent = get("/api/stm/events/recent?limit=100", token);
        assertThat(recent.body()).doesNotContain("must not be stored");
    }

    @Test
    void updateSupersedesAndTheOldEventIsHiddenByDefault() throws Exception {
        String token = bearerToken("it-update-sub");

        long id = storedId(post("/api/stm/events",
                """
                {"actor":"user","kind":"fact","content":"quasar"}""", token, null));

        Response updated = put("/api/stm/events/" + id,
                """
                {"content":"nebula"}""", token, null);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body()).contains("\"supersededId\":" + id);

        assertThat(get("/api/stm/events?query=nebula", token).body()).contains("nebula");
        assertThat(get("/api/stm/events?query=quasar", token).body()).doesNotContain("quasar");
        assertThat(get("/api/stm/events?query=quasar&includeSuperseded=true", token).body()).contains("quasar");
    }

    @Test
    void storeReportsDeprecatedMetaKeysAsWarnings() throws Exception {
        Response stored = post("/api/stm/events",
                """
                {"actor":"user","kind":"fact","content":"warning check","meta":{"quelle":"some doc"}}""",
                bearerToken("it-meta-sub"), null);

        assertThat(stored.status()).isEqualTo(200);
        assertThat(stored.body()).contains("deprecated").contains("origin");
    }

    @Test
    void importFileCreatesAnEntryAndListsIt(@TempDir Path tempDir) throws Exception {
        String token = bearerToken("it-import-sub");
        Path document = tempDir.resolve("note.md");
        Files.writeString(document, "# Notiz\nPDFBox und pgvector sind die Bausteine der Suche.\n",
                StandardCharsets.UTF_8);

        Response imported = post("/api/ltm/imports",
                """
                {"path":"%s","title":"import contract check"}""".formatted(document), token, "it-boot-client");
        assertThat(imported.status()).isEqualTo(200);
        assertThat(imported.body()).contains("\"chunkCount\":");

        assertThat(get("/api/ltm/entries?limit=100", token).body()).contains("import contract check");
    }

    @Test
    void apiRequiresABearerToken() throws Exception {
        assertThat(get("/api/stm/events/recent", null).status()).isEqualTo(401);
        assertThat(get("/api/ping", null).status()).isEqualTo(401);
        assertThat(post("/api/stm/events",
                """
                {"actor":"user","kind":"fact","content":"unauthorized"}""", null, null).status()).isEqualTo(401);
    }

    @Test
    void tokensForAnotherResourceAreRejected() throws Exception {
        String foreign = token("it-foreign-sub", TestSecurityConfig.FOREIGN_AUDIENCE);

        assertThat(get("/api/ping", foreign).status()).isEqualTo(401);
        assertThat(get("/api/stm/events/recent", foreign).status()).isEqualTo(401);
    }

    @Test
    void storeWritesTheTokenSubjectAsOwner() throws Exception {
        String token = bearerToken("owner-user-sub");

        long id = storedId(post("/api/stm/events",
                """
                {"actor":"user","kind":"fact","content":"owner provenance check"}""", token, null));

        String owner = jdbc.sql("SELECT owner FROM stm_event WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
        assertThat(owner).isEqualTo("owner-user-sub");
    }

    // --- harness -----------------------------------------------------------------

    record Response(int status, String body) {
    }

    private long storedId(Response response) {
        return objectMapper.readTree(response.body()).get("id").asLong();
    }

    private String bearerToken(String sub) {
        return token(sub, TestSecurityConfig.AUDIENCE);
    }

    private String token(String sub, String audience) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject(sub)
                .audience(List.of(audience))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private Response get(String path, String token) throws Exception {
        return send(request(path, token, null).GET().build());
    }

    private Response post(String path, String json, String token, String source) throws Exception {
        return send(request(path, token, source).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private Response put(String path, String json, String token, String source) throws Exception {
        return send(request(path, token, source).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpRequest.Builder request(String path, String token, String source) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d%s".formatted(port, path)))
                .timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (source != null) {
            builder.header(SOURCE_HEADER, source);
        }
        return builder;
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
