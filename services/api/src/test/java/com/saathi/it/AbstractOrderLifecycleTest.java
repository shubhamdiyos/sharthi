package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.identity.security.PasswordVault;
import com.saathi.shared.id.Ulid;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fixtures for the order-lifecycle suites: a shop desk that can act on an order, a customer who owns
 * one, and the pilot rows both need.
 *
 * <p>Two suites need exactly this — §6.3's shop edges and §10.6.2's consent are the same order seen
 * from both ends — and a lifecycle order cannot be built by any shorter route: it takes a sign-in on
 * each side and a placement in between.
 *
 * <p>No merchant staff account is seeded, because a committed password hash is the dev credential §14
 * forbids, so each shop's desk is created per test and deleted afterwards — the arrangement
 * {@code MerchantCatalogIT} uses for the same reason. Every seed row a test moves (a price, a stock
 * level, a shop's fulfilment capability, a ward's fee rule) is put back, because the seed is also the
 * fixture of every other suite here. Orders are deliberately left behind: the board is asserted by the
 * code under test rather than by the whole page, so a leftover row from another suite changes nothing.
 */
abstract class AbstractOrderLifecycleTest extends AbstractIntegrationTest {

    protected static final String SIWAN = "siwan";
    protected static final int WARD = 12;
    protected static final String SIWAN_TENANT = "01NGD9Y4JMQ2RBNVVKXX7J04DM";
    protected static final String WARD_12 = "01YNWDVVMJ7A26M1P29AP16GAJ";
    protected static final String GUPTA = "N38GBA9P599PBQ3KC0KKQ1YJ2E";
    protected static final String POULTRY_SHOP = "6DJ31NRMDZ5F5QV7QSDK9C9AFQ";
    protected static final String ANIL = "01Z602EBP9ERSY30BFYGVBJZFP";
    protected static final String MERCHANT_STAFF_ROLE = "0147N54N78DAFBP95FY7DTWQ9R";

    protected static final String SALT = "tata-salt-1kg";
    protected static final String CHICKEN = "chicken-curry-cut";

    /** ₹470/kg × 1 kg, plus Siwan's ₹25 delivery and ₹10 handling. */
    protected static final long CHICKEN_PLACED_TOTAL = 50_500L;
    /** The same bag at 0.98 kg. */
    protected static final long CHICKEN_WEIGHED_GOODS = 46_060L;
    protected static final long CHICKEN_WEIGHED_TOTAL = 49_560L;

    /** ₹28 × 2, plus the same two fees. */
    protected static final long SALT_TOTAL = 9_100L;

    private static final String PASSWORD = "shop-counter-2026";
    private static final long RUN_SALT = ThreadLocalRandom.current().nextInt(0, 90_000_000);
    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong();

    @Autowired
    private RecordingOtpProvider otp;

    @Autowired
    private PasswordVault vault;

    @Autowired
    private Clock clock;

    private final List<String> staffAccounts = new ArrayList<>();
    private Map<String, String> guptaDesk;
    private Map<String, String> poultryDesk;

    @AfterEach
    void putThePilotBack() {
        jdbc.update("UPDATE product SET price_paise = 2800, stock_qty = 30, reserved_qty = 0 "
            + "WHERE code = ?", SALT);
        jdbc.update("UPDATE product SET price_paise = 47000, stock_qty = 12.500, reserved_qty = 0 "
            + "WHERE code = ?", CHICKEN);
        jdbc.update("UPDATE merchant SET fulfilment_capability = 'PLATFORM_ONLY', "
            + "default_fulfilment_mode = 'PLATFORM', customer_choice_allowed = true WHERE id = ?", GUPTA);
        jdbc.update("UPDATE ward_fee_rule SET delivery_fee_paise = 2500, handling_fee_paise = 1000 "
            + "WHERE tenant_id = ? AND ward_id = ?", SIWAN_TENANT, WARD_12);
        for (String accountId : staffAccounts) {
            jdbc.update("DELETE FROM user_session WHERE user_account_id = ?", accountId);
            jdbc.update("DELETE FROM user_role_grant WHERE user_account_id = ?", accountId);
            jdbc.update("DELETE FROM user_account WHERE id = ?", accountId);
        }
        staffAccounts.clear();
        guptaDesk = null;
        poultryDesk = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // shop desks
    // ─────────────────────────────────────────────────────────────────────────

    /** The shop a session acts for, named by nothing but its own sign-in (§10.7). */
    protected Map<String, String> guptaDesk() {
        if (guptaDesk == null) {
            guptaDesk = shopDesk(GUPTA, "gupta.desk@saathi.example", "+919400009101");
        }
        return guptaDesk;
    }

    /** The by-weight shop, whose desk is the only one that can reach T7. */
    protected Map<String, String> poultryDesk() {
        if (poultryDesk == null) {
            poultryDesk = shopDesk(POULTRY_SHOP, "poultry.desk@saathi.example", "+919400009102");
        }
        return poultryDesk;
    }

    /**
     * A shop that can drive its own van as well as use the platform's. The pilot ships no such shop —
     * every Siwan merchant is one capability or the other — and §6.2's reselect is only interesting
     * between two modes one shop can actually take, so the capability is switched for the test and
     * put back afterwards.
     */
    protected Map<String, String> guptaThatDeliversBoth() {
        jdbc.update("UPDATE merchant SET fulfilment_capability = 'BOTH', customer_choice_allowed = true "
            + "WHERE id = ?", GUPTA);
        return guptaDesk();
    }

    private Map<String, String> shopDesk(String merchantId, String email, String phone) {
        removeStaffFor(email);
        String accountId = Ulid.next(clock);
        staffAccounts.add(accountId);
        jdbc.update("INSERT INTO user_account (id, kind, phone, email, display_name, state, "
                + "password_hash, created_at) VALUES (?, 'MERCHANT_STAFF', ?, ?, ?, 'ACTIVE', ?, now())",
            accountId, phone, email, "Shop desk", vault.hash(PASSWORD));
        jdbc.update("INSERT INTO user_role_grant (id, user_account_id, role_id, tenant_id, merchant_id, "
                + "granted_by, reason, granted_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
            Ulid.next(clock), accountId, MERCHANT_STAFF_ROLE, SIWAN_TENANT, merchantId, ANIL,
            "Test: the shop desk for the lifecycle suite");
        retireOtpHistoryFor(phone);

        HttpResponse<String> first = post("/api/v1/auth/login",
            "{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, PASSWORD));
        String challengeId = error(first).get("details").get("challenge_id").stringValue();
        HttpResponse<String> signedIn = post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s", "otp_challenge_id": "%s", "otp_code": "%s"}"""
            .formatted(email, PASSWORD, challengeId, otp.codeFor(phone)));
        assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);
        assertThat(body(signedIn).get("permissions").valueStream().map(JsonNode::stringValue))
            .contains("ORDER_ACCEPT", "ORDER_WEIGH", "ORDER_FULFILMENT_RESELECT", "ORDER_DISPATCH_SELF");
        return asBrowser(signedIn);
    }

    private void removeStaffFor(String email) {
        jdbc.update("DELETE FROM user_session WHERE user_account_id IN "
            + "(SELECT id FROM user_account WHERE email = ?)", email);
        jdbc.update("DELETE FROM user_role_grant WHERE user_account_id IN "
            + "(SELECT id FROM user_account WHERE email = ?)", email);
        jdbc.update("DELETE FROM user_account WHERE email = ?", email);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // customers and placements
    // ─────────────────────────────────────────────────────────────────────────

    protected record Shopper(Map<String, String> browser, String phone) {
    }

    /** Every test shops as a number it invents, so no test inherits another's cart, quote or order. */
    protected Shopper shopper() {
        return signInCustomer(freshPhone());
    }

    /** The customer an order belongs to, signed in again so a test can answer as them. */
    protected Shopper shopperOf(String code) {
        Map<String, Object> owner = jdbc.queryForMap("""
            SELECT ua.phone FROM "order" o JOIN user_account ua ON ua.id = o.customer_id
             WHERE o.code = ?""", code);
        return signInCustomer((String) owner.get("phone"));
    }

    private Shopper signInCustomer(String phone) {
        retireOtpHistoryFor(phone);
        String challengeId = body(post("/api/v1/auth/otp/request", json("phone", phone)))
            .get("challenge_id").stringValue();
        HttpResponse<String> signedIn = post("/api/v1/auth/otp/verify", """
            {"phone": "%s", "challenge_id": "%s", "code": "%s"}"""
            .formatted(phone, challengeId, otp.codeFor(phone)));
        assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);

        HttpResponse<String> bound = put("/api/v1/me/ward", asBrowser(signedIn), """
            {"city_code": "%s", "ward_no": %d}""".formatted(SIWAN, WARD));
        assertThat(bound.statusCode()).isEqualTo(200);
        return new Shopper(asBrowser(signedIn), phone);
    }

    /** Adds one line, quotes it and places it, answering the order code. */
    protected String place(Shopper shopper, String productCode, String qty) {
        HttpResponse<String> added = post("/api/v1/cart/lines", shopper.browser(), """
            {"product_code": "%s", "qty": %s}""".formatted(productCode, qty));
        assertThat(added.statusCode()).as(added.body()).isEqualTo(200);

        JsonNode quote = body(post("/api/v1/quotes", shopper.browser(), "{}")).get("quote");
        Map<String, String> headers = new HashMap<>(shopper.browser());
        headers.put("Idempotency-Key", "it-" + java.util.UUID.randomUUID());
        HttpResponse<String> placed = post("/api/v1/orders", headers, """
            {"quote_id": "%s", "quote_hash": "%s", "ward_no": %d,
             "address": {"line1": "12 Mithan Market", "pincode": "841226"},
             "contact_phone": "%s"}"""
            .formatted(quote.get("id").stringValue(), quote.get("quote_hash").stringValue(),
                WARD, shopper.phone()));
        assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
        return body(placed).get("order").get("code").stringValue();
    }

    /** An accepted order, which is the earliest state any §6.3 shop edge other than T3/T4 can take. */
    protected String acceptedOrder(String productCode, String qty, Map<String, String> shop) {
        String code = place(shopper(), productCode, qty);
        accept(code, shop);
        return code;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // the shop's actions
    // ─────────────────────────────────────────────────────────────────────────

    protected HttpResponse<String> accept(String code, Map<String, String> shop) {
        HttpResponse<String> response = post("/api/v1/m/orders/" + code + "/accept", shop, "{}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response;
    }

    protected HttpResponse<String> reselect(String code, Map<String, String> shop, String mode,
                                            String reason) {
        String reasonField = reason == null ? "" : ", \"reason\": \"" + reason + "\"";
        return post("/api/v1/m/orders/" + code + "/fulfilment-mode", shop,
            "{\"fulfilment_mode\": \"" + mode + "\"" + reasonField + "}");
    }

    /** T7 as the shop sends it: the line's own id, and the shop's words about the scales. */
    protected HttpResponse<String> weigh(String code, Map<String, String> shop, String qtyWeighed) {
        return post("/api/v1/m/orders/" + code + "/weigh", shop, """
            {"lines": [{"order_line_id": "%s", "qty_weighed": %s, "unit": "kg"}], "note": "%s kg"}"""
            .formatted(lineOf(code), qtyWeighed, qtyWeighed));
    }

    /**
     * The order's first line, by id. The scales address a line of the order and never a product of the
     * catalogue (§10.6.3), so a test cannot assert a weigh without naming one.
     */
    protected String lineOf(String code) {
        return jdbc.queryForObject("""
            SELECT ol.id FROM order_line ol JOIN "order" o ON o.id = ol.order_id
             WHERE o.code = ? ORDER BY ol.id LIMIT 1""", String.class, code);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // evidence
    // ─────────────────────────────────────────────────────────────────────────

    protected int orderEvents(String code) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM order_event e JOIN "order" o ON o.id = e.order_id
             WHERE o.code = ?""", Integer.class, code);
        return count == null ? 0 : count;
    }

    protected int auditRows(String code, String action) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM audit_event a JOIN "order" o ON o.id = a.entity_id
             WHERE o.code = ? AND a.action = ?""", Integer.class, code, action);
        return count == null ? 0 : count;
    }

    protected int outbox(String code, String eventType) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM outbox_event x JOIN "order" o ON o.id = x.aggregate_id
             WHERE o.code = ? AND x.event_type = ?""", Integer.class, code, eventType);
        return count == null ? 0 : count;
    }

    protected int activeQuotes(String code) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM price_quote q JOIN "order" o ON o.id = q.order_id
             WHERE o.code = ? AND q.state = 'ACTIVE'""", Integer.class, code);
        return count == null ? 0 : count;
    }

    protected Map<String, Object> orderRow(String code) {
        return jdbc.queryForMap("SELECT * FROM \"order\" WHERE code = ?", code);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // assertions on the wire
    // ─────────────────────────────────────────────────────────────────────────

    protected static List<String> actions(JsonNode node) {
        return node.get("allowed_actions").valueStream().map(JsonNode::stringValue).toList();
    }

    protected static List<String> codes(JsonNode page) {
        return page.get("data").valueStream().map(row -> row.get("code").stringValue()).toList();
    }

    protected static JsonNode rowIn(JsonNode page, String code) {
        return page.get("data").valueStream()
            .filter(row -> row.get("code").stringValue().equals(code))
            .findFirst().orElseThrow(() -> new AssertionError("no " + code + " on this board: " + page));
    }

    /** The newest entry of a detail's timeline, which is the edge a test just took. */
    protected static JsonNode last(JsonNode detail) {
        JsonNode events = detail.get("timeline");
        return events.get(events.size() - 1);
    }

    protected static String field(JsonNode error) {
        return error.get("details").get("field_errors").get(0).get("field").stringValue();
    }

    protected String fieldOf(HttpResponse<String> response) {
        return field(error(response));
    }

    protected static String freshPhone() {
        return String.format("+9197%08d", RUN_SALT + PHONE_SEQUENCE.incrementAndGet());
    }

    protected static String json(String key, String value) {
        return "{\"" + key + "\": \"" + value + "\"}";
    }

    protected JsonNode error(HttpResponse<String> response) {
        return body(response).get("error");
    }
}
