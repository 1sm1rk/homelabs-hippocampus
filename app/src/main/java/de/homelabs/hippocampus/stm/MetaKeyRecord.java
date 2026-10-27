package de.homelabs.hippocampus.stm;

import java.time.Instant;
import java.util.List;

/**
 * One row of the meta_key_registry (V005). Definition-time record on the Postgres
 * type-extensibility model: {@code allowedValues} is NULL for free-text keys and
 * carries the closed range once the owner tightens a key; {@code deprecatedAt} is
 * non-null for keys that warn instead of being removed.
 */
public record MetaKeyRecord(
        String key,
        String valueType,
        List<String> allowedValues,
        boolean required,
        String description,
        Instant deprecatedAt) {
}
