package de.homelabs.hippocampus.ltm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.homelabs.hippocampus.stm.MetaKeyRecord;
import de.homelabs.hippocampus.stm.StmEvent;
import de.homelabs.hippocampus.stm.StmRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * Long-term memory orchestration on top of the session-1 building blocks: the
 * two ingestion paths ({@code promote}, {@code import_file}) and hybrid search
 * ({@code ltm_find} over four arms fused with RRF). Append-only like STM — a
 * re-promotion or unchanged re-import surfaces as a {@link DataIntegrityViolationException}
 * from V008's dedup guards and is reported as a no-op, never a mutation.
 */
@Service
public class LtmService {

    private static final int ARM_LIMIT = 50;
    private static final int EXCERPT_CHARS = 200;

    private final LtmRepository ltmRepository;
    private final StmRepository stmRepository;
    private final Chunker chunker;
    private final TokenCounter tokenCounter;
    private final EmbeddingProvider embeddingProvider;
    private final ObjectMapper objectMapper;

    public LtmService(LtmRepository ltmRepository, StmRepository stmRepository, Chunker chunker,
            TokenCounter tokenCounter, EmbeddingProvider embeddingProvider, ObjectMapper objectMapper) {
        this.ltmRepository = ltmRepository;
        this.stmRepository = stmRepository;
        this.chunker = chunker;
        this.tokenCounter = tokenCounter;
        this.embeddingProvider = embeddingProvider;
        this.objectMapper = objectMapper;
    }

    /** Result of a promote/import: the new entry id (or -1 for a no-op) plus warnings. */
    public record PromoteOutcome(long id, int chunkCount, List<String> warnings) {
        public PromoteOutcome {
            warnings = List.copyOf(warnings);
        }
    }

    /** Result of an import: like {@link PromoteOutcome}, {@code id} is -1 for a no-op. */
    public record ImportOutcome(long id, int chunkCount, List<String> warnings) {
        public ImportOutcome {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Copies an STM event into long-term memory with a provenance pointer
     * ({@code origin_ref} = the STM id; the STM row is never changed). Inherits
     * the event's kind, meta and actor; owner/source come from the caller.
     * Re-promoting the same id is rejected by V008's dedup guard and reported as
     * a warned no-op.
     */
    @Transactional
    public PromoteOutcome promote(String owner, String source, long stmId, String title) {
        StmEvent event = stmRepository.findById(stmId)
                .orElseThrow(() -> new IllegalArgumentException("no event #" + stmId + " to promote"));
        String resolvedTitle = (title == null || title.isBlank()) ? deriveTitle(event.content()) : title;
        String hash = sha256(event.content());
        try {
            long ltmId = ltmRepository.insertEntry(owner, source, event.actor(), resolvedTitle, event.content(),
                    event.kind(), parseMeta(event.meta()), "promote", String.valueOf(stmId), hash);
            int chunks = ingest(ltmId, event.content());
            return new PromoteOutcome(ltmId, chunks, List.of());
        } catch (DataIntegrityViolationException e) {
            return new PromoteOutcome(-1, 0, List.of("event #" + stmId + " is already in long-term memory (no-op)"));
        }
    }

    /**
     * Reads a file server-side (txt/md as UTF-8, PDF via PDFBox) and stores it as
     * an imported entry. Unchanged re-import (same path + content hash) is a
     * warned no-op; changed content creates a new entry (append-only). Scanned
     * PDFs (no text layer) fail with a clear error — OCR is out of Stage 1.
     */
    @Transactional
    public ImportOutcome importFile(String owner, String source, String path, String title, Map<String, String> meta) {
        Path filePath = Path.of(path);
        String content = extractContent(filePath);
        String hash = sha256(content);
        String resolvedTitle = (title == null || title.isBlank()) ? filePath.getFileName().toString() : title;
        List<String> warnings = new ArrayList<>();
        validateMeta(meta, warnings);
        try {
            long ltmId = ltmRepository.insertEntry(owner, source, "user", resolvedTitle, content, null, meta,
                    "import", filePath.toString(), hash);
            int chunks = ingest(ltmId, content);
            return new ImportOutcome(ltmId, chunks, warnings);
        } catch (DataIntegrityViolationException e) {
            return new ImportOutcome(-1, 0, List.of("unchanged re-import (no-op): " + filePath));
        }
    }

    /**
     * Hybrid search: embed the query once, run the four arms, fuse with RRF and
     * dedup per entry (best chunk wins), then build excerpts. Whole content is
     * never returned — {@code ltm_get} is the explicit fetch.
     */
    public List<LtmFindHit> find(String query, int limit) {
        float[] queryVector = embeddingProvider.embed(query);
        List<RrfFusion.ArmResult> arms = List.of(
                new RrfFusion.ArmResult("cosine", ltmRepository.searchCosine(queryVector, ARM_LIMIT)),
                new RrfFusion.ArmResult("fts", ltmRepository.searchFts(query, ARM_LIMIT)),
                new RrfFusion.ArmResult("trigram", ltmRepository.searchTrigram(query, ARM_LIMIT)),
                new RrfFusion.ArmResult("ilike", ltmRepository.searchIlike(query, ARM_LIMIT)));

        List<LtmFindHit> results = new ArrayList<>();
        for (RrfFusion.FusedHit hit : RrfFusion.fuse(arms)) {
            if (results.size() >= limit) {
                break;
            }
            LtmEntry entry = ltmRepository.findById(hit.ltmId()).orElse(null);
            LtmChunk chunk = entry == null ? null : ltmRepository.findChunk(hit.ltmId(), hit.seq()).orElse(null);
            if (entry == null || chunk == null) {
                continue;
            }
            results.add(new LtmFindHit(entry.id(), entry.title(), entry.originType(), entry.originRef(),
                    Excerptor.excerpt(chunk.chunkText(), query, EXCERPT_CHARS), chunk.seq(), hit.score(),
                    hit.arms(), entry.createdAt()));
        }
        return results;
    }

    /** Full entry ({@code fromSeq}/{@code toSeq} both null) or a 1-based chunk slice. */
    public LtmGetResult get(long ltmId, Integer fromSeq, Integer toSeq) {
        LtmEntry entry = ltmRepository.findById(ltmId)
                .orElseThrow(() -> new IllegalArgumentException("no long-term entry #" + ltmId));
        List<LtmChunk> all = ltmRepository.findChunks(ltmId);
        int total = all.size();
        if (fromSeq == null && toSeq == null) {
            return new LtmGetResult(ltmId, entry.title(), entry.kind(), entry.originType(), entry.originRef(),
                    entry.createdAt(), total, entry.content(), null);
        }
        int from = (fromSeq == null) ? 1 : fromSeq;
        int to = (toSeq == null) ? total : toSeq;
        if (from < 1) {
            throw new IllegalArgumentException("from_seq must be >= 1, got " + from);
        }
        if (to < from) {
            throw new IllegalArgumentException("to_seq must be >= from_seq, got " + to + " / " + from);
        }
        List<LtmChunkSlice> slices = all.stream()
                .filter(c -> c.seq() >= from && c.seq() <= to)
                .map(c -> new LtmChunkSlice(c.seq(), c.chunkText()))
                .toList();
        return new LtmGetResult(ltmId, entry.title(), entry.kind(), entry.originType(), entry.originRef(),
                entry.createdAt(), total, null, slices);
    }

    public List<LtmSummary> list(int limit) {
        return ltmRepository.list(limit);
    }

    private int ingest(long ltmId, String content) {
        List<Chunk> chunks = chunker.chunk(content, tokenCounter);
        int seq = 1;
        for (Chunk chunk : chunks) {
            ltmRepository.insertChunk(ltmId, seq, chunk.text(), embeddingProvider.embed(chunk.text()), chunk.tokenCount());
            seq++;
        }
        return chunks.size();
    }

    private String extractContent(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("file not found or not a regular file: " + path);
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) {
            return extractPdf(path);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read file " + path + ": " + e.getMessage(), e);
        }
    }

    private String extractPdf(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "PDF has no text layer (scanned or image-only) — OCR is not built yet: " + path);
            }
            return text;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read PDF " + path + ": " + e.getMessage(), e);
        }
    }

    /** Same meta_key_registry discipline as {@code store}: deprecated warn, closed range reject, unregistered warn. */
    private void validateMeta(Map<String, String> meta, List<String> warnings) {
        if (meta == null || meta.isEmpty()) {
            return;
        }
        Map<String, MetaKeyRecord> registry = stmRepository.findMetaKeys(meta.keySet());
        List<String> unregistered = new ArrayList<>();
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            MetaKeyRecord record = registry.get(entry.getKey());
            if (record == null) {
                unregistered.add(entry.getKey());
            } else if (record.deprecatedAt() != null) {
                warnings.add("meta key '" + entry.getKey() + "' is deprecated — " + record.description());
            } else if (record.allowedValues() != null && !record.allowedValues().contains(entry.getValue())) {
                throw new IllegalArgumentException(
                        "meta key '" + entry.getKey() + "' must be one of " + record.allowedValues());
            }
        }
        if (!unregistered.isEmpty()) {
            warnings.add("unregistered meta keys (curation candidates): " + String.join(", ", unregistered));
        }
    }

    private String deriveTitle(String content) {
        if (content == null || content.isBlank()) {
            return "untitled";
        }
        String firstLine = content.lines().map(String::trim).filter(line -> !line.isEmpty())
                .findFirst().orElse(content.trim());
        if (firstLine.length() <= 60) {
            return firstLine;
        }
        return firstLine.substring(0, 57) + "...";
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMeta(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> raw = objectMapper.readValue(json, Map.class);
        Map<String, String> parsed = new LinkedHashMap<>();
        raw.forEach((key, value) -> parsed.put(key, value == null ? null : String.valueOf(value)));
        return parsed;
    }
}
