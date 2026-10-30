-- R__pilot_reference_data.sql
--
-- The fictional pilot every designed screen depicts: three cities, their wards, the
-- activations each city switched on, the fee the customer sees, and the five people the
-- portals sign in as. This is demo data (PRD §10 asks for it separated from production), so
-- it lives in a location only the `dev` and `test` Flyway configurations read —
-- `application-prod.yml` never sees this directory, and a deployment therefore starts with
-- no fake customers in it.
--
-- Repeatable (`R__`), and every row is written to survive being run twice: a pilot table
-- edited on the laptop is re-applied on the next boot rather than refused by a duplicate
-- key. Flyway re-runs this file when its checksum changes, which is exactly the behaviour a
-- developer tweaking the demo wants.
--
-- Money is integer paise throughout (§9): ₹25 is 2500, never 25.00 and never 25.
-- Identifiers are opaque; the readable names are the `code`, `key`, `slug` and `name`
-- columns, which is what the portals display and what a reviewer greps for.

-- ─────────────────────────────────────────────────────────────────────────────
-- The three cities (PRD §4: a new city is a tenant row, not a fork)
--
-- Siwan is the live pilot. Mashaun is DRAFT — launched on the screens, not on the ground —
-- and Barauli is SUSPENDED, which is the row §10.5.9's state change produces: new orders
-- blocked, existing ones untouched.
--
-- default_commission_bp is 0 for all three. OD-7 is open: no commission rate has been
-- approved by the founder, and 0 is the honest value for "not decided" (§10.6.9 refuses a
-- non-zero write without step-up and an approval_ref).
--
-- The cash caps are the registry's ₹2,000 soft / ₹4,000 hard rider limits (D7, §9.3 A).
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO tenant (id, code, name, state_code, status,
                    soft_cash_limit_paise, hard_cash_limit_paise,
                    stock_policy, weight_tolerance_bp, cart_ttl_minutes, created_at)
VALUES
    ('01NGD9Y4JMQ2RBNVVKXX7J04DM',   'siwan',   'Siwan',   'BR', 'LIVE',      200000, 400000,
     'FLAG_ONLY', 250, 30, '2026-09-01T09:00:00+05:30'),
    ('01YNXV3CA9TS3M70MXR8MJH9TA', 'mashaun', 'Mashaun', 'BR', 'DRAFT',     200000, 400000,
     'FLAG_ONLY', 250, 30, '2026-10-12T11:30:00+05:30'),
    ('014A6TTPBQTSF8DM9P94FN94PS', 'barauli', 'Barauli', 'BR', 'SUSPENDED', 200000, 400000,
     'FLAG_ONLY', 250, 30, '2026-08-20T08:15:00+05:30')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    status = EXCLUDED.status,
    soft_cash_limit_paise = EXCLUDED.soft_cash_limit_paise,
    hard_cash_limit_paise = EXCLUDED.hard_cash_limit_paise,
    stock_policy = EXCLUDED.stock_policy,
    weight_tolerance_bp = EXCLUDED.weight_tolerance_bp;

COMMENT ON COLUMN tenant.stock_policy IS
    'The pilot flags stock rather than reserving it: §7.4-3 revalidates availability when the '
    'merchant accepts, and STOCK_CHANGED is what a change between those two moments answers.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Wards (§5.2)
--
-- The five Siwan wards every designed screen places shops and customers in. Mashaun has two
-- so the "same ward number, different city" case is real data rather than a test fixture:
-- Ward 1 exists twice, and only (tenant_id, ward_no) says which one a row means.
--
-- Sharma Bakery is in Ward 07 and Kumar Provision Store is outside every ward — the second
-- is the hidden-shop reason the operator screens show, so Ward 07 must exist for the first
-- to be about geography and not about a missing row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO service_ward (id, tenant_id, ward_no, name, active, sort, note)
VALUES
    ('01NMEEXSEKWHZEY2J6GY8N4D0G', '01NGD9Y4JMQ2RBNVVKXX7J04DM',   1, 'Ward 01 — Aoriyanimath',   true, 10,
     'Aashirvaad General Store'),
    ('019HSZK7VAPZZC7QG7E328YNPW', '01NGD9Y4JMQ2RBNVVKXX7J04DM',   4, 'Ward 04 — Masjid Road',    true, 20,
     'Sunita Devi''s address; Mishra Kirana Bhandar'),
    ('017CWB0S01Z0ZZTNWNWHEKG9SX', '01NGD9Y4JMQ2RBNVVKXX7J04DM',   7, 'Ward 07 — Nawada',         true, 30,
     'Sharma Bakery, hidden pending a licence photo'),
    ('01PK9PPAHHQZ23FVE8AJ3R4K0Q', '01NGD9Y4JMQ2RBNVVKXX7J04DM',   9, 'Ward 09 — Chandbeli Road', true, 40,
     'Siwan Chicken and Meats'),
    ('01YNWDVVMJ7A26M1P29AP16GAJ', '01NGD9Y4JMQ2RBNVVKXX7J04DM',  12, 'Ward 12 — Mithan Market',  true, 50,
     'Ramesh Kumar''s address; Gupta Fresh Mart, Anita Dairy'),
    ('01K44E2VGSQFR7PZFE3WHF20X8', '01YNXV3CA9TS3M70MXR8MJH9TA', 1, 'Ward 01 — Bhitari Road', true, 10, NULL),
    ('017AK6HC5YS6V41ADC1Y8DHZTH', '01YNXV3CA9TS3M70MXR8MJH9TA', 3, 'Ward 03 — Pachhiari',    true, 20, NULL),
    ('01PZZ05419GG8ME8QDB2T4ZV2B', '014A6TTPBQTSF8DM9P94FN94PS', 1, 'Ward 01 — Barauli Bazar', false, 10,
     'The city is suspended, so the ward is switched off rather than deleted')
ON CONFLICT (tenant_id, ward_no) DO UPDATE SET
    name = EXCLUDED.name,
    active = EXCLUDED.active,
    sort = EXCLUDED.sort,
    note = EXCLUDED.note;

-- ─────────────────────────────────────────────────────────────────────────────
-- People (§12.1)
--
-- The registry's fictional pilot, with the masks filled in: 98•• ••3421, 98•• ••5207,
-- 90•• ••7712. They are not real numbers, and §10 forbids logging them in full anyway —
-- these rows exist so `GET /me` and the operator's rider list have someone to show.
--
-- Rakesh S. is rider R-104 in the screens; `display_name` carries both, because the screens
-- and the ledger must agree on who is being paid.
--
-- Meena Kumari and Anil Verma have no password_hash. Staff credentials are set by accepting
-- an invitation (§12.3), and a seeded hash would be a dev password committed to a repository
-- — the one thing §14 and the secrets gate both forbid. OTP is how the pilot signs in today.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO user_account (id, kind, phone, email, display_name, state,
                          terms_version_accepted, terms_accepted_at, created_at)
VALUES
    ('010KHX27SMXY2F7BGS45KS8X0X', 'CUSTOMER', '+919800003421', NULL, 'Ramesh Kumar', 'ACTIVE',
     'v1', '2026-09-14T19:22:00+05:30', '2026-09-14T19:22:00+05:30'),
    ('01D1NXEKY5TDCZHJN3CF70Y8KS', 'CUSTOMER', '+919800005207', NULL, 'Sunita Devi', 'ACTIVE',
     'v1', '2026-09-28T08:41:00+05:30', '2026-09-28T08:41:00+05:30'),
    ('01TBHYF0BWP2D8W8T9TV6F67GC', 'RIDER', '+919000007712', NULL, 'Rakesh S. (R-104)', 'ACTIVE',
     NULL, NULL, '2026-09-05T10:00:00+05:30'),
    ('01D2S4GNTQ916TY67JTWPEN1BT', 'OPERATOR', '+919800006001', 'meena@saathi.example', 'Meena Kumari', 'ACTIVE',
     NULL, NULL, '2026-09-02T09:30:00+05:30'),
    ('01Z602EBP9ERSY30BFYGVBJZFP', 'SUPER_ADMIN', '+919800006002', 'anil@saathi.example', 'Anil Verma', 'ACTIVE',
     NULL, NULL, '2026-08-25T09:00:00+05:30')
ON CONFLICT (id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    email = EXCLUDED.email,
    state = EXCLUDED.state;

-- Grants, not labels: §11.3 resolves permissions from these rows on every request, so the
-- seed has to write what a real registration or an operator's "add staff" would write. Anil
-- grants everyone else; a platform grant is the only tenantless one the trigger allows.
-- Ramesh and Sunita are scoped to Siwan — the same person in two cities is two grants, and
-- that is the design, not an accident of this file.

INSERT INTO user_role_grant (id, user_account_id, role_id, tenant_id, granted_by, reason, granted_at)
VALUES
    ('01BED2G6NEYD344C63DGG8MTV2', '010KHX27SMXY2F7BGS45KS8X0X', '01KAC4ST0J77JV2VABWQD4TEY9', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01Z602EBP9ERSY30BFYGVBJZFP',
     'Registered on the Siwan storefront', '2026-09-14T19:22:00+05:30'),
    ('01PGB59BWHA0DATPA7GVB0ABWQ', '01D1NXEKY5TDCZHJN3CF70Y8KS', '01KAC4ST0J77JV2VABWQD4TEY9', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01Z602EBP9ERSY30BFYGVBJZFP',
     'Registered on the Siwan storefront', '2026-09-28T08:41:00+05:30'),
    ('01GYYXHZ5RE2KXQRWJHAADHMGY', '01TBHYF0BWP2D8W8T9TV6F67GC', '01C9DQMG5A338RR3G0M3VJSVCW', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01D2S4GNTQ916TY67JTWPEN1BT',
     'Added to the Siwan rider roster (OD-3: no self-service recruitment)', '2026-09-05T10:00:00+05:30'),
    ('01QA8DW7S2V1VB1T251NPFCQ4K', '01D2S4GNTQ916TY67JTWPEN1BT', '01530PKV1M1GJKFZGW7YWK2CN2', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01Z602EBP9ERSY30BFYGVBJZFP',
     'City operator for the live pilot', '2026-09-02T09:30:00+05:30'),
    ('01PCGTE2MY1W711M6FE3BY9QEV', '01Z602EBP9ERSY30BFYGVBJZFP', '01JCAY88EQV22QTDTRKCX6Z5PA', NULL, '01Z602EBP9ERSY30BFYGVBJZFP',
     'Platform seed administrator', '2026-08-25T09:00:00+05:30')
ON CONFLICT (id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    reason = EXCLUDED.reason;

-- ─────────────────────────────────────────────────────────────────────────────
-- What each city sells (§4.1)
--
-- The taxonomy is V3's; a city only decides which nodes are switched on and what its
-- storefront calls them. Mashaun activates two of six because it is a DRAFT city, and
-- Barauli's row is inactive with no actor — which is the shape
-- tenant_category_active_shape says a not-switched-on row must have.
--
-- display_name is the only per-tenant text: the screens call the kirana node "Kirana" in
-- Siwan and "General Store" in Mashaun without either city forking the code.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO tenant_category (id, tenant_id, category_id, active, display_name, sort_override,
                             activated_by, activated_at)
VALUES
    ('019R6M94H680C1V8834RC3T7W4', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01HM1FPVAJ7QF3EMFVF6TRTT8N', true, 'Kirana', 10,
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-01T10:00:00+05:30'),
    ('01X38K8VDE7ES21WAK9F25JEA7', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01V9E094PTY73D1AVPFVDJ4PMY', true, 'Groceries', 20,
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-01T10:00:00+05:30'),
    ('0127ZJEG6EPCQMCQAM5MAQCCR9', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01DQWYNSVDMV1Y2HWNE9WSSVD4', true, 'Dairy', 30,
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-01T10:00:00+05:30'),
    ('01J8D28X6DKV0VKEHVQ5VFYG08', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01DHQACY2YXS9440JBE55PQEC9', true, 'Meat and Fish', 40,
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-01T10:00:00+05:30'),
    ('01NW44HMNDCN8YD20PNSX3BYZ7', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '017RDX7JC6F8XPF6PXXQ6ABYAK', true, 'Bakery', 50,
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-01T10:00:00+05:30'),
    ('018F018ERKVYFSV6DXZ81CCAJZ', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '018H9MWZJBGSJZNA5YS62P892A', true, 'General Store', 60,
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-01T10:00:00+05:30'),
    ('01VZ0VA2D7VAMGKD4H8XPDW7NZ', '01YNXV3CA9TS3M70MXR8MJH9TA', '01HM1FPVAJ7QF3EMFVF6TRTT8N', true, 'General Store', 10,
     '01Z602EBP9ERSY30BFYGVBJZFP', '2026-10-12T12:00:00+05:30'),
    ('01B13DB3G8KD8D9PZWJ97QF2RX', '01YNXV3CA9TS3M70MXR8MJH9TA', '01V9E094PTY73D1AVPFVDJ4PMY', true, 'Groceries', 20,
     '01Z602EBP9ERSY30BFYGVBJZFP', '2026-10-12T12:00:00+05:30'),
    ('01XZB9AJ1MPF2EFDT3J8FR4PTV', '014A6TTPBQTSF8DM9P94FN94PS', '01HM1FPVAJ7QF3EMFVF6TRTT8N', false, NULL, NULL,
     NULL, NULL)
ON CONFLICT (tenant_id, category_id) DO UPDATE SET
    active = EXCLUDED.active,
    display_name = EXCLUDED.display_name,
    sort_override = EXCLUDED.sort_override;

-- ─────────────────────────────────────────────────────────────────────────────
-- Fees (§5.2, OD-2)
--
-- ₹25 delivery + ₹10 handling per ward per fulfilment mode, which is what every designed
-- checkout shows. The pair is per mode on purpose: on a merchant-delivered order the ₹25
-- stays with the shop (§9.3), so a single "ward fee" column would have been one number
-- pretending to be two rules.
--
-- min_order_paise is 0. No minimum basket was ever decided; screens that show "₹99 minimum"
-- are illustrating a rule the founder has not set, and a fee row is where the rule would go.
--
-- Both modes carry the same amounts today. That is the pilot's state, not a law: an operator
-- overrides one ward at /admin/wards/{id}/fee-rules and the next quote reads the new row.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO ward_fee_rule (id, tenant_id, ward_id, fulfilment_mode,
                           delivery_fee_paise, handling_fee_paise, min_order_paise, active, valid_from)
VALUES
    ('011CKH6BK52JWZ3RSGP01ECQQE', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01NMEEXSEKWHZEY2J6GY8N4D0G', 'PLATFORM', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('0150QDJ100BTX5Q28MCEBQQNCG', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01NMEEXSEKWHZEY2J6GY8N4D0G', 'MERCHANT', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01SRNANFBP5DEGAN2MB5PK4VHC', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '019HSZK7VAPZZC7QG7E328YNPW', 'PLATFORM', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01BDXE2T9M1K1PT5280FQG9TA2', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '019HSZK7VAPZZC7QG7E328YNPW', 'MERCHANT', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01GWY1K0ZHYCF51APSGKHWMAGY', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '017CWB0S01Z0ZZTNWNWHEKG9SX', 'PLATFORM', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01C49ZS6E64J9Q69E3BYDG5EEV', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '017CWB0S01Z0ZZTNWNWHEKG9SX', 'MERCHANT', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01Y9F6YS65MAAMH784EZ5CDK9M', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01PK9PPAHHQZ23FVE8AJ3R4K0Q', 'PLATFORM', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01YR5VH4F7A1KS74VWQ67P9WFQ', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01PK9PPAHHQZ23FVE8AJ3R4K0Q', 'MERCHANT', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('013BM1YAMG7JXG6J1CTZW8T51Z', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01YNWDVVMJ7A26M1P29AP16GAJ', 'PLATFORM', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01PGAFVRVFHQWJV6MWQYY4PDC0', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '01YNWDVVMJ7A26M1P29AP16GAJ', 'MERCHANT', 2500, 1000, 0, true, '2026-09-01T00:00:00+05:30'),
    ('01VE2FA4EGQ75YGJ04JCYXHQP2', '01YNXV3CA9TS3M70MXR8MJH9TA', '01K44E2VGSQFR7PZFE3WHF20X8', 'PLATFORM', 2500, 1000, 0, true, '2026-10-12T00:00:00+05:30'),
    ('01NWS2B33Y99NFAK5QJEPX0ZH7', '01YNXV3CA9TS3M70MXR8MJH9TA', '01K44E2VGSQFR7PZFE3WHF20X8', 'MERCHANT', 2500, 1000, 0, true, '2026-10-12T00:00:00+05:30'),
    ('01ETJY3X8G9XWC2RXHZ8NGVQ7B', '01YNXV3CA9TS3M70MXR8MJH9TA', '017AK6HC5YS6V41ADC1Y8DHZTH', 'PLATFORM', 2500, 1000, 0, true, '2026-10-12T00:00:00+05:30'),
    ('01ZYN0154RMJ05V2JEQGKN70HC', '01YNXV3CA9TS3M70MXR8MJH9TA', '017AK6HC5YS6V41ADC1Y8DHZTH', 'MERCHANT', 2500, 1000, 0, true, '2026-10-12T00:00:00+05:30')
ON CONFLICT (tenant_id, ward_id, fulfilment_mode, valid_from) DO UPDATE SET
    delivery_fee_paise = EXCLUDED.delivery_fee_paise,
    handling_fee_paise = EXCLUDED.handling_fee_paise,
    min_order_paise = EXCLUDED.min_order_paise,
    active = EXCLUDED.active;

-- Barauli's ward has no fee rule. That is deliberate and it is the assertion §10.5.2's
-- ward-coverage screen needs: a suspended city's ward resolves to no current rate, so a quote
-- there fails rather than inventing a number.
