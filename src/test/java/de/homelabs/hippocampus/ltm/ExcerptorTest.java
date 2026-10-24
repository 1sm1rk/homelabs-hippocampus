package de.homelabs.hippocampus.ltm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcerptorTest {

    @Test
    void shortTextIsReturnedWhole() {
        String text = "a short chunk";
        assertThat(Excerptor.excerpt(text, "short", 200)).isEqualTo(text);
    }

    @Test
    void blankTextIsEmpty() {
        assertThat(Excerptor.excerpt("", "q", 200)).isEmpty();
        assertThat(Excerptor.excerpt(null, "q", 200)).isEmpty();
        assertThat(Excerptor.excerpt("   ", "q", 200)).isEmpty();
    }

    @Test
    void centersWindowOnTheQueryMatch() {
        String filler = "word word word "; // trailing space, so windows trim cleanly
        String text = filler.repeat(20) + "the hippocampus service embeds content" + filler.repeat(20);
        String excerpt = Excerptor.excerpt(text, "hippocampus", 200);

        assertThat(excerpt).contains("hippocampus");
        assertThat(excerpt).startsWith("…").endsWith("…");
        assertThat(excerpt.length()).isLessThanOrEqualTo(202);
    }

    @Test
    void matchNearStartHasNoLeadingEllipsis() {
        String text = "hippocampus service " + "and ".repeat(100);
        String excerpt = Excerptor.excerpt(text, "hippocampus", 200);

        assertThat(excerpt).startsWith("hippocampus");
        assertThat(excerpt).doesNotStartWith("…");
        assertThat(excerpt).endsWith("…");
    }

    @Test
    void noMatchReturnsTheFirstWindow() {
        String text = "first sentence. " + "filler ".repeat(100);
        String excerpt = Excerptor.excerpt(text, "zzzz-not-present", 200);

        assertThat(excerpt).startsWith("first sentence");
        assertThat(excerpt).endsWith("…");
        assertThat(excerpt).doesNotStartWith("…");
    }

    @Test
    void fallsBackToFirstMeaningfulQueryToken() {
        String text = "prefix ".repeat(20) + "embeddings are useful" + " suffix".repeat(50);
        String excerpt = Excerptor.excerpt(text, "the embedding model", 200);

        assertThat(excerpt).contains("embeddings");
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThatThrownBy(() -> Excerptor.excerpt("text", "q", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
