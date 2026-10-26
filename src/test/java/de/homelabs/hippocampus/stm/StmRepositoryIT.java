package de.homelabs.hippocampus.stm;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class StmRepositoryIT {

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
    StmRepository repository;

    @Test
    void saveFindListRoundtrip() {
        long id1 = repository.save("test-owner", "test", "user", "fact", "Postgres runs on port 5432 in the homelab", Map.of("topic", "infra"));
        long id2 = repository.save("test-owner", "test", "ai", "task", "Buy cat food on Friday", null);
        assertThat(id1).isNotEqualTo(id2);

        List<StmEvent> hits = repository.find("test-owner", "cat food", 10, false);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).isEqualTo("Buy cat food on Friday");
        assertThat(hits.get(0).kind()).isEqualTo("task");
        assertThat(hits.get(0).actor()).isEqualTo("ai");
        assertThat(hits.get(0).supersededBy()).isNull();

        List<StmEvent> recent = repository.recent("test-owner", 20, false);
        assertThat(recent).hasSizeGreaterThanOrEqualTo(2);
        assertThat(recent.get(0).id()).isEqualTo(id2);
        assertThat(recent.get(1).id()).isEqualTo(id1);
    }

    @Test
    void findMatchesGermanStems() {
        repository.save("test-owner", "test", "user", "note", "Der Server speichert alle Ereignisse im Homelab", null);
        repository.save("test-owner", "test", "user", "note", "Unrelated note about grocery shopping", null);

        assertThat(repository.find("test-owner", "speicher", 10, false)).extracting(StmEvent::content)
                .contains("Der Server speichert alle Ereignisse im Homelab");
        assertThat(repository.find("test-owner", "speichert", 10, false)).extracting(StmEvent::content)
                .contains("Der Server speichert alle Ereignisse im Homelab");
    }

    @Test
    void findToleratesTypos() {
        repository.save("test-owner", "test", "user", "note", "The hippocampus service runs on port 8090", null);

        assertThat(repository.find("test-owner", "hippomcampus", 10, false)).extracting(StmEvent::content)
                .contains("The hippocampus service runs on port 8090");
    }

    @Test
    void findIsCaseInsensitiveAndEscapesWildcards() {
        repository.save("test-owner", "test", "user", "note", "The Homelab server is called NORDLICHT", null);
        repository.save("test-owner", "test", "user", "note", "battery charge is at 100% right now", null);

        assertThat(repository.find("test-owner", "homelab", 10, false)).extracting(StmEvent::content)
                .contains("The Homelab server is called NORDLICHT");

        List<StmEvent> wildcardHits = repository.find("test-owner", "100%", 10, false);
        assertThat(wildcardHits).extracting(StmEvent::content).contains("battery charge is at 100% right now");
    }

    @Test
    void supersededEventsAreExcludedByDefaultAndRevealedOnRequest() {
        long oldId = repository.save("test-owner", "test", "user", "note", "old address: Hauptstrasse 1", null);
        long newId = repository.save("test-owner", "test", "user", "note", "new address: Hauptstrasse 1", null);
        repository.markSuperseded(oldId, newId);

        // active-only default: the superseded event is gone
        assertThat(repository.find("test-owner", "Hauptstrasse", 10, false)).extracting(StmEvent::id).containsExactly(newId);
        assertThat(repository.recent("test-owner", 20, false)).extracting(StmEvent::id)
                .contains(newId).doesNotContain(oldId);

        // includeSuperseded reveals it, with the marker set
        StmEvent superseded = repository.find("test-owner", "Hauptstrasse", 10, true).stream()
                .filter(e -> e.id() == oldId).findFirst().orElseThrow();
        assertThat(superseded.supersededBy()).isEqualTo(newId);
        assertThat(superseded.supersededAt()).isNotNull();
        assertThat(repository.recent("test-owner", 20, true)).extracting(StmEvent::id).contains(newId, oldId);
    }

    @Test
    void findMetaKeysReturnsRegistryRows() {
        var registry = repository.findMetaKeys(List.of("topic", "quelle", "week", "nonexistent"));

        assertThat(registry).containsKeys("topic", "quelle", "week");
        assertThat(registry).doesNotContainKey("nonexistent");
        assertThat(registry.get("topic").deprecatedAt()).isNull();
        assertThat(registry.get("topic").allowedValues()).isNull();
        assertThat(registry.get("quelle").deprecatedAt()).isNotNull();
        assertThat(registry.get("week").description()).contains("ISO week");
    }
}