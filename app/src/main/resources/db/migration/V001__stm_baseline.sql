CREATE TABLE stm_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source      TEXT        NOT NULL,
    kind        TEXT        NOT NULL,
    content     TEXT        NOT NULL,
    meta        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stm_event_created_at ON stm_event (created_at DESC);
CREATE INDEX idx_stm_event_source ON stm_event (source);