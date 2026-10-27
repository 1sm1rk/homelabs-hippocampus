-- LTM baseline (V008, AP-6 session 1): the long-term memory tables for Stage 1.
-- pgvector 0.8.5 is available in the container image but the extension is not yet
-- installed — created here (ADR 0005: MiniLM-L12-v2, 384d, in-process embeddings).

CREATE EXTENSION IF NOT EXISTS vector;

-- One long-term memory entry: promoted STM events and imported documents alike.
-- Append-only, like STM (ADR 0002): no mutation, no delete; cleanup is Stage 2.
CREATE TABLE ltm_entry (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       TEXT        NOT NULL,
    content     TEXT        NOT NULL,
    kind        TEXT        NULL,          -- promoted events carry their StmKind; documents get NULL
    meta        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    origin_type TEXT        NOT NULL CHECK (origin_type IN ('promote', 'import')),
    origin_ref  TEXT        NOT NULL,     -- stm event id (promote) | file path (import)
    origin_hash TEXT        NOT NULL,     -- content hash (unchanged re-import detection)
    owner       TEXT        NOT NULL DEFAULT 'unknown',
    source      TEXT        NOT NULL,     -- server-derived clientInfo (V004 semantics)
    actor       TEXT        NOT NULL DEFAULT 'unknown',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ltm_entry_created_at ON ltm_entry (created_at DESC);

-- Dedup guards (append-only): re-promoting the same STM id is a no-op, re-importing
-- an unchanged file is a no-op, changed content creates a new entry.
CREATE UNIQUE INDEX uq_ltm_entry_promote ON ltm_entry (origin_ref) WHERE origin_type = 'promote';
CREATE UNIQUE INDEX uq_ltm_entry_import  ON ltm_entry (origin_ref, origin_hash) WHERE origin_type = 'import';

-- One chunk of an entry, embedded for semantic search. Chunks are 1-based sequential
-- (seq) and never exceed the model's 128-token window (chunker guarantees it).
CREATE TABLE ltm_chunk (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ltm_id      BIGINT      NOT NULL REFERENCES ltm_entry (id) ON DELETE CASCADE,
    seq         INT         NOT NULL,
    chunk_text  TEXT        NOT NULL,
    embedding   VECTOR(384) NOT NULL,
    tokens      INT         NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ltm_id, seq)
);

-- HNSW index for cosine similarity (the semantic search arm: embedding <=> query).
CREATE INDEX idx_ltm_chunk_embedding ON ltm_chunk USING hnsw (embedding vector_cosine_ops);

-- Lexical arms mirror the STM search arms (V002/V003): German FTS + trigram.
CREATE INDEX idx_ltm_chunk_text_fts ON ltm_chunk USING GIN (to_tsvector('german', chunk_text));
CREATE INDEX idx_ltm_chunk_text_trgm ON ltm_chunk USING GIN (chunk_text gin_trgm_ops);
