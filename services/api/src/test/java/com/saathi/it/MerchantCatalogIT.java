package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.identity.security.PasswordVault;
import com.saathi.shared.id.Ulid;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §10.5.6's merchant catalogue and stock, driven by a real merchant session.
 *
 * <p>The owner account is created per test and removed afterwards. No merchant password is seeded:
 * a committed hash is the dev password §14 forbids, so the test hashes a passphrase at run time —
 * the same arrangement {@code StaffAuthIT} uses for the pilot's operators.
 *
 * <p>Every test restores what it touched in the pilot seed, because the seed is also what the
 * storefront suite reads and the two suites must not decide each other's fixtures.
 */
@DisplayName("§10.5.6 merchant catalogue and stock")
class MerchantCatalogIT extends AbstractIntegrationTest {

    private static final String GUPTA_ID = "N38GBA9P599PBQ3KC0KKQ1YJ2E";
    private static final String SIWAN_ID = "01NGD9Y4JMQ2RBNVVKXX7J04DM";
    private static final String MERCHANT_STAFF_ROLE = "0147N54N78DAFBP95FY7DTWQ9R";
    private static final String ANIL_ID = "01Z602EBP9ERSY30BFYGVBJZFP";

    private static final String EMAIL = "gupta.owner@saathi.example";
    private static final String PHONE = "+919400009001";
    private static final String PASSWORD = "gupta-market-2026";

    @Autowired
    private RecordingOtpProvider otp;

    @Autowired
    private PasswordVault vault;

    @Autowired
    private Clock clock;

    private String accountId;
    private HttpResponse<String> session;
    private Map<String, String> browser;

    @BeforeEach
    void aShopMemberSignsIn() {
        removePreviousRun();
        accountId = Ulid.next(clock);
        jdbc.update("INSERT INTO user_account (id, kind, phone, email, display_name, state, "
                + "password_hash, created_at) VALUES (?, 'MERCHANT_STAFF', ?, ?, ?, 'ACTIVE', ?, now())",
            accountId, PHONE, EMAIL, "Gupta Fresh Mart", vault.hash(PASSWORD));
        jdbc.update("INSERT INTO user_role_grant (id, user_account_id, role_id, tenant_id, merchant_id, "
                + "granted_by, reason, granted_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
            Ulid.next(clock), accountId, MERCHANT_STAFF_ROLE, SIWAN_ID, GUPTA_ID, ANIL_ID,
            "Test: owner of the pilot's busiest shop");
        retireOtpHistoryFor(PHONE);

        HttpResponse<String> first = post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s"}""".formatted(EMAIL, PASSWORD));
        String challengeId = error(first).get("details").get("challenge_id").stringValue();
        session = post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s", "otp_challenge_id": "%s", "otp_code": "%s"}"""
            .formatted(EMAIL, PASSWORD, challengeId, otp.codeFor(PHONE)));
        assertThat(session.statusCode()).as(session.body()).isEqualTo(200);
        browser = asBrowser(session);
    }

    @AfterEach
    void putThePilotBack() {
        jdbc.update("DELETE FROM stock_adjustment WHERE product_id IN "
            + "(SELECT id FROM product WHERE merchant_id = ? AND code LIKE 'test-%')", GUPTA_ID);
        jdbc.update("DELETE FROM product WHERE merchant_id = ? AND code LIKE 'test-%'", GUPTA_ID);
        jdbc.update("UPDATE product SET price_paise = 2800, stock_qty = 30, reserved_qty = 0, "
            + "available = true, version = 0 WHERE code = 'tata-salt-1kg'");
        jdbc.update("UPDATE merchant SET fulfilment_capability = 'PLATFORM_ONLY', "
            + "default_fulfilment_mode = 'PLATFORM', customer_choice_allowed = true, "
            + "operating_hours_json = ?, self_deliver_zones_json = NULL WHERE id = ?",
            seedHours(), GUPTA_ID);
        jdbc.update("UPDATE tenant_category SET active = true WHERE tenant_id = ? "
            + "AND category_id = '01DQWYNSVDMV1Y2HWNE9WSSVD4'", SIWAN_ID);
        jdbc.update("DELETE FROM user_session WHERE user_account_id = ?", accountId);
        jdbc.update("DELETE FROM user_role_grant WHERE user_account_id = ?", accountId);
        jdbc.update("DELETE FROM user_account WHERE id = ?", accountId);
    }

    @Test
    @DisplayName("the session is bound to one shop, and the catalogue is that shop's")
    void theSessionNamesTheShop() {
        JsonNode me = body(session);
        JsonNode scope = me.get("scopes").get(0);
        assertThat(scope.get("tenant_code").stringValue()).isEqualTo("siwan");
        assertThat(scope.get("merchant_id").stringValue()).isEqualTo(GUPTA_ID);
        assertThat(me.get("permissions").valueStream().map(JsonNode::stringValue))
            .contains("PRODUCT_READ", "PRODUCT_WRITE", "STOCK_READ", "STOCK_ADJUST",
                "SHOP_SETTINGS_WRITE")
            .doesNotContain("MERCHANT_APPROVE", "SHOP_VISIBILITY_WRITE");

        JsonNode page = body(get("/api/v1/m/products", browser));
        assertThat(codes(page)).containsExactly("aashirvaad-atta-5kg", "tata-salt-1kg");
        assertThat(page.get("data").get(0).get("category_slug").stringValue()).isEqualTo("groceries");
        assertThat(page.get("data").get(1).get("category_name").stringValue()).isEqualTo("Kirana");
    }

    @Test
    @DisplayName("the unit on the label and the unit the shop counts in must agree")
    void labelAndCountUnitAgree() {
        HttpResponse<String> mismatched = post("/api/v1/m/products", browser, """
            {"name": "Test Milk", "category_slug": "dairy", "price_paise": 2900,
             "unit_label": "500 ml", "stock_unit": "pc", "track_stock": true, "stock_qty": 4}""");

        assertThat(mismatched.statusCode()).isEqualTo(422);
        assertThat(fieldError(mismatched).get("field").stringValue()).isEqualTo("unit_label");

        HttpResponse<String> byWeightInPieces = post("/api/v1/m/products", browser, """
            {"name": "Test Chicken", "category_slug": "meat-fish", "price_paise": 47000,
             "unit_label": "1 kg", "stock_unit": "pc", "is_by_weight": true}""");

        assertThat(byWeightInPieces.statusCode()).isEqualTo(422);
        assertThat(fieldError(byWeightInPieces).get("field").stringValue()).isEqualTo("stock_unit");
    }

    @Test
    @DisplayName("a category the city has not switched on is refused before it reaches the trigger")
    void categoryMustBeActiveInTheCity() {
        jdbc.update("UPDATE tenant_category SET active = false WHERE tenant_id = ? "
            + "AND category_id = '01DQWYNSVDMV1Y2HWNE9WSSVD4'", SIWAN_ID);

        HttpResponse<String> response = post("/api/v1/m/products", browser, """
            {"name": "Test Curd", "category_slug": "dairy", "price_paise": 4500,
             "unit_label": "500 g pack", "stock_unit": "pc"}""");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(error(response).get("code").stringValue()).isEqualTo("CATEGORY_NOT_ACTIVE");
    }

    @Test
    @DisplayName("an edit without If-Match is refused, and a stale version is a conflict")
    void everyEditStatesTheVersionItSaw() {
        HttpResponse<String> missing = patch("/api/v1/m/products/tata-salt-1kg", browser, null,
            "{\"price_paise\": 2900}");
        assertThat(missing.statusCode()).isEqualTo(422);
        assertThat(fieldError(missing).get("field").stringValue()).isEqualTo("If-Match");

        HttpResponse<String> stale = patch("/api/v1/m/products/tata-salt-1kg", browser, "\"9\"",
            "{\"price_paise\": 2900}");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(error(stale).get("code").stringValue()).isEqualTo("CONFLICT");
        assertThat(error(stale).get("details").get("current_version").longValue()).isZero();

        HttpResponse<String> edited = patch("/api/v1/m/products/tata-salt-1kg", browser, "\"0\"",
            "{\"price_paise\": 2900}");
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(body(edited).get("price").get("paise").longValue()).isEqualTo(2900L);
        assertThat(body(edited).get("version").longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a price edit is its own audit action, not a generic product update")
    void aPriceChangeIsAuditedAsSuch() {
        HttpResponse<String> edited = patch("/api/v1/m/products/tata-salt-1kg", browser, "\"0\"",
            "{\"price_paise\": 2900}");
        assertThat(edited.statusCode()).isEqualTo(200);

        Map<String, Object> latest = jdbc.queryForMap("SELECT action, reason_code, before_json "
            + "FROM audit_event WHERE entity_id = '7ZBVR3Z1P5J8MNVF23CAGXT4ZW' "
            + "ORDER BY occurred_at DESC LIMIT 1");
        assertThat(latest.get("action")).isEqualTo("PRICE_CHANGED");
        assertThat(latest.get("reason_code")).isEqualTo("Merchant changed the price");
        assertThat(latest.get("before_json").toString()).contains("2800");
    }

    @Test
    @DisplayName("marking an item unavailable hides it from the shelf and stays in history")
    void availabilityIsAToggleNotADelete() {
        HttpResponse<String> off = post("/api/v1/m/products/tata-salt-1kg/availability", browser,
            "{\"available\": false}");
        assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
        assertThat(body(off).get("available").booleanValue()).isFalse();

        assertThat(body(get("/api/v1/shops/gupta-fresh-mart/products")).get("data").valueStream()
            .map(node -> node.get("code").stringValue()).toList())
            .doesNotContain("tata-salt-1kg");
        assertThat(jdbc.queryForObject("SELECT action FROM audit_event WHERE entity_id = "
            + "'7ZBVR3Z1P5J8MNVF23CAGXT4ZW' ORDER BY occurred_at DESC LIMIT 1", String.class))
            .isEqualTo("AVAILABILITY_CHANGED");
    }

    @Test
    @DisplayName("an adjustment is a delta with a reason, and it writes the ledger and the audit")
    void anAdjustmentExplainsEveryChange() {
        HttpResponse<String> created = post("/api/v1/m/products", browser, """
            {"name": "Test Toor Dal", "category_slug": "groceries", "price_paise": 18000,
             "unit_label": "1 kg pack", "stock_unit": "pc", "track_stock": true, "stock_qty": 12,
             "max_qty_per_order": 5}""");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String code = body(created).get("code").stringValue();
        assertThat(code).isEqualTo("test-toor-dal");
        assertThat(body(created).get("stock_state").stringValue()).isEqualTo("IN");

        HttpResponse<String> adjusted = post("/api/v1/m/products/" + code + "/stock", browser, """
            {"delta_qty": -2, "reason": "Two packs damaged in the delivery"}""");
        assertThat(adjusted.statusCode()).as(adjusted.body()).isEqualTo(200);
        assertThat(body(adjusted).get("stock_qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(body(adjusted).get("available_qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(body(adjusted).get("stock_state").stringValue()).isEqualTo("LOW");

        Map<String, Object> ledger = jdbc.queryForMap(
            "SELECT delta_qty, reason, actor_id FROM stock_adjustment WHERE product_id = "
                + "(SELECT id FROM product WHERE code = 'test-toor-dal')");
        assertThat(ledger.get("reason")).isEqualTo("Two packs damaged in the delivery");
        assertThat(ledger.get("actor_id")).isEqualTo(accountId);
        assertThat(jdbc.queryForObject("SELECT action FROM audit_event WHERE entity_id = "
            + "(SELECT id FROM product WHERE code = 'test-toor-dal') ORDER BY occurred_at DESC LIMIT 1",
            String.class)).isEqualTo("STOCK_ADJUSTED");
    }

    @Test
    @DisplayName("stock cannot be adjusted below zero, and an untracked item has no count")
    void theShelfCannotGoNegative() {
        HttpResponse<String> belowZero = post("/api/v1/m/products/tata-salt-1kg/stock", browser, """
            {"delta_qty": -100000, "reason": "Testing the floor"}""");
        assertThat(belowZero.statusCode()).isEqualTo(422);
        assertThat(error(belowZero).get("code").stringValue()).isEqualTo("NEGATIVE_STOCK");

        HttpResponse<String> untracked = post("/api/v1/m/products", browser, """
            {"name": "Test Apron", "category_slug": "groceries", "price_paise": 15000,
             "unit_label": "1 pc", "stock_unit": "pc"}""");
        assertThat(untracked.statusCode()).as(untracked.body()).isEqualTo(201);
        HttpResponse<String> refused = post("/api/v1/m/products/test-apron/stock", browser, """
            {"delta_qty": 1, "reason": "Attempt on an uncounted item"}""");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(error(refused).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("another shop's item is absence on every /m route")
    void anotherShopsItemIsNotFound() {
        HttpResponse<String> stock = post("/api/v1/m/products/milk-bread-400g/stock", browser, """
            {"delta_qty": 1, "reason": "Not mine to adjust"}""");
        HttpResponse<String> edit = patch("/api/v1/m/products/milk-bread-400g", browser, "\"0\"",
            "{\"price_paise\": 1}");

        for (HttpResponse<String> response : List.of(stock, edit)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(error(response).get("code").stringValue()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    @DisplayName("a default fulfilment mode the shop cannot deliver is refused")
    void theDefaultModeMustBePossible() {
        HttpResponse<String> impossible = patch("/api/v1/m/settings", browser, null,
            "{\"fulfilment_capability\": \"MERCHANT_ONLY\"}");
        assertThat(impossible.statusCode()).isEqualTo(422);
        assertThat(error(impossible).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");

        HttpResponse<String> possible = patch("/api/v1/m/settings", browser, null, """
            {"fulfilment_capability": "MERCHANT_ONLY", "default_fulfilment_mode": "MERCHANT",
             "self_deliver_wards": [12]}""");
        assertThat(possible.statusCode()).as(possible.body()).isEqualTo(200);
        assertThat(body(possible).get("fulfilment_capability").stringValue()).isEqualTo("MERCHANT_ONLY");
        assertThat(body(possible).get("default_fulfilment_mode").stringValue()).isEqualTo("MERCHANT");
        assertThat(body(possible).get("self_deliver_wards").get(0).intValue()).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT action FROM audit_event WHERE entity_id = ? "
            + "ORDER BY occurred_at DESC LIMIT 1", String.class, GUPTA_ID))
            .isEqualTo("FULFILMENT_MODE_CONFIG_CHANGED");
    }

    @Test
    @DisplayName("hours are one row per ISO day and times in the city's clock")
    void hoursAreSevenRowsAtMost() {
        StringBuilder eightRows = new StringBuilder("[");
        for (int day = 1; day <= 8; day++) {
            eightRows.append(day == 1 ? "" : ",")
                .append("{\"day\":").append(day)
                .append(",\"opens_at\":\"08:00\",\"closes_at\":\"22:00\",\"closed\":false}");
        }
        eightRows.append("]");

        HttpResponse<String> tooMany = patch("/api/v1/m/settings", browser, null,
            "{\"hours\": " + eightRows + "}");
        assertThat(tooMany.statusCode()).isEqualTo(422);
        assertThat(fieldError(tooMany).get("field").stringValue()).isEqualTo("hours");

        HttpResponse<String> badTime = patch("/api/v1/m/settings", browser, null, """
            {"hours": [{"day": 1, "opens_at": "8am", "closes_at": "22:00", "closed": false}]}""");
        assertThat(badTime.statusCode()).isEqualTo(422);

        HttpResponse<String> accepted = patch("/api/v1/m/settings", browser, null, """
            {"hours": [{"day": 1, "opens_at": "08:00", "closes_at": "22:00", "closed": false},
                       {"day": 2, "opens_at": "08:00", "closes_at": "22:00", "closed": true}]}""");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(body(accepted).get("hours").size()).isEqualTo(2);
        assertThat(body(accepted).get("hours").get(1).get("closed").booleanValue()).isTrue();
    }

    @Test
    @DisplayName("the stock shelves answer low and out from the same projection")
    void shelvesAreDerivedNotStored() {
        JsonNode low = body(get("/api/v1/m/stock?state=low", browser));
        assertThat(low.get("data").size()).isZero();

        // Nine or fewer left is "low"; the seed's chicken has 12.5 kg, so a test item sets the case.
        post("/api/v1/m/products", browser, """
            {"name": "Test Ghee", "category_slug": "dairy", "price_paise": 60000,
             "unit_label": "1 L pack", "stock_unit": "pc", "track_stock": true, "stock_qty": 3}""");
        JsonNode shelf = body(get("/api/v1/m/stock?state=low", browser));
        assertThat(codes(shelf)).containsExactly("test-ghee");
        assertThat(shelf.get("data").get(0).get("stock_state").stringValue()).isEqualTo("LOW");
        assertThat(body(get("/api/v1/m/stock?state=out", browser)).get("data").size()).isZero();
    }

    private HttpResponse<String> patch(String path, Map<String, String> headers, String ifMatch,
                                       String body) {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        try {
            return java.net.http.HttpClient.newHttpClient()
                .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private void removePreviousRun() {
        jdbc.update("DELETE FROM stock_adjustment WHERE product_id IN "
            + "(SELECT id FROM product WHERE merchant_id = ? AND code LIKE 'test-%')", GUPTA_ID);
        jdbc.update("DELETE FROM product WHERE merchant_id = ? AND code LIKE 'test-%'", GUPTA_ID);
        jdbc.update("DELETE FROM user_session WHERE user_account_id IN "
            + "(SELECT id FROM user_account WHERE email = ?)", EMAIL);
        jdbc.update("DELETE FROM user_role_grant WHERE user_account_id IN "
            + "(SELECT id FROM user_account WHERE email = ?)", EMAIL);
        jdbc.update("DELETE FROM user_account WHERE email = ?", EMAIL);
        jdbc.update("UPDATE product SET price_paise = 2800, stock_qty = 30, reserved_qty = 0, "
            + "available = true, version = 0 WHERE code = 'tata-salt-1kg'");
    }

    private static String seedHours() {
        StringBuilder json = new StringBuilder("[");
        for (int day = 1; day <= 7; day++) {
            json.append(day == 1 ? "" : ",")
                .append("{\"day\":").append(day)
                .append(",\"opens_at\":\"08:00\",\"closes_at\":\"22:00\",\"closed\":false}");
        }
        return json.append("]").toString();
    }

    private static List<String> codes(JsonNode page) {
        return page.get("data").valueStream().map(node -> node.get("code").stringValue()).toList();
    }

    private static JsonNode fieldError(HttpResponse<String> response) {
        return error(response).get("details").get("field_errors").get(0);
    }

    private static JsonNode error(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("error");
    }
}
