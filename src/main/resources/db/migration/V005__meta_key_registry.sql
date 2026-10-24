-- Meta key registry (V005, AP-3 session 2): definition table for meta keys on the
-- Postgres type-extensibility model. Definition time = Flyway migration; usage time
-- = store call (store consults this registry). Unknown keys are curation candidates,
-- never rejected. Deprecation via deprecated_at, never delete (pg_type model).
-- Consumer (Q1): the store tool validates registered keys against allowed_values and
-- warns on deprecated / unregistered keys. Contents are curated ONLY via migrations.

CREATE TABLE meta_key_registry (
    key            TEXT        PRIMARY KEY,
    value_type     TEXT        NOT NULL DEFAULT 'string',
    allowed_values TEXT[]      NULL,
    required       BOOLEAN     NOT NULL DEFAULT false,
    description    TEXT        NOT NULL DEFAULT '',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deprecated_at  TIMESTAMPTZ NULL
);

-- Canonical keys (free-text, optional). Seed decision 2026-10-04.
INSERT INTO meta_key_registry (key, value_type, allowed_values, required, description) VALUES
    ('topic',      'string', NULL, false, ''),
    ('project',    'string', NULL, false, ''),
    ('relates_to', 'string', NULL, false, 'comma-separated event ids'),
    ('supersedes', 'string', NULL, false, 'event id'),
    ('typ',        'string', NULL, false, 'free-text subtype (aufgabe, doku-erstellung, …)'),
    ('week',       'string', NULL, false, 'ISO week, e.g. 2026-W41'),
    ('stage',      'string', NULL, false, ''),
    ('origin',     'string', NULL, false, '');

-- Deprecated keys (born-deprecated: future stores warn). Seed decision 2026-10-04.
INSERT INTO meta_key_registry (key, value_type, allowed_values, required, description, deprecated_at) VALUES
    ('quelle',  'string', NULL, false, 'prefer ''origin''', now()),
    ('ziel',    'string', NULL, false, 'prefer ''topic''', now()),
    ('feld',    'string', NULL, false, 'prefer ''typ''', now()),
    ('kadenz',  'string', NULL, false, 'no replacement (SEP-watch only, dormant)', now()),
    ('vorbild', 'string', NULL, false, 'prefer ''origin''', now());
