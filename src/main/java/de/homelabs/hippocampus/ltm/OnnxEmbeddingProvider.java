package de.homelabs.hippocampus.ltm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process embedding via ONNX Runtime (ADR 0005): paraphrase-multilingual-
 * MiniLM-L12-v2, 384d. Museum lesson applied: {@code OrtEnvironment} is a
 * singleton and is never closed. The model and tokenizer load EAGERLY at service
 * startup via {@link #init()} (called from {@link LtmConfig}): a missing or broken
 * artifact aborts the context refresh, so the service never comes up with a
 * silently broken LTM. {@link #init()} also runs one warm-up embed through the
 * full pipeline and verifies the output dimension (384) — a wrong model file
 * fails at boot instead of at the first chunk INSERT with a cryptic pgvector
 * error. {@link #embed} keeps its ensure-loaded guard as defense in depth.
 * Pooling is attention-mask-weighted mean followed by L2 normalization (the
 * chosen model's spec, not CLS).
 */
public class OnnxEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

    private final Path modelPath;
    private final Path tokenizerPath;
    private final int dimension;

    private volatile HuggingFaceTokenizer tokenizer;
    private volatile OrtSession session;
    private volatile Set<String> inputNames;

    public OnnxEmbeddingProvider(LtmProperties.Embedding config) {
        this.modelPath = Path.of(config.modelPath()).toAbsolutePath().normalize();
        this.tokenizerPath = Path.of(config.tokenizerPath()).toAbsolutePath().normalize();
        this.dimension = config.dimension();
    }

    /**
     * Loads the tokenizer and ONNX session eagerly and runs one warm-up embed
     * through the full pipeline (tokenizer → tensors → session.run → pooling),
     * which verifies the output dimension against the configured one (384).
     * Called from {@link LtmConfig} at startup; a failure propagates and aborts
     * the context refresh, so the service never starts with a missing or broken
     * model.
     */
    public synchronized void init() {
        if (session != null) {
            return; // already initialized
        }
        load();
        // Warm-up through the real pipeline; embedBatch asserts the model's
        // output dimension, so a wrong model file fails here, at boot.
        embed("hippocampus warm-up");
        LOG.info("Embedding model ready: {} (dimension {}, warm-up ok)", modelPath, dimension);
    }

    @Override
    public float[] embed(String text) {
        ensureLoaded();
        return embedBatch(List.of(text))[0];
    }

    private synchronized void ensureLoaded() {
        if (session == null) {
            load();
        }
    }

    private void load() {
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalStateException("Embedding model not found: " + modelPath
                    + " — run scripts/install-minilm.sh (see README)");
        }
        if (!Files.isRegularFile(tokenizerPath)) {
            throw new IllegalStateException("Tokenizer not found: " + tokenizerPath
                    + " — run scripts/install-minilm.sh (see README)");
        }
        try {
            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath, Map.of("addSpecialTokens", "true"));
            // OrtEnvironment is a process-wide singleton — never close it.
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
            this.inputNames = session.getInputNames();
        } catch (OrtException | java.io.IOException e) {
            throw new IllegalStateException("Failed to load embedding model: " + modelPath, e);
        }
    }

    private float[][] embedBatch(List<String> texts) {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try {
            var encodings = tokenizer.batchEncode(texts.toArray(String[]::new));
            int batch = encodings.length;
            int seqLen = 0;
            for (var e : encodings) {
                seqLen = Math.max(seqLen, (int) e.getIds().length);
            }
            if (seqLen == 0) {
                return new float[batch][dimension];
            }

            long[][] inputIds = new long[batch][seqLen];
            long[][] attentionMask = new long[batch][seqLen];
            long[][] tokenTypeIds = new long[batch][seqLen];
            for (int b = 0; b < batch; b++) {
                var e = encodings[b];
                int n = (int) e.getIds().length;
                for (int i = 0; i < n; i++) {
                    inputIds[b][i] = e.getIds()[i];
                    attentionMask[b][i] = e.getAttentionMask()[i];
                    tokenTypeIds[b][i] = e.getTypeIds()[i];
                }
            }

            Map<String, OnnxTensor> inputs = new HashMap<>();
            // Only feed the inputs the model actually declares.
            if (inputNames.contains("input_ids")) {
                inputs.put("input_ids", OnnxTensor.createTensor(env, inputIds));
            }
            if (inputNames.contains("attention_mask")) {
                inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMask));
            }
            if (inputNames.contains("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeIds));
            }

            try {
                float[][] result;
                try (var ortResult = session.run(inputs)) {
                    float[][][] lastHidden = (float[][][]) ortResult.get(0).getValue();
                    int actualDim = lastHidden[0][0].length;
                    if (actualDim != dimension) {
                        throw new IllegalStateException("Embedding model dimension mismatch: expected "
                                + dimension + " but the model outputs " + actualDim
                                + " — run scripts/install-minilm.sh (see README)");
                    }
                    result = meanPool(lastHidden, attentionMask, dimension);
                }
                return result;
            } finally {
                for (OnnxTensor tensor : inputs.values()) {
                    tensor.close();
                }
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Embedding failed", e);
        }
    }

    /** Attention-mask-weighted mean pooling over the last hidden state, then L2 normalization. */
    private static float[][] meanPool(float[][][] lastHidden, long[][] attentionMask, int dim) {
        int batch = lastHidden.length;
        float[][] result = new float[batch][dim];
        for (int b = 0; b < batch; b++) {
            float[][] tokens = lastHidden[b];
            long[] mask = attentionMask[b];
            double[] sum = new double[dim];
            int count = 0;
            for (int t = 0; t < tokens.length; t++) {
                if (mask[t] == 0) {
                    continue;
                }
                for (int i = 0; i < dim; i++) {
                    sum[i] += tokens[t][i];
                }
                count++;
            }
            float[] mean = new float[dim];
            if (count > 0) {
                for (int i = 0; i < dim; i++) {
                    mean[i] = (float) (sum[i] / count);
                }
            }
            double norm = 0;
            for (float v : mean) {
                norm += (double) v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dim; i++) {
                    mean[i] = (float) (mean[i] / norm);
                }
            }
            result[b] = mean;
        }
        return result;
    }
}
