package de.homelabs.hippocampus.stm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * Business logic between the MCP tool layer and the repository.
 *
 * <p>{@code store} consults the meta_key_registry (Q1): registered keys are
 * validated against their closed value range, deprecated keys and unregistered keys
 * are accepted but surfaced as warnings (unregistered keys are curation candidates,
 * never rejected). {@code update} implements mechanized supersedes (Q2/Q3): the
 * corrected event is written as a new row, the old row is marked superseded, and no
 * content is ever mutated (ADR 0002).
 */
@Service
public class StmService {

    private final StmRepository repository;
    private final ObjectMapper objectMapper;

    public StmService(StmRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Result of a store call: the new event id plus any non-fatal warnings. */
    public record StoreOutcome(long id, List<String> warnings) {
        public StoreOutcome {
            warnings = List.copyOf(warnings);
        }
    }

    public StoreOutcome store(String owner, String source, String actor, String kind, String content, Map<String, String> meta) {
        List<String> warnings = new ArrayList<>();
        if (meta != null && !meta.isEmpty()) {
            Map<String, MetaKeyRecord> registry = repository.findMetaKeys(meta.keySet());
            List<String> unregistered = new ArrayList<>();
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                MetaKeyRecord record = registry.get(entry.getKey());
                if (record == null) {
                    unregistered.add(entry.getKey());
                } else if (record.deprecatedAt() != null) {
                    warnings.add("meta key '" + entry.getKey() + "' is deprecated — " + record.description());
                } else if (record.allowedValues() != null && !record.allowedValues().contains(entry.getValue())) {
                    throw new IllegalArgumentException("meta key '" + entry.getKey() + "' must be one of " + record.allowedValues());
                }
            }
            if (!unregistered.isEmpty()) {
                warnings.add("unregistered meta keys (curation candidates): " + String.join(", ", unregistered));
            }
        }
        long id = repository.save(owner, source, actor, kind, content, meta);
        return new StoreOutcome(id, warnings);
    }

    /**
     * Mechanized supersedes: writes the corrected event and marks the old one
     * superseded. Absent fields are inherited from the old event; {@code meta} is
     * merged per-key (new keys added, same-named keys override, the rest survives).
     */
    public long update(String owner, String source, long id, String content, StmKind kind, String actor, Map<String, String> meta) {
        if (content == null && kind == null && actor == null && (meta == null || meta.isEmpty())) {
            throw new IllegalArgumentException("nothing to change: pass at least one of content, kind, actor, meta");
        }
        StmEvent old = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no event #" + id + " to update"));

        String newContent = content != null ? content : old.content();
        String newKind = kind != null ? kind.value() : old.kind();
        String newActor = actor != null ? actor : old.actor();

        Map<String, String> mergedMeta = mergeMeta(old.meta(), meta);
        mergedMeta.put("supersedes", String.valueOf(id));

        long newId = repository.save(owner, source, newActor, newKind, newContent, mergedMeta);
        repository.markSuperseded(id, newId);
        return newId;
    }

    public List<StmEvent> find(String query, int limit, boolean includeSuperseded) {
        return repository.find(query, limit, includeSuperseded);
    }

    public List<StmEvent> recent(int limit, boolean includeSuperseded) {
        return repository.recent(limit, includeSuperseded);
    }

    private Map<String, String> mergeMeta(String oldMetaJson, Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(parseMeta(oldMetaJson));
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged;
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
