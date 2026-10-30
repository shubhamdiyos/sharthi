-- V1__foundation_infrastructure.sql
--
-- The four tables the shared kernel writes, and nothing else. Merchant, order,
-- ledger and every other table arrives with its own module's migration, so this
-- baseline stays small and reviewable (System Design §4.2, §4.6, §13, §14.5).
--
-- Two rules this file exists to enforce rather than merely record:
--   * audit_event is append-only at the database level, not by convention (§13.1);
--   * job_run's closed list lives in a CHECK, so a new job is a migration and a
--     review, not a string someone typed at 2am (§4.6).

-- ─────────────────────────────────────────────────────────────────────────────
-- ULID-shaped identifiers are checked the same way Ulid.isValid() checks them:
-- 26 uppercase Crockford characters, with I, L, O and U excluded. Encoding it in
-- the database means a hand-inserted fixture row cannot drift from what the
-- application will accept.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE FUNCTION saathi_is_ulid(candidate text) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT candidate ~ '^[0-9A-HJKMNP-TV-Z]{26}$' $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- audit_event (§13) — append-only, written in the same transaction as the change
-- it describes. JdbcAuditRecorder enforces that with Propagation.MANDATORY; this
-- trigger enforces it even for a psql session.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE audit_event (
    id              char(26)      PRIMARY KEY,
    tenant_id       char(26),

    actor_type      text          NOT NULL,
    actor_id        text,

    action          text          NOT NULL,
    entity          text          NOT NULL,
    entity_id       text          NOT NULL,

    before_json     jsonb,
    after_json      jsonb,

    reason_code     text,
    request_id      text,
    ip_hash         char(64),
    occurred_at     timestamptz   NOT NULL DEFAULT now(),

    -- A correction is a new row pointing at the one it replaces. There is no
    -- other way to amend history in an append-only table (§13.1).
    reversal_of_id  char(26)      REFERENCES audit_event (id),

    business_day    date GENERATED ALWAYS AS
                      ((occurred_at AT TIME ZONE 'Asia/Kolkata')::date) STORED,

    CONSTRAINT audit_event_id_ulid        CHECK (saathi_is_ulid (id)),
    CONSTRAINT audit_event_tenant_ulid    CHECK (tenant_id IS NULL OR saathi_is_ulid (tenant_id)),

    -- actor_id is absent only for the platform's own actions; a human or a
    -- merchant account always names who acted (§13.2).
    CONSTRAINT audit_event_actor_identity CHECK (
        (actor_type = 'SYSTEM' AND actor_id IS NULL) OR
        (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL)
    ),
    CONSTRAINT audit_event_actor_type     CHECK (actor_type IN (
        'CUSTOMER', 'MERCHANT_STAFF', 'RIDER', 'OPERATOR', 'SUPER_ADMIN', 'SYSTEM'
    )),
    -- The ⚑ actions that require a reason are a property of the application's
    -- rules (§13.2), so that list is enforced in AuditEntry.REASON_CODE_REQUIRED
    -- where it can be unit-tested. What the database can say is narrower and
    -- still worth saying: a row that carries no reason must carry a cause.
    CONSTRAINT audit_event_reversal_reason CHECK (
        reversal_of_id IS NULL OR reason_code IS NOT NULL
    ),
    -- ip_hash is the only trace of a client address and it is never the address
    -- itself (§13.4). 64 hex characters or nothing.
    CONSTRAINT audit_event_ip_hash_shape  CHECK (ip_hash IS NULL OR ip_hash ~ '^[0-9a-f]{64}$'),
    -- before/after are the shape the reviewer will read; an empty object is not
    -- a snapshot of anything (§13.3).
    CONSTRAINT audit_event_snapshots_meaningful CHECK (
        before_json IS NULL OR jsonb_typeof (before_json) = 'object'
    ),
    CONSTRAINT audit_event_after_meaningful CHECK (
        after_json IS NULL OR jsonb_typeof (after_json) = 'object'
    ),
    -- The request id is the ULID the response carried, so the same value joins
    -- a user's error screen to this row (§10.1). A job writes none: it has no
    -- request.
    CONSTRAINT audit_event_request_ulid   CHECK (
        request_id IS NULL OR saathi_is_ulid (request_id)
    )
);

CREATE INDEX audit_event_tenant_time  ON audit_event (tenant_id, business_day DESC, occurred_at DESC);
CREATE INDEX audit_event_entity       ON audit_event (entity, entity_id, occurred_at DESC);
CREATE INDEX audit_event_actor        ON audit_event (actor_type, actor_id, occurred_at DESC);
CREATE INDEX audit_event_request      ON audit_event (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX audit_event_action_day   ON audit_event (action, business_day);

CREATE FUNCTION forbid_row_mutation() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'saathi: % is append-only; write a reversing row instead', TG_TABLE_NAME
        USING ERRCODE = 'SA001', HINT = TG_OP;
END;
$$;

-- Row-level for UPDATE/DELETE, statement-level for TRUNCATE: a TRUNCATE trigger
-- is the only thing that closes that door, and leaving it open would make the
-- row triggers decorative.
CREATE TRIGGER audit_event_forbid_update
    BEFORE UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_row_mutation();

CREATE TRIGGER audit_event_forbid_delete
    BEFORE DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_row_mutation();

CREATE TRIGGER audit_event_forbid_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_row_mutation();

-- ─────────────────────────────────────────────────────────────────────────────
-- idempotency_record (§10.4) — the store behind Idempotency-Key.
--
-- Primary key is composite and starts with tenant_id: a key generated by one
-- tenant's app must never be able to claim another tenant's order. That is the
-- same argument §4.2 makes for every association table, applied to the one table
-- whose whole job is "have I seen this before".
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE idempotency_record (
    tenant_id        char(26)     NOT NULL,
    idempotency_key  text         NOT NULL,
    endpoint         text         NOT NULL,

    -- SHA-256 of the canonical request body. A second call with the same key but
    -- a different body is IDEMPOTENCY_KEY_CONFLICT, not a silent second order.
    request_hash     char(64)     NOT NULL,

    -- NULL while the first request is still in flight: a concurrent retry waits
    -- or gets IDEMPOTENCY_IN_PROGRESS rather than double-submitting.
    response_status  smallint,
    response_json    jsonb,
    resource_id      text,

    created_at       timestamptz  NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    expires_at       timestamptz  NOT NULL DEFAULT now() + interval '24 hours',

    PRIMARY KEY (tenant_id, idempotency_key, endpoint),

    CONSTRAINT idempotency_tenant_ulid  CHECK (saathi_is_ulid (tenant_id)),
    -- Below 8 characters the key is a guessable integer; above 200 it is a
    -- client that has put a payload in the header.
    CONSTRAINT idempotency_key_shape    CHECK (
        char_length (idempotency_key) BETWEEN 8 AND 200
    ),
    CONSTRAINT idempotency_hash_shape   CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- An endpoint is a method and a templated path ('POST /orders'), never a
    -- query string — the query is where a client could hide a varying value and
    -- make every key look new.
    CONSTRAINT idempotency_endpoint_shape CHECK (
        endpoint ~ '^(GET|POST|PUT|PATCH|DELETE) /' AND endpoint NOT LIKE '%?%'
    ),
    CONSTRAINT idempotency_response_shape CHECK (
        (response_status IS NULL AND completed_at IS NULL) OR
        (response_status IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT idempotency_expiry_after_creation CHECK (expires_at > created_at)
);

-- The purge is a plain range scan; no job may delete live records, only expired
-- ones (§4.6's job list has no licence for more).
CREATE INDEX idempotency_expiry ON idempotency_record (expires_at) WHERE response_status IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- outbox_event (§14.5) — notifications are recorded in the writer's transaction
-- and delivered later. Nothing here sends anything: the external provider is
-- out of scope, so this table's whole value is that delivery state survives a
-- crash instead of living in a thread.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE outbox_event (
    id              char(26)      PRIMARY KEY,
    tenant_id       char(26)      NOT NULL,

    aggregate_type  text          NOT NULL,
    aggregate_id    text          NOT NULL,
    event_type      text          NOT NULL,
    payload         jsonb         NOT NULL,

    occurred_at     timestamptz   NOT NULL DEFAULT now(),
    -- Backoff for retries; the drainer takes only rows it is allowed to look at.
    available_at    timestamptz   NOT NULL DEFAULT now(),
    dispatched_at   timestamptz,
    attempt_count   integer       NOT NULL DEFAULT 0,
    last_error_code text,

    CONSTRAINT outbox_id_ulid          CHECK (saathi_is_ulid (id)),
    CONSTRAINT outbox_tenant_ulid      CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT outbox_payload_object   CHECK (jsonb_typeof (payload) = 'object'),
    CONSTRAINT outbox_attempts_non_negative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_dispatched_after_event CHECK (
        dispatched_at IS NULL OR dispatched_at >= occurred_at
    ),
    -- An un-dispatched row is never older than its own retry gate.
    CONSTRAINT outbox_available_after_event CHECK (available_at >= occurred_at),
    -- Recorded failure without a claim of delivery, and vice versa.
    CONSTRAINT outbox_error_only_when_attempted CHECK (
        last_error_code IS NULL OR attempt_count > 0
    )
);

-- The drainer's query is "undispatched, due, oldest first per tenant"; a partial
-- index keeps it that narrow as the dispatched history grows.
CREATE INDEX outbox_pending ON outbox_event (tenant_id, available_at)
    WHERE dispatched_at IS NULL;
CREATE INDEX outbox_aggregate ON outbox_event (aggregate_type, aggregate_id, occurred_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- job_run (§4.6) — one row per mutating job per tenant per business day.
--
-- run_key is unique per (job_key, tenant_id, business_day) so a re-run cannot
-- double-post a ledger entry: the Idempotency-Key discipline applied to money.
-- job_key is a CHECK constraint because §4.6's list is closed by decision —
-- auto-reject, offer expiry, auto-cancel and auto-suspend do not exist here.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE job_run (
    id              char(26)      PRIMARY KEY,
    job_key         text          NOT NULL,
    tenant_id       char(26)      NOT NULL,
    run_key         text          NOT NULL,
    business_day    date          NOT NULL,

    started_at      timestamptz   NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    heartbeat_at    timestamptz,
    rows_affected   bigint        NOT NULL DEFAULT 0,
    error           text,

    CONSTRAINT job_run_id_ulid        CHECK (saathi_is_ulid (id)),
    CONSTRAINT job_run_tenant_ulid    CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT job_run_key_closed_list CHECK (job_key IN (
        'outbox_drain',
        'statement_materialisation',
        'nightly_reconciliation_assertion',
        'exception_scan',
        'backup_verification'
    )),
    CONSTRAINT job_run_rows_non_negative CHECK (rows_affected >= 0),
    CONSTRAINT job_run_finished_window CHECK (
        finished_at IS NULL OR (finished_at >= started_at AND heartbeat_at IS NULL)
    ),
    -- A job that reports an error has run out of attempts or failed; a job still
    -- running has neither.
    CONSTRAINT job_run_error_means_over CHECK (error IS NULL OR finished_at IS NOT NULL),
    -- §4.6's uniqueness, expressed as a constraint and not as a query that
    -- checks first: two processes must not both get to "check then insert".
    CONSTRAINT job_run_one_per_day UNIQUE (job_key, tenant_id, business_day),
    CONSTRAINT job_run_run_key_shape CHECK (char_length (run_key) BETWEEN 1 AND 128)
);

CREATE INDEX job_run_day ON job_run (business_day, job_key);
CREATE INDEX job_run_running ON job_run (started_at) WHERE finished_at IS NULL;

COMMENT ON TABLE audit_event IS
    'Append-only (§13). UPDATE, DELETE and TRUNCATE are rejected by trigger.';
COMMENT ON COLUMN job_run.job_key IS
    'Closed list per §4.6. Adding a value here is a design change, not a code change.';
COMMENT ON COLUMN audit_event.ip_hash IS
    'HMAC-SHA256(business_day || "" || ip, daily key). The address itself is never stored (§13.4).';
