package de.homelabs.hippocampus.ltm;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LTM repository against a fresh pgvector container: V008 applies cleanly, and an
 * entry + chunks round-trip using the deterministic {@link MockEmbeddingProvider}
 * (no real model needed).
 */
@SpringBootTest
@Testcontainers
class LtmRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("hippocampus.ltm.embedding.provider", () -> "mock");
    }

    @Autowired
    LtmRepository repository;

    @Autowired
    EmbeddingProvider embeddingProvider;

    @Autowired
    JdbcClient jdbc;

    @Test
    void v008CreatesVectorExtensionAndLtmTables() {
        Boolean vector = jdbc.sql("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')")
                .query(Boolean.class).single();
        assertThat(vector).isTrue();

        for (String table : List.of("ltm_entry", "ltm_chunk")) {
            String regclass = jdbc.sql("SELECT to_regclass(?)").param("public." + table)
                    .query(String.class).single();
            assertThat(regclass).isNotNull().as("table %s exists", table);
        }
    }

    @Test
    void entryAndChunksRoundtripWithMockEmbedding() {
        float[] vector = embeddingProvider.embed("the homelab server runs on port 5432");

        long id = repository.insertEntry("test-owner", "test", "user", "Infra note",
                "The homelab server runs on port 5432 and speaks Postgres.", "fact",
                Map.of("topic", "infra"), "promote", "42", "hash-abc");
        repository.insertChunk(id, 1, "The homelab server runs on port 5432", vector, 8);
        repository.insertChunk(id, 2, "and speaks Postgres.", embeddingProvider.embed("and speaks Postgres."), 3);

        LtmEntry entry = repository.findById("test-owner", id).orElseThrow();
        assertThat(entry.title()).isEqualTo("Infra note");
        assertThat(entry.kind()).isEqualTo("fact");
        assertThat(entry.originType()).isEqualTo("promote");
        assertThat(entry.originRef()).isEqualTo("42");
        assertThat(entry.originHash()).isEqualTo("hash-abc");
        assertThat(entry.owner()).isEqualTo("test-owner");
        assertThat(entry.source()).isEqualTo("test");
        assertThat(entry.actor()).isEqualTo("user");
        assertThat(entry.meta()).contains("infra");

        List<LtmChunk> chunks = repository.findChunks("test-owner", id);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).seq()).isEqualTo(1);
        assertThat(chunks.get(0).chunkText()).isEqualTo("The homelab server runs on port 5432");
        assertThat(chunks.get(0).tokens()).isEqualTo(8);
        assertThat(chunks.get(1).seq()).isEqualTo(2);
        assertThat(chunks.get(1).chunkText()).isEqualTo("and speaks Postgres.");
    }

    @Test
    void storedEmbeddingsHaveTheModelDimension() {
        long id = repository.insertEntry("test-owner", "test", "user", "t", "content", null,
                null, "import", "/tmp/dim-check.md", "hash-dim");
        repository.insertChunk(id, 1, "content", embeddingProvider.embed("content"), 1);

        Integer dims = jdbc.sql("SELECT vector_dims(embedding) FROM ltm_chunk WHERE ltm_id = ?")
                .param(id).query(Integer.class).single();
        assertThat(dims).isEqualTo(384);
    }

    @Test
    void rePromotingSameOriginRefIsRejectedByDedupGuard() {
        repository.insertEntry("test-owner", "test", "user", "t", "content", "fact", null,
                "promote", "7", "hash-x");

        assertThatThrownBy(() -> repository.insertEntry("test-owner", "test", "user", "t", "content", "fact",
                null, "promote", "7", "hash-x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reImportWithChangedHashCreatesNewEntry() {
        repository.insertEntry("test-owner", "test", "user", "t", "content", null, null,
                "import", "/tmp/doc.md", "hash-1");
        long second = repository.insertEntry("test-owner", "test", "user", "t", "content changed", null,
                null, "import", "/tmp/doc.md", "hash-2");

        assertThat(second).isPositive();
        assertThat(repository.findChunks("test-owner", second)).isEmpty();
    }
}
