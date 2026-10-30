-- ─────────────────────────────────────────────────────────────────────────────
-- §10.6.1 replays "the exact bytes of the original 201". jsonb cannot carry
-- that promise: Postgres normalises a jsonb value — key order and whitespace
-- both change on write — so a replay read back as jsonb is a re-render, not a
-- replay. The stored response therefore has to be text, opaque to the database.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE idempotency_record
    ALTER COLUMN response_json TYPE text;

COMMENT ON COLUMN idempotency_record.response_json IS
    'The response body exactly as it was sent. text, not jsonb: a jsonb round trip re-orders keys and re-prints whitespace.';
