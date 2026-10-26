package de.homelabs.hippocampus.stm;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

/**
 * JDBC access to the short-term memory (V001/V002/V003/V006/V007).
 *
 * <p>Every read path is owner-scoped (strict isolation, ADR 0007): {@code find},
 * {@code recent} and {@code findById} filter {@code owner = ?} with the token
 * {@code sub}, so one user's events are invisible to every other principal. Rows
 * on {@code owner = 'unknown'} (pre-auth data) match no token and stay invisible
 * until backfilled — fail-closed, no escape hatch. {@code findMetaKeys} is the
 * deliberate exception: the meta registry is curation data, not user data.
 * {@code markSuperseded} is internal and reachable only after a scoped load.
 */
@Repository
public class StmRepository {

    private static final String SELECT_COLUMNS =
            "id, source, actor, kind, content, meta::text AS meta, created_at, superseded_by, superseded_at";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public StmRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long save(String owner, String source, String actor, String kind, String content, Map<String, String> meta) {
        String metaJson = (meta == null || meta.isEmpty()) ? "{}" : objectMapper.writeValueAsString(meta);
        return jdbc.sql("INSERT INTO stm_event (owner, source, actor, kind, content, meta) VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id")
                .param(owner)
                .param(source)
                .param(actor)
                .param(kind)
                .param(content)
                .param(metaJson)
                .query(Long.class)
                .single();
    }

    public Optional<StmEvent> findById(String owner, long id) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM stm_event WHERE id = ? AND owner = ?")
                .param(id)
                .param(owner)
                .query(this::mapRow)
                .optional();
    }

    public void markSuperseded(long oldId, long newId) {
        jdbc.sql("UPDATE stm_event SET superseded_by = ?, superseded_at = now() WHERE id = ?")
                .param(newId)
                .param(oldId)
                .update();
    }

    public List<StmEvent> find(String owner, String query, int limit, boolean includeSuperseded) {
        String active = includeSuperseded ? "" : "superseded_by IS NULL AND ";
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM stm_event WHERE " + active
                        + "owner = ? AND (content ILIKE ? ESCAPE '\\' OR tsv @@ plainto_tsquery('german', ?) OR content %> ?) ORDER BY created_at DESC LIMIT ?")
                .param(owner)
                .param(likePattern(query))
                .param(query)
                .param(query)
                .param(limit)
                .query(this::mapRow)
                .list();
    }

    public List<StmEvent> recent(String owner, int limit, boolean includeSuperseded) {
        String active = includeSuperseded ? "" : "superseded_by IS NULL AND ";
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM stm_event WHERE " + active
                        + "owner = ? ORDER BY created_at DESC LIMIT ?")
                .param(owner)
                .param(limit)
                .query(this::mapRow)
                .list();
    }

    public Map<String, MetaKeyRecord> findMetaKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT key, value_type, allowed_values, required, description, deprecated_at "
                        + "FROM meta_key_registry WHERE key IN (:keys)")
                .param("keys", keys)
                .query(this::mapMetaKey)
                .list()
                .stream()
                .collect(Collectors.toMap(MetaKeyRecord::key, r -> r));
    }

    private StmEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StmEvent(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("actor"),
                rs.getString("kind"),
                rs.getString("content"),
                rs.getString("meta"),
                rs.getTimestamp("created_at").toInstant(),
                nullableLong(rs, "superseded_by"),
                nullableInstant(rs, "superseded_at"));
    }

    private MetaKeyRecord mapMetaKey(ResultSet rs, int rowNum) throws SQLException {
        List<String> allowedValues = null;
        java.sql.Array array = rs.getArray("allowed_values");
        if (array != null) {
            String[] raw = (String[]) array.getArray();
            allowedValues = (raw == null) ? null : List.of(raw);
        }
        return new MetaKeyRecord(
                rs.getString("key"),
                rs.getString("value_type"),
                allowedValues,
                rs.getBoolean("required"),
                rs.getString("description"),
                nullableInstant(rs, "deprecated_at"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String likePattern(String query) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
