package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §10.5.4's basket, §8's quote and §10.6.1's placement over a socket, against the pilot's own
 * shops and fee rows.
 *
 * <p>Every test shops as a number it invented, so one test cannot inherit another's cart, quote or
 * order. The catalogue is shared, so a test that moves a price or a stock level puts it back.
 */
@DisplayName("§10.5.4 cart, §8 quote and §10.6.1 placement")
class CartQuoteOrderIT extends AbstractIntegrationTest {

    private static final String SIWAN = "siwan";
    private static final int WARD = 12;
    private static final String SALT = "tata-salt-1kg";
    private static final String CHICKEN = "chicken-curry-cut";

    /** A base per run plus a counter, so no number is reused inside §12.8's hour. */
    private static final long RUN_SALT = ThreadLocalRandom.current().nextInt(0, 90_000_000);
    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong();

    @Autowired
    private RecordingOtpProvider otp;

    @AfterEach
    void restoreCatalogue() {
        jdbc.update("UPDATE product SET price_paise = 2800, stock_qty = 30, reserved_qty = 0 "
            + "WHERE code = ?", SALT);
        jdbc.update("UPDATE product SET reserved_qty = 0 WHERE code = ?", CHICKEN);
    }

    @Test
    @DisplayName("a basket has lines and a subtotal until a quote turns it into a total")
    void theBasketBecomesATotalOnlyWhenItIsQuoted() {
        Shopper shopper = shopper();

        HttpResponse<String> cartResponse = get("/api/v1/cart", shopper.browser());
        JsonNode empty = body(cartResponse);
        assertThat(empty.get("lines").size()).isZero();
        assertThat(empty.get("cart").get("ward").get("ward_no").intValue()).isEqualTo(WARD);
        assertThat(empty.has("active_quote")).as("nothing to quote yet, and §10.1 omits it").isFalse();

        JsonNode cart = body(addLine(shopper, SALT, "2"));
        assertThat(cart.get("cart").get("shop").get("code").stringValue()).isEqualTo("gupta-fresh-mart");
        assertThat(cart.get("lines").size()).isEqualTo(1);
        JsonNode line = cart.get("lines").get(0);
        assertThat(line.get("product_code").stringValue()).isEqualTo(SALT);
        assertThat(line.get("qty").decimalValue()).isEqualByComparingTo("2");
        assertThat(line.get("unit_price_at_add").get("paise").longValue()).isEqualTo(2800);
        assertThat(line.get("line_total").get("paise").longValue()).isEqualTo(5600);
        assertThat(cart.get("subtotal").get("paise").longValue()).isEqualTo(5600);
        assertThat(cart.has("active_quote"))
            .as("a basket is not a decision about fees, so it carries no total")
            .isFalse();

        HttpResponse<String> issued = quote(shopper);
        assertThat(issued.statusCode()).isEqualTo(201);
        JsonNode quoteView = body(issued);
        JsonNode quote = quoteView.get("quote");
        JsonNode totals = quote.get("totals");
        assertThat(totals.get("goods").get("paise").longValue()).isEqualTo(5600);
        assertThat(totals.get("delivery_fee").get("paise").longValue()).isEqualTo(2500);
        assertThat(totals.get("handling_fee").get("paise").longValue()).isEqualTo(1000);
        assertThat(totals.get("total").get("paise").longValue()).isEqualTo(9100);
        assertThat(totals.get("commission_illustrative").get("paise").longValue()).isZero();
        assertThat(quote.get("state").stringValue()).isEqualTo("ACTIVE");
        assertThat(quote.get("quote_hash").stringValue()).startsWith("sha256:");
        assertThat(quote.get("fulfilment_mode_snapshot").stringValue()).isEqualTo("PLATFORM");
        assertThat(quote.get("ward").get("ward_no").intValue()).isEqualTo(WARD);
        assertThat(Duration.between(Instant.parse(quote.get("issued_at").stringValue()),
            Instant.parse(quote.get("expires_at").stringValue())))
            .as("the tenant's 30-minute freshness policy").isEqualTo(Duration.ofMinutes(30));

        JsonNode quoteLine = quoteView.get("lines").get(0);
        assertThat(quoteLine.get("price_source").stringValue()).isEqualTo("LIST");
        assertThat(quoteLine.get("line_total").get("paise").longValue()).isEqualTo(5600);
        assertThat(quoteLine.get("price_delta").get("paise").longValue()).isZero();
        assertThat(quoteLine.get("consent_required").booleanValue()).isFalse();
        assertThat(quote.get("consent_required").size()).isZero();
        assertThat(quote.get("fulfilment_options").get(0).get("mode").stringValue())
            .isEqualTo("PLATFORM");
        assertThat(quote.get("fulfilment_options").get(0).get("total").get("paise").longValue())
            .isEqualTo(9100);

        JsonNode withQuote = body(get("/api/v1/cart", shopper.browser()));
        assertThat(withQuote.get("active_quote").get("quote").get("id").stringValue())
            .isEqualTo(quote.get("id").stringValue());

        JsonNode reread = body(get("/api/v1/quotes/" + quote.get("id").stringValue(),
            shopper.browser())).get("quote");
        assertThat(reread.get("quote_hash").stringValue())
            .as("§10.5.4: the consent artifact re-renders to the same total")
            .isEqualTo(quote.get("quote_hash").stringValue());
    }

    @Test
    @DisplayName("a second shop's item is refused with both shops named")
    void aBasketHoldsOneShop() {
        Shopper shopper = shopper();
        addLine(shopper, SALT, "1");

        HttpResponse<String> response = addLine(shopper, CHICKEN, "1");

        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("MIXED_MERCHANT_CART");
        assertThat(error.get("details").get("cart_shop").stringValue()).isEqualTo("Gupta Fresh Mart");
        assertThat(error.get("details").get("added_shop").stringValue())
            .isEqualTo("Siwan Chicken and Meats");
        assertThat(body(get("/api/v1/cart", shopper.browser())).get("lines").size())
            .as("the refusal did not add the line it refused").isEqualTo(1);
    }

    @Test
    @DisplayName("quantity rules are the catalogue's, and by-weight is the one that accepts decimals")
    void quantitiesAreCheckedBeforeTheyReachAQuote() {
        Shopper shopper = shopper();

        HttpResponse<String> overTheShopCap = addLine(shopper, SALT, "11");
        assertThat(overTheShopCap.statusCode()).isEqualTo(409);
        assertThat(error(overTheShopCap).get("code").stringValue()).isEqualTo("MAX_QTY_EXCEEDED");
        assertThat(error(overTheShopCap).get("details").get("max_qty_per_order").intValue())
            .isEqualTo(10);

        HttpResponse<String> fractionalPiece = addLine(shopper, SALT, "1.5");
        assertThat(fractionalPiece.statusCode()).isEqualTo(422);
        assertThat(error(fractionalPiece).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");

        JsonNode chickens = body(addLine(shopper, CHICKEN, "1.5"));
        JsonNode line = chickens.get("lines").get(0);
        assertThat(line.get("by_weight").booleanValue()).isTrue();
        assertThat(line.get("line_total").get("paise").longValue()).isEqualTo(70500);
        assertThat(line.get("line_total").get("approx").booleanValue())
            .as("§9.7: 1.5 kg is an estimate until the shop weighs it").isTrue();

        String lineId = line.get("id").stringValue();
        HttpResponse<String> fourDecimals = patch("/api/v1/cart/lines/" + lineId, shopper.browser(),
            "{\"qty\": 1.0001}");
        assertThat(fourDecimals.statusCode()).isEqualTo(422);
        assertThat(error(fourDecimals).get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("qty");

        HttpResponse<String> aboveTheCap = patch("/api/v1/cart/lines/" + lineId, shopper.browser(),
            "{\"qty\": 6}");
        assertThat(aboveTheCap.statusCode()).isEqualTo(409);
        assertThat(error(aboveTheCap).get("code").stringValue()).isEqualTo("MAX_QTY_EXCEEDED");

        HttpResponse<String> removed = delete("/api/v1/cart/lines/" + lineId, shopper.browser());
        assertThat(removed.statusCode()).isEqualTo(200);
        JsonNode emptied = body(removed);
        assertThat(emptied.get("lines").size()).isZero();
        assertThat(emptied.get("cart").has("id")).isTrue();
        assertThat(emptied.get("cart").has("shop"))
            .as("an empty basket is not bound to a shop").isFalse();
    }

    @Test
    @DisplayName("a placement writes the order, its lines, its trail, and clears the basket")
    void aPlacementIsOneTransaction() {
        Shopper shopper = shopper();
        addLine(shopper, SALT, "2");
        JsonNode quote = quoteOf(shopper);
        String quoteId = quote.get("id").stringValue();
        String quoteHash = quote.get("quote_hash").stringValue();

        HttpResponse<String> placed = place(shopper, newKey(), placementBody(quoteId, quoteHash,
            shopper.phone(), "Ring the bell"));

        assertThat(placed.statusCode()).isEqualTo(201);
        JsonNode placement = body(placed);
        JsonNode order = placement.get("order");
        String code = order.get("code").stringValue();
        assertThat(code).matches("SAATH-\\d{4,}");
        assertThat(order.get("status").stringValue()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(order.get("allowed_actions").get(0).stringValue()).isEqualTo("cancel");
        assertThat(order.get("payment_status").stringValue()).isEqualTo("UNPAID");
        assertThat(order.get("collection_channel").stringValue()).isEqualTo("RIDER_CASH");
        assertThat(order.get("fulfilment_mode_snapshot").stringValue()).isEqualTo("PLATFORM");
        assertThat(order.get("money").get("goods").get("paise").longValue()).isEqualTo(5600);
        assertThat(order.get("money").get("total").get("paise").longValue()).isEqualTo(9100);
        assertThat(order.get("money").get("outstanding").get("paise").longValue()).isEqualTo(9100);
        assertThat(order.get("money").get("collected").get("paise").longValue()).isZero();
        assertThat(order.get("money").get("commission").get("paise").longValue()).isZero();
        assertThat(order.get("lines").get(0).get("product_code").stringValue()).isEqualTo(SALT);
        assertThat(placement.get("quote").get("id").stringValue()).isEqualTo(quoteId);
        assertThat(placement.get("quote").get("state").stringValue()).isEqualTo("ACCEPTED");

        String orderId = jdbc.queryForObject("SELECT id FROM \"order\" WHERE code = ?", String.class, code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_line WHERE order_id = ?",
            Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_event WHERE order_id = ?",
            Integer.class, orderId))
            .as("CREATED and the SYSTEM move to PENDING_CONFIRMATION").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? "
            + "AND event_type = 'ORDER_PLACED'", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM price_quote WHERE id = ?",
            String.class, quoteId)).isEqualTo("ACCEPTED");

        JsonNode cleared = body(get("/api/v1/cart", shopper.browser()));
        assertThat(cleared.get("lines").size()).isZero();
        assertThat(cleared.has("active_quote")).isFalse();
        assertThat(cleared.get("cart").get("id").stringValue())
            .as("the basket itself stays, empty, until the customer fills it again").isNotEmpty();
    }

    @Test
    @DisplayName("a replay answers the original bytes; the same key on another order is refused")
    void retryingAPlacementIsSafeAndReusingTheKeyIsNot() {
        Shopper shopper = shopper();
        addLine(shopper, SALT, "2");
        JsonNode quote = quoteOf(shopper);
        String quoteId = quote.get("id").stringValue();
        String quoteHash = quote.get("quote_hash").stringValue();
        String key = newKey();
        String body = placementBody(quoteId, quoteHash, shopper.phone(), "Ring the bell");

        HttpResponse<String> first = place(shopper, key, body);
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponse<String> replay = place(shopper, key, body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replay").orElse("false")).isEqualTo("true");
        assertThat(replay.body())
            .as("§10.6.1: the replayed body is the original one, not a re-render")
            .isEqualTo(first.body());

        HttpResponse<String> differently = place(shopper, key,
            placementBody(quoteId, quoteHash, shopper.phone(), "Leave it at the gate"));
        assertThat(differently.statusCode()).isEqualTo(409);
        assertThat(error(differently).get("code").stringValue()).isEqualTo("DUPLICATE_SUBMISSION");

        String accountId = accountId(shopper.phone());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"order\" WHERE customer_id = ?",
            Integer.class, accountId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a price that rose after the quote needs consent before the order exists")
    void aPriceIncreaseTurnsthePlacementIntoAConsentRequest() {
        Shopper shopper = shopper();
        addLine(shopper, SALT, "2");
        JsonNode quote = quoteOf(shopper);
        String quoteId = quote.get("id").stringValue();
        String quoteHash = quote.get("quote_hash").stringValue();
        String key = newKey();

        jdbc.update("UPDATE product SET price_paise = 3000 WHERE code = ?", SALT);
        HttpResponse<String> refused = place(shopper, key,
            placementBody(quoteId, quoteHash, shopper.phone(), null));

        assertThat(refused.statusCode()).isEqualTo(409);
        JsonNode error = error(refused);
        assertThat(error.get("code").stringValue()).isEqualTo("PRICE_CHANGED");
        JsonNode details = error.get("details");
        JsonNode fresh = details.get("quote").get("quote");
        assertThat(fresh.get("id").stringValue()).isNotEqualTo(quoteId);
        assertThat(details.get("quote_hash").stringValue()).isNotEqualTo(quoteHash);
        assertThat(fresh.get("totals").get("goods").get("paise").longValue()).isEqualTo(6000);
        assertThat(fresh.get("totals").get("total").get("paise").longValue()).isEqualTo(9500);
        JsonNode consent = fresh.get("consent_required").get(0);
        assertThat(consent.get("code").stringValue()).isEqualTo("PRICE_INCREASED");
        assertThat(consent.get("line_id").stringValue())
            .as("§9.7: the consent names the line of this artifact, not the product it came from")
            .isEqualTo(details.get("quote").get("lines").get(0).get("id").stringValue());
        assertThat(consent.get("from").get("paise").longValue()).isEqualTo(2800);
        assertThat(consent.get("to").get("paise").longValue()).isEqualTo(3000);
        assertThat(details.get("quote").get("lines").get(0).get("consent_required").booleanValue())
            .isTrue();
        assertThat(accountIdOrders(shopper.phone()))
            .as("the refusal wrote nothing but the quote it is asking about").isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM price_quote WHERE id = ?",
            String.class, quoteId)).isEqualTo("SUPERSEDED");

        HttpResponse<String> placed = place(shopper, key,
            placementBody(fresh.get("id").stringValue(),
                fresh.get("quote_hash").stringValue(), shopper.phone(), null));

        assertThat(placed.statusCode()).isEqualTo(201);
        assertThat(body(placed).get("order").get("money").get("total").get("paise").longValue())
            .isEqualTo(9500);
    }

    @Test
    @DisplayName("a stale quote is recorded as expired before the customer is told to refresh")
    void expiryIsLazyAndLeavesEvidence() {
        Shopper shopper = shopper();
        addLine(shopper, SALT, "2");
        String readQuoteId = quoteOf(shopper).get("id").stringValue();
        age(readQuoteId);

        HttpResponse<String> refusedRead = get("/api/v1/quotes/" + readQuoteId, shopper.browser());
        assertThat(refusedRead.statusCode()).isEqualTo(409);
        assertThat(error(refusedRead).get("code").stringValue()).isEqualTo("QUOTE_STALE");
        assertThat(jdbc.queryForObject("SELECT state FROM price_quote WHERE id = ?",
            String.class, readQuoteId))
            .as("§8.2: the row records why the read failed, so the refusal is auditable")
            .isEqualTo("EXPIRED_BY_RULE");

        JsonNode second = quoteOf(shopper);
        String placedQuoteId = second.get("id").stringValue();
        age(placedQuoteId);

        HttpResponse<String> refusedPlacement = place(shopper, newKey(),
            placementBody(placedQuoteId, second.get("quote_hash").stringValue(),
                shopper.phone(), null));
        assertThat(refusedPlacement.statusCode()).isEqualTo(409);
        assertThat(error(refusedPlacement).get("code").stringValue()).isEqualTo("QUOTE_STALE");
        assertThat(jdbc.queryForObject("SELECT state FROM price_quote WHERE id = ?",
            String.class, placedQuoteId)).isEqualTo("EXPIRED_BY_RULE");
        assertThat(accountIdOrders(shopper.phone())).isZero();
    }

    @Test
    @DisplayName("stock vanishing between the quote and the placement refuses the order")
    void aStockDropBetweenQuoteAndPlacementIsRefused() {
        Shopper shopper = shopper();
        addLine(shopper, SALT, "2");
        JsonNode quote = quoteOf(shopper);

        jdbc.update("UPDATE product SET stock_qty = 1 WHERE code = ?", SALT);
        HttpResponse<String> refused = place(shopper, newKey(),
            placementBody(quote.get("id").stringValue(), quote.get("quote_hash").stringValue(),
                shopper.phone(), null));

        assertThat(refused.statusCode()).isEqualTo(409);
        JsonNode error = error(refused);
        assertThat(error.get("code").stringValue()).isEqualTo("STOCK_CHANGED");
        assertThat(error.get("details").get("product_code").stringValue()).isEqualTo(SALT);
        assertThat(error.get("details").get("available_qty").stringValue()).isEqualTo("1");
        assertThat(accountIdOrders(shopper.phone())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM price_quote WHERE id = ?",
            String.class, quote.get("id").stringValue()))
            .as("the transaction rolled back, so the quote was not accepted")
            .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a customer cancels before the shop accepts, once")
    void cancellationIsT5AndOnlyOnce() {
        Shopper shopper = shopper();
        String code = placeOne(shopper);

        HttpResponse<String> cancelled = post("/api/v1/orders/" + code + "/cancel",
            shopper.browser(), "{\"reason\": \"Ordered by mistake\"}");

        assertThat(cancelled.statusCode()).isEqualTo(200);
        JsonNode order = body(cancelled);
        assertThat(order.get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(order.get("allowed_actions").size()).isZero();
        JsonNode last = order.get("timeline").get(order.get("timeline").size() - 1);
        assertThat(last.get("kind").stringValue()).isEqualTo("CANCELLATION");
        assertThat(last.get("status_from").stringValue()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(last.get("status_to").stringValue()).isEqualTo("CANCELLED");
        assertThat(last.get("note").stringValue()).isEqualTo("Ordered by mistake");
        assertThat(last.get("actor").get("label").stringValue()).isEqualTo("You");

        HttpResponse<String> timeline = get("/api/v1/orders/" + code + "/timeline",
            shopper.browser());
        assertThat(timeline.statusCode()).isEqualTo(200);
        assertThat(body(timeline).get("events").size()).isEqualTo(3);
        assertThat(body(timeline).get("order_code").stringValue()).isEqualTo(code);

        HttpResponse<String> again = post("/api/v1/orders/" + code + "/cancel",
            shopper.browser(), "{}");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(error(again).get("code").stringValue()).isEqualTo("INVALID_TRANSITION");
        assertThat(error(again).get("details").get("status").stringValue()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("an order, its timeline and its quote belong to the customer who made them")
    void anotherCustomersOrderIsNotThere() {
        Shopper owner = shopper();
        Shopper stranger = shopper();
        addLine(owner, SALT, "1");
        JsonNode quote = quoteOf(owner);
        HttpResponse<String> placed = place(owner, newKey(),
            placementBody(quote.get("id").stringValue(), quote.get("quote_hash").stringValue(),
                owner.phone(), null));
        String code = body(placed).get("order").get("code").stringValue();

        for (HttpResponse<String> response : List.of(
            get("/api/v1/orders/" + code, stranger.browser()),
            get("/api/v1/orders/" + code + "/timeline", stranger.browser()),
            post("/api/v1/orders/" + code + "/cancel", stranger.browser(), "{}"),
            get("/api/v1/quotes/" + quote.get("id").stringValue(), stranger.browser()))) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(error(response).get("code").stringValue()).isEqualTo("NOT_FOUND");
        }

        assertThat(body(get("/api/v1/orders", stranger.browser())).get("data").size()).isZero();
        assertThat(body(get("/api/v1/orders/" + code, owner.browser())).get("code").stringValue())
            .isEqualTo(code);
    }

    @Test
    @DisplayName("the order list pages by cursor, filters by state, and refuses what it cannot parse")
    void theListIsKeysetPaged() {
        Shopper shopper = shopper();
        String first = placeOne(shopper);
        addLine(shopper, SALT, "1");
        JsonNode secondQuote = quoteOf(shopper);
        HttpResponse<String> secondPlacement = place(shopper, newKey(), placementBody(
            secondQuote.get("id").stringValue(), secondQuote.get("quote_hash").stringValue(),
            shopper.phone(), null));
        String second = body(secondPlacement).get("order").get("code").stringValue();
        assertThat(first).isNotEqualTo(second);

        HttpResponse<String> page1 = get("/api/v1/orders?limit=1", shopper.browser());
        assertThat(page1.statusCode()).isEqualTo(200);
        JsonNode body1 = body(page1);
        assertThat(body1.get("data").size()).isEqualTo(1);
        assertThat(body1.get("page").get("has_more").booleanValue()).isTrue();
        assertThat(body1.get("page").get("limit").intValue()).isEqualTo(1);
        String cursor = body1.get("page").get("next_cursor").stringValue();
        assertThat(cursor).isNotBlank();

        JsonNode body2 = body(get("/api/v1/orders?limit=1&cursor=" + cursor, shopper.browser()));
        assertThat(body2.get("data").size()).isEqualTo(1);
        assertThat(body2.get("page").get("has_more").booleanValue()).isFalse();
        assertThat(List.of(body1.get("data").get(0).get("code").stringValue(),
            body2.get("data").get(0).get("code").stringValue()))
            .containsExactlyInAnyOrder(first, second);

        assertThat(body(get("/api/v1/orders?status=CREATED", shopper.browser())).get("data").size())
            .as("a placed order is past CREATED, so the filter finds nothing").isZero();
        assertThat(body(get("/api/v1/orders?status=PENDING_CONFIRMATION", shopper.browser()))
            .get("data").size()).isEqualTo(2);

        HttpResponse<String> badStatus = get("/api/v1/orders?status=BANANA", shopper.browser());
        assertThat(badStatus.statusCode()).isEqualTo(422);
        assertThat(error(badStatus).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");

        HttpResponse<String> badCursor = get("/api/v1/orders?cursor=not-a-cursor", shopper.browser());
        assertThat(badCursor.statusCode()).isEqualTo(422);
        assertThat(error(badCursor).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");

        assertThat(get("/api/v1/orders?limit=0", shopper.browser()).statusCode()).isEqualTo(422);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private record Shopper(HttpResponse<String> signIn, Map<String, String> browser, String phone) {
    }

    /** A new number, signed in and bound to Gupta's ward, with the cookie and CSRF pair it earned. */
    private Shopper shopper() {
        String phone = freshPhone();
        retireOtpHistoryFor(phone);
        String challengeId = body(post("/api/v1/auth/otp/request", json("phone", phone)))
            .get("challenge_id").stringValue();
        HttpResponse<String> signedIn = post("/api/v1/auth/otp/verify", """
            {"phone": "%s", "challenge_id": "%s", "code": "%s"}"""
            .formatted(phone, challengeId, otp.codeFor(phone)));
        assertThat(signedIn.statusCode()).isEqualTo(200);

        HttpResponse<String> bound = put("/api/v1/me/ward", asBrowser(signedIn), """
            {"city_code": "%s", "ward_no": %d}""".formatted(SIWAN, WARD));
        assertThat(bound.statusCode()).isEqualTo(200);
        return new Shopper(signedIn, asBrowser(signedIn), phone);
    }

    private HttpResponse<String> addLine(Shopper shopper, String productCode, String qty) {
        return post("/api/v1/cart/lines", shopper.browser(), """
            {"product_code": "%s", "qty": %s}""".formatted(productCode, qty));
    }

    private HttpResponse<String> quote(Shopper shopper) {
        return post("/api/v1/quotes", shopper.browser(), "{}");
    }

    /** A quote response is {@code {"quote": ..., "lines": [...]}}; this is the quote itself. */
    private JsonNode quoteOf(Shopper shopper) {
        return body(quote(shopper)).get("quote");
    }

    private HttpResponse<String> place(Shopper shopper, String key, String body) {
        Map<String, String> headers = new HashMap<>(shopper.browser());
        headers.put("Idempotency-Key", key);
        return post("/api/v1/orders", headers, body);
    }

    /** Adds a line, quotes it and places it, answering the order code. */
    private String placeOne(Shopper shopper) {
        addLine(shopper, SALT, "1");
        JsonNode quote = quoteOf(shopper);
        HttpResponse<String> placed = place(shopper, newKey(), placementBody(
            quote.get("id").stringValue(), quote.get("quote_hash").stringValue(),
            shopper.phone(), null));
        assertThat(placed.statusCode()).isEqualTo(201);
        return body(placed).get("order").get("code").stringValue();
    }

    private static String placementBody(String quoteId, String quoteHash, String phone, String note) {
        String deliveryNote = note == null ? ""
            : ", \"delivery_note\": \"" + note + "\"";
        return "{\"quote_id\": \"" + quoteId + "\", \"quote_hash\": \"" + quoteHash + "\", "
            + "\"ward_no\": " + WARD + ", "
            + "\"address\": {\"line1\": \"12 Mithan Market\", \"landmark\": \"Beside the well\", "
            + "\"pincode\": \"841226\"}, "
            + "\"contact_phone\": \"" + phone + "\"" + deliveryNote + "}";
    }

    /** Moves a quote's window into the past without breaking {@code expires_at > issued_at}. */
    private void age(String quoteId) {
        jdbc.update("UPDATE price_quote SET issued_at = now() - interval '2 hours', "
            + "expires_at = now() - interval '1 hour' WHERE id = ?", quoteId);
    }

    private String accountId(String phone) {
        return jdbc.queryForObject("SELECT id FROM user_account WHERE phone = ?", String.class, phone);
    }

    private int accountIdOrders(String phone) {
        return jdbc.queryForObject("SELECT count(*) FROM \"order\" WHERE customer_id = ?",
            Integer.class, accountId(phone));
    }

    private static String newKey() {
        return "it-" + java.util.UUID.randomUUID();
    }

    private static String json(String key, String value) {
        return "{\"" + key + "\": \"" + value + "\"}";
    }

    private static String freshPhone() {
        return String.format("+9197%08d", RUN_SALT + PHONE_SEQUENCE.incrementAndGet());
    }

    private JsonNode error(HttpResponse<String> response) {
        return body(response).get("error");
    }
}
