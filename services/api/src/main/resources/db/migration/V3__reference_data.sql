-- V3__reference_data.sql
--
-- Rows the product cannot run without. They are in a versioned migration and not in the
-- pilot seed because a deployment that lacked them would be a deployment with no roles, no
-- taxonomy and no kill switches — §12.1's role list, §4.1's global taxonomy and PRD §12's
-- switch mechanism are all decisions, not demo data.
--
-- The pilot's own rows (three cities, their wards, activations, fees, and the fictional
-- people) live in db/seed/R__pilot_reference_data.sql, which only the `dev` and `test`
-- Flyway locations read. Same reason PRD §10 separates seed data from production.
--
-- Permissions are deliberately absent. Every permission is a capability an endpoint checks,
-- so it arrives in the migration of the module that implements that endpoint — the same
-- discipline §4.6 applies to job names. A complete matrix with nothing enforcing it would
-- let `GET /me` promise `ORDER_APPROVE` two days before the transition exists.

-- ─────────────────────────────────────────────────────────────────────────────
-- Roles (§12.1)
--
-- A principal's access is (role, scope) — user_role_grant in V2 — and the role carries the
-- permissions. Six keys, no more: MERCHANT_OWNER and MERCHANT_STAFF are one role until the
-- merchant module needs to distinguish who can change a shop's settings from who can accept
-- an order, and inventing the split now would be a permission list nobody has an endpoint
-- for.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO role (id, key, description)
VALUES
    -- Orders in from the app, no city data beyond their own basket.
    ('01KAC4ST0J77JV2VABWQD4TEY9', 'CUSTOMER',       'Places and tracks own orders'),
    -- Jobs offered to them and the cash they hold (§6.4, §9.3 A).
    ('01C9DQMG5A338RR3G0M3VJSVCW', 'RIDER',          'Delivery partner holding jobs and cash'),
    -- One shop: catalogue, stock, order transitions (§13.7).
    ('0147N54N78DAFBP95FY7DTWQ9R', 'MERCHANT_STAFF', 'Shop staff within one merchant'),
    -- One city: queue, riders, cash, settlement drafts, support (§6.5, §9.5).
    ('01530PKV1M1GJKFZGW7YWK2CN2', 'OPERATOR',     'City operator within one tenant'),
    -- Read-only support seat. §12.1 lists it; it is granted per ticket, not per person.
    ('01VQ9PWGJQENY0R60T07Y9GVXC', 'SUPPORT',      'Support read access, scoped by grant'),
    -- The platform. The only tenantless role V2's grant trigger will accept (§4.4).
    ('01JCAY88EQV22QTDTRKCX6Z5PA', 'SUPER_ADMIN',  'Platform-wide configuration and cross-city read');

COMMENT ON TABLE role IS
    'Closed list by design (§12.1). A new role is a migration, and the permissions it holds '
    'arrive with the module that enforces them.';

-- ─────────────────────────────────────────────────────────────────────────────
-- The global category taxonomy (§4.1)
--
-- Global, not per city: "Kirana" means the same thing in Siwan and Mashaun, and a fork per
-- tenant is how v0.1 ended with nine spellings of one category. What a city owns is whether
-- the node is switched on and what it is called on its storefront (tenant_category).
--
-- Depth is one here. A parent node arrives with the module that needs the level, and
-- `category.parent_id` already allows it.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO category (id, slug, name, requires_licence, active, sort)
VALUES
    ('01HM1FPVAJ7QF3EMFVF6TRTT8N', 'kirana',        'Kirana / General Grocery', false, true, 10),
    ('01V9E094PTY73D1AVPFVDJ4PMY', 'groceries',     'Groceries',                false, true, 20),
    ('01DQWYNSVDMV1Y2HWNE9WSSVD4', 'dairy',         'Dairy',                    false, true, 30),
    -- PRD §15: a butcher and a fish seller are the pilot's clearest licence case, so the
    -- node carries the requirement and the merchant module reads it rather than hard-coding.
    ('01DHQACY2YXS9440JBE55PQEC9', 'meat-fish',     'Meat and Fish',            true,  true, 40),
    ('017RDX7JC6F8XPF6PXXQ6ABYAK', 'bakery',        'Bakery',                   true,  true, 50),
    ('018H9MWZJBGSJZNA5YS62P892A', 'general-store', 'General Store',            false, true, 60);

COMMENT ON COLUMN category.requires_licence IS
    'The node, not a code branch, says whether an applicant must show a licence (PRD §15). '
    'MERCHANT_APPROVE reads it; LICENCE_INCOMPLETE is the refusal.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Kill switches (PRD §12)
--
-- Global defaults with tenant_id NULL; a city row overrides one of these later. The values
-- are the honest state of the build, not a wish: each capability PRD §12 and the design
-- describe as not-yet-built is off, so a half-finished feature cannot be reached by a
-- client that guesses the endpoint exists.
--
-- depends_on stays NULL. A dependency between two switches is a claim about the code that
-- implements them, and it will be recorded when the pair exists.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO feature_flag (id, key, tenant_id, enabled, depends_on, description)
VALUES
    ('018JRJ6K4D8DZ7F8TP4NNS44N9', 'online_payment', NULL, false, NULL,
     'PRD §12: no gateway, card, UPI or wallet is integrated. COD and recorded cash only (D2).'),
    ('01DQ8NS0RFRQ9XEVK4ZH7VVWFC', 'auto_dispatch', NULL, false, NULL,
     '§6.4: assignment is an operator action (D10). No timer offers a job to a rider.'),
    ('01DNV7PDS6PDXXGJBQB0VXWGD0', 'proof_of_delivery_media', NULL, false, NULL,
     '§6.4: photo or signature proof of delivery is not built; completion is an event, not an artefact.'),
    ('01B3ASN0S8JY6DPCW12WWW8KZ3', 'in_app_chat', NULL, false, NULL,
     '§11.4: contact is a masked phone number revealed at the right transition, not a message thread.'),
    -- The one flag that is on: §9.3 A treats rider-held cash as the default, and a pilot
    -- without it cannot pay anyone.
    ('01HTQA7P44MPEZPB73DAKSZTSG', 'cash_on_delivery', NULL, true, NULL,
     '§9.3: riders collect cash at the door. Turning this off removes the custody ledger.');

COMMENT ON TABLE feature_flag IS
    'PRD §12 kill switches. Reading one is cheap enough to do per request; writing one is a '
    'FLAG_WRITE act, step-up gated and audited (§10.5.9).';
