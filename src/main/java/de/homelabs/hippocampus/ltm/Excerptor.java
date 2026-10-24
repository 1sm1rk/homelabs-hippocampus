package de.homelabs.hippocampus.ltm;

/**
 * Builds the short excerpt {@code ltm_find} returns per hit (AP-6 scope, #13):
 * a ~200-char window around the matching region of the matched chunk, never the
 * whole entry. The window is centred on the query (or its first meaningful
 * token) when it appears in the chunk, snapped to a word boundary, and marked
 * with ellipses when trimmed on either side.
 */
public final class Excerptor {

    private Excerptor() {
    }

    /**
     * Returns an excerpt of at most {@code windowChars} chars from {@code text},
     * centred on the best match of {@code query}. Short texts are returned whole;
     * texts longer than the window are trimmed with leading/trailing ellipses.
     */
    public static String excerpt(String text, String query, int windowChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (windowChars <= 0) {
            throw new IllegalArgumentException("windowChars must be > 0, got " + windowChars);
        }
        int len = text.length();
        if (len <= windowChars) {
            return text;
        }

        int pos = locate(text, query);
        int start = (pos >= 0) ? Math.max(0, pos - windowChars / 2) : 0;
        start = Math.min(start, len - windowChars);
        // Do not start mid-word: advance to just after the next whitespace when
        // it lies close ahead (keeps the excerpt readable).
        if (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            int space = text.indexOf(' ', start);
            if (space >= 0 && space < start + windowChars / 2) {
                start = space + 1;
            }
        }
        int end = Math.min(len, start + windowChars);
        String window = text.substring(start, end).strip();
        return (start > 0 ? "…" : "") + window + (end < len ? "…" : "");
    }

    /** Index of the query (or its first token of length ≥ 3) in the text, else -1. */
    private static int locate(String text, String query) {
        String lower = text.toLowerCase();
        String q = (query == null ? "" : query.trim()).toLowerCase();
        if (q.isEmpty()) {
            return -1;
        }
        int idx = lower.indexOf(q);
        if (idx >= 0) {
            return idx;
        }
        for (String token : q.split("\\s+")) {
            if (token.length() >= 3) {
                idx = lower.indexOf(token);
                if (idx >= 0) {
                    return idx;
                }
            }
        }
        return -1;
    }
}
