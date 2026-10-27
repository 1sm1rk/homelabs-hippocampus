package de.homelabs.hippocampus.ltm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import de.homelabs.hippocampus.stm.StmRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LTM service end-to-end against a fresh pgvector container with the
 * deterministic {@link MockEmbeddingProvider} (no real model). Covers both
 * ingestion paths, hybrid find, get/list, and the append-only dedup guards.
 * Semantic quality (paraphrased queries) is the live-verification step, not CI.
 */
@SpringBootTest
@Testcontainers
class LtmServiceIT {

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
    LtmService service;

    @Autowired
    StmRepository stmRepository;

    @Test
    void promoteThenFindRoundtrip() {
        long stmId = stmRepository.save("test-owner", "test", "user", "fact",
                "The homelab runs MiniLM for semantic embeddings", Map.of("topic", "ltm"));

        LtmService.PromoteOutcome outcome = service.promote("test-owner", "test", stmId, null);

        assertThat(outcome.id()).isPositive();
        assertThat(outcome.chunkCount()).isEqualTo(1);

        List<LtmFindHit> hits = service.find("test-owner", "MiniLM", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).ltmId()).isEqualTo(outcome.id());
        assertThat(hits.get(0).title()).isEqualTo("The homelab runs MiniLM for semantic embeddings");
        assertThat(hits.get(0).originType()).isEqualTo("promote");
        assertThat(hits.get(0).originRef()).isEqualTo(String.valueOf(stmId));
        assertThat(hits.get(0).excerpt()).contains("MiniLM");
        assertThat(hits.get(0).matchedSeq()).isEqualTo(1);
        assertThat(hits.get(0).matchedArms()).isNotEmpty();
    }

    @Test
    void rePromotingSameEventIsWarnedNoOp() {
        long stmId = stmRepository.save("test-owner", "test", "user", "note",
                "promote me only once please", null);
        service.promote("test-owner", "test", stmId, null);

        LtmService.PromoteOutcome again = service.promote("test-owner", "test", stmId, null);

        assertThat(again.id()).isEqualTo(-1);
        assertThat(again.warnings()).anyMatch(w -> w.contains("already in long-term memory"));
    }

    @Test
    void promotingUnknownEventFails() {
        assertThatThrownBy(() -> service.promote("test-owner", "test", 999999L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no event #999999");
    }

    @Test
    void importTxtRoundtripAndUnchangedReImportIsNoOp() throws Exception {
        Path file = Files.createTempFile("ltm-import-", ".md");
        Files.writeString(file, "# Notes\nGerman umlauts: ä ö ü ß Ä Ö Ü Straße", StandardCharsets.UTF_8);

        LtmService.ImportOutcome outcome = service.importFile("test-owner", "test", file.toString(), null,
                Map.of("topic", "docs"));

        assertThat(outcome.id()).isPositive();
        assertThat(outcome.chunkCount()).isEqualTo(1);
        assertThat(outcome.warnings()).isEmpty();

        List<LtmFindHit> hits = service.find("test-owner", "umlauts", 5);
        assertThat(hits).anyMatch(h -> h.ltmId() == outcome.id());
        assertThat(hits.stream().filter(h -> h.ltmId() == outcome.id()).findFirst().orElseThrow().title())
                .isEqualTo(file.getFileName().toString());

        // unchanged re-import = warned no-op
        LtmService.ImportOutcome again = service.importFile("test-owner", "test", file.toString(), null, null);
        assertThat(again.id()).isEqualTo(-1);
        assertThat(again.warnings()).anyMatch(w -> w.contains("unchanged re-import"));

        // changed content = a new entry
        Files.writeString(file, "# Notes\nchanged content now", StandardCharsets.UTF_8);
        LtmService.ImportOutcome changed = service.importFile("test-owner", "test", file.toString(), null, null);
        assertThat(changed.id()).isPositive();
        assertThat(changed.id()).isNotEqualTo(outcome.id());
    }

    @Test
    void importPdfExtractsGermanUmlauts() throws Exception {
        Path pdf = Files.createTempFile("ltm-pdf-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.OVERWRITE, true)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Über die Hälfte der Bevölkerung wohnt in Städten mit Straßen.");
                cs.endText();
            }
            document.save(pdf.toFile());
        }

        LtmService.ImportOutcome outcome = service.importFile("test-owner", "test", pdf.toString(), "German cities", null);

        assertThat(outcome.id()).isPositive();
        LtmGetResult full = service.get("test-owner", outcome.id(), null, null);
        assertThat(full.content()).contains("Bevölkerung").contains("Städten").contains("Straßen");
    }

    @Test
    void getSupportsFullEntryAndChunkSlice() {
        long stmId = stmRepository.save("test-owner", "test", "user", "fact", "single chunk content", null);
        long ltmId = service.promote("test-owner", "test", stmId, null).id();

        LtmGetResult full = service.get("test-owner", ltmId, null, null);
        assertThat(full.content()).isEqualTo("single chunk content");
        assertThat(full.totalChunks()).isEqualTo(1);
        assertThat(full.chunks()).isNull();

        LtmGetResult slice = service.get("test-owner", ltmId, 1, 1);
        assertThat(slice.content()).isNull();
        assertThat(slice.chunks()).extracting(LtmChunkSlice::seq).containsExactly(1);
        assertThat(slice.chunks().get(0).text()).isEqualTo("single chunk content");
    }

    @Test
    void getRejectsUnknownEntryAndBadRange() {
        assertThatThrownBy(() -> service.get("test-owner", 999999L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no long-term entry #999999");

        long stmId = stmRepository.save("test-owner", "test", "user", "fact", "range check", null);
        long ltmId = service.promote("test-owner", "test", stmId, null).id();
        assertThatThrownBy(() -> service.get("test-owner", ltmId, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from_seq must be >= 1");
        assertThatThrownBy(() -> service.get("test-owner", ltmId, 2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to_seq must be >= from_seq");
    }

    @Test
    void listReturnsNewestFirstWithoutContent() {
        long stm1 = stmRepository.save("test-owner", "test", "user", "fact", "first entry", null);
        long stm2 = stmRepository.save("test-owner", "test", "user", "fact", "second entry", null);
        long first = service.promote("test-owner", "test", stm1, "first").id();
        long second = service.promote("test-owner", "test", stm2, "second").id();

        List<LtmSummary> summaries = service.list("test-owner", 10);

        assertThat(summaries).extracting(LtmSummary::id).contains(second, first);
        assertThat(summaries.get(0).title()).isEqualTo("second");
        assertThat(summaries.get(0).chunkCount()).isEqualTo(1);
    }
}
