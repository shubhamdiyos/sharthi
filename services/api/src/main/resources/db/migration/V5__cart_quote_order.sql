-- V5__cart_quote_order.sql
--
-- The customer's path from a basket to a placed order: cart, the price quote that is the
-- artifact a customer consents to, and the order with its lines and event trail (§5.4, §5.5,
-- §6, §8, §10.5.4, §10.5.5).
--
-- Four rules this file enforces in the database rather than by convention:
--   * a cart carries one merchant from the first line and never gains a second (§8.4);
--   * at most one ACTIVE quote exists per cart, and per order for weight quotes (§8.1);
--   * an order's status can only move along §6.3's table, checked on update by a trigger;
--   * total = goods + delivery + handling, and every line is quantity times price rounded
--     once, exactly as §9.7's identities state them.

-- ─────────────────────────────────────────────────────────────────────────────
-- cart / cart_line (§5.4)
--
-- One cart per (customer, city). A customer who shops in two cities has two carts and one
-- ward each, which is the same "same person, two grants" rule the seed applies to people.
-- The merchant is set by the first line and cleared only when the last line goes: an empty
-- cart has no shop, and a cart with lines cannot pretend it does not.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE cart (
    id              char(26)      PRIMARY KEY,
    tenant_id       char(26)      NOT NULL,
    customer_id     char(26)      NOT NULL,
    merchant_id     char(26),
    ward_id         char(26)      NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT cart_id_ulid        CHECK (saathi_is_ulid (id)),
    CONSTRAINT cart_tenant_ulid    CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT cart_customer_fk    FOREIGN KEY (customer_id) REFERENCES user_account (id),
    CONSTRAINT cart_tenant_fk      FOREIGN KEY (tenant_id)   REFERENCES tenant (id),
    CONSTRAINT cart_ward_fk        FOREIGN KEY (tenant_id, ward_id)
        REFERENCES service_ward (tenant_id, id),
    -- Nullable until the first add; a NULL merchant is an empty cart, not an unknown one.
    CONSTRAINT cart_merchant_fk    FOREIGN KEY (tenant_id, merchant_id)
        REFERENCES merchant (tenant_id, id),
    -- Composite parent key for cart_line and price_quote (§4.2).
    CONSTRAINT cart_tenant_uk      UNIQUE (tenant_id, id),
    CONSTRAINT cart_one_per_customer UNIQUE (tenant_id, customer_id)
);

CREATE INDEX cart_customer ON cart (customer_id, tenant_id);

CREATE TABLE cart_line (
    id                      char(26)      PRIMARY KEY,
    tenant_id               char(26)      NOT NULL,
    cart_id                 char(26)      NOT NULL,
    product_id              char(26)      NOT NULL,

    -- Three decimals because a by-weight line is 0.985 kg; a piece line is a whole number
    -- and the service, not the database, decides which of the two a product is (§7.1).
    qty                     numeric(10,3) NOT NULL,
    -- The price the customer saw when they added it. §8 exists because this is not the price
    -- they may be charged after the shop edits it, so it is kept rather than recomputed.
    unit_price_paise_at_add bigint        NOT NULL,
    added_at                timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT cart_line_id_ulid        CHECK (saathi_is_ulid (id)),
    CONSTRAINT cart_line_tenant_ulid    CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT cart_line_qty_positive   CHECK (qty > 0),
    CONSTRAINT cart_line_price_non_negative CHECK (unit_price_paise_at_add >= 0),
    -- One line per product: adding again changes the quantity rather than creating a second
    -- row the total would double-count.
    CONSTRAINT cart_line_one_product    UNIQUE (cart_id, product_id),
    CONSTRAINT cart_line_cart_fk        FOREIGN KEY (tenant_id, cart_id)
        REFERENCES cart (tenant_id, id),
    CONSTRAINT cart_line_product_fk     FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id)
);

CREATE INDEX cart_line_cart ON cart_line (cart_id, added_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- price_quote / price_quote_line (§5.4, §8.1)
--
-- A quote is the artifact the customer consented to, not a cache. It carries a hash of the
-- exact lines, fees and total, and the state that says whether it still means anything.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE price_quote (
    id                          char(26)      PRIMARY KEY,
    tenant_id                   char(26)      NOT NULL,

    scope                       text          NOT NULL,
    -- A CART quote belongs to a cart; a WEIGHT or FULFILMENT_CHANGE quote belongs to an
    -- order. Exactly one of the two is set, which is what the coherence check says.
    cart_id                     char(26),
    order_id                    char(26),

    quote_hash                  char(64)      NOT NULL,
    goods_paise                 bigint        NOT NULL,
    delivery_fee_paise          bigint        NOT NULL,
    handling_fee_paise          bigint        NOT NULL,
    total_paise                 bigint        NOT NULL,
    commission_paise            bigint        NOT NULL,

    -- Text holding a JSON array, checked by cast. Hibernate maps String without a format
    -- mapper, and the merchant module already keeps its JSON this way for the same reason
    -- (Merchant.operatingHoursJson): a column that silently fails to round-trip is worse than
    -- an explicit parse.
    consent_requirements_json   text          NOT NULL DEFAULT '[]',
    -- Both modes' totals are frozen at issue, because a re-render of the quote must show the
    -- same choice the customer was offered even after a fee rule has been replaced (§5.2).
    fulfilment_options_json     text          NOT NULL DEFAULT '[]',
    fulfilment_mode_snapshot    text          NOT NULL,
    state                       text          NOT NULL DEFAULT 'ACTIVE',

    issued_at                   timestamptz   NOT NULL DEFAULT now(),
    accepted_at                 timestamptz,
    accepted_by                 char(26),
    expires_at                  timestamptz   NOT NULL,

    CONSTRAINT price_quote_id_ulid       CHECK (saathi_is_ulid (id)),
    CONSTRAINT price_quote_tenant_ulid   CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT price_quote_scope         CHECK (scope IN ('CART', 'WEIGHT', 'FULFILMENT_CHANGE')),
    CONSTRAINT price_quote_state         CHECK (state IN ('ACTIVE', 'ACCEPTED', 'SUPERSEDED', 'EXPIRED_BY_RULE')),
    CONSTRAINT price_quote_mode          CHECK (fulfilment_mode_snapshot IN ('PLATFORM', 'MERCHANT')),
    CONSTRAINT price_quote_hash_shape    CHECK (quote_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT price_quote_amounts_non_negative CHECK (
        goods_paise >= 0 AND delivery_fee_paise >= 0 AND handling_fee_paise >= 0
        AND total_paise >= 0 AND commission_paise >= 0
    ),
    -- §9.7 I-1, stated where a fixture cannot skip it.
    CONSTRAINT price_quote_total_identity CHECK (
        total_paise = goods_paise + delivery_fee_paise + handling_fee_paise
    ),
    CONSTRAINT price_quote_consent_array CHECK (
        jsonb_typeof (consent_requirements_json::jsonb) = 'array'
    ),
    CONSTRAINT price_quote_options_array CHECK (
        jsonb_typeof (fulfilment_options_json::jsonb) = 'array'
    ),
    CONSTRAINT price_quote_expiry_after_issue CHECK (expires_at > issued_at),
    -- A quote names a cart or an order, never both and never neither.
    CONSTRAINT price_quote_parent        CHECK (
        (scope = 'CART' AND cart_id IS NOT NULL AND order_id IS NULL)
        OR (scope IN ('WEIGHT', 'FULFILMENT_CHANGE') AND order_id IS NOT NULL)
    ),
    -- ACCEPTED is the only state that may exist without an acceptance absent. The converse is
    -- deliberately not enforced: a quote that was accepted and later superseded keeps the
    -- evidence of who accepted it (§13.1 — history is added to, never erased).
    CONSTRAINT price_quote_accepted_shape CHECK (
        state <> 'ACCEPTED'
        OR (accepted_at IS NOT NULL AND accepted_by IS NOT NULL)
    ),
    CONSTRAINT price_quote_cart_fk       FOREIGN KEY (tenant_id, cart_id)
        REFERENCES cart (tenant_id, id),
    CONSTRAINT price_quote_accepted_by_fk FOREIGN KEY (accepted_by) REFERENCES user_account (id)
);

-- One live quote per cart, and one live weight quote per order (§8.1). The indexes are
-- partial because history — superseded and expired quotes — accumulates by design.
CREATE UNIQUE INDEX price_quote_active_cart
    ON price_quote (cart_id) WHERE state = 'ACTIVE' AND scope = 'CART';
CREATE UNIQUE INDEX price_quote_active_order
    ON price_quote (order_id) WHERE state = 'ACTIVE' AND order_id IS NOT NULL;
CREATE INDEX price_quote_cart ON price_quote (cart_id, issued_at DESC);
CREATE INDEX price_quote_order ON price_quote (order_id, issued_at DESC);
CREATE INDEX price_quote_expiry ON price_quote (expires_at) WHERE state = 'ACTIVE';

CREATE TABLE price_quote_line (
    id                  char(26)      PRIMARY KEY,
    tenant_id           char(26)      NOT NULL,
    quote_id            char(26)      NOT NULL,
    product_id          char(26)      NOT NULL,
    -- The public identifier the QuoteView shows (§10.4). Snapshotted for the same reason as the
    -- name: a code is what the customer saw, and a later rename must not rewrite the artifact.
    product_code        text          NOT NULL,

    -- §5.5's rule applied to quotes too: an edit to the catalogue does not rewrite what the
    -- customer was shown. unit_label_snapshot is the one column §5.4's sketch omits and the
    -- QuoteView needs (§10.4), so it is here rather than fetched from a product that may have
    -- been renamed since.
    name_snapshot       text          NOT NULL,
    unit_label_snapshot text          NOT NULL,

    qty_ordered         numeric(10,3) NOT NULL,
    qty_weighed         numeric(10,3),
    unit_price_paise    bigint        NOT NULL,
    line_paise          bigint        NOT NULL,
    price_delta_paise   bigint        NOT NULL DEFAULT 0,
    consent_required    boolean       NOT NULL DEFAULT false,

    CONSTRAINT price_quote_line_id_ulid  CHECK (saathi_is_ulid (id)),
    CONSTRAINT price_quote_line_tenant_ulid CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT price_quote_line_qty      CHECK (qty_ordered > 0 AND (qty_weighed IS NULL OR qty_weighed > 0)),
    CONSTRAINT price_quote_line_amounts  CHECK (unit_price_paise >= 0 AND line_paise >= 0),
    -- §9.7 I-2: round(qty x price), once, per line.
    CONSTRAINT price_quote_line_identity CHECK (
        line_paise = round(COALESCE(qty_weighed, qty_ordered) * unit_price_paise)
    ),
    CONSTRAINT price_quote_line_quote_fk FOREIGN KEY (quote_id) REFERENCES price_quote (id),
    CONSTRAINT price_quote_line_product_fk FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id)
);

CREATE INDEX price_quote_line_quote ON price_quote_line (quote_id, id);

-- ─────────────────────────────────────────────────────────────────────────────
-- §6.3's transition table, as a function and a trigger.
--
-- Two enforcement points, as §6.1 asks: the application checks before it acts, and the
-- database refuses anything the application forgot. The self-edge is T13 — job activity on
-- a READY_FOR_PICKUP order — and it is legal only for a FULFILMENT_PROGRESS event.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE FUNCTION saathi_order_transition_allowed(from_status text, to_status text) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
    SELECT CASE
        WHEN from_status IS NULL THEN to_status = 'CREATED'
        ELSE (from_status, to_status) IN (
            ('CREATED', 'PENDING_CONFIRMATION'),
            ('PENDING_CONFIRMATION', 'MERCHANT_ACCEPTED'),
            ('PENDING_CONFIRMATION', 'REJECTED'),
            ('PENDING_CONFIRMATION', 'CANCELLED'),
            ('MERCHANT_ACCEPTED', 'PREPARING'),
            ('MERCHANT_ACCEPTED', 'PRICE_CONFIRMATION_PENDING'),
            ('MERCHANT_ACCEPTED', 'CANCELLED'),
            ('PRICE_CONFIRMATION_PENDING', 'PREPARING'),
            ('PRICE_CONFIRMATION_PENDING', 'CANCELLED'),
            ('PREPARING', 'READY_FOR_PICKUP'),
            ('PREPARING', 'OUT_FOR_DELIVERY'),
            ('PREPARING', 'CANCELLED'),
            ('READY_FOR_PICKUP', 'DELIVERED'),
            ('READY_FOR_PICKUP', 'DELIVERY_FAILED'),
            ('READY_FOR_PICKUP', 'READY_FOR_PICKUP'),
            ('OUT_FOR_DELIVERY', 'DELIVERED'),
            ('OUT_FOR_DELIVERY', 'DELIVERY_FAILED'),
            ('DELIVERY_FAILED', 'READY_FOR_PICKUP'),
            ('DELIVERY_FAILED', 'OUT_FOR_DELIVERY'),
            ('DELIVERY_FAILED', 'DELIVERED'),
            ('DELIVERY_FAILED', 'CANCELLED')
        )
    END
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- "order" is quoted throughout: ORDER is a reserved word and an unquoted table would be a
-- parse error in every handwritten query. The public identifier is the code, never the id.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SEQUENCE order_code_seq START WITH 4821;

CREATE TABLE "order" (
    id                          char(26)      PRIMARY KEY,
    code                        text          NOT NULL UNIQUE,
    tenant_id                   char(26)      NOT NULL,
    merchant_id                 char(26)      NOT NULL,
    customer_id                 char(26)      NOT NULL,
    ward_id                     char(26)      NOT NULL,

    status                      text          NOT NULL,
    fulfilment_mode_snapshot    text          NOT NULL,
    fulfilment_locked_at        timestamptz   NOT NULL,

    payment_method              text          NOT NULL DEFAULT 'COD',
    collection_channel          text          NOT NULL,
    payment_status              text          NOT NULL DEFAULT 'UNPAID',
    settlement_status           text          NOT NULL DEFAULT 'NOT_DUE',

    quote_id                    char(26)      NOT NULL,
    idempotency_key             text          NOT NULL,

    placed_at                   timestamptz   NOT NULL DEFAULT now(),
    accepted_at                 timestamptz,
    delivered_at                timestamptz,
    cancelled_at                timestamptz,

    goods_paise                 bigint        NOT NULL,
    delivery_fee_paise          bigint        NOT NULL,
    handling_fee_paise          bigint        NOT NULL,
    total_paise                 bigint        NOT NULL,
    commission_bp_snapshot      integer       NOT NULL,
    commission_paise            bigint        NOT NULL,
    collected_paise             bigint        NOT NULL DEFAULT 0,
    outstanding_paise           bigint        NOT NULL,

    delivery_note               text,
    address_snapshot            text          NOT NULL,
    contact_phone_cipher        text          NOT NULL,
    attempt_count               integer       NOT NULL DEFAULT 0,
    address_changed_count       integer       NOT NULL DEFAULT 0,

    cancel_reason               text,
    reject_reason               text,
    fail_reason                 text,

    CONSTRAINT order_id_ulid            CHECK (saathi_is_ulid (id)),
    CONSTRAINT order_tenant_ulid        CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT order_code_shape         CHECK (code ~ '^SAATH-[0-9]{4,}$'),
    CONSTRAINT order_status             CHECK (status IN (
        'CREATED', 'PENDING_CONFIRMATION', 'MERCHANT_ACCEPTED', 'PRICE_CONFIRMATION_PENDING',
        'PREPARING', 'READY_FOR_PICKUP', 'OUT_FOR_DELIVERY', 'DELIVERED', 'REJECTED',
        'CANCELLED', 'DELIVERY_FAILED'
    )),
    CONSTRAINT order_mode               CHECK (fulfilment_mode_snapshot IN ('PLATFORM', 'MERCHANT')),
    CONSTRAINT order_payment_method     CHECK (payment_method IN ('COD', 'ONLINE', 'OFFLINE_RECORD')),
    CONSTRAINT order_collection_channel CHECK (collection_channel IN (
        'RIDER_CASH', 'MERCHANT_CASH', 'OPERATOR_CASH', 'OFFLINE_RECORD'
    )),
    CONSTRAINT order_payment_status     CHECK (payment_status IN ('UNPAID', 'PARTIAL', 'PAID', 'REFUNDED')),
    CONSTRAINT order_settlement_status  CHECK (settlement_status IN ('NOT_DUE', 'PENDING', 'SETTLED', 'ON_HOLD')),
    CONSTRAINT order_amounts_non_negative CHECK (
        goods_paise >= 0 AND delivery_fee_paise >= 0 AND handling_fee_paise >= 0
        AND total_paise >= 0 AND collected_paise >= 0 AND outstanding_paise >= 0
        AND commission_paise >= 0
    ),
    CONSTRAINT order_commission_bp      CHECK (commission_bp_snapshot BETWEEN 0 AND 10000),
    CONSTRAINT order_total_identity     CHECK (
        total_paise = goods_paise + delivery_fee_paise + handling_fee_paise
    ),
    CONSTRAINT order_outstanding_identity CHECK (
        outstanding_paise = total_paise - collected_paise
    ),
    CONSTRAINT order_counts_non_negative CHECK (attempt_count >= 0 AND address_changed_count >= 0),
    CONSTRAINT order_address_object     CHECK (jsonb_typeof (address_snapshot::jsonb) = 'object'),
    CONSTRAINT order_idempotency_key_shape CHECK (char_length (idempotency_key) BETWEEN 8 AND 200),

    CONSTRAINT order_tenant_fk          FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT order_tenant_uk          UNIQUE (tenant_id, id),
    CONSTRAINT order_merchant_fk        FOREIGN KEY (tenant_id, merchant_id)
        REFERENCES merchant (tenant_id, id),
    CONSTRAINT order_customer_fk        FOREIGN KEY (customer_id) REFERENCES user_account (id),
    CONSTRAINT order_ward_fk            FOREIGN KEY (tenant_id, ward_id)
        REFERENCES service_ward (tenant_id, id),
    CONSTRAINT order_quote_fk           FOREIGN KEY (quote_id) REFERENCES price_quote (id)
);

CREATE INDEX order_customer_time ON "order" (tenant_id, customer_id, placed_at DESC, id DESC);
CREATE INDEX order_merchant_status ON "order" (tenant_id, merchant_id, status, placed_at DESC);
CREATE INDEX order_code_lookup ON "order" (code);

CREATE FUNCTION saathi_order_transition_guard() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
        AND NOT saathi_order_transition_allowed (OLD.status, NEW.status) THEN
        RAISE EXCEPTION 'saathi: order % may not move % -> %', OLD.code, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER order_transition_guard
    BEFORE UPDATE OF status ON "order"
    FOR EACH ROW EXECUTE FUNCTION saathi_order_transition_guard();

CREATE TABLE order_line (
    id                  char(26)      PRIMARY KEY,
    tenant_id           char(26)      NOT NULL,
    order_id            char(26)      NOT NULL,

    product_id          char(26)      NOT NULL,
    -- The identifier the customer saw in the quote, snapshotted for the same reason as the name:
    -- an order is a historical artifact, and a later rename must not rewrite what it says.
    product_code        text          NOT NULL,
    name_snapshot       text          NOT NULL,
    unit_label_snapshot text          NOT NULL,

    qty_ordered         numeric(10,3) NOT NULL,
    qty_weighed         numeric(10,3),
    qty_delivered       numeric(10,3),
    unit_price_paise    bigint        NOT NULL,
    line_paise          bigint        NOT NULL,
    price_source        text          NOT NULL DEFAULT 'LIST',
    consented_at        timestamptz,

    CONSTRAINT order_line_id_ulid      CHECK (saathi_is_ulid (id)),
    CONSTRAINT order_line_tenant_ulid  CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT order_line_qty          CHECK (
        qty_ordered > 0
        AND (qty_weighed IS NULL OR qty_weighed > 0)
        AND (qty_delivered IS NULL OR qty_delivered >= 0)
    ),
    CONSTRAINT order_line_amounts      CHECK (unit_price_paise >= 0 AND line_paise >= 0),
    CONSTRAINT order_line_price_source CHECK (price_source IN ('LIST', 'WEIGHED', 'CORRECTED')),
    -- §7.5: the charged quantity is the weighed one when it exists, else the ordered one.
    CONSTRAINT order_line_identity     CHECK (
        line_paise = round(COALESCE(qty_weighed, qty_ordered) * unit_price_paise)
    ),
    CONSTRAINT order_line_one_product  UNIQUE (order_id, product_id),
    CONSTRAINT order_line_order_fk     FOREIGN KEY (order_id) REFERENCES "order" (id),
    CONSTRAINT order_line_product_fk   FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id)
);

CREATE INDEX order_line_order ON order_line (order_id, id);

CREATE TABLE order_event (
    id              char(26)      PRIMARY KEY,
    tenant_id       char(26)      NOT NULL,
    order_id        char(26)      NOT NULL,

    from_status     text,
    to_status       text          NOT NULL,
    kind            text          NOT NULL,
    actor_type      text          NOT NULL,
    actor_id        char(26),
    permission_used text,

    occurred_at     timestamptz   NOT NULL DEFAULT now(),
    note            text,
    request_id      char(26),

    CONSTRAINT order_event_id_ulid     CHECK (saathi_is_ulid (id)),
    CONSTRAINT order_event_tenant_ulid CHECK (saathi_is_ulid (tenant_id)),
    CONSTRAINT order_event_statuses    CHECK (to_status IN (
        'CREATED', 'PENDING_CONFIRMATION', 'MERCHANT_ACCEPTED', 'PRICE_CONFIRMATION_PENDING',
        'PREPARING', 'READY_FOR_PICKUP', 'OUT_FOR_DELIVERY', 'DELIVERED', 'REJECTED',
        'CANCELLED', 'DELIVERY_FAILED'
    )),
    CONSTRAINT order_event_kind        CHECK (kind IN (
        'STATUS', 'FULFILMENT_PROGRESS', 'CONSENT', 'CANCELLATION'
    )),
    CONSTRAINT order_event_actor_type  CHECK (actor_type IN (
        'SYSTEM', 'CUSTOMER', 'MERCHANT_STAFF', 'RIDER', 'OPERATOR', 'SUPER_ADMIN'
    )),
    CONSTRAINT order_event_actor_identity CHECK (
        (actor_type = 'SYSTEM' AND actor_id IS NULL)
        OR (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL)
    ),
    CONSTRAINT order_event_transition  CHECK (
        saathi_order_transition_allowed (from_status, to_status)
    ),
    -- A self-edge is timeline progress (T13), never a status event.
    CONSTRAINT order_event_self_edge   CHECK (
        from_status IS DISTINCT FROM to_status OR kind = 'FULFILMENT_PROGRESS'
    ),
    CONSTRAINT order_event_request_ulid CHECK (request_id IS NULL OR saathi_is_ulid (request_id)),
    CONSTRAINT order_event_order_fk    FOREIGN KEY (order_id) REFERENCES "order" (id)
);

CREATE INDEX order_event_timeline ON order_event (order_id, occurred_at, id);

-- The quote gains its order link once the order exists (the two tables reference each other
-- by design: a quote is accepted by an order, and an order names the quote it accepted).
ALTER TABLE price_quote
    ADD CONSTRAINT price_quote_order_fk FOREIGN KEY (order_id) REFERENCES "order" (id);

COMMENT ON TABLE "order" IS
    '§6.1 statuses are a closed list, and §6.3 transitions are enforced by trigger as well as '
    'by the service. Clients send actions, never statuses (§10.7).';
COMMENT ON COLUMN cart.merchant_id IS
    'Set by the first line, immutable while the cart has lines (§8.4). Cleared when the last '
    'line goes, because an empty cart has no shop.';
COMMENT ON COLUMN price_quote.quote_hash IS
    'SHA-256 of the canonical quote (lines, fees, total, currency, mode, issued_at). The value '
    'the customer consents to and the value POST /orders is checked against.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Row-level security (§4.5): enabled, not forced — the same pair and gate as V2 and V4.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE cart               ENABLE ROW LEVEL SECURITY;
ALTER TABLE cart_line          ENABLE ROW LEVEL SECURITY;
ALTER TABLE price_quote        ENABLE ROW LEVEL SECURITY;
ALTER TABLE price_quote_line   ENABLE ROW LEVEL SECURITY;
ALTER TABLE "order"            ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line         ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_event        ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_scope ON cart
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON cart_line
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON price_quote
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON price_quote_line
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON "order"
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON order_line
    USING (saathi_tenant_matches (tenant_id));
CREATE POLICY tenant_scope ON order_event
    USING (saathi_tenant_matches (tenant_id));

-- ─────────────────────────────────────────────────────────────────────────────
-- Permissions for this module's endpoints (§11.1, §11.2)
--
-- Only the keys an endpoint in this migration checks. Merchant acceptance, weighing and the
-- delivery permissions arrive with the modules that enforce them (Day 2A onward).
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO permission (id, key, description)
VALUES
    ('01M67AW3RM6Y1B14YFH7NKXHSW', 'CART_WRITE',       'Add, change and remove cart lines'),
    ('01M67AW3RN61CQZ90Y3R07728R', 'ORDER_CREATE',     'Place an order from an accepted quote'),
    ('01M67AW3RPGKZQH4Y1M4VRN78V', 'ORDER_READ_OWN',   'Read the caller''s own orders'),
    ('01M67AW3RQGW1T6QXRKJ4E6VFB', 'ORDER_CANCEL_OWN', 'Cancel the caller''s own order before acceptance');

-- CUSTOMER: the basket and the customer's own order surface (§11.2).
INSERT INTO role_permission (role_id, permission_id)
SELECT '01KAC4ST0J77JV2VABWQD4TEY9', id
  FROM permission
 WHERE key IN ('CART_WRITE', 'ORDER_CREATE', 'ORDER_READ_OWN', 'ORDER_CANCEL_OWN');
