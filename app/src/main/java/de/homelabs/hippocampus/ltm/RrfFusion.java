package de.homelabs.hippocampus.ltm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion over the four {@code ltm_find} search arms (cosine,
 * German FTS, trigram, ILIKE). No weight tuning — each arm contributes
 * {@code 1 / (K + rank)} for its 1-based rank, the contributions sum per chunk,
 * and the per-entry best chunk wins the dedup (ADR 0005 / AP-6 scope: the
 * museum's 0.5/0.3/0.2 weights belonged to a 3-arm graph setup Stage 1 has not).
 *
 * <p>Pure function, unit-testable without a database.
 */
public final class RrfFusion {

    /** The RRF smoothing constant (60 is the standard value). */
    public static final double K = 60.0;

    private RrfFusion() {
    }

    /** One arm's result: its name (cosine/fts/trigram/ilike) and its ranked hits. */
    public record ArmResult(String name, List<SearchArmHit> hits) {
    }

    /** A fused, deduplicated hit: the best chunk per entry plus its arms and score. */
    public record FusedHit(long ltmId, int seq, double score, List<String> arms) {
        public FusedHit {
            arms = List.copyOf(arms);
        }
    }

    /**
     * Fuses the arms into one ranking, ordered by descending score (then by
     * ascending {@code seq} and {@code ltmId} for determinism), deduplicated so
     * each entry appears once with its highest-scoring chunk.
     */
    public static List<FusedHit> fuse(List<ArmResult> armResults) {
        Map<Long, Map<Integer, Accumulator>> byEntry = new LinkedHashMap<>();
        for (ArmResult arm : armResults) {
            List<SearchArmHit> hits = arm.hits();
            for (int i = 0; i < hits.size(); i++) {
                SearchArmHit hit = hits.get(i);
                double contribution = 1.0 / (K + i + 1);
                byEntry.computeIfAbsent(hit.ltmId(), key -> new LinkedHashMap<>())
                        .computeIfAbsent(hit.seq(), key -> new Accumulator())
                        .add(contribution, arm.name());
            }
        }

        List<FusedHit> fused = new ArrayList<>();
        for (Map.Entry<Long, Map<Integer, Accumulator>> entry : byEntry.entrySet()) {
            long ltmId = entry.getKey();
            for (Map.Entry<Integer, Accumulator> chunk : entry.getValue().entrySet()) {
                Accumulator acc = chunk.getValue();
                fused.add(new FusedHit(ltmId, chunk.getKey(), acc.score, List.copyOf(acc.arms)));
            }
        }
        fused.sort(Comparator.comparingDouble(FusedHit::score).reversed()
                .thenComparingInt(FusedHit::seq)
                .thenComparingLong(FusedHit::ltmId));

        Map<Long, FusedHit> bestPerEntry = new LinkedHashMap<>();
        for (FusedHit hit : fused) {
            bestPerEntry.putIfAbsent(hit.ltmId(), hit);
        }
        return new ArrayList<>(bestPerEntry.values());
    }

    private static final class Accumulator {
        double score;
        final Set<String> arms = new LinkedHashSet<>();

        void add(double contribution, String arm) {
            score += contribution;
            arms.add(arm);
        }
    }
}
