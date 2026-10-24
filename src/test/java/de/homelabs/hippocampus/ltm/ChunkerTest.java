package de.homelabs.hippocampus.ltm;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerTest {

    /** One token per whitespace-delimited word (simple, deterministic stand-in). */
    private static final TokenCounter words =
            text -> (text == null || text.isBlank()) ? 0 : text.trim().split("\\s+").length;

    /** One token per 4 chars (subword-ish, so a long unbroken run still spans many tokens). */
    private static final TokenCounter chars = text -> text == null ? 0 : (text.length() + 3) / 4;

    private final Chunker chunker = new Chunker(6, 2, 8);

    @Test
    void blankOrNullProducesNoChunks() {
        assertThat(chunker.chunk(null, words)).isEmpty();
        assertThat(chunker.chunk("   ", words)).isEmpty();
        assertThat(chunker.chunk("", words)).isEmpty();
    }

    @Test
    void textThatFitsStaysOneChunk() {
        List<Chunk> chunks = chunker.chunk("a short note", words);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("a short note");
        assertThat(chunks.get(0).tokenCount()).isEqualTo(3);
    }

    @Test
    void chunksRespectSentenceBoundariesAndHardCap() {
        String text = "One two three. Four five six. Seven eight nine. Ten eleven twelve.";
        List<Chunk> chunks = chunker.chunk(text, words);

        assertThat(chunks).isNotEmpty();
        for (Chunk chunk : chunks) {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(8);
            String trimmed = chunk.text().trim();
            assertThat(trimmed).endsWith(".");
        }
    }

    @Test
    void adjacentChunksOverlapAtWordBoundary() {
        String text = "One two three four five. Six seven eight nine ten. "
                + "Eleven twelve thirteen fourteen fifteen. Sixteen seventeen eighteen nineteen twenty.";
        List<Chunk> chunks = chunker.chunk(text, words);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i < chunks.size() - 1; i++) {
            String tail = lastWord(chunks.get(i).text());
            assertThat(chunks.get(i + 1).text()).contains(tail);
        }
    }

    @Test
    void noChunkExceedsHardCapEvenWithoutWordBoundaries() {
        // 512 chars, no spaces: ~128 tokens under the char counter, must be split.
        String unbroken = "x".repeat(512);
        Chunker realistic = new Chunker(115, 24, 128);
        List<Chunk> chunks = realistic.chunk(unbroken, chars);

        assertThat(chunks.size()).isGreaterThan(1);
        for (Chunk chunk : chunks) {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(128);
        }
        // No content is lost (only duplicated by overlap).
        int covered = chunks.stream().mapToInt(c -> c.text().length()).sum();
        assertThat(covered).isGreaterThanOrEqualTo(512);
    }

    @Test
    void constructorRejectsInvalidParameters() {
        assertThatThrownBy(() -> new Chunker(0, 0, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(6, 6, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(10, 2, 8)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String lastWord(String text) {
        String trimmed = text.trim();
        int i = trimmed.lastIndexOf(' ');
        return i < 0 ? trimmed : trimmed.substring(i + 1);
    }
}
