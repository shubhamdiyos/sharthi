package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.catalog.web.CatalogViews;
import com.saathi.identity.security.PasswordVault;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §10.5.3's public surfaces and §10.5.8's shop directory, against the seeded pilot.
 *
 * <p>The seed is the fixture: seven Siwan shops, two of them hidden, and a chicken shop whose
 * category requires the licence it holds. A storefront test that invented its own rows would
 * prove the query compiles; this one proves the screens' city is what the API answers.
 */
@DisplayName("§10.5.3 storefront and §10.5.8 shop directory")
class StorefrontCatalogIT extends AbstractIntegrationTest {

    private static final String SIWAN = "siwan";
    private static final String GUPTA = "gupta-fresh-mart";
    private static final String CHICKEN = "siwan-chicken-and-meats";

    private static final String MEENA_EMAIL = "meena@saathi.example";
    private static final String MEENA_PHONE = "+919800006001";
    private static final String PASSWORD = "warehouse-lease-2026";

    @Autowired
    private RecordingOtpProvider otp;

    @Autowired
    private PasswordVault vault;

    @BeforeEach
    void arrangeStaffPassword() {
        restoreStaffRow();
        jdbc.update("UPDATE user_account SET password_hash = ? WHERE email = ?",
            vault.hash(PASSWORD), MEENA_EMAIL);
        retireOtpHistoryFor(MEENA_PHONE);
        jdbc.update("DELETE FROM merchant WHERE code = 'test-meat-shop'");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM user_session WHERE user_account_id = "
            + "(SELECT id FROM user_account WHERE email = ?)", MEENA_EMAIL);
        jdbc.update("DELETE FROM merchant WHERE code = 'test-meat-shop'");
        restoreStaffRow();
    }

    @Test
    @DisplayName("the storefront lists the five visible Siwan shops and neither hidden one")
    void theStorefrontIsTheVisibleShopsOfOneLiveCity() {
        HttpResponse<String> response = get("/api/v1/shops?city_code=" + SIWAN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode page = body(response);
        assertThat(names(page)).containsExactly(
            "Aashirvaad General Store", "Anita Dairy", "Gupta Fresh Mart",
            "Mishra Kirana Bhandar", "Siwan Chicken and Meats");
        assertThat(names(page)).doesNotContain("Sharma Bakery", "Kumar Provision Store");
        assertThat(page.get("page").get("has_more").booleanValue()).isFalse();

        JsonNode gupta = page.get("data").get(2);
        assertThat(gupta.get("code").stringValue()).isEqualTo(GUPTA);
        assertThat(gupta.get("category_slug").stringValue()).isEqualTo("groceries");
        assertThat(gupta.get("ward_no").intValue()).isEqualTo(12);
        assertThat(gupta.get("closing_at").stringValue()).isEqualTo("22:00");
        assertThat(gupta.get("fulfilment_options").get(0).stringValue()).isEqualTo("PLATFORM");
        assertThat(gupta.get("licence_verified").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("a ward filter cuts by the ward the customer chose, not by the shop's own claim")
    void aWardFilterIsTheCustomersWard() {
        JsonNode ward12 = body(get("/api/v1/shops?city_code=" + SIWAN + "&ward_no=12"));

        assertThat(names(ward12)).containsExactly("Anita Dairy", "Gupta Fresh Mart");
        assertThat(names(body(get("/api/v1/shops?city_code=" + SIWAN + "&ward_no=4"))))
            .containsExactly("Mishra Kirana Bhandar");
    }

    @Test
    @DisplayName("a hidden shop is absent from every public URL, not forbidden")
    void hiddenIsAbsence() {
        HttpResponse<String> shop = get("/api/v1/shops/sharma-bakery?city_code=" + SIWAN);
        HttpResponse<String> products = get("/api/v1/shops/sharma-bakery/products?city_code=" + SIWAN);
        HttpResponse<String> product = get("/api/v1/products/milk-bread-400g?city_code=" + SIWAN);

        for (HttpResponse<String> response : List.of(shop, products, product)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(error(response).get("code").stringValue()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    @DisplayName("a city that is not live refuses the storefront by name")
    void aDraftCityIsClosedNotMissing() {
        HttpResponse<String> response = get("/api/v1/shops?city_code=mashaun");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(error(response).get("code").stringValue()).isEqualTo("TENANT_NOT_LIVE");
    }

    @Test
    @DisplayName("a licensed shop's detail carries the licence badge and the city's clock")
    void licenceAndHoursAreOnTheDetail() {
        JsonNode detail = body(get("/api/v1/shops/" + CHICKEN + "?city_code=" + SIWAN));

        assertThat(detail.get("licence_verified").booleanValue()).isTrue();
        assertThat(detail.get("category_slug").stringValue()).isEqualTo("meat-fish");
        assertThat(detail.get("closing_at").stringValue()).isEqualTo("20:30");
        assertThat(detail.get("hours").size()).isEqualTo(7);
        assertThat(detail.get("hours").get(0).get("day").intValue()).isEqualTo(1);
        assertThat(detail.get("hours").get(0).get("opens_at").stringValue()).isEqualTo("08:00");
        assertThat(detail.get("fulfilment_options").size()).isEqualTo(1);
        assertThat(detail.get("product_count").longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("a by-weight product says its price is an estimate, in the platform's words")
    void byWeightIsAnEstimateAndSaysSo() {
        JsonNode product = body(get("/api/v1/products/chicken-curry-cut?city_code=" + SIWAN));

        assertThat(product.get("is_by_weight").booleanValue()).isTrue();
        assertThat(product.get("price").get("paise").longValue()).isEqualTo(47000L);
        assertThat(product.get("price").get("approx").booleanValue()).isTrue();
        assertThat(product.get("consent_notice").stringValue())
            .isEqualTo(CatalogViews.WEIGHT_CONSENT_NOTICE);
        assertThat(product.get("shop_code").stringValue()).isEqualTo(CHICKEN);
        assertThat(product.get("category_slug").stringValue()).isEqualTo("meat-fish");

        JsonNode salt = body(get("/api/v1/products/tata-salt-1kg?city_code=" + SIWAN));
        assertThat(salt.get("price").get("approx").booleanValue()).isFalse();
        assertThat(salt.has("consent_notice"))
            .as("a fixed-price item has nothing to consent to")
            .isFalse();
    }

    @Test
    @DisplayName("search answers both sections the drawn screen shows")
    void searchHasShopsAndProducts() {
        JsonNode found = body(get("/api/v1/search?city_code=" + SIWAN + "&q=chicken"));

        // The shop section is a bare array, not a page: the screen shows a short list of shops
        // beside the product results, and the cursor belongs to the products alone.
        assertThat(found.get("shops").valueStream().map(node -> node.get("name").stringValue()))
            .containsExactly("Siwan Chicken and Meats");
        assertThat(found.get("products").get("data").get(0).get("code").stringValue())
            .isEqualTo("chicken-curry-cut");

        assertThat(body(get("/api/v1/search?city_code=" + SIWAN + "&q=")).get("products")
            .get("data").size())
            .as("an empty box is not a request for the whole catalogue")
            .isZero();
    }

    @Test
    @DisplayName("the operator's directory is the one list that includes hidden shops")
    void theOperatorSeesWhatTheStorefrontCannot() {
        HttpResponse<String> signedIn = signInAsMeena();

        JsonNode page = body(get("/api/v1/o/shops", asBrowser(signedIn)));
        assertThat(page.get("data").size()).isEqualTo(7);
        JsonNode sharma = nodeNamed(page.get("data"), "sharma-bakery");
        assertThat(sharma.get("state").stringValue()).isEqualTo("APPROVED");
        assertThat(sharma.get("hidden_reason").stringValue()).isEqualTo("Licence photo invalid");
        assertThat(sharma.get("licence_state").stringValue()).isEqualTo("INVALID");
        assertThat(sharma.get("ward_no").intValue()).isEqualTo(7);
        assertThat(nodeNamed(page.get("data"), "kumar-provision-store").get("hidden_reason")
            .stringValue()).isEqualTo("Address outside all wards");
    }

    @Test
    @DisplayName("an operator may not hide a shop, and a licensed shop needs proof before approval")
    void approvalIsALicenceDecisionAndHidingIsNotTheOperators() {
        HttpResponse<String> signedIn = signInAsMeena();
        Map<String, String> browser = asBrowser(signedIn);

        HttpResponse<String> hide = post("/api/v1/o/shops/" + CHICKEN + "/visibility", browser, """
            {"hidden": true, "reason": "Operator wants it gone"}""");
        assertThat(hide.statusCode()).isEqualTo(403);
        assertThat(error(hide).get("code").stringValue()).isEqualTo("FORBIDDEN");

        HttpResponse<String> applied = post("/api/v1/m/onboarding", """
            {"city_code": "siwan", "ward_no": 9, "legal_name": "Test Meat Shop",
             "trade_name": "Test Meat Shop", "category_slug": "meat-fish",
             "phone": "9400009911", "address_text": "Chandbeli Road, Ward 09, Siwan",
             "licence_ref": "BR-MEAT-2026-9999"}""");
        assertThat(applied.statusCode()).isEqualTo(201);
        assertThat(body(applied).get("state").stringValue()).isEqualTo("PENDING");

        HttpResponse<String> refused = post("/api/v1/o/merchants/test-meat-shop/approve", browser, "{}");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(error(refused).get("code").stringValue()).isEqualTo("LICENCE_INCOMPLETE");

        // The operator records the checked licence, then approves: the same decision, now backed.
        jdbc.update("UPDATE merchant SET licence_state = 'VALID' WHERE code = 'test-meat-shop'");
        HttpResponse<String> approved = post("/api/v1/o/merchants/test-meat-shop/approve", browser, "{}");
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(body(approved).get("state").stringValue()).isEqualTo("APPROVED");
        assertThat(body(approved).get("licence_ref").stringValue()).isEqualTo("BR-MEAT-2026-9999");
    }

    @Test
    @DisplayName("a shop's staff cannot hide a shop through the operator route")
    void theStorefrontHidingPermissionIsNotInTheOperatorRole() {
        // The seed's operator role is the whole point: SHOP_VISIBILITY_WRITE is absent from V4's
        // role_permission rows for CITY_OPERATOR, so the refusal is the grant and not a code branch.
        Integer held = jdbc.queryForObject("""
            SELECT count(*) FROM role_permission rp
              JOIN role r ON r.id = rp.role_id
              JOIN permission p ON p.id = rp.permission_id
             WHERE r.id = '01530PKV1M1GJKFZGW7YWK2CN2' AND p.key = 'SHOP_VISIBILITY_WRITE'
            """, Integer.class);
        assertThat(held).isZero();
    }

    private HttpResponse<String> signInAsMeena() {
        HttpResponse<String> first = post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s"}""".formatted(MEENA_EMAIL, PASSWORD));
        String challengeId = error(first).get("details").get("challenge_id").stringValue();
        HttpResponse<String> second = post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s", "otp_challenge_id": "%s", "otp_code": "%s"}"""
            .formatted(MEENA_EMAIL, PASSWORD, challengeId, otp.codeFor(MEENA_PHONE)));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        return second;
    }

    private void restoreStaffRow() {
        jdbc.update("UPDATE user_account SET password_hash = NULL, failed_auth_count = 0, "
            + "locked_until = NULL, state = 'ACTIVE' WHERE email = ?", MEENA_EMAIL);
    }

    private static List<String> names(JsonNode page) {
        return page.get("data").valueStream().map(node -> node.get("name").stringValue()).toList();
    }

    private static JsonNode nodeNamed(JsonNode data, String code) {
        return data.valueStream()
            .filter(node -> node.get("code").stringValue().equals(code))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no shop named " + code + " in " + data));
    }

    private JsonNode error(HttpResponse<String> response) {
        return body(response).get("error");
    }
}
