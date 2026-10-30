package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.shared.id.Ulid;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §10.5.2's public surface: {@code GET /cities}, {@code GET /geo/wards}, {@code POST /geo/check} and
 * {@code GET /categories} — the four calls PRD §8.1's first screen makes before anybody signs in.
 *
 * <p>These are the endpoints where a wrong answer is not a crash but a customer, so the assertions
 * are about what a storefront can act on: which cities exist at all, which wards of a live city are
 * served, what serving one costs in the shape §10.4 mandates, and which kill switches are up.
 *
 * <p>Two of the rules here have no unit test that could prove them. A city is named by its public
 * {@code code} and not by its id (§4.3-6), which is only visible on bytes; and "a ward number means
 * nothing outside its city" (§4.2) is only provable when one number exists twice in the database,
 * which is why the pilot seeds Ward 1 in both Siwan and Mashaun.
 */
class GeoTenancyIT extends AbstractIntegrationTest {

    private static final String SIWAN = "siwan";
    private static final String MASHAUN = "mashaun";
    private static final String BARAULI = "barauli";

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("GET /cities lists only LIVE cities, as an array, with only what a customer may see")
    void citiesListsLiveOnesOnly() {
        HttpResponse<String> response = get("/api/v1/cities");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = body(response);
        // §10.5.2 declares CitySummary[] rather than a page, and the endpoint keeps the document's
        // word over the "every collection is a page" habit.
        assertThat(json.isArray()).as("§10.5.2's shape is an array").isTrue();

        List<String> codes = json.valueStream().map(node -> node.get("code").stringValue()).toList();
        assertThat(codes).as("only a LIVE city is announced to a customer").containsExactly(SIWAN);

        // A city's phone number and its money rules are operator data. The list is the one surface
        // where "no PII" has to be a property of the type rather than of someone remembering.
        json.valueStream().forEach(city -> assertThat(city.propertyNames())
            .containsExactlyInAnyOrder("code", "name", "state_code"));
        assertThat(response.body()).doesNotContain("contact_phone", "cash", "commission", "_id");
    }

    @Test
    @DisplayName("a city code is the address a client sends back, and it is read case-insensitively")
    void cityCodeIsCaseInsensitive() {
        for (String spelled : List.of("siwan", "SIWAN", "Siwan", "%20siwan%20")) {
            HttpResponse<String> response = get("/api/v1/geo/wards?city_code=" + spelled);

            assertThat(response.statusCode()).as("§10.5.2: %s is one city", spelled).isEqualTo(200);
            assertThat(wardNumbers(response)).as("%s resolves to the pilot's five wards", spelled)
                .containsExactly(1, 4, 7, 9, 12);
        }
    }

    @Test
    @DisplayName("a ward list carries the public pair and no internal id")
    void wardListIsPublicShaped() {
        JsonNode json = body(get("/api/v1/geo/wards?city_code=" + SIWAN));

        // Screen order is sort then ward number, applied server-side: the list a picker renders is
        // the list the operator ordered.
        assertThat(json.get(0).get("ward_no").intValue()).isEqualTo(1);
        assertThat(json.get(4).get("ward_no").intValue()).isEqualTo(12);
        json.valueStream().forEach(ward -> assertThat(ward.propertyNames()).containsExactly("ward_no", "name"));
    }

    @Test
    @DisplayName("a city that is not open is refused with its own code, not with 'no such city'")
    void draftAndSuspendedCitiesAreNamed() {
        assertThat(error(get("/api/v1/geo/wards?city_code=" + MASHAUN)).get("code").stringValue())
            .as("Mashaun is launched on screens, not on the ground").isEqualTo("TENANT_NOT_LIVE");
        assertThat(error(get("/api/v1/geo/wards?city_code=" + BARAULI)).get("code").stringValue())
            .as("§10.5.9's state change blocks new orders and says so").isEqualTo("TENANT_NOT_LIVE");
        assertThat(error(get("/api/v1/geo/wards?city_code=nagpur")).get("code").stringValue())
            .as("a city the platform never announced").isEqualTo("OUTSIDE_SERVICE_AREA");
    }

    @Test
    @DisplayName("a missing city_code is a 422 that names the query key, not the Java component")
    void missingCityCodeIsRejected() {
        HttpResponse<String> response = get("/api/v1/geo/wards");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("city_code");
    }

    @Test
    @DisplayName("POST /geo/check answers the resolved ward with both modes' fees in the §10.4 money shape")
    void checkQuotesBothModesOfAWard() {
        HttpResponse<String> response = post("/api/v1/geo/check", """
            {"city_code": "siwan", "ward_no": 7, "pincode": "841226", "address": "Nawada, near the bakery"}""");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = body(response);
        assertThat(json.get("city").get("code").stringValue()).isEqualTo(SIWAN);
        assertThat(json.get("ward").get("ward_no").intValue()).isEqualTo(7);

        // One entry per mode the ward has a live rule for, and the pair is what the modes are:
        // on a merchant-delivered order the delivery fee stays with the shop (§9.3), so a single
        // "ward fee" column would have been one number pretending to be two rules.
        List<String> modes = json.get("fees").valueStream()
            .map(fee -> fee.get("fulfilment_mode").stringValue())
            .toList();
        assertThat(modes).containsExactlyInAnyOrder("MERCHANT", "PLATFORM");
        json.get("fees").valueStream().forEach(fee -> {
            assertThat(money(fee.get("delivery_fee"))).isEqualTo("2500|INR|₹25.00");
            assertThat(money(fee.get("handling_fee"))).isEqualTo("1000|INR|₹10.00");
            assertThat(money(fee.get("min_order")))
                .as("OD-2: no minimum basket was ever decided, so the honest value is 0")
                .isEqualTo("0|INR|₹0.00");
            assertThat(fee.get("delivery_fee").get("approx").booleanValue())
                .as("a fee is a settled amount, never an estimate").isFalse();
        });

        assertThat(json.get("flags").propertyNames()).containsExactlyInAnyOrder(
            "cash_on_delivery", "online_payment", "auto_dispatch", "proof_of_delivery_media", "in_app_chat");
        assertThat(json.get("flags").get("cash_on_delivery").booleanValue()).isTrue();
        assertThat(json.get("flags").get("online_payment").booleanValue()).isFalse();

        // The two free-text fields are accepted and unused — no geocoder exists, and a ward is the
        // unit of serviceability (D8) — but they must not come back: an address that arrives as text
        // should not leave as one.
        assertThat(response.body()).doesNotContain("841226", "Nawada, near");
    }

    @Test
    @DisplayName("a ward is only served inside its own city")
    void wardNumbersAreScopedToTheirCity() {
        // Ward 1 is Siwan's Aoriyanimath and Mashaun's Bhitari Road. Only the pair says which.
        assertThat(body(post("/api/v1/geo/check", """
            {"city_code": "siwan", "ward_no": 1}""")).get("ward").get("name").stringValue())
            .contains("Aoriyanimath");

        HttpResponse<String> notThere = post("/api/v1/geo/check", """
            {"city_code": "siwan", "ward_no": 3}""");
        assertThat(notThere.statusCode()).isEqualTo(409);
        assertThat(error(notThere).get("code").stringValue()).isEqualTo("OUTSIDE_SERVICE_AREA");
        assertThat(error(notThere).get("message").stringValue())
            .as("ward 3 is Mashaun's, and the refusal must not say so").doesNotContain("Mashaun");
    }

    @Test
    @DisplayName("a ward switched off in a live city is not served, and is not in the list either")
    void inactiveWardIsNotServed() {
        String wardId = Ulid.next(clock);
        jdbc.update("INSERT INTO service_ward (id, tenant_id, ward_no, name, active, sort) "
            + "SELECT ?, id, 99, 'Ward 99 — closed', false, 90 FROM tenant WHERE code = ?",
            wardId, SIWAN);
        try {
            assertThat(wardNumbers(get("/api/v1/geo/wards?city_code=" + SIWAN)))
                .as("an inactive ward is not a place a customer can pick").doesNotContain(99);

            HttpResponse<String> response = post("/api/v1/geo/check", """
                {"city_code": "siwan", "ward_no": 99}""");
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(error(response).get("code").stringValue()).isEqualTo("OUTSIDE_SERVICE_AREA");
        } finally {
            jdbc.update("DELETE FROM service_ward WHERE id = ?", wardId);
        }
    }

    @Test
    @DisplayName("a ward with no fee rule quotes nothing rather than inventing a number")
    void wardWithoutFeesQuotesNoFees() {
        // Barauli's ward is inactive and unpriced, but the city alone cannot be refused first if a
        // LIVE city ever gains an unpriced ward, so the shape is asserted through the only route
        // that reaches it: an explicit insert.
        String wardId = Ulid.next(clock);
        jdbc.update("INSERT INTO service_ward (id, tenant_id, ward_no, name, active, sort) "
            + "SELECT ?, id, 98, 'Ward 98 — unpriced', true, 98 FROM tenant WHERE code = ?", wardId, SIWAN);
        try {
            JsonNode json = body(post("/api/v1/geo/check", """
                {"city_code": "siwan", "ward_no": 98}"""));
            assertThat(json.get("fees").size())
                .as("§10.5.2: a mode with no live rule is absent, not free").isZero();
            assertThat(json.get("ward").get("ward_no").intValue()).isEqualTo(98);
        } finally {
            jdbc.update("DELETE FROM service_ward WHERE id = ?", wardId);
        }
    }

    @Test
    @DisplayName("the ward number is required, because a city alone is a different question")
    void checkWithoutWardIsRejected() {
        HttpResponse<String> response = post("/api/v1/geo/check", """
            {"city_code": "siwan"}""");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(error(response).get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("ward_no");

        assertThat(error(post("/api/v1/geo/check", """
            {"city_code": "siwan", "ward_no": 0}""")).get("details").get("field_errors").get(0)
            .get("field").stringValue())
            .as("service_ward_no_positive is a CHECK in the schema and a constraint here")
            .isEqualTo("ward_no");
    }

    @Test
    @DisplayName("a city shows the taxonomy with its own names and its own order")
    void categoriesAreTheCitySlice() {
        HttpResponse<String> siwanResponse = get("/api/v1/categories?city_code=" + SIWAN);
        assertThat(siwanResponse.statusCode()).isEqualTo(200);
        JsonNode siwan = body(siwanResponse);

        assertThat(siwan.valueStream().map(node -> node.get("name").stringValue()).toList())
            .containsExactly("Kirana", "Groceries", "Dairy", "Meat and Fish", "Bakery", "General Store");
        siwan.valueStream().forEach(category ->
            assertThat(category.propertyNames()).containsExactly("slug", "name", "requires_licence"));
        assertThat(siwan.get(3).get("slug").stringValue()).isEqualTo("meat-fish");
        assertThat(siwan.get(3).get("requires_licence").booleanValue())
            .as("PRD §15: the node, not a code branch, carries the licence rule").isTrue();
        assertThat(siwan.get(0).get("requires_licence").booleanValue()).isFalse();
    }

    /**
     * The pilot's second city is the rename case (§4.1: one taxonomy node, two storefront names),
     * and it is DRAFT, so reaching it means switching it on for the length of one assertion.
     *
     * <p>The row is restored in a {@code finally} rather than left for the next run: this database is
     * shared with every other IT, and a Mashaun that stayed live would make the geography refusals
     * above true by accident instead of by rule.
     */
    @Test
    @DisplayName("one taxonomy node wears the city's name, not a forked one")
    void aRenameIsDataAndNotACodePath() {
        HttpResponse<String> draft = get("/api/v1/categories?city_code=" + MASHAUN);
        assertThat(draft.statusCode()).as("a DRAFT city sells nothing, activations and all").isEqualTo(409);
        assertThat(error(draft).get("code").stringValue()).isEqualTo("TENANT_NOT_LIVE");

        jdbc.update("UPDATE tenant SET status = 'LIVE' WHERE code = ?", MASHAUN);
        try {
            JsonNode mashaun = body(get("/api/v1/categories?city_code=" + MASHAUN));
            assertThat(mashaun.valueStream().map(node -> node.get("name").stringValue()).toList())
                .as("§4.1: 'Kirana' in Siwan is 'General Store' in Mashaun")
                .containsExactly("General Store", "Groceries");
            assertThat(mashaun.valueStream().map(node -> node.get("slug").stringValue()).toList())
                .as("the rename does not move the handle a client sends back")
                .containsExactly("kirana", "groceries");
            assertThat(mashaun.get(0).get("requires_licence").booleanValue())
                .as("a city cannot rename away a platform rule").isFalse();
        } finally {
            jdbc.update("UPDATE tenant SET status = 'DRAFT' WHERE code = ?", MASHAUN);
        }
    }

    @Test
    @DisplayName("a suspended city sells nothing, even though its activations are still rows")
    void categoriesRefuseACityThatIsNotLive() {
        HttpResponse<String> response = get("/api/v1/categories?city_code=" + BARAULI);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(error(response).get("code").stringValue()).isEqualTo("TENANT_NOT_LIVE");
    }

    /** The rule a quote reads and the rule the storefront's list reads are the same one. */
    @Test
    @DisplayName("an effective fee is the newest rule that has started, not the only row present")
    void aSupersededFeeRuleIsNotQuoted() {
        String siwanId = tenantId(SIWAN);
        String wardId = jdbc.queryForObject(
            "SELECT id FROM service_ward WHERE tenant_id = ? AND ward_no = 7", String.class, siwanId);
        String replacement = Ulid.next(clock);
        jdbc.update("INSERT INTO ward_fee_rule (id, tenant_id, ward_id, fulfilment_mode, delivery_fee_paise, "
            + "handling_fee_paise, min_order_paise, active, valid_from) "
            + "VALUES (?, ?, ?, 'PLATFORM', 3000, 1500, 0, true, now() - interval '1 hour')",
            replacement, siwanId, wardId);
        try {
            JsonNode platform = body(post("/api/v1/geo/check", """
                {"city_code": "siwan", "ward_no": 7}""")).get("fees").valueStream()
                .filter(fee -> fee.get("fulfilment_mode").stringValue().equals("PLATFORM"))
                .findFirst().orElseThrow();

            assertThat(money(platform.get("delivery_fee"))).isEqualTo("3000|INR|₹30.00");
            assertThat(money(platform.get("handling_fee"))).isEqualTo("1500|INR|₹15.00");
            assertThat(platform.get("fulfilment_mode").stringValue()).isEqualTo("PLATFORM");
        } finally {
            jdbc.update("DELETE FROM ward_fee_rule WHERE id = ?", replacement);
        }
    }

    private String tenantId(String code) {
        return jdbc.queryForObject("SELECT id FROM tenant WHERE code = ?", String.class, code);
    }

    private List<Integer> wardNumbers(HttpResponse<String> response) {
        return body(response).valueStream().map(ward -> ward.get("ward_no").intValue()).toList();
    }

    /** paise|currency|display, so one assertion covers all three fields §10.4 names. */
    private static String money(JsonNode value) {
        return value.get("paise").longValue() + "|" + value.get("currency").stringValue()
            + "|" + value.get("display").stringValue();
    }

    private JsonNode error(HttpResponse<String> response) {
        return body(response).get("error");
    }
}
