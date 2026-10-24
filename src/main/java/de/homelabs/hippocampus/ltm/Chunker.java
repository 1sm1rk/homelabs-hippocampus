package de.homelabs.hippocampus.ltm;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into chunks sized for the MiniLM 128-token window (ADR 0005):
 * aim for ~{@code targetTokens} tokens, slide with ~{@code overlapTokens} overlap,
 * prefer sentence boundaries (word boundary as fallback), and never exceed
 * {@code hardCapTokens}. A text that already fits the target stays a single chunk.
 *
 * <p>Sizing is delegated to a {@link TokenCounter} (tokenizer-based in production,
 * a fake in unit tests) so chunks respect the real model vocabulary. The token
 * counts include the model's special tokens.
 */
public class Chunker {

    private final int targetTokens;
    private final int overlapTokens;
    private final int hardCapTokens;

    public Chunker(int targetTokens, int overlapTokens, int hardCapTokens) {
        if (targetTokens <= 0) {
            throw new IllegalArgumentException("targetTokens must be > 0, got " + targetTokens);
        }
        if (overlapTokens < 0 || overlapTokens >= targetTokens) {
            throw new IllegalArgumentException(
                    "overlapTokens must be in [0, targetTokens), got " + overlapTokens + " / " + targetTokens);
        }
        if (hardCapTokens < targetTokens) {
            throw new IllegalArgumentException(
                    "hardCapTokens must be >= targetTokens, got " + hardCapTokens + " / " + targetTokens);
        }
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
        this.hardCapTokens = hardCapTokens;
    }

    /**
     * Splits {@code text} into an ordered list of chunks. The returned order is the
     * chunk sequence. An empty/blank text yields no chunks.
     */
    public List<Chunk> chunk(String text, TokenCounter counter) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int total = counter.count(text);
        if (total <= targetTokens) {
            chunks.add(new Chunk(text, total));
            return chunks;
        }

        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = findEnd(text, start, counter);
            if (end <= start) {
                end = Math.min(start + 1, len); // degenerate fallback: always advance
            }
            String chunkText = text.substring(start, end).strip();
            if (!chunkText.isEmpty()) {
                chunks.add(new Chunk(chunkText, counter.count(chunkText)));
            }
            if (end >= len) {
                break;
            }
            int next = findOverlapStart(text, end, counter);
            start = (next > start) ? next : end; // drop overlap rather than stall
        }
        return chunks;
    }

    /**
     * Chooses the chunk end: prefer extending forward to the next sentence
     * boundary (up to the hard cap) — this keeps an overlapping chunk from
     * collapsing to its overlap tail — then fall back to the previous sentence
     * boundary, then a word boundary. Chunks stay sentence-aligned where possible
     * and never exceed {@code hardCapTokens}.
     */
    private int findEnd(String text, int start, TokenCounter counter) {
        int targetLimit = maxFeasibleEnd(text, start, counter, targetTokens);
        int hardLimit = maxFeasibleEnd(text, start, counter, hardCapTokens);

        int boundary = nextSentenceBoundary(text, targetLimit, hardLimit);
        if (boundary < 0) {
            boundary = lastSentenceBoundary(text, start, targetLimit);
        }
        if (boundary > start) {
            return boundary;
        }
        boundary = lastWordBoundary(text, start, targetLimit);
        if (boundary > start) {
            return boundary;
        }
        return targetLimit;
    }

    /**
     * Overlap start: the earliest index whose tail {@code text[s..end]} is at most
     * {@code overlapTokens} tokens, snapped forward to a word boundary so the next
     * chunk does not begin mid-word. Word boundary only — the overlap tail is a
     * partial re-read, so starting mid-sentence is fine, starting mid-word is not.
     */
    private int findOverlapStart(String text, int end, TokenCounter counter) {
        int s = minFeasibleStart(text, end, counter, overlapTokens);
        int boundary = firstWordBoundary(text, s, end);
        if (boundary >= 0) {
            return boundary;
        }
        return s;
    }

    private int maxFeasibleEnd(String text, int start, TokenCounter counter, int maxTokens) {
        int lo = start;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (counter.count(text.substring(start, mid)) <= maxTokens) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private int minFeasibleStart(String text, int end, TokenCounter counter, int maxTokens) {
        int lo = 0;
        int hi = end;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (counter.count(text.substring(mid, end)) <= maxTokens) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** Smallest boundary index in {@code [from, to]} whose preceding char ends a sentence. */
    private int nextSentenceBoundary(String text, int from, int to) {
        for (int i = Math.max(from, 1); i <= to; i++) {
            if (isSentenceTerminator(text.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /** Largest boundary index in {@code (start, limit]} whose preceding char ends a sentence. */
    private int lastSentenceBoundary(String text, int start, int limit) {
        for (int i = limit; i > start; i--) {
            if (isSentenceTerminator(text.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /** Largest boundary index in {@code (start, limit]} whose preceding char is whitespace. */
    private int lastWordBoundary(String text, int start, int limit) {
        for (int i = limit; i > start; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /** Smallest boundary index in {@code [from, to]} whose preceding char is whitespace. */
    private int firstWordBoundary(String text, int from, int to) {
        for (int i = Math.max(from, 1); i <= to; i++) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSentenceTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\n';
    }
}
