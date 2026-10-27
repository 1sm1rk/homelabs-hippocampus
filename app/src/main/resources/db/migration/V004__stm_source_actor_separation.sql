-- Source/actor separation (2026-09-28):
-- source = technical channel through which the memory arrived. From now on derived
--          server-side from the MCP clientInfo of the initialize handshake; the store
--          tool no longer accepts it as an argument (self-declared values were spoofable).
-- actor  = who authored the content: 'user' (later: name or email of a person),
--          an AI agent, or a tool. Free text on purpose, set by the caller per event.
-- Historical rows: source was client-declared and unverified, actor did not exist.

ALTER TABLE stm_event
    ADD COLUMN actor TEXT NOT NULL DEFAULT 'unknown';

UPDATE stm_event SET source = 'unknown';