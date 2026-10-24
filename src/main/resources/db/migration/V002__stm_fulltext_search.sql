-- Lexical search for STM (Stage 1 prep, decision 2026-09-09):
-- hybrid FTS + embeddings later; embeddings may be down, ILIKE stays as fallback.
-- 'german' config because most memories are German; stems 'speichert' -> 'speicher'.

ALTER TABLE stm_event
    ADD COLUMN tsv tsvector GENERATED ALWAYS AS
        (to_tsvector('german', content)) STORED;

CREATE INDEX idx_stm_event_tsv ON stm_event USING GIN (tsv);