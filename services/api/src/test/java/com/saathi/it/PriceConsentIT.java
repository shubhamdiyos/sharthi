package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * §10.6.2: the customer's answer to a total the shop changed.
 *
 * <p>T8 and T9 are the only two edges in §6.3 a customer takes after placement, and T8 is the only
 * write in the whole kernel that rewrites an order's money — which is why the pair
 * {@code (quote_id, quote_hash)} is the consent and not merely a reference. A test of this endpoint is
 * a test of that pair: the accept must move exactly the figures the hash covered, the decline must end
 * the order without collecting anything, and an answer written against a different artifact must be
 * refused before it reaches either.
 *
 * <p>The shop's half of the same machine — T3 to T12 and §6.2's reselect — is
 * {@link MerchantFulfilmentIT}.
 */
@DisplayName("§10.6.2 the price confirmation")
class PriceConsentIT extends AbstractOrderLifecycleTest {

    // ─────────────────────────────────────────────────────────────────────────
    // T8: the accept
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a customer who agrees to the weighed bag is charged for the bag they get")
    void theWeighedTotalBecomesTheOrdersWhenTheCustomerAccepts() {
        Map<String, String> shop = poultryDesk();
        String code = acceptedOrder(CHICKEN, "1", shop);
        JsonNode quote = body(weigh(code, shop, "0.98")).get("quote").get("quote");
        String quoteId = quote.get("id").stringValue();
        String customerId = (String) orderRow(code).get("customer_id");

        // Lowercase on purpose: §10.6.2 names the two decisions, it does not demand their spelling.
        HttpResponse<String> answer = post("/api/v1/orders/" + code + "/price-confirmation",
            shopperOf(code).browser(), """
                {"decision": "accept", "quote_id": "%s", "quote_hash": "%s"}"""
                .formatted(quoteId, quote.get("quote_hash").stringValue()));

        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode order = body(answer);
        assertThat(order.get("status").stringValue())
            .as("§6.3: T8 resumes the order — the shop stopped to weigh, and the answer unblocks it")
            .isEqualTo("PREPARING");
        assertThat(order.get("money").get("goods").get("paise").longValue())
            .isEqualTo(CHICKEN_WEIGHED_GOODS);
        assertThat(order.get("money").get("total").get("paise").longValue())
            .as("this is the one edge that rewrites the order's money")
            .isEqualTo(CHICKEN_WEIGHED_TOTAL);
        assertThat(order.get("money").get("collected").get("paise").longValue()).isZero();
        assertThat(order.get("money").get("outstanding").get("paise").longValue())
            .isEqualTo(CHICKEN_WEIGHED_TOTAL);
        assertThat(order.has("active_quote")).isFalse();

        JsonNode line = order.get("lines").get(0);
        assertThat(line.get("qty_weighed").decimalValue()).isEqualByComparingTo("0.98");
        assertThat(line.get("line_total").get("paise").longValue()).isEqualTo(CHICKEN_WEIGHED_GOODS);
        assertThat(line.get("price_source").stringValue())
            .as("§8.1: the line no longer claims a list price it did not charge").isEqualTo("WEIGHED");
        assertThat(jdbc.queryForObject(
            "SELECT consented_at FROM order_line WHERE id = ?", String.class, line.get("id").stringValue()))
            .as("when the customer agreed to this quantity, for §13.3 to answer later")
            .isNotNull();

        assertThat(last(order).get("kind").stringValue()).isEqualTo("CONSENT");
        assertThat(last(order).get("status_from").stringValue()).isEqualTo("PRICE_CONFIRMATION_PENDING");
        assertThat(last(order).get("status_to").stringValue()).isEqualTo("PREPARING");
        assertThat(orderEvents(code))
            .as("placement's two, the accept, the scales and this answer").isEqualTo(5);

        Map<String, Object> acceptedQuote = jdbc.queryForMap(
            "SELECT state, accepted_by, accepted_at FROM price_quote WHERE id = ?", quoteId);
        assertThat(acceptedQuote.get("state")).isEqualTo("ACCEPTED");
        assertThat(acceptedQuote.get("accepted_by")).isEqualTo(customerId);
        assertThat(acceptedQuote.get("accepted_at")).isNotNull();
        assertThat(auditRows(code, "QUOTE_ACCEPTED")).isEqualTo(1);
        assertThat(auditRows(code, "WEIGHED")).isEqualTo(1);

        JsonNode shopView = body(get("/api/v1/m/orders/" + code, shop));
        assertThat(actions(shopView))
            .as("the kitchen is open again, and the mode is no longer selectable — the lock is PREPARING")
            .containsExactly("ready", "cancel");
    }

    @Test
    void anAnswerWrittenAgainstADifferentTotalIsRefusedAndNamesTheOneOnTheOrder() {
        Map<String, String> shop = poultryDesk();
        String code = acceptedOrder(CHICKEN, "1", shop);
        JsonNode quote = body(weigh(code, shop, "0.98")).get("quote").get("quote");
        String quoteId = quote.get("id").stringValue();
        String liveHash = jdbc.queryForObject("SELECT quote_hash FROM price_quote WHERE id = ?",
            String.class, quoteId);
        Map<String, String> customer = shopperOf(code).browser();

        HttpResponse<String> tampered = post("/api/v1/orders/" + code + "/price-confirmation",
            customer, """
                {"decision": "ACCEPT", "quote_id": "%s", "quote_hash": "%s"}"""
                .formatted(quoteId, "0".repeat(64)));
        assertThat(tampered.statusCode()).isEqualTo(409);
        JsonNode refused = error(tampered);
        assertThat(refused.get("code").stringValue()).isEqualTo("QUOTE_STALE");
        assertThat(refused.get("details").get("quote_id").stringValue()).isEqualTo(quoteId);
        assertThat(refused.get("details").get("quote_hash").stringValue()).contains(liveHash);
        assertThat(refused.get("details").get("decision").stringValue())
            .as("the refusal says which half of the answer was refused, so the screen re-asks the right one")
            .isEqualTo("ACCEPT");

        // The quote this order was placed against is the artifact a stale client is most likely to
        // still be holding: it is ACCEPTED, it belongs to the order, and its hash still verifies.
        HttpResponse<String> placement = post("/api/v1/orders/" + code + "/price-confirmation",
            customer, """
                {"decision": "ACCEPT", "quote_id": "%s", "quote_hash": "%s"}"""
                .formatted(orderRow(code).get("quote_id"), liveHash));
        assertThat(placement.statusCode()).isEqualTo(409);
        assertThat(error(placement).get("code").stringValue()).isEqualTo("QUOTE_STALE");

        HttpResponse<String> unnamed = post("/api/v1/orders/" + code + "/price-confirmation",
            customer, "{\"decision\": \"ACCEPT\"}");
        assertThat(unnamed.statusCode()).isEqualTo(409);
        assertThat(error(unnamed).get("code").stringValue()).isEqualTo("QUOTE_STALE");

        assertThat(body(get("/api/v1/orders/" + code, customer)).get("status").stringValue())
            .as("none of the three refusals moved the order").isEqualTo("PRICE_CONFIRMATION_PENDING");
        assertThat(orderRow(code).get("total_paise")).isEqualTo(CHICKEN_PLACED_TOTAL);
        assertThat(activeQuotes(code)).isEqualTo(1);
        assertThat(orderEvents(code)).isEqualTo(4);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T9: the decline
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void decliningTheWeighedTotalEndsTheOrderAndCollectsNothing() {
        Map<String, String> shop = poultryDesk();
        String code = acceptedOrder(CHICKEN, "1", shop);
        JsonNode quote = body(weigh(code, shop, "0.98")).get("quote").get("quote");

        HttpResponse<String> answer = post("/api/v1/orders/" + code + "/price-confirmation",
            shopperOf(code).browser(), """
                {"decision": "DECLINE", "quote_id": "%s", "quote_hash": "%s"}"""
                .formatted(quote.get("id").stringValue(), quote.get("quote_hash").stringValue()));

        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode order = body(answer);
        assertThat(order.get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(actions(order)).isEmpty();
        assertThat(order.get("payment_status").stringValue()).isEqualTo("UNPAID");
        assertThat(order.get("money").get("collected").get("paise").longValue())
            .as("§6.3: nothing is refunded, because a COD order that was never delivered collected nothing")
            .isZero();
        assertThat(last(order).get("kind").stringValue()).isEqualTo("CANCELLATION");
        assertThat(last(order).get("note").stringValue()).isEqualTo("PRICE_DECLINED");

        Map<String, Object> row = orderRow(code);
        assertThat(row.get("cancel_reason")).isEqualTo("PRICE_DECLINED");
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(row.get("total_paise"))
            .as("the order is cancelled at the price it was placed at, not at the one declined")
            .isEqualTo(CHICKEN_PLACED_TOTAL);

        assertThat(auditRows(code, "QUOTE_DECLINED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT reason_code FROM audit_event WHERE entity = 'order' AND action = 'QUOTE_DECLINED' "
                + "AND entity_id = ?", String.class, row.get("id")))
            .as("§13.2: QUOTE_DECLINED is a ⚑ action, so its row cannot exist without a reason")
            .isEqualTo("PRICE_DECLINED");
        assertThat(outbox(code, "ORDER_CANCELLED")).isEqualTo(1);
        Map<String, Object> message = jdbc.queryForMap(
            "SELECT payload ->> 'declined_total_paise' AS declined, payload ->> 'quote_id' AS quote,"
                + " payload ->> 'cancel_reason' AS reason, payload ->> 'status' AS status"
                + " FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ORDER_CANCELLED'",
            row.get("id"));
        assertThat(message).containsEntry("declined", "49560")
            .containsEntry("quote", quote.get("id").stringValue())
            .containsEntry("reason", "PRICE_DECLINED")
            .containsEntry("status", "CANCELLED");

        assertThat(jdbc.queryForObject("SELECT state FROM price_quote WHERE id = ?", String.class,
            quote.get("id").stringValue()))
            .as("§6.3: a terminal order cannot leave a total somebody could still accept")
            .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM stock_reservation WHERE order_id = ? AND state = 'HELD'",
            Integer.class, row.get("id")))
            .as("§7.2: the cancel releases what the scales were holding").isZero();

        HttpResponse<String> again = post("/api/v1/orders/" + code + "/price-confirmation",
            shopperOf(code).browser(), "{\"decision\": \"DECLINE\"}");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(error(again).get("code").stringValue()).isEqualTo("INVALID_TRANSITION");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // who may answer, and what an answer cannot be
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void onlyTheCustomerOfThatOrderHasAQuestionToAnswer() {
        Map<String, String> shop = poultryDesk();
        String code = acceptedOrder(CHICKEN, "1", shop);
        JsonNode quote = body(weigh(code, shop, "0.98")).get("quote").get("quote");
        String body = """
            {"decision": "ACCEPT", "quote_id": "%s", "quote_hash": "%s"}"""
            .formatted(quote.get("id").stringValue(), quote.get("quote_hash").stringValue());

        HttpResponse<String> stranger = post("/api/v1/orders/" + code + "/price-confirmation",
            shopper().browser(), body);
        assertThat(stranger.statusCode())
            .as("§10.2: an order code that answers 403 is a code that exists, and it is on the receipt")
            .isEqualTo(404);
        assertThat(error(stranger).get("code").stringValue()).isEqualTo("NOT_FOUND");

        HttpResponse<String> desk = post("/api/v1/orders/" + code + "/price-confirmation",
            guptaDesk(), body);
        assertThat(desk.statusCode()).isEqualTo(403);
        assertThat(error(desk).get("code").stringValue())
            .as("the shop raised this total; consenting to it was never its to give")
            .isEqualTo("FORBIDDEN");

        assertThat(activeQuotes(code)).as("neither refusal answered anything").isEqualTo(1);
    }

    @Test
    void anOrderWithNothingWaitingForAnAnswerSaysSoAndNamesWhatRemains() {
        String code = place(shopper(), SALT, "1");
        Map<String, String> customer = shopperOf(code).browser();

        HttpResponse<String> refused = post("/api/v1/orders/" + code + "/price-confirmation",
            customer, "{\"decision\": \"ACCEPT\"}");
        assertThat(refused.statusCode()).isEqualTo(409);
        JsonNode error = error(refused);
        assertThat(error.get("code").stringValue()).isEqualTo("INVALID_TRANSITION");
        assertThat(error.get("details").get("status").stringValue()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(actions(error.get("details"))).containsExactly("cancel");

        HttpResponse<String> nonsense = post("/api/v1/orders/" + code + "/price-confirmation",
            customer, "{\"decision\": \"MAYBE\"}");
        assertThat(nonsense.statusCode()).isEqualTo(422);
        assertThat(fieldOf(nonsense)).isEqualTo("decision");
        assertThat(error(nonsense).get("details").get("field_errors").get(0).get("message").stringValue())
            .as("§10.6.2: the vocabulary is closed, and it is the same two words either way round")
            .isEqualTo("must be ACCEPT or DECLINE");
    }

    @Test
    void theCustomerCannotCancelTheirWayOutOfAWeighedTotal() {
        Map<String, String> shop = poultryDesk();
        String code = acceptedOrder(CHICKEN, "1", shop);
        body(weigh(code, shop, "0.98"));

        HttpResponse<String> cancelled = post("/api/v1/orders/" + code + "/cancel",
            shopperOf(code).browser(), "{\"reason\": \"Found it cheaper next door\"}");

        assertThat(cancelled.statusCode()).isEqualTo(409);
        JsonNode error = error(cancelled);
        assertThat(error.get("code").stringValue()).isEqualTo("PRICE_CONFIRMATION_REQUIRED");
        assertThat(actions(error.get("details"))).containsExactly("price_confirmation");
        assertThat(orderRow(code).get("cancelled_at"))
            .as("T5 does not half-run: the decline is the other endpoint's, and this order has no cancel on it")
            .isNull();

        // The shop's own way out of the same status is T9b, which is a cancel with a reason.
        HttpResponse<String> byShop = post("/api/v1/m/orders/" + code + "/cancel", shop,
            "{\"reason\": \"OUT_OF_STOCK\"}");
        assertThat(byShop.statusCode()).isEqualTo(200);
        assertThat(body(byShop).get("status").stringValue()).isEqualTo("CANCELLED");
    }
}
