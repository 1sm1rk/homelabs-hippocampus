-- Mechanized supersedes (V006, AP-3 session 2, owner decision #42):
-- update writes the corrected event as a new row and marks the old row superseded.
-- Append-only (ADR 0002): no content mutation, no delete. Reactivation = re-store
-- the old content as a new event (the #34 forgetting model: hard to find, never lost).
-- find/list exclude superseded events by default (superseded_by IS NOT NULL).

ALTER TABLE stm_event
    ADD COLUMN superseded_by BIGINT      NULL REFERENCES stm_event (id),
    ADD COLUMN superseded_at TIMESTAMPTZ NULL;

CREATE INDEX idx_stm_event_superseded_by ON stm_event (superseded_by);
