package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * §6.3's shop edges and §6.2's delivery choice, taken over a socket by two shop desks.
 *
 * <p>Gupta Fresh Mart is the platform-delivered shop with a fixed-quantity product, so it carries the
 * happy path and the fulfilment reselect; Siwan Chicken and Meats sells by weight, so it carries the
 * scales. Two desks also matter to the refusals: a cross-shop read has to answer {@code NOT_FOUND},
 * and that is only provable when another shop is signed in and looking.
 *
 * <p>The customer's own half of the same machine — T8 and T9 — is {@link PriceConsentIT}.
 */
@DisplayName("§6.3 shop edges and §6.2's delivery choice")
class MerchantFulfilmentIT extends AbstractOrderLifecycleTest {

    // ─────────────────────────────────────────────────────────────────────────
    // T3, T6, T11: the platform-delivered order a shop can complete
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the queue, the accept, the kitchen and the handover are one order's four moves")
    void aShopTakesAnOrderToReady() {
        Map<String, String> shop = guptaDesk();
        String code = place(shopper(), SALT, "2");

        JsonNode queue = body(get("/api/v1/m/orders", shop));
        JsonNode row = rowIn(queue, code);
        assertThat(row.get("status").stringValue()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(row.get("merchant").get("code").stringValue()).isEqualTo("gupta-fresh-mart");
        assertThat(row.get("ward").get("ward_no").intValue()).isEqualTo(WARD);
        assertThat(row.get("total").get("paise").longValue()).isEqualTo(SALT_TOTAL);
        assertThat(row.get("collection_channel").stringValue()).isEqualTo("RIDER_CASH");
        assertThat(actions(row)).containsExactly("accept", "reject");

        HttpResponse<String> elsewhere = get("/api/v1/m/orders/" + code, poultryDesk());
        assertThat(elsewhere.statusCode()).isEqualTo(404);
        assertThat(error(elsewhere).get("code").stringValue())
            .as("§10.2: another shop's order is not there, rather than not yours").isEqualTo("NOT_FOUND");
        assertThat(codes(body(get("/api/v1/m/orders", poultryDesk())))).doesNotContain(code);

        JsonNode accepted = body(accept(code, shop));
        assertThat(accepted.get("status").stringValue()).isEqualTo("MERCHANT_ACCEPTED");
        assertThat(accepted.get("money").get("total").get("paise").longValue()).isEqualTo(SALT_TOTAL);
        assertThat(actions(accepted)).contains("prepare", "weigh", "cancel", "fulfilment_mode");
        assertThat(orderRow(code).get("accepted_at")).as("§6.3: T3 stamps the acceptance").isNotNull();
        assertThat(outbox(code, "ORDER_ACCEPTED")).isEqualTo(1);
        assertThat(orderEvents(code)).as("placement's two plus the accept").isEqualTo(3);

        HttpResponse<String> prepared = post("/api/v1/m/orders/" + code + "/prepare", shop, "{}");
        assertThat(prepared.statusCode()).isEqualTo(200);
        assertThat(body(prepared).get("status").stringValue()).isEqualTo("PREPARING");
        assertThat(actions(body(prepared))).containsExactly("ready", "cancel");

        HttpResponse<String> ready = post("/api/v1/m/orders/" + code + "/ready", shop, "{}");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(body(ready).get("status").stringValue()).isEqualTo("READY_FOR_PICKUP");
        assertThat(actions(body(ready)))
            .as("what happens after the handover belongs to the job module, and this build has none")
            .isEmpty();

        assertThat(orderEvents(code)).isEqualTo(5);
        assertThat(auditRows(code, "STATE_TRANSITION"))
            .as("placement's SYSTEM move and the four shop edges").isEqualTo(4);
        assertThat(auditRows(code, "ORDER_CREATED")).isEqualTo(1);
        assertThat(outbox(code, "ORDER_PLACED")).as("T6 and T11 are timeline-only").isEqualTo(1);
        assertThat(outbox(code, "ORDER_READY")).isZero();

        JsonNode customerView = body(get("/api/v1/orders/" + code, shopperOf(code).browser()));
        assertThat(customerView.get("status").stringValue()).isEqualTo("READY_FOR_PICKUP");
        assertThat(customerView.get("timeline").size()).isEqualTo(5);
        assertThat(customerView.get("timeline").get(2).get("actor").get("label").stringValue())
            .as("§10.4: the shop's move is credited to the shop, not to the customer").isEqualTo("Shop");
    }

    @Test
    void anEdgeAlreadyTakenNamesTheActionsThatRemain() {
        Map<String, String> shop = guptaDesk();
        String code = acceptedOrder(SALT, "1", shop);

        HttpResponse<String> refused = post("/api/v1/m/orders/" + code + "/accept", shop, "{}");

        assertThat(refused.statusCode()).isEqualTo(409);
        JsonNode error = error(refused);
        assertThat(error.get("code").stringValue()).isEqualTo("INVALID_TRANSITION");
        assertThat(error.get("details").get("status").stringValue()).isEqualTo("MERCHANT_ACCEPTED");
        assertThat(actions(error.get("details"))).contains("prepare", "weigh", "fulfilment_mode");
    }

    @Test
    void aCustomerSessionGetsForbiddenOnTheShopSurface() {
        Shopper shopper = shopper();
        String code = place(shopper, SALT, "1");

        HttpResponse<String> response = post("/api/v1/m/orders/" + code + "/accept",
            shopper.browser(), "{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(error(response).get("code").stringValue()).isEqualTo("FORBIDDEN");
        assertThat(orderRow(code).get("status"))
            .as("the permission is settled before the row is opened").isEqualTo("PENDING_CONFIRMATION");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T4: the closed reject
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aRejectionNeedsAReasonTheCustomerIsShown() {
        Map<String, String> shop = guptaDesk();
        String code = place(shopper(), SALT, "1");

        HttpResponse<String> noReason = post("/api/v1/m/orders/" + code + "/reject", shop, "{}");
        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(fieldOf(noReason)).isEqualTo("reason");

        HttpResponse<String> invented = post("/api/v1/m/orders/" + code + "/reject", shop,
            "{\"reason\": \"SHOP_IS_HUNGRY\"}");
        assertThat(invented.statusCode()).isEqualTo(422);
        assertThat(field(error(invented))).isEqualTo("reason");
        assertThat(error(invented).get("details").get("field_errors").get(0).get("message").stringValue())
            .as("§6.3: the vocabulary is closed").contains("OUT_OF_STOCK");

        HttpResponse<String> rejected = post("/api/v1/m/orders/" + code + "/reject", shop,
            "{\"reason\": \"out_of_stock\"}");
        assertThat(rejected.statusCode()).isEqualTo(200);
        JsonNode order = body(rejected);
        assertThat(order.get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(actions(order)).isEmpty();
        assertThat(last(order).get("kind").stringValue()).isEqualTo("STATUS");
        assertThat(last(order).get("status_to").stringValue()).isEqualTo("REJECTED");
        assertThat(last(order).get("note").stringValue())
            .as("the reason is what the customer's screen shows").isEqualTo("OUT_OF_STOCK");
        assertThat(orderRow(code).get("reject_reason")).isEqualTo("OUT_OF_STOCK");
        assertThat(orderEvents(code)).isEqualTo(3);
        assertThat(outbox(code, "ORDER_REJECTED")).isEqualTo(1);
        assertThat(activeQuotes(code))
            .as("§6.3: a terminal order cannot leave a total somebody could still accept").isZero();

        HttpResponse<String> again = post("/api/v1/m/orders/" + code + "/reject", shop,
            "{\"reason\": \"OTHER\"}");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(actions(error(again).get("details"))).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T7: the scales
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aBagOverTheToleranceIsRefusedAndSaysWhatTheCeilingWas() {
        Map<String, String> shop = poultryDesk();
        String code = acceptedOrder(CHICKEN, "1", shop);

        HttpResponse<String> refused = weigh(code, shop, "1.9");

        assertThat(refused.statusCode()).isEqualTo(422);
        JsonNode error = error(refused);
        assertThat(error.get("code").stringValue()).isEqualTo("WEIGHT_OUT_OF_TOLERANCE");
        JsonNode details = error.get("details");
        assertThat(details.get("product_code").stringValue()).isEqualTo(CHICKEN);
        assertThat(details.get("qty_ordered").decimalValue()).isEqualByComparingTo("1");
        assertThat(details.get("qty_weighed").decimalValue()).isEqualByComparingTo("1.9");
        assertThat(details.get("max_qty_weighed").decimalValue())
            .as("§7.2: 1 kg × (1 + 250/10000), the ceiling the placement reserved to").isEqualTo("1.025");
        assertThat(details.get("weight_tolerance_bp").intValue()).isEqualTo(250);
        assertThat(field(error)).isEqualTo("lines.qty_weighed");
        assertThat(body(get("/api/v1/m/orders/" + code, shop)).get("status").stringValue())
            .as("the refusal wrote nothing").isEqualTo("MERCHANT_ACCEPTED");
        assertThat(activeQuotes(code)).isZero();
    }

    @Test
    void aWeighedOrderStagesANewTotalWithoutTouchingTheOrdersMoney() {
        Map<String, String> shop = poultryDesk();
        String code = acceptedOrder(CHICKEN, "1", shop);

        HttpResponse<String> noNote = post("/api/v1/m/orders/" + code + "/weigh", shop, """
            {"lines": [{"order_line_id": "%s", "qty_weighed": 0.98}]}""".formatted(lineOf(code)));
        assertThat(noNote.statusCode()).isEqualTo(422);
        assertThat(fieldOf(noNote))
            .as("§13.2: WEIGHED is a ⚑ action, so the shop's own words are mandatory").isEqualTo("note");

        HttpResponse<String> unknownLine = post("/api/v1/m/orders/" + code + "/weigh", shop, """
            {"lines": [{"order_line_id": "01JUNKJUNKJUNKJUNKJUNKJUNK0", "qty_weighed": 0.98}],
             "note": "980 g"}""");
        assertThat(unknownLine.statusCode()).isEqualTo(422);
        assertThat(fieldOf(unknownLine)).isEqualTo("lines.order_line_id");

        HttpResponse<String> weighed = weigh(code, shop, "0.98");

        assertThat(weighed.statusCode()).as(weighed.body()).isEqualTo(200);
        JsonNode order = body(weighed).get("order");
        JsonNode quoteView = body(weighed).get("quote").get("quote");
        assertThat(order.get("status").stringValue()).isEqualTo("PRICE_CONFIRMATION_PENDING");
        assertThat(order.get("money").get("goods").get("paise").longValue()).isEqualTo(47_000L);
        assertThat(order.get("money").get("total").get("paise").longValue())
            .as("§6.3: T7 stages the revised price in a quote; T8 is the edge that rewrites the order")
            .isEqualTo(CHICKEN_PLACED_TOTAL);
        assertThat(actions(order))
            .as("the shop's exit from a total it cannot stand is T9b's reasoned cancel")
            .containsExactly("cancel");

        assertThat(quoteView.get("scope").stringValue()).isEqualTo("WEIGHT");
        assertThat(quoteView.get("state").stringValue()).isEqualTo("ACTIVE");
        assertThat(quoteView.has("expires_at"))
            .as("§6.5: a consent request the customer must answer does not expire").isFalse();
        assertThat(quoteView.get("fulfilment_mode_snapshot").stringValue()).isEqualTo("PLATFORM");
        assertThat(quoteView.get("totals").get("goods").get("paise").longValue())
            .isEqualTo(CHICKEN_WEIGHED_GOODS);
        assertThat(quoteView.get("totals").get("total").get("paise").longValue())
            .isEqualTo(CHICKEN_WEIGHED_TOTAL);
        assertThat(quoteView.get("consent_required").size())
            .as("§8.2: consent is asked when a figure rises, and this one fell").isZero();
        JsonNode quoteLine = body(weighed).get("quote").get("lines").get(0);
        assertThat(quoteLine.get("qty_weighed").decimalValue()).isEqualByComparingTo("0.98");
        assertThat(quoteLine.get("price_delta").get("paise").longValue()).isEqualTo(-940);

        assertThat(orderEvents(code)).isEqualTo(4);
        assertThat(auditRows(code, "WEIGHED")).isEqualTo(1);
        assertThat(outbox(code, "PRICE_CONFIRMATION_REQUESTED")).isEqualTo(1);

        HttpResponse<String> prepare = post("/api/v1/m/orders/" + code + "/prepare", shop, "{}");
        assertThat(prepare.statusCode()).isEqualTo(409);
        assertThat(error(prepare).get("code").stringValue()).isEqualTo("PRICE_CONFIRMATION_REQUIRED");
        assertThat(error(prepare).get("details").get("quote_id").stringValue())
            .as("§10.5.6: the refusal carries the total, so the shop can show the customer what is waiting")
            .isEqualTo(quoteView.get("id").stringValue());

        JsonNode customerView = body(get("/api/v1/orders/" + code, shopperOf(code).browser()));
        assertThat(customerView.get("active_quote").get("quote").get("id").stringValue())
            .isEqualTo(quoteView.get("id").stringValue());
        assertThat(actions(customerView)).containsExactly("price_confirmation");
    }

    @Test
    void anOrderWithNothingToWeighIsToldToPrepareInstead() {
        Map<String, String> shop = guptaDesk();
        String code = acceptedOrder(SALT, "1", shop);

        HttpResponse<String> response = post("/api/v1/m/orders/" + code + "/weigh", shop, """
            {"lines": [{"order_line_id": "%s", "qty_weighed": 1}], "note": "on the scales"}"""
            .formatted(lineOf(code)));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(error(response).get("code").stringValue()).isEqualTo("INVALID_TRANSITION");
        assertThat(error(response).get("message").stringValue()).contains("prepare");

        Map<String, String> poultry = poultryDesk();
        String started = acceptedOrder(CHICKEN, "1", poultry);
        assertThat(post("/api/v1/m/orders/" + started + "/prepare", poultry, "{}").statusCode())
            .isEqualTo(200);

        HttpResponse<String> late = weigh(started, poultry, "0.98");
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(error(late).get("details").get("status").stringValue()).isEqualTo("PREPARING");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §6.2: the delivery choice, while it is still cheap to change
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aModeChangeThatCostsNothingIsAppliedAtOnceAndAsksNobody() {
        Map<String, String> shop = guptaThatDeliversBoth();
        String code = acceptedOrder(SALT, "2", shop);

        HttpResponse<String> response =
            reselect(code, shop, "MERCHANT", "Our boy is already going that way");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode order = body(response).get("order");
        assertThat(order.get("fulfilment_mode_snapshot").stringValue()).isEqualTo("MERCHANT");
        assertThat(order.get("collection_channel").stringValue())
            .as("who holds the customer's cash follows the mode at once").isEqualTo("MERCHANT_CASH");
        assertThat(order.get("money").get("total").get("paise").longValue())
            .as("ward 12 prices both modes at ₹25, so there is nothing to consent to").isEqualTo(SALT_TOTAL);
        assertThat(order.get("status").stringValue()).isEqualTo("MERCHANT_ACCEPTED");
        JsonNode quote = body(response).get("quote").get("quote");
        assertThat(quote.get("scope").stringValue()).isEqualTo("FULFILMENT_CHANGE");
        assertThat(quote.get("state").stringValue()).isEqualTo("ACTIVE");
        assertThat(quote.get("consent_required").size()).isZero();

        JsonNode detail = body(get("/api/v1/m/orders/" + code, shop));
        assertThat(detail.has("active_quote"))
            .as("a requote that raises nothing is a record, not a question, so it is not the total "
                + "anybody is being asked about").isFalse();
        assertThat(actions(detail)).contains("prepare");
        assertThat(last(detail).get("kind").stringValue())
            .as("§6.2 moves no status, so its event is a progress milestone")
            .isEqualTo("FULFILMENT_PROGRESS");
        assertThat(last(detail).get("note").stringValue()).isEqualTo("Our boy is already going that way");
        assertThat(orderEvents(code)).isEqualTo(4);
        assertThat(auditRows(code, "FULFILMENT_MODE_CHANGED")).isEqualTo(1);

        assertThat(post("/api/v1/m/orders/" + code + "/prepare", shop, "{}").statusCode()).isEqualTo(200);
        HttpResponse<String> ready = post("/api/v1/m/orders/" + code + "/ready", shop, "{}");
        assertThat(ready.statusCode()).isEqualTo(409);
        assertThat(error(ready).get("code").stringValue()).isEqualTo("MODE_FORBIDS_TRANSITION");

        HttpResponse<String> dispatched = post("/api/v1/m/orders/" + code + "/dispatch-self", shop, "{}");
        assertThat(dispatched.statusCode()).isEqualTo(200);
        assertThat(body(dispatched).get("status").stringValue()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(actions(body(dispatched))).containsExactly("fail_delivery");
        assertThat(outbox(code, "ORDER_DISPATCHED"))
            .as("§6.3: T12 is timeline-only — there is no job, so there is nothing to offer").isZero();

        HttpResponse<String> locked = reselect(code, shop, "PLATFORM", "Changed my mind");
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(error(locked).get("code").stringValue()).isEqualTo("FULFILMENT_LOCKED");
        assertThat(error(locked).get("details").get("status").stringValue()).isEqualTo("OUT_FOR_DELIVERY");

        HttpResponse<String> failed = post("/api/v1/m/orders/" + code + "/fail-delivery", shop,
            "{\"reason\": \"CUSTOMER_UNREACHABLE\"}");
        assertThat(failed.statusCode()).isEqualTo(200);
        assertThat(body(failed).get("status").stringValue()).isEqualTo("DELIVERY_FAILED");
        assertThat(orderRow(code).get("fail_reason")).isEqualTo("CUSTOMER_UNREACHABLE");
        assertThat(actions(body(failed))).containsExactly("dispatch_self");

        HttpResponse<String> retry = post("/api/v1/m/orders/" + code + "/dispatch-self", shop, "{}");
        assertThat(retry.statusCode()).as("T18b: the same action, the same edge").isEqualTo(200);
        assertThat(body(retry).get("status").stringValue()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(orderEvents(code)).isEqualTo(8);
    }

    @Test
    void aCostlierModeChangeWaitsForTheCustomerBeforeTheOrderMovesOn() {
        jdbc.update("UPDATE ward_fee_rule SET delivery_fee_paise = 4500 WHERE tenant_id = ? "
            + "AND ward_id = ? AND fulfilment_mode = 'MERCHANT'", SIWAN_TENANT, WARD_12);
        Map<String, String> shop = guptaThatDeliversBoth();
        String code = acceptedOrder(SALT, "2", shop);

        HttpResponse<String> response = reselect(code, shop, "MERCHANT", "Two wards over now");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode order = body(response).get("order");
        JsonNode quote = body(response).get("quote").get("quote");
        assertThat(order.get("fulfilment_mode_snapshot").stringValue()).isEqualTo("MERCHANT");
        assertThat(order.get("money").get("total").get("paise").longValue())
            .as("§8.3: a higher fee is charged at T8, not one moment before it is agreed")
            .isEqualTo(SALT_TOTAL);
        assertThat(quote.get("totals").get("total").get("paise").longValue()).isEqualTo(11_100L);
        assertThat(quote.get("consent_required").get(0).get("code").stringValue())
            .isEqualTo("FULFILMENT_MODE_CHANGE");
        assertThat(quote.get("consent_required").get(0).get("from").get("paise").longValue())
            .isEqualTo(SALT_TOTAL);
        assertThat(quote.get("consent_required").get(0).get("to").get("paise").longValue())
            .isEqualTo(11_100L);
        assertThat(actions(order)).contains("cancel", "fulfilment_mode").doesNotContain("prepare");

        HttpResponse<String> prepare = post("/api/v1/m/orders/" + code + "/prepare", shop, "{}");
        assertThat(prepare.statusCode()).isEqualTo(409);
        assertThat(error(prepare).get("code").stringValue()).isEqualTo("PRICE_CONFIRMATION_REQUIRED");
        assertThat(post("/api/v1/m/orders/" + code + "/dispatch-self", shop, "{}").statusCode())
            .as("T12 stands on the same gate").isEqualTo(409);

        HttpResponse<String> answer = post("/api/v1/orders/" + code + "/price-confirmation",
            shopperOf(code).browser(), """
                {"decision": "ACCEPT", "quote_id": "%s", "quote_hash": "%s"}"""
                .formatted(quote.get("id").stringValue(), quote.get("quote_hash").stringValue()));

        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode consented = body(answer);
        assertThat(consented.get("status").stringValue())
            .as("the order never left MERCHANT_ACCEPTED, so it does not arrive at PREPARING either")
            .isEqualTo("MERCHANT_ACCEPTED");
        assertThat(consented.get("money").get("delivery_fee").get("paise").longValue()).isEqualTo(4_500L);
        assertThat(consented.get("money").get("total").get("paise").longValue()).isEqualTo(11_100L);
        assertThat(consented.get("money").get("outstanding").get("paise").longValue()).isEqualTo(11_100L);
        assertThat(consented.has("active_quote"))
            .as("§10.4: the consent request is answered, so there is no question left on the order")
            .isFalse();
        assertThat(actions(consented))
            .as("a customer's own list never names the shop's actions")
            .isEmpty();
        assertThat(last(consented).get("kind").stringValue())
            .as("a consent that moved no status is still progress on the timeline")
            .isEqualTo("FULFILMENT_PROGRESS");

        assertThat(actions(body(get("/api/v1/m/orders/" + code, shop)))).contains("prepare");

        assertThat(post("/api/v1/m/orders/" + code + "/prepare", shop, "{}").statusCode()).isEqualTo(200);
        assertThat(auditRows(code, "QUOTE_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void aReselectIsRefusedWithoutAReasonForTheWrongModeAndAfterTheLock() {
        Map<String, String> shop = guptaDesk();
        String code = acceptedOrder(SALT, "1", shop);

        HttpResponse<String> noReason = reselect(code, shop, "MERCHANT", null);
        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(fieldOf(noReason)).isEqualTo("reason");

        HttpResponse<String> notAvailable = reselect(code, shop, "MERCHANT", "We can drive it");
        assertThat(notAvailable.statusCode()).isEqualTo(409);
        assertThat(error(notAvailable).get("code").stringValue()).isEqualTo("MODE_NOT_AVAILABLE");
        assertThat(error(notAvailable).get("details").get("fulfilment_capability").stringValue())
            .isEqualTo("PLATFORM_ONLY");
        assertThat(activeQuotes(code)).as("the refusal issued no quote").isZero();

        HttpResponse<String> sameMode = reselect(code, shop, "PLATFORM", "Our own van");
        assertThat(sameMode.statusCode()).isEqualTo(422);
        assertThat(fieldOf(sameMode)).isEqualTo("fulfilment_mode");

        HttpResponse<String> nonsense = reselect(code, shop, "DRONE", "Test");
        assertThat(nonsense.statusCode()).isEqualTo(422);
        assertThat(fieldOf(nonsense)).isEqualTo("fulfilment_mode");
        assertThat(error(nonsense).get("details").get("field_errors").get(0).get("message").stringValue())
            .isEqualTo("must be PLATFORM or MERCHANT");

        assertThat(post("/api/v1/m/orders/" + code + "/prepare", shop, "{}").statusCode()).isEqualTo(200);
        HttpResponse<String> locked = reselect(code, shop, "MERCHANT", "Too late");
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(error(locked).get("code").stringValue()).isEqualTo("FULFILMENT_LOCKED");

        String ended = place(shopper(), SALT, "1");
        assertThat(post("/api/v1/orders/" + ended + "/cancel", shopperOf(ended).browser(),
            "{\"reason\": \"Ordered by mistake\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> afterTheEnd = reselect(ended, shop, "MERCHANT", "After the end");
        assertThat(afterTheEnd.statusCode()).isEqualTo(409);
        assertThat(error(afterTheEnd).get("code").stringValue()).isEqualTo("INVALID_TRANSITION");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T10 and T9b: the shop ends an order it already took
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aShopThatCancelsAfterAcceptingHasToSayWhy() {
        Map<String, String> shop = guptaDesk();
        String code = acceptedOrder(SALT, "2", shop);

        assertThat(post("/api/v1/m/orders/" + code + "/cancel", shop, "{}").statusCode()).isEqualTo(422);

        HttpResponse<String> cancelled = post("/api/v1/m/orders/" + code + "/cancel", shop,
            "{\"reason\": \"OUT_OF_STOCK\"}");

        assertThat(cancelled.statusCode()).isEqualTo(200);
        JsonNode order = body(cancelled);
        assertThat(order.get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(actions(order)).isEmpty();
        assertThat(last(order).get("kind").stringValue()).isEqualTo("CANCELLATION");
        assertThat(orderRow(code).get("cancel_reason")).isEqualTo("OUT_OF_STOCK");
        assertThat(orderRow(code).get("cancelled_at")).isNotNull();
        assertThat(outbox(code, "ORDER_CANCELLED_BY_SHOP"))
            .as("§6.3: the shop's cancel is not the customer's event type").isEqualTo(1);

        HttpResponse<String> again = post("/api/v1/m/orders/" + code + "/cancel", shop,
            "{\"reason\": \"OTHER\"}");
        assertThat(again.statusCode()).isEqualTo(409);

        Map<String, String> poultry = poultryDesk();
        String weighed = acceptedOrder(CHICKEN, "1", poultry);
        assertThat(weigh(weighed, poultry, "1.02").statusCode()).isEqualTo(200);

        HttpResponse<String> gaveUp = post("/api/v1/m/orders/" + weighed + "/cancel", poultry,
            "{\"reason\": \"OUT_OF_STOCK\"}");
        assertThat(gaveUp.statusCode())
            .as("T9b is this same edge from the status the scales produced").isEqualTo(200);
        assertThat(body(gaveUp).get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(activeQuotes(weighed))
            .as("the weighed total the customer never answered closes with the order").isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §10.5.6's queue filters
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void theQueueFiltersByStateAndByWardAndRefusesAFilterItCannotParse() {
        Map<String, String> shop = guptaDesk();
        String waiting = place(shopper(), SALT, "1");
        String preparing = acceptedOrder(SALT, "1", shop);
        assertThat(post("/api/v1/m/orders/" + preparing + "/prepare", shop, "{}").statusCode())
            .isEqualTo(200);

        assertThat(codes(body(get("/api/v1/m/orders", shop)))).contains(waiting, preparing);
        JsonNode pending = body(get("/api/v1/m/orders?status=PENDING_CONFIRMATION", shop));
        assertThat(codes(pending)).contains(waiting).doesNotContain(preparing);
        assertThat(codes(body(get("/api/v1/m/orders?ward=12", shop)))).contains(waiting, preparing);

        HttpResponse<String> otherWard = get("/api/v1/m/orders?ward=99", shop);
        assertThat(otherWard.statusCode()).isEqualTo(422);
        assertThat(fieldOf(otherWard))
            .as("an empty board would be a wrong fact about the city, not an empty answer").isEqualTo("ward");
        assertThat(get("/api/v1/m/orders?status=BANANA", shop).statusCode()).isEqualTo(422);

        JsonNode page = body(get("/api/v1/m/orders?limit=1", shop));
        assertThat(page.get("data").size()).isEqualTo(1);
        assertThat(page.get("page").get("has_more").booleanValue()).isTrue();
        JsonNode next = body(get("/api/v1/m/orders?limit=1&cursor="
            + page.get("page").get("next_cursor").stringValue(), shop));
        assertThat(next.get("data").get(0).get("code").stringValue())
            .isNotEqualTo(page.get("data").get(0).get("code").stringValue());
    }
}
