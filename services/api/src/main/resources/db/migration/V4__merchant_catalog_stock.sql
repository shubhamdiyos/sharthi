-- V4__merchant_catalog_stock.sql
--
-- §5.3's merchant and catalogue spine, plus §7's stock tables and the permissions the
-- endpoints in this module enforce. One migration per module is the rule V3 states: a
-- permission arrives with the endpoint that checks it, never ahead of it.
--
-- Rules this file enforces rather than records:
--   * a merchant's ward is pinned to the merchant's own city by a composite foreign key, so
--     a shop cannot be filed under another city's Ward 4 (§4.2);
--   * a product's category must be activated in its own city — CHECK cannot read another
--     table, so this one is a trigger, and it exists because §4.1's tenant_category is a
--     real gate, not a display list;
--   * stock can never be negative and a reservation can never be zero or negative (§7.1);
--   * `stock_state` is absent on purpose: §7.1 calls it a derived projection, and a stored
--     copy is a count that can disagree with `stock_qty − reserved_qty`.
--
-- `merchant.code` and `product.code` are the public identifiers (§4.3-6). Merchant codes are
-- unique per tenant; product codes are unique platform-wide, because `GET /products/{code}`
-- carries no city parameter and a per-tenant duplicate would make that URL ambiguous.

-- ─────────────────────────────────────────────────────────────────────────────
-- Merchants (§5.3)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE merchant (
    id                      char(26)      PRIMARY KEY,
    tenant_id               char(26)      NOT NULL,

    -- Public, human-readable, stable. The operator screens and every order summary show it.
    code                    text          NOT NULL,
    legal_name              text          NOT NULL,
    trade_name              text          NOT NULL,

    category_id             char(26)      NOT NULL,
    -- Nullable until the owner accepts an invitation (§12.3). An application is a person and
    -- a shop, not an account: creating a login for an unapproved applicant would be a
    -- credential nobody asked for.
    owner_user_id           char(26),

    phone                   text          NOT NULL,
    address_text            text          NOT NULL,
    ward_id                 char(26),
    lat                     numeric(9,6),
    lng                     numeric(9,6),
    operating_hours_json    text,

    state                   text          NOT NULL DEFAULT 'PENDING',
    -- A hidden shop is APPROVED and invisible. PRD §5.4: only SHOP_VISIBILITY_WRITE (super
    -- admin) sets this; operators raise a SHOP_FLAG ticket instead.
    hidden_reason           text,

    fulfilment_capability   text          NOT NULL DEFAULT 'PLATFORM_ONLY',
    default_fulfilment_mode text          NOT NULL DEFAULT 'PLATFORM',
    customer_choice_allowed boolean       NOT NULL DEFAULT true,
    self_deliver_zones_json text,

    licence_ref             text,
    licence_state           text          NOT NULL DEFAULT 'NOT_SUBMITTED',

    onboarding_completed_at timestamptz,
    approved_by             char(26),
    approved_at             timestamptz,
    rejection_reason        text,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    version                 bigint        NOT NULL DEFAULT 0,

    CONSTRAINT merchant_id_ulid           CHECK (saathi_is_ulid (id)),
    CONSTRAINT merchant_tenant_ulid       CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT merchant_code_shape        CHECK (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length (code) BETWEEN 3 AND 80),
    CONSTRAINT merchant_name_shape        CHECK (char_length (legal_name) BETWEEN 2 AND 200
                                                 AND char_length (trade_name) BETWEEN 2 AND 200),
    CONSTRAINT merchant_phone_e164        CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT merchant_state             CHECK (state IN ('PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED')),
    CONSTRAINT merchant_fulfilment_capability CHECK (fulfilment_capability IN ('PLATFORM_ONLY', 'MERCHANT_ONLY', 'BOTH')),
    CONSTRAINT merchant_default_mode      CHECK (default_fulfilment_mode IN ('PLATFORM', 'MERCHANT')),
    CONSTRAINT merchant_licence_state     CHECK (licence_state IN ('NOT_SUBMITTED', 'SUBMITTED', 'VALID', 'INVALID')),
    -- A merchant-delivered default is only legal when the shop can deliver. Otherwise
    -- default_fulfilment_mode names a capability the shop does not have, and the first order
    -- discovers it.
    CONSTRAINT merchant_default_mode_within_capability CHECK (
        fulfilment_capability = 'BOTH'
        OR (default_fulfilment_mode = 'PLATFORM' AND fulfilment_capability = 'PLATFORM_ONLY')
        OR (default_fulfilment_mode = 'MERCHANT' AND fulfilment_capability = 'MERCHANT_ONLY')
    ),
    -- An approval names its approver; a rejection says why. A row that claims either without
    -- the evidence is a decision nobody signed for.
    CONSTRAINT merchant_approval_shape    CHECK (
        (state = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR state <> 'APPROVED'
    ),
    CONSTRAINT merchant_rejection_shape   CHECK (state <> 'REJECTED' OR rejection_reason IS NOT NULL),
    CONSTRAINT merchant_hidden_reason_shape CHECK (
        hidden_reason IS NULL OR char_length (hidden_reason) BETWEEN 3 AND 200
    ),
    CONSTRAINT merchant_tenant_code       UNIQUE (tenant_id, code),
    -- The pair a child references: product (tenant_id, merchant_id) can only point at a
    -- merchant of the same city (§4.2).
    CONSTRAINT merchant_tenant_uk         UNIQUE (tenant_id, id),
    CONSTRAINT merchant_tenant_fk         FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT merchant_category_fk       FOREIGN KEY (category_id) REFERENCES category (id),
    CONSTRAINT merchant_owner_fk          FOREIGN KEY (owner_user_id) REFERENCES user_account (id),
    CONSTRAINT merchant_approved_by_fk    FOREIGN KEY (approved_by) REFERENCES user_account (id),
    -- Composite: the ward must belong to the merchant's own tenant. A NULL ward is legal — a
    -- hidden shop outside every ward is exactly the case the operator screens show.
    CONSTRAINT merchant_ward_fk           FOREIGN KEY (tenant_id, ward_id)
        REFERENCES service_ward (tenant_id, id)
);

-- The composite FK V2's user_role_grant comment promised: a merchant-scoped grant can only
-- name a shop of the grant's own city (§4.2).
ALTER TABLE user_role_grant
    ADD CONSTRAINT grant_merchant_fk FOREIGN KEY (tenant_id, merchant_id)
        REFERENCES merchant (tenant_id, id);

CREATE INDEX merchant_storefront ON merchant (tenant_id, state, hidden_reason, category_id);
CREATE INDEX merchant_ward ON merchant (tenant_id, ward_id)
    WHERE ward_id IS NOT NULL AND state = 'APPROVED' AND hidden_reason IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Products (§5.3, §7.1)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE product (
    id                  char(26)      PRIMARY KEY,
    tenant_id           char(26)      NOT NULL,
    merchant_id         char(26)      NOT NULL,
    category_id         char(26)      NOT NULL,

    code                text          NOT NULL,
    name                text          NOT NULL,
    description         text,
    image_key           text,

    price_paise         bigint        NOT NULL,
    currency            char(3)       NOT NULL DEFAULT 'INR',

    unit_label          text          NOT NULL,
    is_by_weight        boolean       NOT NULL DEFAULT false,
    weight_grams        numeric(10,3),
    pack_qty            numeric(10,3),

    track_stock         boolean       NOT NULL DEFAULT false,
    stock_qty           numeric(10,3) NOT NULL DEFAULT 0,
    -- Kept as a column and not summed on read: §7.3's conditional UPDATE is what makes two
    -- customers unable to both see the last packet, and that statement needs a scalar.
    reserved_qty        numeric(10,3) NOT NULL DEFAULT 0,
    stock_unit          text          NOT NULL DEFAULT 'pc',
    allow_oversell      boolean       NOT NULL DEFAULT false,
    max_qty_per_order   integer       NOT NULL DEFAULT 99,

    available           boolean       NOT NULL DEFAULT true,
    active              boolean       NOT NULL DEFAULT true,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    version             bigint        NOT NULL DEFAULT 0,

    CONSTRAINT product_id_ulid          CHECK (saathi_is_ulid (id)),
    CONSTRAINT product_tenant_ulid      CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT product_code_shape       CHECK (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length (code) BETWEEN 3 AND 100),
    CONSTRAINT product_name_shape       CHECK (char_length (name) BETWEEN 1 AND 160),
    CONSTRAINT product_price_non_negative CHECK (price_paise >= 0),
    CONSTRAINT product_currency         CHECK (currency = 'INR'),
    CONSTRAINT product_unit_shape       CHECK (char_length (unit_label) BETWEEN 1 AND 40),
    CONSTRAINT product_stock_unit       CHECK (stock_unit IN ('pc', 'kg', 'g', 'L', 'ml')),
    CONSTRAINT product_stock_non_negative CHECK (stock_qty >= 0 AND reserved_qty >= 0),
    -- Reserved cannot exceed on-hand unless the shop allows overselling: the same rule as
    -- §7.3's conditional UPDATE, repeated where a hand-written fixture cannot skip it.
    CONSTRAINT product_reserved_within_stock CHECK (allow_oversell OR reserved_qty <= stock_qty),
    CONSTRAINT product_max_qty          CHECK (max_qty_per_order BETWEEN 1 AND 999),
    CONSTRAINT product_weight_positive  CHECK (weight_grams IS NULL OR weight_grams > 0),
    CONSTRAINT product_pack_qty_positive CHECK (pack_qty IS NULL OR pack_qty > 0),

    -- Public product codes are platform-wide: GET /products/{code} takes no city (§10.5.3).
    CONSTRAINT product_code_unique      UNIQUE (code),
    CONSTRAINT product_tenant_uk        UNIQUE (tenant_id, id),
    -- Composite: a product cannot be attached to another city's shop (§4.2).
    CONSTRAINT product_merchant_fk      FOREIGN KEY (tenant_id, merchant_id)
        REFERENCES merchant (tenant_id, id),
    CONSTRAINT product_category_fk      FOREIGN KEY (category_id) REFERENCES category (id)
);

CREATE INDEX product_shop_listing ON product (merchant_id, active, available, category_id, name);
CREATE INDEX product_search ON product (tenant_id, name);
CREATE INDEX product_low_stock ON product (merchant_id, stock_qty)
    WHERE track_stock AND active;

-- Product writes may not name a category the city has not switched on (§4.1). A CHECK cannot
-- read tenant_category, so this is a trigger; CATEGORY_NOT_ACTIVE is what the endpoint would
-- answer, and this is the same rule stated where a fixture cannot skip it.
CREATE FUNCTION saathi_product_category_activated() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM tenant_category tc
          JOIN category c ON c.id = tc.category_id AND c.active
         WHERE tc.tenant_id = NEW.tenant_id
           AND tc.category_id = NEW.category_id
           AND tc.active
    ) THEN
        RAISE EXCEPTION 'category % is not active in tenant %', NEW.category_id, NEW.tenant_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER product_category_activated
    BEFORE INSERT OR UPDATE OF category_id, tenant_id ON product
    FOR EACH ROW EXECUTE FUNCTION saathi_product_category_activated();

-- ─────────────────────────────────────────────────────────────────────────────
-- Stock (§7) — policy override, reservations, adjustments
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE stock_policy_override (
    merchant_id     char(26)      PRIMARY KEY,
    policy          text          NOT NULL,
    set_by          char(26),
    set_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT stock_policy_override_policy CHECK (policy IN (
        'FLAG_ONLY', 'RESERVE_ON_PLACEMENT', 'RESERVE_ON_ACCEPT'
    )),
    CONSTRAINT stock_policy_override_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchant (id),
    CONSTRAINT stock_policy_override_set_by_fk   FOREIGN KEY (set_by) REFERENCES user_account (id)
);

CREATE TABLE stock_reservation (
    id              char(26)      PRIMARY KEY,
    tenant_id       char(26)      NOT NULL,
    product_id      char(26)      NOT NULL,
    order_id        char(26)      NOT NULL,
    -- §7.2: reservations are per order line, so a partial cancel releases exactly its own
    -- quantity. Nullable because the order module creates the line and the reservation in
    -- one transaction and the id is not known until the line is written.
    order_line_id   char(26),
    qty_reserved    numeric(10,3) NOT NULL,
    state           text          NOT NULL DEFAULT 'HELD',
    created_at      timestamptz   NOT NULL DEFAULT now(),
    closed_at       timestamptz,

    CONSTRAINT stock_reservation_id_ulid     CHECK (saathi_is_ulid (id)),
    CONSTRAINT stock_reservation_tenant_ulid CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT stock_reservation_qty_positive CHECK (qty_reserved > 0),
    CONSTRAINT stock_reservation_state       CHECK (state IN ('HELD', 'CONSUMED', 'RELEASED', 'TRUE_UP')),
    CONSTRAINT stock_reservation_closed_shape CHECK (
        (state = 'HELD' AND closed_at IS NULL) OR (state <> 'HELD' AND closed_at IS NOT NULL)
    ),
    CONSTRAINT stock_reservation_product_fk  FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id)
);

CREATE INDEX stock_reservation_open ON stock_reservation (product_id) WHERE state = 'HELD';
CREATE INDEX stock_reservation_order ON stock_reservation (order_id, state);

CREATE TABLE stock_adjustment (
    id          char(26)      PRIMARY KEY,
    tenant_id   char(26)      NOT NULL,
    product_id  char(26)      NOT NULL,
    delta_qty   numeric(10,3) NOT NULL,
    reason      text          NOT NULL,
    actor_id    char(26)      NOT NULL,
    at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT stock_adjustment_id_ulid     CHECK (saathi_is_ulid (id)),
    CONSTRAINT stock_adjustment_tenant_ulid CHECK (saathi_is_ulid (tenant_id)),
    -- A zero adjustment records nothing; the row exists to explain a change.
    CONSTRAINT stock_adjustment_delta       CHECK (delta_qty <> 0),
    CONSTRAINT stock_adjustment_reason_shape CHECK (char_length (reason) BETWEEN 3 AND 200),
    CONSTRAINT stock_adjustment_product_fk  FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id),
    CONSTRAINT stock_adjustment_actor_fk    FOREIGN KEY (actor_id) REFERENCES user_account (id)
);

CREATE INDEX stock_adjustment_product ON stock_adjustment (product_id, at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Media (§5.3, §14.5) — the row is written when an image is accepted; the object itself
-- lives outside this database. Three widths, WebP only, never the original.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE merchant_media (
    id          char(26)      PRIMARY KEY,
    tenant_id   char(26)      NOT NULL,
    merchant_id char(26)      NOT NULL,
    product_id  char(26),
    object_key  text          NOT NULL,
    width       integer       NOT NULL,
    format      text          NOT NULL DEFAULT 'WEBP',
    bytes       bigint        NOT NULL,
    created_by  char(26)      NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT merchant_media_id_ulid     CHECK (saathi_is_ulid (id)),
    CONSTRAINT merchant_media_tenant_ulid CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT merchant_media_width       CHECK (width > 0),
    CONSTRAINT merchant_media_format      CHECK (format = 'WEBP'),
    CONSTRAINT merchant_media_bytes       CHECK (bytes > 0),
    CONSTRAINT merchant_media_object_unique UNIQUE (object_key),
    CONSTRAINT merchant_media_merchant_fk FOREIGN KEY (tenant_id, merchant_id)
        REFERENCES merchant (tenant_id, id),
    CONSTRAINT merchant_media_product_fk  FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id),
    CONSTRAINT merchant_media_created_by_fk FOREIGN KEY (created_by) REFERENCES user_account (id)
);

CREATE INDEX merchant_media_product ON merchant_media (product_id, width);

-- ─────────────────────────────────────────────────────────────────────────────
-- Permissions for this module's endpoints (§11.2)
--
-- The keys are the ones §11.1 lists; the role sets are §11.2's. EARNINGS_READ,
-- SETTLEMENT_READ, MEDIA_UPLOAD and SHOP_FLAG arrive with the modules that enforce them, so
-- they are deliberately absent here even though their screens exist.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO permission (id, key, description)
VALUES
    ('01M67798D0JYDNCQ3QNFR3C656', 'PRODUCT_READ',       'Read the shop''s own catalogue'),
    ('01M67798D0Y795DVNC4H85XKSW', 'PRODUCT_WRITE',      'Create and edit products and availability'),
    ('01M67798D0H515AV7CG0M18QVP', 'STOCK_READ',         'Read the shop''s own stock counts'),
    ('01M67798D009X34EQ74QTQD1E5', 'STOCK_ADJUST',       'Record a stock adjustment'),
    ('01M67798D0AS4VPB7THX85AF25', 'SHOP_SETTINGS_WRITE','Hours, fulfilment capability, shop defaults'),
    ('01M67798D0VQGCVNX804NVM34Q', 'MERCHANT_READ_TENANT','Read shops in the operator''s city'),
    ('01M67798D00FW1N3TVJE6PNQRH', 'MERCHANT_APPROVE',   'Approve or reject a merchant application'),
    ('01M67798D07DVXCRF6WYG2DNYG', 'MERCHANT_SUSPEND',   'Suspend an approved shop'),
    ('01M67798D06KS47B6CB48GSQKB', 'SHOP_VISIBILITY_WRITE','Hide or unhide a shop (platform decision)');

-- MERCHANT_STAFF: catalogue and stock, no approval and no visibility.
INSERT INTO role_permission (role_id, permission_id)
SELECT '0147N54N78DAFBP95FY7DTWQ9R', id
  FROM permission
 WHERE key IN ('PRODUCT_READ', 'PRODUCT_WRITE', 'STOCK_READ', 'STOCK_ADJUST', 'SHOP_SETTINGS_WRITE');

-- CITY_OPERATOR: read and judge shops; hiding is not theirs (§11.1, §11.2).
INSERT INTO role_permission (role_id, permission_id)
SELECT '01530PKV1M1GJKFZGW7YWK2CN2', id
  FROM permission
 WHERE key IN ('MERCHANT_READ_TENANT', 'MERCHANT_APPROVE', 'MERCHANT_SUSPEND');

-- SUPER_ADMIN: platform decisions. CATEGORY_* still wait for the admin module's endpoints.
INSERT INTO role_permission (role_id, permission_id)
SELECT '01JCAY88EQV22QTDTRKCX6Z5PA', id
  FROM permission
 WHERE key IN ('PRODUCT_READ', 'MERCHANT_READ_TENANT', 'MERCHANT_APPROVE',
               'MERCHANT_SUSPEND', 'SHOP_VISIBILITY_WRITE');

-- ─────────────────────────────────────────────────────────────────────────────
-- RLS (§4.5) — enabled, not forced. Same pair and same gate as V2.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE merchant           ENABLE ROW LEVEL SECURITY;
ALTER TABLE product            ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_reservation  ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_adjustment   ENABLE ROW LEVEL SECURITY;
ALTER TABLE merchant_media     ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_scope ON merchant
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON product
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON stock_reservation
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON stock_adjustment
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON merchant_media
    USING (saathi_tenant_matches (tenant_id));

COMMENT ON TABLE merchant IS
    'PRD §15 onboarding lives here: PENDING until an operator approves it with a valid licence '
    'for a licence-requiring category. hidden_reason hides an APPROVED shop (super admin only).';
COMMENT ON COLUMN product.reserved_qty IS
    '§7.3: updated by one conditional statement, never read-then-write. A zero-row update is '
    '409 STOCK_CHANGED.';
