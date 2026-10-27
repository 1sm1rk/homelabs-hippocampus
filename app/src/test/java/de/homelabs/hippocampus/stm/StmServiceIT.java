package de.homelabs.hippocampus.stm;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class StmServiceIT {

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
    StmService service;

    @Autowired
    JdbcClient jdbc;

    @Test
    void storeWarnsOnDeprecatedKey() {
        var outcome = service.store("test-owner", "test", "user", "fact", "x", Map.of("quelle", "some doc"));

        assertThat(outcome.warnings())
                .anyMatch(w -> w.equals("meta key 'quelle' is deprecated — prefer 'origin'"));
    }

    @Test
    void storeWarnsOnUnregisteredKey() {
        var outcome = service.store("test-owner", "test", "user", "fact", "x", Map.of("brandnew", "v"));

        assertThat(outcome.warnings())
                .anyMatch(w -> w.equals("unregistered meta keys (curation candidates): brandnew"));
    }

    @Test
    void storeIsSilentForCanonicalKeys() {
        var outcome = service.store("test-owner", "test", "user", "fact", "x", Map.of("topic", "infra", "week", "2026-W41"));

        assertThat(outcome.warnings()).isEmpty();
    }

    @Test
    void storeRejectsValueOutsideClosedRange() {
        jdbc.sql("INSERT INTO meta_key_registry (key, value_type, allowed_values) VALUES ('priority', 'string', ARRAY['low','high'])")
                .update();

        assertThatThrownBy(() -> service.store("test-owner", "test", "user", "fact", "x", Map.of("priority", "urgent")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority")
                .hasMessageContaining("[low, high]");
    }

    @Test
    void updateInheritsAbsentFieldsAndMarksOldSuperseded() {
        long oldId = service.store("test-owner", "test", "user", "fact", "quasar", Map.of("topic", "infra")).id();

        long newId = service.update("test-owner", "test", oldId, "nebula", null, null, null);

        StmEvent updated = service.find("test-owner", "nebula", 10, false).get(0);
        assertThat(updated.id()).isEqualTo(newId);
        assertThat(updated.kind()).isEqualTo("fact");
        assertThat(updated.actor()).isEqualTo("user");
        assertThat(updated.meta()).contains("infra").contains("supersedes");

        // the old event is superseded and hidden from default find
        assertThat(service.find("test-owner", "quasar", 10, false)).isEmpty();
        StmEvent old = service.find("test-owner", "quasar", 10, true).get(0);
        assertThat(old.supersededBy()).isEqualTo(newId);
    }

    @Test
    void updateMergesMetaPerKey() {
        long oldId = service.store("test-owner", "test", "user", "note", "meeting",
                Map.of("topic", "infra", "week", "2026-W40")).id();

        long newId = service.update("test-owner", "test", oldId, null, null, null,
                Map.of("week", "2026-W41", "uhrzeit", "09:00"));

        StmEvent updated = service.find("test-owner", "meeting", 10, true).get(0);
        assertThat(updated.meta())
                .contains("infra")           // inherited topic survives
                .contains("2026-W41")        // overridden week
                .contains("09:00")           // new key added
                .doesNotContain("2026-W40"); // old value replaced
    }

    @Test
    void updateRejectsNothingToChangeAndUnknownId() {
        assertThatThrownBy(() -> service.update("test-owner", "test", 999999L, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to change");

        assertThatThrownBy(() -> service.update("test-owner", "test", 999999L, "content", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no event #999999");
    }
}
