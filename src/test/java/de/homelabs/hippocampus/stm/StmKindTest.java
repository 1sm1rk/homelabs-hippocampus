package de.homelabs.hippocampus.stm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StmKindTest {

    @Test
    void parsesKnownValuesCaseInsensitive() {
        assertThat(StmKind.parse("fact")).isEqualTo(StmKind.fact);
        assertThat(StmKind.parse("NOTE")).isEqualTo(StmKind.note);
        assertThat(StmKind.parse(" Task ")).isEqualTo(StmKind.task);
        assertThat(StmKind.parse("Decision")).isEqualTo(StmKind.decision);
    }

    @Test
    void rejectsUnknownAndNull() {
        assertThatThrownBy(() -> StmKind.parse("gibberish"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gibberish")
                .hasMessageContaining("fact, note, task, decision");
        assertThatThrownBy(() -> StmKind.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StmKind.parse(null)).isNull();
    }

    @Test
    void wireValuesAreStable() {
        assertThat(StmKind.fact.value()).isEqualTo("fact");
        assertThat(StmKind.note.value()).isEqualTo("note");
        assertThat(StmKind.task.value()).isEqualTo("task");
        assertThat(StmKind.decision.value()).isEqualTo("decision");
    }
}