-- V2__identity_and_tenancy.sql
--
-- The two modules everything else stands on: §5.1 (principals, credentials, roles, grants,
-- sessions, OTP) and §5.2 (cities, wards, the global category taxonomy, per-tenant
-- activation, ward fees, flags).
--
-- Every scoped table carries its §4.2 composite keys in the migration that creates it,
-- because retrofitting them means rewriting every child too — §20.3 names that as the cost
-- the baseline exists to avoid paying twice.
--
-- Four rules this file enforces rather than records:
--   * cross-tenant identifier mixing is a database error, not an application convention: a
--     child that points at another tenant's parent cannot be inserted at all;
--   * `otp_challenge` stores no plaintext code and no plaintext phone number (§12.8, §14);
--   * a tenantless grant is legal only for a platform role, and that is a trigger because
--     a CHECK constraint may not read another table;
--   * every scoped table is RLS-ready (§4.5): ENABLE plus one shared permissive policy and
--     no FORCE. Both are inert while the application role owns the schema, which is exactly
--     why turning FORCE on must also move the role to non-owner. That pair is the tenant-#2
--     gate, so the policies go in now rather than under pressure later.
--
-- Table order is not alphabetical: `user_account` precedes `tenant_category` because the
-- activation audit column references it.
--
-- Deliberate departures from §5.1/§5.2, each flagged for the founder rather than smuggled in:
--   * A composite foreign key to `tenant (tenant_id, id)` is not written, because it cannot
--     be: the pair would have to exist in `tenant`, where `tenant_id` is generated from `id`,
--     so the only row satisfying it is the tenant itself. The first draft of this file wrote
--     that FK on `service_ward`, `tenant_category` and `ward_fee_rule` and then could not
--     insert a single ward (measured: "Key (tenant_id, id)=(…siwan…, …ward01…) is not present
--     in table \"tenant\""). §4.2's composite discipline belongs on the *child-to-parent*
--     links that carry two real identities — `ward_fee_rule (tenant_id, ward_id)` into
--     `service_ward (tenant_id, id)` — and each row's tenant is pinned by a plain
--     `FOREIGN KEY (tenant_id) REFERENCES tenant (id)`. Same guarantee, and it is insertable.
--   * `user_account.kind` gains `RIDER`. §5.1 lists four kinds; §12.1 says rider accounts
--     are `kind = RIDER`. §12.1 wins — a rider is not a customer.
--   * `step_up_grant.used_at` — §12.6's "single-use per action" needs a row that records the
--     using, and §5.1 does not carry one.
--   * `otp_challenge` is deliberately global. It is written before a principal is known, so
--     the alternative is an unscoped row; §4.1's scoped list does not name it either.

-- ─────────────────────────────────────────────────────────────────────────────
-- Principals (§5.1)
--
-- user_account is global by decision (§4.1): the same person can order in Siwan and later
-- in Mashaun without duplicating identity, and a person holding two roles has one account
-- with two grants (§12.1) — otherwise order_event.actor_id stops naming a person (PRD §13.9).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE user_account (
    id                      char(26)      PRIMARY KEY,
    kind                    text          NOT NULL,
    phone                   text,
    email                   text,
    display_name            text,
    state                   text          NOT NULL DEFAULT 'ACTIVE',

    password_hash           text,
    failed_auth_count       integer       NOT NULL DEFAULT 0,
    locked_until            timestamptz,

    terms_version_accepted  text,
    terms_accepted_at       timestamptz,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    last_seen_at            timestamptz,

    CONSTRAINT user_account_id_ulid       CHECK (saathi_is_ulid (id)),
    CONSTRAINT user_account_kind          CHECK (kind IN (
        'CUSTOMER', 'RIDER', 'MERCHANT_STAFF', 'OPERATOR', 'SUPER_ADMIN'
    )),
    CONSTRAINT user_account_state         CHECK (state IN ('ACTIVE', 'LOCKED', 'DEACTIVATED')),
    -- E.164, normalised on the way in by PhoneNormaliser. Without this the column accepts
    -- "9800003421", "09800003421" and "+91 98000 03421" as three different people.
    CONSTRAINT user_account_phone_e164    CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT user_account_email_lower   CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT user_account_email_shape   CHECK (
        email IS NULL OR email ~ '^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$'
    ),
    -- §12.1: customers and riders authenticate with a phone and never hold a password;
    -- staff authenticate with an email and always carry a recovery phone (§12.7). `kind` is
    -- a guard, not a label (§12.2).
    CONSTRAINT user_account_credential_shape CHECK (
        (kind IN ('CUSTOMER', 'RIDER') AND phone IS NOT NULL AND password_hash IS NULL)
        OR
        (kind IN ('MERCHANT_STAFF', 'OPERATOR', 'SUPER_ADMIN') AND email IS NOT NULL
            AND phone IS NOT NULL)
    ),
    -- The Argon2id encoded form, so a bcrypt string or a plaintext password cannot be
    -- committed by accident and still "look like a password".
    CONSTRAINT user_account_argon2_shape  CHECK (
        password_hash IS NULL OR password_hash LIKE '$argon2id$v=%'
    ),
    -- §12.8: six consecutive failures lock the account; the column never needs to count
    -- past the threshold, and a runaway counter is a bug worth naming.
    CONSTRAINT user_account_failed_count  CHECK (failed_auth_count BETWEEN 0 AND 20),
    -- A LOCKED row has a moment when it stops being locked. A silent permanent lock is the
    -- behaviour §12.8 explicitly refuses.
    CONSTRAINT user_account_lock_has_until CHECK (
        (state = 'LOCKED' AND locked_until IS NOT NULL) OR state <> 'LOCKED'
    ),
    CONSTRAINT user_account_terms_pair    CHECK (
        (terms_version_accepted IS NULL AND terms_accepted_at IS NULL)
        OR (terms_version_accepted IS NOT NULL AND terms_accepted_at IS NOT NULL)
    )
);

-- Login looks a customer up by phone and a staffer by email, and each must be unique where
-- present. Partial indexes because a NULL means "not supplied", not "another empty value".
CREATE UNIQUE INDEX user_account_phone_unique ON user_account (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX user_account_email_unique ON user_account (email) WHERE email IS NOT NULL;
CREATE INDEX user_account_directory ON user_account (state, kind, created_at);

COMMENT ON COLUMN user_account.password_hash IS
    'Argon2id (§12.2). Never selected into a response, never logged, never in a fixture.';

CREATE TABLE role (
    id          char(26)      PRIMARY KEY,
    key         text          NOT NULL UNIQUE,
    description text          NOT NULL,

    CONSTRAINT role_id_ulid       CHECK (saathi_is_ulid (id)),
    CONSTRAINT role_key_shape     CHECK (key ~ '^[A-Z][A-Z0-9_]{2,63}$')
);

CREATE TABLE permission (
    id          char(26)      PRIMARY KEY,
    key         text          NOT NULL UNIQUE,
    description text          NOT NULL,

    CONSTRAINT permission_id_ulid     CHECK (saathi_is_ulid (id)),
    CONSTRAINT permission_key_shape   CHECK (key ~ '^[A-Z][A-Z0-9_]{2,63}$')
);

CREATE TABLE role_permission (
    role_id         char(26)      NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    permission_id   char(26)      NOT NULL REFERENCES permission (id) ON DELETE CASCADE,

    PRIMARY KEY (role_id, permission_id)
);

-- A principal can hold a permission exactly once through one role; the reverse index is what
-- makes "who may approve a merchant?" a cheap question for /a/overview screens.
CREATE INDEX role_permission_permission ON role_permission (permission_id, role_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Tenancy and geography (§5.2)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE tenant (
    id                      char(26)      PRIMARY KEY,

    code                    text          NOT NULL UNIQUE,
    name                    text          NOT NULL,
    state_code              text,
    timezone                text          NOT NULL DEFAULT 'Asia/Kolkata',
    currency                char(3)       NOT NULL DEFAULT 'INR',
    contact_phone           text,

    status                  text          NOT NULL DEFAULT 'DRAFT',
    default_commission_bp   integer       NOT NULL DEFAULT 0,
    soft_cash_limit_paise   bigint        NOT NULL,
    hard_cash_limit_paise   bigint        NOT NULL,
    stock_policy            text          NOT NULL,
    weight_tolerance_bp     integer,
    cart_ttl_minutes        integer       NOT NULL DEFAULT 30,
    created_at              timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT tenant_id_ulid             CHECK (saathi_is_ulid (id)),
    CONSTRAINT tenant_status              CHECK (status IN ('LIVE', 'DRAFT', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT tenant_commission_bp       CHECK (default_commission_bp BETWEEN 0 AND 10000),
    CONSTRAINT tenant_weight_tolerance    CHECK (
        weight_tolerance_bp IS NULL OR weight_tolerance_bp BETWEEN 0 AND 10000
    ),
    -- One currency, asserted rather than assumed: §9's ledger arithmetic is integer paise and
    -- a second currency would be a design change, not a column edit.
    CONSTRAINT tenant_currency            CHECK (currency = 'INR'),
    CONSTRAINT tenant_timezone_shape      CHECK (timezone ~ '^[A-Za-z]+/[A-Za-z_]+$'),
    CONSTRAINT tenant_phone_e164          CHECK (contact_phone IS NULL OR contact_phone ~ '^\+[1-9][0-9]{7,14}$'),
    -- §9.3's caps are a soft warning under a hard refusal; the opposite order makes the soft
    -- cap unreachable, which is not a configuration anyone intends.
    CONSTRAINT tenant_cash_limit_order    CHECK (hard_cash_limit_paise >= soft_cash_limit_paise),
    CONSTRAINT tenant_cash_limits_non_negative CHECK (
        soft_cash_limit_paise >= 0 AND hard_cash_limit_paise >= 0
    ),
    CONSTRAINT tenant_stock_policy        CHECK (stock_policy IN (
        'FLAG_ONLY', 'RESERVE_ON_PLACEMENT', 'RESERVE_ON_ACCEPT'
    )),
    -- A cart that lives five minutes is a UI trap; one that lives a week is a stock lie.
    CONSTRAINT tenant_cart_ttl            CHECK (cart_ttl_minutes BETWEEN 5 AND 1440)
);

CREATE INDEX tenant_status_idx ON tenant (status, code);

COMMENT ON COLUMN tenant.default_commission_bp IS
    'OD-7 is open: no rate is approved, so the reference data seeds 0. Writing a non-zero '
    'rate is a production act requiring step-up and an approval_ref (§10.6.9, RATE_NOT_APPROVED).';

CREATE TABLE service_ward (
    id          char(26)      PRIMARY KEY,
    tenant_id   char(26)      NOT NULL,

    ward_no     integer       NOT NULL,
    name        text          NOT NULL,
    active      boolean       NOT NULL DEFAULT true,
    sort        integer       NOT NULL DEFAULT 0,
    note        text,
    launched_at timestamptz,

    CONSTRAINT service_ward_id_ulid       CHECK (saathi_is_ulid (id)),
    CONSTRAINT service_ward_tenant_ulid   CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT service_ward_no_positive   CHECK (ward_no > 0),
    CONSTRAINT service_ward_name_shape    CHECK (char_length (name) BETWEEN 1 AND 120),

    -- The pair children reference. ward_fee_rule's (tenant_id, ward_id) can only be checked
    -- against service_ward (tenant_id, id) if that pair is unique here — which is the whole
    -- of §4.2's isolation guarantee expressed as a key: a rule may not name a ward that
    -- belongs to another city. A scoped table carries this pair only when a child needs it;
    -- tenant, feature_flag and the rest are referenced by id alone and do not.
    CONSTRAINT service_ward_tenant_uk     UNIQUE (tenant_id, id),
    -- Ward numbers are unique inside a city and mean nothing across cities: "Ward 12" in
    -- Siwan is a different place from "Ward 12" in Mashaun, and the pair is what §4.2's
    -- composite keys express.
    CONSTRAINT service_ward_one_number    UNIQUE (tenant_id, ward_no),
    CONSTRAINT service_ward_tenant_fk     FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

-- The storefront's hot path is "active wards of this city, in screen order".
CREATE INDEX service_ward_lookup ON service_ward (tenant_id, active, sort, ward_no);

-- The taxonomy is global; activation is per tenant (§4.1). Nothing here assumes four
-- categories, because nothing in code may.
CREATE TABLE category (
    id                char(26)      PRIMARY KEY,
    slug              text          NOT NULL UNIQUE,
    name              text          NOT NULL,
    parent_id         char(26)      REFERENCES category (id),
    requires_licence  boolean       NOT NULL DEFAULT false,
    active            boolean       NOT NULL DEFAULT true,
    sort              integer       NOT NULL DEFAULT 0,

    CONSTRAINT category_id_ulid         CHECK (saathi_is_ulid (id)),
    CONSTRAINT category_slug_shape      CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT category_name_shape      CHECK (char_length (name) BETWEEN 1 AND 120),
    -- A node is not its own parent. A full cycle check needs recursion; the taxonomy's
    -- depth is two, so this is the cheap half of the guard and §4.1's retirement rule
    -- (CATEGORY_IN_USE) is the rest.
    CONSTRAINT category_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX category_parent ON category (parent_id, sort);

CREATE TABLE tenant_category (
    id                char(26)      PRIMARY KEY,
    tenant_id         char(26)      NOT NULL,
    category_id       char(26)      NOT NULL,

    active            boolean       NOT NULL DEFAULT true,
    -- "Kirana" in Siwan, "General Store" in Mashaun, one taxonomy node (§4.1): display text,
    -- never a code fork.
    display_name      text,
    sort_override     integer,
    min_licence_state text,
    activated_by      char(26),
    activated_at      timestamptz,

    CONSTRAINT tenant_category_id_ulid       CHECK (saathi_is_ulid (id)),
    CONSTRAINT tenant_category_tenant_ulid   CHECK (saathi_is_ulid (tenant_id)),
    -- An activation names who did it and when. A row that claims active with no actor is a
    -- configuration nobody signed for.
    CONSTRAINT tenant_category_active_shape  CHECK (
        (active AND activated_by IS NOT NULL AND activated_at IS NOT NULL) OR NOT active
    ),
    CONSTRAINT tenant_category_licence_state CHECK (min_licence_state IS NULL OR min_licence_state IN (
        'NOT_SUBMITTED', 'SUBMITTED', 'VALID', 'INVALID'
    )),

    CONSTRAINT tenant_category_one_per_node  UNIQUE (tenant_id, category_id),
    -- One tenant's activation of one global node: the pair that makes "this city turned on
    -- this category" unrepeatable, and the reason no (tenant_id, id) unique is declared here
    -- — nothing references this table's rows by pair, so §4.2's key stops at (tenant_id,
    -- category_id).
    CONSTRAINT tenant_category_tenant_fk     FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    -- The taxonomy is global, so this reference stays a plain FK (§4.2).
    CONSTRAINT tenant_category_category_fk   FOREIGN KEY (category_id)
        REFERENCES category (id),
    CONSTRAINT tenant_category_activated_by_fk FOREIGN KEY (activated_by)
        REFERENCES user_account (id)
);

-- One fee rule per ward per fulfilment mode (OD-2): on a merchant-delivered order the
-- platform pays no rider and the drawn screens keep the ₹25 with the shop, so "one number
-- per ward" would have been the wrong shape from the first day.
CREATE TABLE ward_fee_rule (
    id                  char(26)      PRIMARY KEY,
    tenant_id           char(26)      NOT NULL,
    ward_id             char(26)      NOT NULL,

    fulfilment_mode     text          NOT NULL,
    delivery_fee_paise  bigint        NOT NULL,
    handling_fee_paise  bigint        NOT NULL,
    min_order_paise     bigint        NOT NULL DEFAULT 0,
    active              boolean       NOT NULL DEFAULT true,
    valid_from          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ward_fee_rule_id_ulid        CHECK (saathi_is_ulid (id)),
    CONSTRAINT ward_fee_rule_tenant_ulid    CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT ward_fee_rule_mode           CHECK (fulfilment_mode IN ('PLATFORM', 'MERCHANT')),
    -- §9's money axis is integer paise, and a negative fee is a ledger bug dressed up as a
    -- discount.
    CONSTRAINT ward_fee_rule_amounts_non_negative CHECK (
        delivery_fee_paise >= 0 AND handling_fee_paise >= 0 AND min_order_paise >= 0
    ),

    -- A live rate is never rewritten in place: a new row with a later valid_from replaces it
    -- (§10.5.2). Two active rows effective at the same instant for one ward-mode is a
    -- quotation that cannot be reproduced, which is what §9.7's identity tests need.
    CONSTRAINT ward_fee_rule_one_effective  UNIQUE (tenant_id, ward_id, fulfilment_mode, valid_from),
    -- The only parent link this table needs. It is composite, so it pins the ward *and* the
    -- city the ward belongs to together — a rule for Siwan's Ward 1 cannot name Mashaun's
    -- tenant id, and because the ward row it points at already has a checked tenant, there
    -- is no separate FOREIGN KEY (tenant_id) REFERENCES tenant to state.
    CONSTRAINT ward_fee_rule_ward_fk        FOREIGN KEY (tenant_id, ward_id)
        REFERENCES service_ward (tenant_id, id)
);

CREATE INDEX ward_fee_rule_current
    ON ward_fee_rule (tenant_id, ward_id, fulfilment_mode, valid_from DESC)
    WHERE active;

-- Feature flags (§5.2): the PRD §12 kill switch. A NULL tenant is the platform default and
-- a tenant row overrides it.
CREATE TABLE feature_flag (
    id          char(26)      PRIMARY KEY,
    key         text          NOT NULL,
    tenant_id   char(26)      REFERENCES tenant (id),
    enabled     boolean       NOT NULL,
    -- A flag that depends on another is refused at write time (FLAG_DEPENDENCY) rather than
    -- left to produce a half-enabled capability.
    depends_on  text,
    description text          NOT NULL,

    CONSTRAINT feature_flag_id_ulid     CHECK (saathi_is_ulid (id)),
    CONSTRAINT feature_flag_key_shape   CHECK (key ~ '^[a-z][a-z0-9_]{1,62}$'),
    CONSTRAINT feature_flag_not_self_dependency CHECK (depends_on IS NULL OR depends_on <> key),
    -- NULLS NOT DISTINCT (PostgreSQL 15+) is what makes "one global row per key" a real
    -- constraint: plain UNIQUE treats two NULL tenants as different rows, which is exactly
    -- the second global default someone will insert by accident.
    CONSTRAINT feature_flag_one_per_scope UNIQUE NULLS NOT DISTINCT (key, tenant_id)
);

CREATE INDEX feature_flag_key_lookup ON feature_flag (key);

-- ─────────────────────────────────────────────────────────────────────────────
-- Grants (§5.1, §11.3)
--
-- A grant is (principal, role, scope). §11.3's four checks read this table on every
-- request, which is why revoking a grant ends data access at the next request rather than
-- at the next login (§12.5).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE user_role_grant (
    id                  char(26)      PRIMARY KEY,
    user_account_id     char(26)      NOT NULL,
    role_id             char(26)      NOT NULL,
    -- Nullable because a platform role has no tenant: §5.1 marks the column optional while
    -- §4.1 lists the table as scoped. The trigger below closes the gap a CHECK cannot —
    -- "is this role a platform role" reads another table.
    tenant_id           char(26),
    merchant_id         char(26),
    ward_id             char(26),

    granted_by          char(26)      NOT NULL,
    granted_at          timestamptz   NOT NULL DEFAULT now(),
    revoked_by          char(26),
    revoked_at          timestamptz,
    reason              text          NOT NULL,

    CONSTRAINT grant_id_ulid          CHECK (saathi_is_ulid (id)),
    CONSTRAINT grant_user_ulid        CHECK (saathi_is_ulid (user_account_id)),
    CONSTRAINT grant_tenant_ulid      CHECK (tenant_id IS NULL OR saathi_is_ulid (tenant_id)),
    CONSTRAINT grant_ward_ulid        CHECK (ward_id IS NULL OR saathi_is_ulid (ward_id)),
    CONSTRAINT grant_merchant_ulid    CHECK (merchant_id IS NULL OR saathi_is_ulid (merchant_id)),
    -- §4.4 and §13.2: a grant row without a reason is a permission nobody can explain later.
    CONSTRAINT grant_reason_mandatory CHECK (char_length (reason) BETWEEN 3 AND 500),
    CONSTRAINT grant_revocation_pair  CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoked_at >= granted_at)
    ),

    CONSTRAINT grant_user_fk          FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT grant_role_fk          FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT grant_tenant_fk        FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    -- No MATCH clause, which is what makes this a MATCH SIMPLE foreign key. Postgres
    -- wants a wardless grant to be legal: with MATCH FULL a NULL ward_id beside a
    -- non-NULL tenant_id would have to find a parent row, and every tenant-scoped
    -- grant would fail. Naming the default is not an option — the parser rejects
    -- `MATCH SIMPLE` outright (measured: "syntax error at or near \"MATCH\""), so
    -- omitting it is the only way to ask for this behaviour.
    CONSTRAINT grant_ward_fk          FOREIGN KEY (tenant_id, ward_id)
        REFERENCES service_ward (tenant_id, id),
    CONSTRAINT grant_granted_by_fk    FOREIGN KEY (granted_by) REFERENCES user_account (id),
    CONSTRAINT grant_revoked_by_fk    FOREIGN KEY (revoked_by) REFERENCES user_account (id)
    -- merchant_id's composite FK to merchant (tenant_id, id) arrives with the merchant
    -- module. Saying so here is better than a bare column that looks enforced.
);

CREATE INDEX grant_principal ON user_role_grant (user_account_id) WHERE revoked_at IS NULL;
CREATE INDEX grant_tenant_scope ON user_role_grant (tenant_id, role_id) WHERE revoked_at IS NULL;

-- The platform roles, in one place: a tenantless grant is legal for exactly these and for
-- nothing else.
CREATE FUNCTION saathi_platform_role_keys() RETURNS text[]
LANGUAGE sql IMMUTABLE AS
$$ SELECT ARRAY['SUPER_ADMIN'] $$;

CREATE FUNCTION forbid_bad_platform_grant() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.tenant_id IS NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM role r
             WHERE r.id = NEW.role_id
               AND r.key = ANY (saathi_platform_role_keys())
        ) THEN
            RAISE EXCEPTION
                'saathi: only a platform role may be granted without a tenant (role %)',
                NEW.role_id
                USING ERRCODE = 'SA002';
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM role r
         WHERE r.id = NEW.role_id
           AND r.key = ANY (saathi_platform_role_keys())
    ) THEN
        RAISE EXCEPTION
            'saathi: a platform grant carries no tenant_id (role %, tenant %)',
            NEW.role_id, NEW.tenant_id
            USING ERRCODE = 'SA002';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER user_role_grant_platform_shape
    BEFORE INSERT OR UPDATE ON user_role_grant
    FOR EACH ROW EXECUTE FUNCTION forbid_bad_platform_grant();

-- ─────────────────────────────────────────────────────────────────────────────
-- Sessions (§12.4)
--
-- The row is authoritative and expiry is evaluated on read. There is no session sweeper,
-- because §6.5 removed timers from the product and a reaper would be one of them — the
-- cookie of an expired session fails the same way a revoked one does.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE user_session (
    id                  char(26)      PRIMARY KEY,
    user_account_id     char(26)      NOT NULL,
    principal_kind      text          NOT NULL,
    tenant_scope        char(26),
    merchant_scope      char(26),
    -- The storefront resolves a tenant from the customer's selected ward (§10.1), so the
    -- ward has to be on the session the way the tenant is, or every cart request would
    -- accept a ward from the client.
    ward_scope          char(26),

    created_at          timestamptz   NOT NULL DEFAULT now(),
    last_seen_at        timestamptz   NOT NULL DEFAULT now(),
    idle_expires_at     timestamptz,
    absolute_expires_at timestamptz   NOT NULL,
    revoked_at          timestamptz,
    revoke_reason       text,

    device_label        text,
    ip_hash             char(64),
    user_agent_hash     char(64),

    CONSTRAINT session_id_ulid            CHECK (saathi_is_ulid (id)),
    CONSTRAINT session_user_ulid          CHECK (saathi_is_ulid (user_account_id)),
    CONSTRAINT session_merchant_ulid      CHECK (merchant_scope IS NULL OR saathi_is_ulid (merchant_scope)),
    CONSTRAINT session_principal_kind     CHECK (principal_kind IN (
        'CUSTOMER', 'RIDER', 'MERCHANT_STAFF', 'OPERATOR', 'SUPER_ADMIN'
    )),
    -- A SYSTEM actor has no session: jobs are not devices, and letting them look like
    -- principals would put a fake human in GET /auth/sessions (§12.5).
    CONSTRAINT session_expiry_ordered     CHECK (
        absolute_expires_at > created_at
        AND (idle_expires_at IS NULL
             OR (idle_expires_at > created_at AND idle_expires_at <= absolute_expires_at))
    ),
    -- §12.5: every revocation carries a reason the affected principal can be shown.
    CONSTRAINT session_revocation_reason  CHECK (
        revoked_at IS NULL OR (revoke_reason IS NOT NULL AND char_length (revoke_reason) >= 3)
    ),
    CONSTRAINT session_ip_hash_shape      CHECK (ip_hash IS NULL OR ip_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT session_agent_hash_shape   CHECK (
        user_agent_hash IS NULL OR user_agent_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT session_last_seen_pair     CHECK (last_seen_at >= created_at),

    CONSTRAINT session_account_fk         FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT session_tenant_fk          FOREIGN KEY (tenant_scope) REFERENCES tenant (id),
    -- A session bound to a ward is bound to that ward's tenant, or a customer's stored ward
    -- could be used to reach another city's storefront. Most sessions bind no ward at all,
    -- so this is the same clause-less (MATCH SIMPLE) form as grant_ward_fk above.
    CONSTRAINT session_ward_fk            FOREIGN KEY (tenant_scope, ward_scope)
        REFERENCES service_ward (tenant_id, id)
    -- merchant_scope's composite FK arrives with the merchant module, alongside the grant's.
);

CREATE INDEX session_account_live ON user_session (user_account_id, absolute_expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE user_session IS
    'The row, not the cookie, decides access (§12.4). The credential is the 128-bit id plus '
    'an HMAC of it under a subkey of saathi.session-secret; nothing secret is stored here.';

-- ─────────────────────────────────────────────────────────────────────────────
-- OTP challenges (§12.8)
--
-- Six digits, single-use, five minutes, at most five verify attempts, bound to a purpose so
-- a login code cannot be replayed as a phone-change code, stored hashed, never logged.
-- Global by nature: it is written before any principal is known.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE otp_challenge (
    id              char(26)      PRIMARY KEY,
    purpose         text          NOT NULL,
    -- HMAC-SHA256 of the normalised E.164 number under a purpose-scoped subkey. A plain
    -- SHA-256 of an Indian mobile number is brute-forceable in minutes; the column is both
    -- the lookup key and the throttle key, so it must be deterministic and must not be the
    -- number itself.
    target_hash     char(64)      NOT NULL,
    code_hash       char(64)      NOT NULL,
    state           text          NOT NULL DEFAULT 'OPEN',
    attempts        integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    consumed_at     timestamptz,
    expires_at      timestamptz   NOT NULL,
    -- The X-Request-Id that asked for the code: the join from a support conversation to this
    -- row without ever searching by phone number.
    request_id      char(26)      NOT NULL,

    CONSTRAINT otp_id_ulid                CHECK (saathi_is_ulid (id)),
    CONSTRAINT otp_request_ulid           CHECK (saathi_is_ulid (request_id)),
    CONSTRAINT otp_purpose                CHECK (purpose IN (
        'LOGIN', 'REGISTRATION', 'STEP_UP', 'PHONE_CHANGE', 'PASSWORD_RESET'
    )),
    CONSTRAINT otp_state                  CHECK (state IN ('OPEN', 'CONSUMED', 'EXPIRED', 'FAILED')),
    CONSTRAINT otp_hash_shape             CHECK (
        target_hash ~ '^[0-9a-f]{64}$' AND code_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT otp_attempts_bounded       CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT otp_expiry_after_creation  CHECK (expires_at > created_at),
    CONSTRAINT otp_consumed_is_consumed   CHECK (
        state <> 'CONSUMED' OR (consumed_at IS NOT NULL AND consumed_at >= created_at)
    )
);

-- The throttle counts rows per phone per hour, and the per-hour budget is the reason the
-- full history is kept rather than overwritten.
CREATE INDEX otp_target_recent ON otp_challenge (target_hash, created_at);

-- Only one OPEN challenge per phone per purpose. The predicate matters: a UNIQUE over all
-- rows would let a phone ever have one code in its lifetime and would turn every second
-- login into a constraint violation instead of a new challenge.
CREATE UNIQUE INDEX otp_one_open_per_target
    ON otp_challenge (target_hash, purpose)
    WHERE state = 'OPEN';

COMMENT ON COLUMN otp_challenge.code_hash IS
    'HMAC-SHA256 of the six digits under the OTP subkey. The code is never persisted, never '
    'returned in an error body and never logged (§12.8, §14).';

-- ─────────────────────────────────────────────────────────────────────────────
-- Invitations, step-up, recovery (§5.1)
--
-- Their endpoints arrive with the operator and admin modules; the tables are here because
-- a scoped table's composite keys belong in the migration that creates it, and because a
-- grant model that grows into a second module afterwards is how v0.1 wrote its tenancy
-- story wrong.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE invitation (
    id              char(26)      PRIMARY KEY,
    invited_by      char(26)      NOT NULL,
    email           text          NOT NULL,
    phone           text,
    role_id         char(26)      NOT NULL,
    tenant_id       char(26),
    merchant_id     char(26),
    -- 32 random bytes, stored hashed only (§12.3). The token travels in a request body and
    -- never in a URL, so it cannot reach an access log, a referrer header or history.
    token_hash      char(64)      NOT NULL UNIQUE,
    state           text          NOT NULL DEFAULT 'PENDING',
    created_at      timestamptz   NOT NULL DEFAULT now(),
    expires_at      timestamptz   NOT NULL,
    accepted_at     timestamptz,

    CONSTRAINT invitation_id_ulid         CHECK (saathi_is_ulid (id)),
    CONSTRAINT invitation_state           CHECK (state IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT invitation_email_lower     CHECK (email = lower(email)),
    CONSTRAINT invitation_phone_e164      CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT invitation_hash_shape      CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT invitation_merchant_ulid   CHECK (merchant_id IS NULL OR saathi_is_ulid (merchant_id)),
    -- §12.3's seven-day validity, stated as an upper bound so a thirty-day invitation is a
    -- migration and a review rather than a number typed into a screen.
    CONSTRAINT invitation_seven_day_life  CHECK (
        expires_at > created_at AND expires_at <= created_at + interval '7 days'
    ),
    CONSTRAINT invitation_accepted_pair   CHECK (
        (state = 'ACCEPTED' AND accepted_at IS NOT NULL) OR state <> 'ACCEPTED'
    ),

    CONSTRAINT invitation_inviter_fk      FOREIGN KEY (invited_by) REFERENCES user_account (id),
    CONSTRAINT invitation_role_fk         FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT invitation_tenant_fk       FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE INDEX invitation_pending ON invitation (state, expires_at) WHERE state = 'PENDING';

CREATE TABLE step_up_grant (
    id                  char(26)      PRIMARY KEY,
    user_session_id     char(26)      NOT NULL,
    permission_key      text          NOT NULL,
    reason              text          NOT NULL,
    granted_at          timestamptz   NOT NULL DEFAULT now(),
    expires_at          timestamptz   NOT NULL,
    -- Added to §5.1: "single-use per action" (§12.6) needs a row that records the using.
    used_at             timestamptz,

    CONSTRAINT step_up_id_ulid          CHECK (saathi_is_ulid (id)),
    CONSTRAINT step_up_permission_shape CHECK (permission_key ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT step_up_reason_mandatory CHECK (char_length (reason) BETWEEN 3 AND 500),
    -- §12.6: a grant is valid for at most ten minutes, and the database enforces the window
    -- rather than trusting the caller's arithmetic.
    CONSTRAINT step_up_ten_minutes      CHECK (
        expires_at > granted_at AND expires_at <= granted_at + interval '10 minutes'
    ),
    CONSTRAINT step_up_single_use       CHECK (used_at IS NULL OR used_at >= granted_at),
    CONSTRAINT step_up_session_fk       FOREIGN KEY (user_session_id) REFERENCES user_session (id)
);

CREATE INDEX step_up_lookup ON step_up_grant (user_session_id, permission_key, expires_at);

-- Recovery: an alternate contact value mapped to an account, hashed, so a lost phone does
-- not mean an unrecoverable identity (§12.7). Global like user_account.
CREATE TABLE resource_link (
    id                  char(26)      PRIMARY KEY,
    user_account_id     char(26)      NOT NULL,
    provider            text          NOT NULL,
    value_hash          char(64)      NOT NULL,

    CONSTRAINT resource_link_id_ulid    CHECK (saathi_is_ulid (id)),
    CONSTRAINT resource_link_provider   CHECK (provider IN ('PHONE', 'EMAIL')),
    CONSTRAINT resource_link_hash_shape CHECK (value_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT resource_link_account_fk FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    -- Recovery lookup is one-way: a value maps to exactly one account.
    CONSTRAINT resource_link_unique     UNIQUE (provider, value_hash)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- The shared RLS policy (§4.5), installed ready and left un-forced.
--
-- `app.tenant_id` is set per transaction (never at session level, because pooling would
-- carry one tenant's setting into another's connection); `app.scope = 'platform'` is the
-- aggregate-reporting escape hatch and is set on no other connection. Flipping FORCE on is
-- paired with making the application role a non-owner — an owner bypasses policies no
-- matter what is enabled here.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE FUNCTION saathi_tenant_matches(row_tenant char(26)) RETURNS boolean
LANGUAGE sql STABLE AS
$$
    SELECT row_tenant = current_setting('app.tenant_id', true)
        OR current_setting('app.scope', true) = 'platform'
$$;

ALTER TABLE service_ward    ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE ward_fee_rule   ENABLE ROW LEVEL SECURITY;
ALTER TABLE feature_flag    ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_role_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_session    ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation      ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_scope ON service_ward
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON tenant_category
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON ward_fee_rule
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON feature_flag
    USING (tenant_id IS NULL OR saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON user_role_grant
    USING (tenant_id IS NULL OR saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON user_session
    USING (tenant_scope IS NULL OR saathi_tenant_matches (tenant_scope));
CREATE POLICY tenant_scope ON invitation
    USING (tenant_id IS NULL OR saathi_tenant_matches (tenant_id));

COMMENT ON FUNCTION saathi_tenant_matches IS
    'The one policy expression (§4.5). inert until FORCE ROW LEVEL SECURITY plus a non-owner '
    'application role, which is the launch gate before tenant #2 goes live.';
