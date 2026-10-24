package de.homelabs.hippocampus.ltm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

/**
 * Token counting via the model's HuggingFace tokenizer (tokenizer.json) loaded
 * through DJL (ADR 0005 / museum tokenizer-decision). Loaded EAGERLY at service
 * startup via {@link #init()} (called from {@link LtmConfig}): a missing or broken
 * tokenizer aborts the context refresh, so the service never comes up with a
 * silently broken tokenizer. {@link #count} keeps its ensure-loaded guard as
 * defense in depth.
 */
public class DjlTokenCounter implements TokenCounter {

    private final Path tokenizerPath;

    private volatile HuggingFaceTokenizer tokenizer;

    public DjlTokenCounter(String tokenizerPath) {
        this.tokenizerPath = Path.of(tokenizerPath).toAbsolutePath().normalize();
    }

    /**
     * Loads the tokenizer eagerly. Called from {@link LtmConfig} at startup; a
     * failure propagates and aborts the context refresh, so the service never
     * starts with a missing or broken tokenizer.
     */
    public synchronized void init() {
        if (tokenizer == null) {
            load();
        }
    }

    @Override
    public int count(String text) {
        return (int) tokenizer().encode(text).getIds().length;
    }

    private synchronized HuggingFaceTokenizer tokenizer() {
        if (tokenizer == null) {
            load();
        }
        return tokenizer;
    }

    private void load() {
        if (!Files.isRegularFile(tokenizerPath)) {
            throw new IllegalStateException("Tokenizer not found: " + tokenizerPath
                    + " — run scripts/install-minilm.sh (see README)");
        }
        try {
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath, Map.of("addSpecialTokens", "true"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load tokenizer: " + tokenizerPath, e);
        }
    }
}
