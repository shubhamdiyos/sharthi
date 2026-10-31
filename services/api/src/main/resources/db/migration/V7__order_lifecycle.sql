-- ─────────────────────────────────────────────────────────────────────────────
-- Day 2A: the order lifecycle the shop runs, and the one customer action it needs.
--
-- Nothing here creates a table. V5 already wrote the whole order spine — the status
-- column, the transition trigger, order_event, the WEIGHT scope of price_quote — so
-- what the lifecycle actually needs is five narrower things: a consent request that
-- cannot expire, the closed reason vocabularies §6.3 names but did not enumerate, an
-- index for the queue it pages, a timeline that admits a milestone as well as a
-- transition, and the permissions and the audit actions whose endpoints arrive in this
-- module.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. The weight quote has no expiry ────────────────────────────────────────
-- §6.5 forbids a price-confirmation countdown, and §10.6.2 says the order waits at
-- PRICE_CONFIRMATION_PENDING indefinitely. Those two sentences are only both true if
-- the quote carrying the new total outlives the cart TTL: an ACTIVE quote that expired
-- on read would leave the order with no consent a customer could give and no way out
-- except the shop cancelling it. Expiry is therefore a property of a cart quote, not of
-- every quote.
--
-- expires_at > issued_at is left in place: a quote that does expire cannot say so by
-- putting a time in the past, because NULL is the only no-expiry value and a CHECK
-- passes on NULL rather than failing.
ALTER TABLE price_quote
    ALTER COLUMN expires_at DROP NOT NULL;

COMMENT ON COLUMN price_quote.expires_at IS
    'NULL means this quote does not expire (§6.5). A CART quote always has a time — '
    'price_quote_expiry_after_issue stops a caller encoding "never" as a past instant.';

-- ── 2. The closed reason vocabularies (§6.3, §6.4) ───────────────────────────
-- §6.3 requires reject_reason "from a closed enum" and §6.4 enumerates the delivery
-- failure set. §6.3 names the reject enum but does not list its members, so this
-- migration is the list: adding a member is a migration plus review, exactly as §13.2
-- requires of audit_event.action, never a string a UI invents.
--
-- cancel_reason is deliberately unconstrained. It carries the customer's free-text note
-- at T5 as well as the shop's code at T9b/T10, and a CHECK that made the column an enum
-- would refuse the customer what §10.5.5 invites them to write.

ALTER TABLE "order" ADD CONSTRAINT order_reject_reason CHECK (
    reject_reason IS NULL OR reject_reason IN (
        'OUT_OF_STOCK', 'SHOP_CLOSED', 'AREA_NOT_SERVED', 'SUSPICIOUS_ORDER', 'OTHER'
    )
);

ALTER TABLE "order" ADD CONSTRAINT order_fail_reason CHECK (
    fail_reason IS NULL OR fail_reason IN (
        'CUSTOMER_UNREACHABLE', 'WRONG_ADDRESS', 'SHOP_CLOSED', 'CUSTOMER_REFUSED',
        'DAMAGED_IN_TRANSIT', 'CASH_DISPUTE', 'SAFETY', 'OTHER'
    )
);

-- ── 3. The merchant queue is paged, not scanned (§10.3) ──────────────────────
-- order_merchant_status stops at placed_at DESC. Keyset paging needs the id tiebreaker
-- in the index or every page fetch sorts the rows the cursor already resolved, so the
-- queue gets the exact column order its WHERE and ORDER BY use.
DROP INDEX order_merchant_status;
CREATE INDEX order_merchant_queue ON "order" (tenant_id, merchant_id, status, placed_at DESC, id DESC);

-- ── 4. The timeline holds milestones, not only transitions (§6.2, T13) ───────
-- order_event re-uses §6.3's transition function as a CHECK on its own status pair, which
-- left V5 able to list exactly one self-edge — READY_FOR_PICKUP -> READY_FOR_PICKUP, the
-- job activity T13 names. §6.2's reselect and a second weigh both write a timeline row at
-- the status the order already holds, and OrderTrail writes every milestone as a
-- FULFILMENT_PROGRESS row. The pair list is therefore the wrong place for them: it is
-- §6.3's transition table, and a milestone is not a transition. It is admitted by kind
-- instead, which is also what order_event_self_edge already requires of a self-pair.
--
-- The status column's own guard is untouched. saathi_order_transition_guard fires only on
-- UPDATE OF status and refuses nothing that does not actually move, so a milestone still
-- cannot smuggle a status change past §6.3.
ALTER TABLE order_event DROP CONSTRAINT order_event_transition;
ALTER TABLE order_event ADD CONSTRAINT order_event_transition CHECK (
    (from_status = to_status AND kind = 'FULFILMENT_PROGRESS')
    OR saathi_order_transition_allowed (from_status, to_status)
);

-- ── 5. Permissions for this module's endpoints (§11.1, §11.2) ────────────────
-- ORDER_DELIVERED_SELF, JOB_HANDOVER_CONFIRM and every ORDER_*_ANY key stay out: they
-- arrive with the accrual and operator endpoints that enforce them, not before.
INSERT INTO permission (id, key, description)
VALUES
    ('01M67CK0P3W9CRHGGQRGQMGQ5T', 'ORDER_READ_MERCHANT',        'Read the shop''s own order queue'),
    ('01M67CK0P3XNEF8VFV555Y11WB', 'ORDER_ACCEPT',               'T3: accept an incoming order'),
    ('01M67CK0P3TV0ZCA546N8PRNX8', 'ORDER_REJECT',               'T4: reject an incoming order with a reason'),
    ('01M67CK0P3V01K264FYS4SQJR7', 'ORDER_PREPARE',              'T6: start preparing an accepted order'),
    ('01M67CK0P3RG2S6A26DEWZA533', 'ORDER_MARK_READY',           'T11: hand a prepared order to platform delivery'),
    ('01M67CK0P3VA9TYXH6ADXW3KB4', 'ORDER_WEIGH',                'T7: record weighed quantities and ask the customer to confirm the total'),
    ('01M67CK0P3T912EPFMBX4VXFPD', 'ORDER_DISPATCH_SELF',        'T12/T18b: send the shop''s own rider out'),
    ('01M67CK0P3EDBZ7EDYB16FSCR3', 'ORDER_FAIL_SELF',            'T17: a self-delivery attempt failed'),
    ('01M67CK0P32ZVB4RPDEDRYD2E7', 'ORDER_CANCEL_AFTER_ACCEPT',  'T9b/T10: cancel an order the shop already accepted'),
    ('01M67CK0P3NCACYKFYHTYMH4EB', 'ORDER_FULFILMENT_RESELECT',  '§6.2: change the delivery mode before it locks'),
    ('01M67CK0P3SSN2VT6VQHZ90Y60', 'ORDER_CONFIRM_PRICE',        'T8: accept the weighed total'),
    ('01M67CK0P3G9SFCSS6R6K375JX', 'ORDER_DECLINE_PRICE',        'T9: decline the weighed total');

-- MERCHANT_STAFF: the whole shop-side machine (§11.2).
INSERT INTO role_permission (role_id, permission_id)
SELECT '0147N54N78DAFBP95FY7DTWQ9R', id
  FROM permission
 WHERE key IN ('ORDER_READ_MERCHANT', 'ORDER_ACCEPT', 'ORDER_REJECT', 'ORDER_PREPARE',
               'ORDER_MARK_READY', 'ORDER_WEIGH', 'ORDER_DISPATCH_SELF', 'ORDER_FAIL_SELF',
               'ORDER_CANCEL_AFTER_ACCEPT', 'ORDER_FULFILMENT_RESELECT');

-- CUSTOMER: the two halves of the consent request the shop raises by weighing (§10.6.2).
-- Decline is a separate key rather than a flag on ORDER_CANCEL_OWN because §6.3 gives the
-- two edges different side effects — a decline supersedes the weight quote, a cancel does
-- not — and one permission that covers both would let a caller take the wrong branch.
INSERT INTO role_permission (role_id, permission_id)
SELECT '01KAC4ST0J77JV2VABWQD4TEY9', id
  FROM permission
 WHERE key IN ('ORDER_CONFIRM_PRICE', 'ORDER_DECLINE_PRICE');

-- ── 6. The audit action list is closed where the document says it is (§13.2) ─
-- §13.2 declares `action` "a closed enum with a DB CHECK constraint — adding an action is
-- a migration plus review, not a string an application layer can invent", and V1 built the
-- table without one. This is the module that makes the claim true: the lifecycle writes
-- STATE_TRANSITION, WEIGHED, QUOTE_ACCEPTED, QUOTE_DECLINED and FULFILMENT_MODE_CHANGED,
-- and a reason code that only the application remembers is a convention, not a control.
--
-- The list is §13.2's table verbatim, all eight domains, so the delivery, money and support
-- rows are already legal when those modules arrive — and a module cannot invent a near
-- synonym for a name that exists.
--
-- One consequence worth naming: §6.3's T8 calls its audit row PRICE_CONSENT, which is not a
-- member of §13.2's list. The taxonomy wins, because §13.2 declares itself closed and the
-- row it describes is a quote the customer accepted (QUOTE_ACCEPTED).
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action CHECK (
    action IN (
        -- auth
        'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGOUT', 'OTP_ISSUED', 'OTP_VERIFY_FAILED',
        'PASSWORD_RESET', 'SESSION_REVOKED', 'STEP_UP_ISSUED', 'INVITATION_SENT',
        'INVITATION_ACCEPTED', 'INVITATION_REVOKED', 'GRANT_ADDED', 'GRANT_REVOKED',
        'USER_DEACTIVATED', 'USER_FORCE_LOGOUT', 'PHONE_CHANGED',
        -- tenancy
        'TENANT_CREATED', 'TENANT_STATE_CHANGED', 'WARD_CREATED', 'WARD_UPDATED',
        'WARD_FEE_RULE_CHANGED', 'CATEGORY_ACTIVATED', 'CATEGORY_DEACTIVATED', 'FLAG_CHANGED',
        'TENANT_DATA_ACCESS_GRANTED', 'IMPERSONATION_STARTED', 'IMPERSONATION_ENDED',
        -- merchant
        'MERCHANT_APPLIED', 'LICENCE_STATE_CHANGED', 'MERCHANT_APPROVED', 'MERCHANT_REJECTED',
        'MERCHANT_SUSPENDED', 'MERCHANT_REINSTATED', 'SHOP_VISIBILITY_CHANGED',
        'FULFILMENT_MODE_CONFIG_CHANGED',
        -- catalogue
        'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRICE_CHANGED', 'AVAILABILITY_CHANGED',
        'STOCK_ADJUSTED', 'PRODUCT_DEACTIVATED', 'MEDIA_UPLOADED',
        -- order
        'ORDER_CREATED', 'STATE_TRANSITION', 'QUOTE_ISSUED', 'QUOTE_ACCEPTED', 'QUOTE_DECLINED',
        'QUOTE_SUPERSEDED', 'WEIGHED', 'FULFILMENT_MODE_CHANGED', 'TERMS_ACCEPTED',
        'OFFLINE_PAYMENT_RECORDED', 'PII_REVEALED',
        -- delivery
        'JOB_OFFERED', 'JOB_ACCEPTED', 'JOB_DECLINED', 'JOB_COLLECTED', 'JOB_COMPLETED',
        'JOB_FAILED', 'JOB_RETURNED_TO_SHOP', 'JOB_CANCELLED_BY_OPERATOR', 'ASSIGN_OVERRIDE',
        'RIDER_LIMIT_CHANGED', 'RIDER_AVAILABILITY_CHANGED', 'HANDOVER_CONFIRMED',
        -- money
        'CUSTODY_ENTRY_CREATED', 'PAYOUT_TO_MERCHANT', 'SETTLEMENT_BATCH_CREATED',
        'SETTLEMENT_BATCH_APPROVED', 'SETTLEMENT_BATCH_VOIDED', 'REFUND_RECORDED',
        'SHORTFALL_WRITTEN_OFF', 'CREDIT_ISSUED', 'EXCEPTION_OPENED', 'EXCEPTION_RESOLVED',
        'RATE_RULE_WRITE',
        -- support
        'TICKET_OPENED', 'TICKET_ACKNOWLEDGED', 'TICKET_RESOLVED', 'TICKET_CLOSED'
    )
);

