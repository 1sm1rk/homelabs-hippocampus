package de.homelabs.hippocampus.ltm;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

/**
 * JDBC access to the long-term memory tables (V008). Append-only like STM: inserts
 * and reads, no update or delete. The dedup guards live in the schema as partial
 * unique indexes (V008) — a re-promotion or unchanged re-import surfaces as a
 * {@code DataIntegrityViolationException} that the tool layer (session 2) turns
 * into a no-op report.
 */
@Repository
public class LtmRepository {

    private static final String ENTRY_COLUMNS =
            "id, title, content, kind, meta::text AS meta, origin_type, origin_ref, origin_hash, "
                    + "owner, source, actor, created_at";

    private static final String CHUNK_COLUMNS = "id, ltm_id, seq, chunk_text, tokens, created_at";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public LtmRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long insertEntry(String owner, String source, String actor,
            String title, String content, String kind, Map<String, String> meta,
            String originType, String originRef, String originHash) {
        String metaJson = (meta == null || meta.isEmpty()) ? "{}" : objectMapper.writeValueAsString(meta);
        return jdbc.sql("INSERT INTO ltm_entry (owner, source, actor, title, content, kind, meta, "
                        + "origin_type, origin_ref, origin_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id")
                .param(owner)
                .param(source)
                .param(actor)
                .param(title)
                .param(content)
                .param(kind)
                .param(metaJson)
                .param(originType)
                .param(originRef)
                .param(originHash)
                .query(Long.class)
                .single();
    }

    public void insertChunk(long ltmId, int seq, String chunkText, float[] embedding, int tokens) {
        // pgvector accepts a '[a, b, c]' literal; Arrays.toString produces exactly that.
        String vector = Arrays.toString(embedding);
        jdbc.sql("INSERT INTO ltm_chunk (ltm_id, seq, chunk_text, embedding, tokens) "
                        + "VALUES (?, ?, ?, ?::vector, ?)")
                .param(ltmId)
                .param(seq)
                .param(chunkText)
                .param(vector)
                .param(tokens)
                .update();
    }

    public Optional<LtmEntry> findById(long id) {
        return jdbc.sql("SELECT " + ENTRY_COLUMNS + " FROM ltm_entry WHERE id = ?")
                .param(id)
                .query(this::mapEntry)
                .optional();
    }

    public List<LtmChunk> findChunks(long ltmId) {
        return jdbc.sql("SELECT " + CHUNK_COLUMNS + " FROM ltm_chunk WHERE ltm_id = ? ORDER BY seq")
                .param(ltmId)
                .query(this::mapChunk)
                .list();
    }

    public Optional<LtmChunk> findChunk(long ltmId, int seq) {
        return jdbc.sql("SELECT " + CHUNK_COLUMNS + " FROM ltm_chunk WHERE ltm_id = ? AND seq = ?")
                .param(ltmId)
                .param(seq)
                .query(this::mapChunk)
                .optional();
    }

    /**
     * The four {@code ltm_find} search arms, each returning its top hits ordered
     * best-first (the rank supplies the RRF contribution). The cosine arm uses
     * the HNSW index via the {@code <=>} cosine-distance operator; the lexical
     * arms mirror the STM search arms (V002/V003).
     */
    public List<SearchArmHit> searchCosine(float[] embedding, int limit) {
        return jdbc.sql("SELECT c.ltm_id, c.seq FROM ltm_chunk c JOIN ltm_entry e ON e.id = c.ltm_id "
                        + "ORDER BY c.embedding <=> ?::vector LIMIT ?")
                .param(Arrays.toString(embedding))
                .param(limit)
                .query(this::mapArmHit)
                .list();
    }

    public List<SearchArmHit> searchFts(String query, int limit) {
        return jdbc.sql("SELECT c.ltm_id, c.seq FROM ltm_chunk c JOIN ltm_entry e ON e.id = c.ltm_id "
                        + "WHERE to_tsvector('german', c.chunk_text) @@ plainto_tsquery('german', ?) "
                        + "ORDER BY ts_rank(to_tsvector('german', c.chunk_text), plainto_tsquery('german', ?)) DESC LIMIT ?")
                .param(query)
                .param(query)
                .param(limit)
                .query(this::mapArmHit)
                .list();
    }

    public List<SearchArmHit> searchTrigram(String query, int limit) {
        return jdbc.sql("SELECT c.ltm_id, c.seq FROM ltm_chunk c JOIN ltm_entry e ON e.id = c.ltm_id "
                        + "WHERE c.chunk_text %> ? ORDER BY word_similarity(c.chunk_text, ?) DESC LIMIT ?")
                .param(query)
                .param(query)
                .param(limit)
                .query(this::mapArmHit)
                .list();
    }

    public List<SearchArmHit> searchIlike(String query, int limit) {
        return jdbc.sql("SELECT c.ltm_id, c.seq FROM ltm_chunk c JOIN ltm_entry e ON e.id = c.ltm_id "
                        + "WHERE c.chunk_text ILIKE ? ESCAPE '\\' ORDER BY e.created_at DESC LIMIT ?")
                .param(likePattern(query))
                .param(limit)
                .query(this::mapArmHit)
                .list();
    }

    public List<LtmSummary> list(int limit) {
        return jdbc.sql("SELECT e.id, e.title, e.origin_type, e.origin_ref, e.created_at, "
                        + "(SELECT count(*) FROM ltm_chunk c WHERE c.ltm_id = e.id) AS chunk_count "
                        + "FROM ltm_entry e ORDER BY e.created_at DESC LIMIT ?")
                .param(limit)
                .query(this::mapSummary)
                .list();
    }

    private SearchArmHit mapArmHit(ResultSet rs, int rowNum) throws SQLException {
        return new SearchArmHit(rs.getLong("ltm_id"), rs.getInt("seq"));
    }

    private LtmSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new LtmSummary(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("origin_type"),
                rs.getString("origin_ref"),
                rs.getInt("chunk_count"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String likePattern(String query) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private LtmEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new LtmEntry(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("kind"),
                rs.getString("meta"),
                rs.getString("origin_type"),
                rs.getString("origin_ref"),
                rs.getString("origin_hash"),
                rs.getString("owner"),
                rs.getString("source"),
                rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant());
    }

    private LtmChunk mapChunk(ResultSet rs, int rowNum) throws SQLException {
        return new LtmChunk(
                rs.getLong("id"),
                rs.getLong("ltm_id"),
                rs.getInt("seq"),
                rs.getString("chunk_text"),
                rs.getInt("tokens"),
                rs.getTimestamp("created_at").toInstant());
    }
}
