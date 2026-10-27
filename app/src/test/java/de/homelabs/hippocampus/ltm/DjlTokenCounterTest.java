package de.homelabs.hippocampus.ltm;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast unit test for the eager {@link DjlTokenCounter#init()}. Only a bogus
 * path is used: the file existence check throws BEFORE any native library load,
 * so this test is safe in-process (no DJL natives here).
 */
class DjlTokenCounterTest {

    @TempDir
    Path tempDir;

    @Test
    void initFailsFastWhenTokenizerIsMissing() {
        DjlTokenCounter counter = new DjlTokenCounter(tempDir.resolve("missing-tokenizer.json").toString());

        assertThatThrownBy(counter::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tokenizer not found")
                .hasMessageContaining("run scripts/install-minilm.sh");
    }
}
