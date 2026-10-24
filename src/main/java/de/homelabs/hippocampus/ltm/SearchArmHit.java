package de.homelabs.hippocampus.ltm;

/**
 * One hit of a single {@code ltm_find} search arm: the chunk that matched,
 * identified by its entry id and 1-based {@code seq}. The arm's own ordering
 * (best first) supplies the rank for Reciprocal Rank Fusion.
 */
public record SearchArmHit(long ltmId, int seq) {
}
