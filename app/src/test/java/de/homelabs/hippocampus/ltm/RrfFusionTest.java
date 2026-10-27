package de.homelabs.hippocampus.ltm;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private static SearchArmHit hit(long ltmId, int seq) {
        return new SearchArmHit(ltmId, seq);
    }

    @Test
    void emptyInputFusesToEmpty() {
        assertThat(RrfFusion.fuse(List.of())).isEmpty();
    }

    @Test
    void multipleArmHitsScoreHigherThanSingleArmHits() {
        // entry 1 chunk 1 hits arm "a" at rank 1 AND arm "b" at rank 1; entry 2 only arm "a" at rank 2
        var arms = List.of(
                new RrfFusion.ArmResult("a", List.of(hit(1, 1), hit(2, 1))),
                new RrfFusion.ArmResult("b", List.of(hit(1, 1))));

        var fused = RrfFusion.fuse(arms);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).ltmId()).isEqualTo(1);
        assertThat(fused.get(0).seq()).isEqualTo(1);
        assertThat(fused.get(0).arms()).containsExactly("a", "b");
        // 2/61 > 1/62
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        assertThat(fused.get(1).ltmId()).isEqualTo(2);
        assertThat(fused.get(1).arms()).containsExactly("a");
    }

    @Test
    void deduplicatesPerEntryKeepingTheBestChunk() {
        // entry 1 has seq 1 (rank 1 in arm "a") and seq 2 (rank 3 in arm "a")
        var arms = List.of(new RrfFusion.ArmResult("a", List.of(hit(1, 1), hit(9, 1), hit(1, 2))));

        var fused = RrfFusion.fuse(arms);

        assertThat(fused).extracting(RrfFusion.FusedHit::ltmId).containsExactly(1L, 9L);
        assertThat(fused.get(0).seq()).isEqualTo(1);
    }

    @Test
    void armNamesArePreservedInOrderAndDeduplicated() {
        // the same chunk hit twice in the same arm contributes twice to the score but lists the arm once
        var arms = List.of(
                new RrfFusion.ArmResult("cosine", List.of(hit(1, 1), hit(1, 1))),
                new RrfFusion.ArmResult("ilike", List.of(hit(1, 1))));

        var fused = RrfFusion.fuse(arms);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).arms()).containsExactly("cosine", "ilike");
        // the duplicated cosine hit occupies ranks 1 and 2 of that arm (1/61 + 1/62), plus ilike rank 1 (1/61)
        assertThat(fused.get(0).score()).isCloseTo(
                2.0 / (RrfFusion.K + 1) + 1.0 / (RrfFusion.K + 2),
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void resultsAreDeterministicOnTies() {
        var arms = List.of(
                new RrfFusion.ArmResult("a", List.of(hit(2, 1))),
                new RrfFusion.ArmResult("b", List.of(hit(1, 1))));

        assertThat(RrfFusion.fuse(arms)).isEqualTo(RrfFusion.fuse(arms));
    }
}
