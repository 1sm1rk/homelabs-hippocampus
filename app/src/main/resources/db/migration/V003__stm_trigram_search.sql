-- Trigram search (pg_trgm): German snowball stemming is asymmetric for verb forms
-- ('speichert' stays unstemmed while 'speichere' stems to 'speich'), so FTS alone
-- misses morphological variants. word_similarity catches them (0.8 for that pair)
-- plus typos. GIN gin_trgm_ops also accelerates ILIKE.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_stm_event_content_trgm ON stm_event USING GIN (content gin_trgm_ops);