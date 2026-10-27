package de.homelabs.hippocampus.stm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Closed vocabulary for the kind column. Stage 0 usage insight (2026-09-28):
 * kinds must be a finite set; anything else is rejected by store.
 * Used as the store tool parameter type since 2026-10-04 (AP-3, rule R3:
 * schema over prose). Two hard findings from the first run:
 * (1) Spring AI 2.0.1 validates tool input SERVER-SIDE against the generated
 *     JSON schema before binding — the enum is enforced at the protocol layer.
 * (2) victools maps the enum CONSTANT NAMES into the schema, not @JsonValue —
 *     so the constants carry the wire values (lowercase) on purpose: schema,
 *     wire and database share one vocabulary. Historical rows and clients
 *     send lowercase; uppercase spellings are rejected by the schema.
 * Java naming convention (UPPER_CASE constants) yields to wire alignment here.
 */
public enum StmKind {
    fact("fact"),
    note("note"),
    task("task"),
    decision("decision");

    private final String value;

    StmKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Parses the wire value, case-insensitive. Throws for unknown values so
     * the binding layer rejects them with a helpful message instead of
     * storing null. Returns null only for a null/absent argument.
     */
    @JsonCreator
    public static StmKind parse(String value) {
        if (value == null) {
            return null;
        }
        for (StmKind kind : values()) {
            if (kind.value.equalsIgnoreCase(value.trim())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown kind '" + value + "'; allowed values: fact, note, task, decision");
    }
}