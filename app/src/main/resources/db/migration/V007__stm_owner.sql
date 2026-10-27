-- Owner column (V007, AP-7): user identity from the OAuth 2.1 access token.
-- The authenticated principal (JWT `sub`, the Keycloak user id) is written on
-- every store — WRITE SIDE ONLY. Read-side scoping (find/list filtering) stays
-- parked until the ADR-0004 trigger (second user / beyond-LAN); no index yet,
-- nothing queries this column.
-- Backfill: existing rows default to 'unknown'. The single user's sub is the
-- user id seeded in keycloak/import/hippocampus-realm.json
-- (2a4e8c1e-9b3d-4f5a-8c6e-1d2b3a4c5d6e). One-time manual step (README):
--   UPDATE stm_event SET owner = '<sub>' WHERE owner = 'unknown';
-- (not a migration: the sub is an AS specific and must not live in service code).

ALTER TABLE stm_event
    ADD COLUMN owner TEXT NOT NULL DEFAULT 'unknown';
