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

-- ─────────────────────────────────────────────────────────────────────────────
-- Shops (§5.3, §10.5.3, §10.5.8)
--
-- The registry's nine shops, with the states the operator screens show rather than a uniform
-- "approved": five live in Siwan, one hidden for a licence problem, one hidden for geography,
-- one still PENDING in draft-city Mashaun, and one suspended with Barauli.
--
-- Gupta Fresh Mart is the busiest Siwan shop and the one every cash-ledger screen names.
-- Anita Dairy delivers itself (SAATH-4849), so its capability is MERCHANT_ONLY and its default
-- mode is MERCHANT — the CHECK in V4 refuses any other pairing. Sharma Bakery's bakery category
-- requires a licence and its photo is invalid, which is why it is hidden rather than rejected:
-- the shop was approved, the visibility decision is separate (§5.4).
--
-- Hours are stored in the same snake_case JSON the settings endpoint writes, per day, with
-- 1 = Monday. Gupta's 8:00–22:00 is the closing time every screen shows.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO merchant (id, tenant_id, code, legal_name, trade_name, category_id,
                      phone, address_text, ward_id, operating_hours_json,
                      state, hidden_reason, fulfilment_capability, default_fulfilment_mode,
                      customer_choice_allowed, self_deliver_zones_json,
                      licence_ref, licence_state, onboarding_completed_at,
                      approved_by, approved_at, created_at, updated_at)
VALUES
    ('N38GBA9P599PBQ3KC0KKQ1YJ2E', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'gupta-fresh-mart',
     'Gupta Fresh Mart', 'Gupta Fresh Mart', '01V9E094PTY73D1AVPFVDJ4PMY',
     '+919400001180', 'Mithan Market, Ward 12, Siwan', '01YNWDVVMJ7A26M1P29AP16GAJ',
     '[{"day":1,"opens_at":"08:00","closes_at":"22:00","closed":false},
       {"day":2,"opens_at":"08:00","closes_at":"22:00","closed":false},
       {"day":3,"opens_at":"08:00","closes_at":"22:00","closed":false},
       {"day":4,"opens_at":"08:00","closes_at":"22:00","closed":false},
       {"day":5,"opens_at":"08:00","closes_at":"22:00","closed":false},
       {"day":6,"opens_at":"08:00","closes_at":"22:00","closed":false},
       {"day":7,"opens_at":"08:00","closes_at":"22:00","closed":false}]',
     'APPROVED', NULL, 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     NULL, 'NOT_SUBMITTED', '2026-09-03T11:00:00+05:30',
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-03T11:00:00+05:30',
     '2026-09-02T16:10:00+05:30', '2026-09-03T11:00:00+05:30'),

    ('WK2QBRKYZFQD4JSJ2H7QVKSWS3', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'mishra-kirana-bhandar',
     'Mishra Kirana Bhandar', 'Mishra Kirana Bhandar', '01HM1FPVAJ7QF3EMFVF6TRTT8N',
     '+919400001181', 'Masjid Road, Ward 04, Siwan', '019HSZK7VAPZZC7QG7E328YNPW',
     '[{"day":1,"opens_at":"07:00","closes_at":"21:30","closed":false},
       {"day":2,"opens_at":"07:00","closes_at":"21:30","closed":false},
       {"day":3,"opens_at":"07:00","closes_at":"21:30","closed":false},
       {"day":4,"opens_at":"07:00","closes_at":"21:30","closed":false},
       {"day":5,"opens_at":"07:00","closes_at":"21:30","closed":false},
       {"day":6,"opens_at":"07:00","closes_at":"21:30","closed":false},
       {"day":7,"opens_at":"07:00","closes_at":"21:30","closed":false}]',
     'APPROVED', NULL, 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     NULL, 'NOT_SUBMITTED', '2026-09-04T10:20:00+05:30',
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-04T10:20:00+05:30',
     '2026-09-03T18:40:00+05:30', '2026-09-04T10:20:00+05:30'),

    ('6DJ31NRMDZ5F5QV7QSDK9C9AFQ', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'siwan-chicken-and-meats',
     'Siwan Chicken and Meats', 'Siwan Chicken and Meats', '01DHQACY2YXS9440JBE55PQEC9',
     '+919400001182', 'Chandbeli Road, Ward 09, Siwan', '01PK9PPAHHQZ23FVE8AJ3R4K0Q',
     '[{"day":1,"opens_at":"08:00","closes_at":"20:30","closed":false},
       {"day":2,"opens_at":"08:00","closes_at":"20:30","closed":false},
       {"day":3,"opens_at":"08:00","closes_at":"20:30","closed":false},
       {"day":4,"opens_at":"08:00","closes_at":"20:30","closed":false},
       {"day":5,"opens_at":"08:00","closes_at":"20:30","closed":false},
       {"day":6,"opens_at":"08:00","closes_at":"20:30","closed":false},
       {"day":7,"opens_at":"08:00","closes_at":"20:30","closed":false}]',
     'APPROVED', NULL, 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     'BR-MEAT-2026-0417', 'VALID', '2026-09-05T09:15:00+05:30',
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-05T09:15:00+05:30',
     '2026-09-04T12:05:00+05:30', '2026-09-05T09:15:00+05:30'),

    ('9NQBYMYNRJ85CTJREARRA7G8F2', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'anita-dairy',
     'Anita Dairy', 'Anita Dairy', '01DQWYNSVDMV1Y2HWNE9WSSVD4',
     '+919400001183', 'Mithan Market, Ward 12, Siwan', '01YNWDVVMJ7A26M1P29AP16GAJ',
     '[{"day":1,"opens_at":"06:30","closes_at":"20:00","closed":false},
       {"day":2,"opens_at":"06:30","closes_at":"20:00","closed":false},
       {"day":3,"opens_at":"06:30","closes_at":"20:00","closed":false},
       {"day":4,"opens_at":"06:30","closes_at":"20:00","closed":false},
       {"day":5,"opens_at":"06:30","closes_at":"20:00","closed":false},
       {"day":6,"opens_at":"06:30","closes_at":"20:00","closed":false},
       {"day":7,"opens_at":"06:30","closes_at":"20:00","closed":false}]',
     'APPROVED', NULL, 'MERCHANT_ONLY', 'MERCHANT', false, '[12]',
     NULL, 'NOT_SUBMITTED', '2026-09-06T08:30:00+05:30',
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-06T08:30:00+05:30',
     '2026-09-04T15:25:00+05:30', '2026-09-06T08:30:00+05:30'),

    ('AMTVQ0PYWQFJYYV17X1Q5R7W5B', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'aashirvaad-general-store',
     'Aashirvaad General Store', 'Aashirvaad General Store', '018H9MWZJBGSJZNA5YS62P892A',
     '+919400001184', 'Ward 01, Aoriyanimath, Siwan', '01NMEEXSEKWHZEY2J6GY8N4D0G',
     '[{"day":1,"opens_at":"07:00","closes_at":"22:30","closed":false},
       {"day":2,"opens_at":"07:00","closes_at":"22:30","closed":false},
       {"day":3,"opens_at":"07:00","closes_at":"22:30","closed":false},
       {"day":4,"opens_at":"07:00","closes_at":"22:30","closed":false},
       {"day":5,"opens_at":"07:00","closes_at":"22:30","closed":false},
       {"day":6,"opens_at":"07:00","closes_at":"22:30","closed":false},
       {"day":7,"opens_at":"07:00","closes_at":"22:30","closed":false}]',
     'APPROVED', NULL, 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     NULL, 'NOT_SUBMITTED', '2026-09-07T13:00:00+05:30',
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-07T13:00:00+05:30',
     '2026-09-06T17:45:00+05:30', '2026-09-07T13:00:00+05:30'),

    ('SJ3MYKT3ZPSC5KZKPR3G892N34', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'sharma-bakery',
     'Sharma Bakery', 'Sharma Bakery', '017RDX7JC6F8XPF6PXXQ6ABYAK',
     '+919400001185', 'Nawada, Ward 07, Siwan', '017CWB0S01Z0ZZTNWNWHEKG9SX',
     '[{"day":1,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":2,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":3,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":4,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":5,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":6,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":7,"opens_at":"07:00","closes_at":"21:00","closed":false}]',
     'APPROVED', 'Licence photo invalid', 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     'BR-BAKE-2026-0091', 'INVALID', '2026-09-08T10:00:00+05:30',
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-08T10:00:00+05:30',
     '2026-09-07T09:20:00+05:30', '2026-09-08T10:00:00+05:30'),

    ('1MQQC2GG3VKPEH4A760X321DBX', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'kumar-provision-store',
     'Kumar Provision Store', 'Kumar Provision Store', '01HM1FPVAJ7QF3EMFVF6TRTT8N',
     '+919400001186', 'Main Road, Siwan', NULL,
     '[{"day":1,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":2,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":3,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":4,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":5,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":6,"opens_at":"07:00","closes_at":"21:00","closed":false},
       {"day":7,"opens_at":"07:00","closes_at":"21:00","closed":false}]',
     'APPROVED', 'Address outside all wards', 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     NULL, 'NOT_SUBMITTED', '2026-09-09T12:00:00+05:30',
     '01D2S4GNTQ916TY67JTWPEN1BT', '2026-09-09T12:00:00+05:30',
     '2026-09-08T14:30:00+05:30', '2026-09-09T12:00:00+05:30'),

    ('GC6BNF3SM6GXKNGJH82081FSCJ', '01YNXV3CA9TS3M70MXR8MJH9TA', 'siwan-dairy-point',
     'Siwan Dairy Point', 'Siwan Dairy Point', '01DQWYNSVDMV1Y2HWNE9WSSVD4',
     '+919400001187', 'Bhitari Road, Ward 01, Mashaun', '01K44E2VGSQFR7PZFE3WHF20X8',
     NULL, 'PENDING', NULL, 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     NULL, 'SUBMITTED', NULL, NULL, NULL,
     '2026-10-20T11:10:00+05:30', '2026-10-20T11:10:00+05:30'),

    ('WTPER5KHDTX945WQZZA88YP3WZ', '014A6TTPBQTSF8DM9P94FN94PS', 'barauli-kirana-hub',
     'Barauli Kirana Hub', 'Barauli Kirana Hub', '01HM1FPVAJ7QF3EMFVF6TRTT8N',
     '+919400001188', 'Barauli Bazar, Ward 01, Barauli', '01PZZ05419GG8ME8QDB2T4ZV2B',
     '[{"day":1,"opens_at":"08:00","closes_at":"20:00","closed":false},
       {"day":2,"opens_at":"08:00","closes_at":"20:00","closed":false},
       {"day":3,"opens_at":"08:00","closes_at":"20:00","closed":false},
       {"day":4,"opens_at":"08:00","closes_at":"20:00","closed":false},
       {"day":5,"opens_at":"08:00","closes_at":"20:00","closed":false},
       {"day":6,"opens_at":"08:00","closes_at":"20:00","closed":false},
       {"day":7,"opens_at":"08:00","closes_at":"20:00","closed":false}]',
     'SUSPENDED', NULL, 'PLATFORM_ONLY', 'PLATFORM', true, NULL,
     NULL, 'NOT_SUBMITTED', '2026-08-22T09:00:00+05:30',
     '01Z602EBP9ERSY30BFYGVBJZFP', '2026-08-22T09:00:00+05:30',
     '2026-08-21T10:40:00+05:30', '2026-08-22T09:00:00+05:30')
ON CONFLICT (tenant_id, code) DO UPDATE SET
    legal_name = EXCLUDED.legal_name,
    trade_name = EXCLUDED.trade_name,
    category_id = EXCLUDED.category_id,
    phone = EXCLUDED.phone,
    address_text = EXCLUDED.address_text,
    ward_id = EXCLUDED.ward_id,
    operating_hours_json = EXCLUDED.operating_hours_json,
    state = EXCLUDED.state,
    hidden_reason = EXCLUDED.hidden_reason,
    fulfilment_capability = EXCLUDED.fulfilment_capability,
    default_fulfilment_mode = EXCLUDED.default_fulfilment_mode,
    customer_choice_allowed = EXCLUDED.customer_choice_allowed,
    self_deliver_zones_json = EXCLUDED.self_deliver_zones_json,
    licence_ref = EXCLUDED.licence_ref,
    licence_state = EXCLUDED.licence_state,
    onboarding_completed_at = EXCLUDED.onboarding_completed_at,
    approved_by = EXCLUDED.approved_by,
    approved_at = EXCLUDED.approved_at;

-- ─────────────────────────────────────────────────────────────────────────────
-- The shelf the demo orders are built from (§5.3, §7)
--
-- Prices are the registry's rupee figures, in paise. The two by-weight lines are why
-- `is_by_weight` exists: chicken is priced per kilogram and the customer sees an estimate
-- until the shop weighs it (§7.5), so `weight_grams` stays NULL and the consent notice comes
-- from the API rather than a stored sentence.
--
-- `stock_unit` is coherent with `unit_label` under §7.1's rule — a "5 kg pack" is counted in
-- packs, so `pc`; loose chicken is counted in `kg`. None of these rows may be edited into a
-- label and a unit that disagree, because the write endpoint would refuse the same pair.
--
-- Sharma Bakery and Kumar Provision Store keep a shelf while hidden: hiding is a visibility
-- decision, not a deletion, and the operator can unhide a shop that still has a catalogue.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO product (id, tenant_id, merchant_id, category_id, code, name, description,
                     price_paise, unit_label, is_by_weight, weight_grams, pack_qty,
                     track_stock, stock_qty, stock_unit, max_qty_per_order,
                     available, active, created_at, updated_at)
VALUES
    ('F3TDJ1FQMY8SXJ0TKH29AB3M3T', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'N38GBA9P599PBQ3KC0KKQ1YJ2E',
     '01V9E094PTY73D1AVPFVDJ4PMY', 'aashirvaad-atta-5kg', 'Aashirvaad Atta',
     'Whole wheat atta, 5 kg pack', 32000, '5 kg pack', false, 5000, 1,
     true, 18, 'pc', 4, true, true,
     '2026-09-03T12:00:00+05:30', '2026-09-03T12:00:00+05:30'),

    ('7ZBVR3Z1P5J8MNVF23CAGXT4ZW', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'N38GBA9P599PBQ3KC0KKQ1YJ2E',
     '01HM1FPVAJ7QF3EMFVF6TRTT8N', 'tata-salt-1kg', 'Tata Salt',
     'Iodised salt, 1 kg pack', 2800, '1 kg pack', false, 1000, 1,
     true, 30, 'pc', 10, true, true,
     '2026-09-03T12:05:00+05:30', '2026-09-03T12:05:00+05:30'),

    ('DRCAYPS3ZK4MDP9NXNA7E2613F', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '6DJ31NRMDZ5F5QV7QSDK9C9AFQ',
     '01DHQACY2YXS9440JBE55PQEC9', 'chicken-curry-cut', 'Chicken (curry cut)',
     'Fresh chicken, cut for curry, sold by weight', 47000, '1 kg', true, NULL, NULL,
     true, 12.500, 'kg', 5, true, true,
     '2026-09-05T10:00:00+05:30', '2026-09-05T10:00:00+05:30'),

    ('05V9EEXG8H50XSYNK199XXMWJA', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '6DJ31NRMDZ5F5QV7QSDK9C9AFQ',
     '01DHQACY2YXS9440JBE55PQEC9', 'eggs-6pc', 'Eggs',
     'Farm eggs, pack of six', 4200, '6 pc', false, NULL, 6,
     true, 40, 'pc', 10, true, true,
     '2026-09-05T10:05:00+05:30', '2026-09-05T10:05:00+05:30'),

    ('19J7AGVXTY2YK8S267X0T0MD9C', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '9NQBYMYNRJ85CTJREARRA7G8F2',
     '01DQWYNSVDMV1Y2HWNE9WSSVD4', 'amul-taaza-500ml', 'Amul Taaza Milk',
     'Toned milk, 500 ml pack', 2900, '500 ml pack', false, 500, 1,
     true, 24, 'pc', 10, true, true,
     '2026-09-06T09:00:00+05:30', '2026-09-06T09:00:00+05:30'),

    ('HFPXN5CEN667K0E954TJKAXM6X', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '9NQBYMYNRJ85CTJREARRA7G8F2',
     '01DQWYNSVDMV1Y2HWNE9WSSVD4', 'curd-500g', 'Fresh Curd',
     'Set curd, 500 g pack', 4500, '500 g pack', false, 500, 1,
     true, 12, 'pc', 6, true, true,
     '2026-09-06T09:05:00+05:30', '2026-09-06T09:05:00+05:30'),

    ('CFMP2B8QNJMAH4MKYWC8KMWFXG', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '9NQBYMYNRJ85CTJREARRA7G8F2',
     '01DQWYNSVDMV1Y2HWNE9WSSVD4', 'paneer-200g', 'Paneer',
     'Fresh paneer, 200 g pack', 9000, '200 g pack', false, 200, 1,
     true, 8, 'pc', 4, true, true,
     '2026-09-06T09:10:00+05:30', '2026-09-06T09:10:00+05:30'),

    ('8WD1NJ3H8GGER3VQ4B9MXA5JRY', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'WK2QBRKYZFQD4JSJ2H7QVKSWS3',
     '01HM1FPVAJ7QF3EMFVF6TRTT8N', 'sona-masoori-rice-5kg', 'Sona Masoori Rice',
     'Everyday rice, 5 kg pack', 42000, '5 kg pack', false, 5000, 1,
     true, 10, 'pc', 3, true, true,
     '2026-09-04T11:00:00+05:30', '2026-09-04T11:00:00+05:30'),

    ('Q5368662BXXBP7V86C3AVRQ1PS', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'WK2QBRKYZFQD4JSJ2H7QVKSWS3',
     '01HM1FPVAJ7QF3EMFVF6TRTT8N', 'fortune-mustard-oil-1l', 'Fortune Mustard Oil',
     'Mustard oil, 1 L bottle', 14500, '1 L bottle', false, NULL, 1,
     true, 14, 'L', 4, true, true,
     '2026-09-04T11:05:00+05:30', '2026-09-04T11:05:00+05:30'),

    ('5D7HEYD51GRGEMSB84Z6D5VQAQ', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'AMTVQ0PYWQFJYYV17X1Q5R7W5B',
     '018H9MWZJBGSJZNA5YS62P892A', 'parle-g-biscuits', 'Parle-G Biscuits',
     'Glucose biscuits, 250 g pack', 3000, '250 g pack', false, 250, 1,
     true, 60, 'pc', 12, true, true,
     '2026-09-07T14:00:00+05:30', '2026-09-07T14:00:00+05:30'),

    ('3Z516TRD2QQXR0KGJ7EJCM3HBJ', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'AMTVQ0PYWQFJYYV17X1Q5R7W5B',
     '018H9MWZJBGSJZNA5YS62P892A', 'lux-soap-bar', 'Lux Soap',
     'Bathing soap, 100 g bar', 4500, '100 g bar', false, 100, 1,
     true, 36, 'pc', 12, true, true,
     '2026-09-07T14:05:00+05:30', '2026-09-07T14:05:00+05:30'),

    ('P331R7S327XZ7QRB25AQJ3WHEX', '01NGD9Y4JMQ2RBNVVKXX7J04DM', 'SJ3MYKT3ZPSC5KZKPR3G892N34',
     '017RDX7JC6F8XPF6PXXQ6ABYAK', 'milk-bread-400g', 'Milk Bread',
     'Soft milk bread, 400 g pack', 4000, '400 g pack', false, 400, 1,
     true, 6, 'pc', 4, true, true,
     '2026-09-08T11:00:00+05:30', '2026-09-08T11:00:00+05:30'),

    ('1GVSZHRXZ3GNYAMVRAEZW3B0WY', '01NGD9Y4JMQ2RBNVVKXX7J04DM', '1MQQC2GG3VKPEH4A760X321DBX',
     '01HM1FPVAJ7QF3EMFVF6TRTT8N', 'maggi-noodles-70g', 'Maggi Noodles',
     'Instant noodles, 70 g pack', 1400, '70 g pack', false, 70, 1,
     true, 48, 'pc', 12, true, true,
     '2026-09-09T13:00:00+05:30', '2026-09-09T13:00:00+05:30')
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    category_id = EXCLUDED.category_id,
    price_paise = EXCLUDED.price_paise,
    unit_label = EXCLUDED.unit_label,
    is_by_weight = EXCLUDED.is_by_weight,
    weight_grams = EXCLUDED.weight_grams,
    pack_qty = EXCLUDED.pack_qty,
    track_stock = EXCLUDED.track_stock,
    stock_qty = EXCLUDED.stock_qty,
    stock_unit = EXCLUDED.stock_unit,
    max_qty_per_order = EXCLUDED.max_qty_per_order,
    available = EXCLUDED.available,
    active = EXCLUDED.active;

-- No merchant owner account is seeded. A MERCHANT_STAFF row needs email, phone and — in
-- practice — a password the owner set by accepting an invitation (§12.3), and a committed
-- password hash is the one thing §14 forbids. The integration tests create a staff account
-- with a hash at run time; the demo signs in as a customer or an operator.
