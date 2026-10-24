package de.homelabs.hippocampus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot smoke test: context boots against a real Postgres, Flyway migrates,
 * the MCP endpoint answers initialize + tools/list + ping — now behind the
 * OAuth 2.1 resource server (AP-7): every MCP call carries a Bearer JWT, the
 * PRM discovery document is public, and stores carry the token principal as
 * owner.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
class HippocampusBootIT {

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

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;

    @Test
    void mcpEndpointBootsMigratesAndAnswersPing() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);

        var init = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it","version":"0"}}}
                """, null);
        assertThat(initializeResult(init.body())).contains("hippocampus");

        var session = init.sessionId();
        var ping = post(url, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ping","arguments":{}}}
                """, session);
        assertThat(ping.body()).contains("pong: hippocampus alive");
    }

    @Test
    void storeDerivesSourceFromClientInfoAndSavesActor() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);

        var init = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it-boot-client","version":"0"}}}
                """, null);
        var session = init.sessionId();

        var stored = post(url, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store","arguments":{"actor":"user","kind":"fact","content":"Boot IT stores with derived source"}}}
                """, session);
        assertThat(stored.body()).contains("stored event #");

        var listed = post(url, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list","arguments":{"limit":1}}}
                """, session);
        assertThat(listed.body()).contains("it-boot-client");
        assertThat(listed.body()).contains("Boot IT stores with derived source");
        assertThat(listed.body()).contains("\\\"actor\\\":\\\"user\\\"");
    }

    @Test
    void storeRejectsUnknownKindViaSchema() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);

        var init = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it-boot-client","version":"0"}}}
                """, null);
        var session = init.sessionId();

        var tools = post(url, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, session);
        // kind enum is part of the generated JSON schema (R3: schema over prose)
        assertThat(tools.body()).contains("\"enum\"");
        assertThat(tools.body()).contains("\"fact\"");
        assertThat(tools.body()).contains("\"decision\"");

        var rejected = post(url, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"store","arguments":{"actor":"user","kind":"gibberish","content":"must not be stored"}}}
                """, session);
        // server-side schema validation rejects before binding; the detailed
        // message is locale-dependent, assert on the stable wrapper parts
        assertThat(rejected.body()).contains("input validation failed");
        assertThat(rejected.body()).contains("/kind");

        var listed = post(url, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list","arguments":{"limit":100}}}
                """, session);
        assertThat(listed.body()).doesNotContain("must not be stored");
    }

    @Test
    void updateToolSupersedesAndIsHiddenByDefault() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);

        var init = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it-boot-client","version":"0"}}}
                """, null);
        var session = init.sessionId();

        var stored = post(url, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store","arguments":{"actor":"user","kind":"fact","content":"quasar"}}}
                """, session);
        long id = extractStoredId(stored.body());

        var updated = post(url, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"update","arguments":{"id":%d,"content":"nebula"}}}
                """.formatted(id), session);
        assertThat(updated.body()).contains("updated: event #").contains("supersedes #" + id);

        // the corrected event is findable by default
        assertThat(post(url, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find","arguments":{"query":"nebula"}}}
                """, session).body()).contains("nebula");

        // the superseded event is hidden by default and revealed on request
        assertThat(post(url, """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"find","arguments":{"query":"quasar"}}}
                """, session).body()).doesNotContain("quasar");
        assertThat(post(url, """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"find","arguments":{"query":"quasar","includeSuperseded":true}}}
                """, session).body()).contains("quasar");
    }

    @Test
    void storeWarnsOnDeprecatedMetaKey() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);

        var init = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it-boot-client","version":"0"}}}
                """, null);
        var session = init.sessionId();

        var stored = post(url, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store","arguments":{"actor":"user","kind":"fact","content":"warning check","meta":{"quelle":"some doc"}}}}
                """, session);
        assertThat(stored.body()).contains("stored event #");
        assertThat(stored.body()).contains("deprecated").contains("origin");
    }

    @Test
    void unauthenticatedPostReturns401WithResourceMetadata() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);

        var response = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it","version":"0"}}}
                """, null, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.wwwAuthenticate())
                .isNotNull()
                .contains("Bearer")
                .contains("resource_metadata=")
                .contains("/.well-known/oauth-protected-resource");
    }

    @Test
    void authenticatedInitializeAndToolsListSucceed() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);
        String token = bearerToken("it-user-sub");

        var init = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it","version":"0"}}}
                """, null, token);
        assertThat(init.status()).isEqualTo(200);
        assertThat(initializeResult(init.body())).contains("hippocampus");

        var tools = post(url, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, init.sessionId(), token);
        assertThat(tools.status()).isEqualTo(200);
        assertThat(tools.body()).contains("\"ping\"").contains("\"store\"").contains("\"find\"").contains("\"list\"");
    }

    @Test
    void protectedResourceMetadataDocumentIsServed() throws Exception {
        var url = "http://localhost:%d/.well-known/oauth-protected-resource".formatted(port);

        var connection = java.net.HttpURLConnection.class.cast(java.net.URI.create(url).toURL().openConnection());
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        assertThat(status).isEqualTo(200);

        JsonNode document;
        try (var in = connection.getInputStream()) {
            document = objectMapper.readTree(in.readAllBytes());
        }
        assertThat(document.get("resource").asString()).isEqualTo(TestSecurityConfig.AUDIENCE);
        assertThat(document.get("authorization_servers").isArray()).isTrue();
        assertThat(document.get("authorization_servers").get(0).asString())
                .isEqualTo("http://localhost:8091/realms/hippocampus");
        assertThat(document.get("scopes_supported").isArray()).isTrue();
        assertThat(document.get("scopes_supported").get(0).asString()).isEqualTo("mcp:tools");
    }

    @Test
    void storeWritesOwnerFromTokenPrincipal() throws Exception {
        var url = "http://localhost:%d/mcp".formatted(port);
        String token = bearerToken("owner-user-sub");

        var init = post(url, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"it-boot-client","version":"0"}}}
                """, null, token);
        var session = init.sessionId();

        var stored = post(url, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store","arguments":{"actor":"user","kind":"fact","content":"owner provenance check"}}}
                """, session, token);
        long id = extractStoredId(stored.body());

        String owner = jdbc.sql("SELECT owner FROM stm_event WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
        assertThat(owner).isEqualTo("owner-user-sub");
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

    private long extractStoredId(String raw) {
        var matcher = java.util.regex.Pattern.compile("stored event #(\\d+)").matcher(raw);
        if (!matcher.find()) {
            throw new AssertionError("no stored id in: " + raw);
        }
        return Long.parseLong(matcher.group(1));
    }

    private String initializeResult(String sseOrJson) throws Exception {
        return extractData(sseOrJson);
    }

    private String extractData(String raw) throws Exception {
        for (String line : raw.split("\n")) {
            if (line.startsWith("data:")) {
                JsonNode root = objectMapper.readTree(line.substring("data:".length()));
                if (root.has("result") && root.path("result").has("serverInfo")) {
                    return root.get("result").get("serverInfo").get("name").asString();
                }
            }
        }
        return raw;
    }

    record Response(String body, String sessionId, int status, String wwwAuthenticate) {}

    private Response post(String url, String json, String sessionId) throws Exception {
        return post(url, json, sessionId, bearerToken("it-default-user"));
    }

    private Response post(String url, String json, String sessionId, String token) throws Exception {
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
        return new Response(body, connection.getHeaderField("Mcp-Session-Id"), status,
                connection.getHeaderField("WWW-Authenticate"));
    }
}
