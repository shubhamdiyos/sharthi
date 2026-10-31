package com.saathi.order.service;

import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEvent;
import com.saathi.order.domain.OrderEventKind;
import com.saathi.order.domain.OrderStatus;
import com.saathi.order.repository.OrderEventRepository;
import com.saathi.order.repository.OrderRepository;
import com.saathi.quote.service.QuoteService;
import com.saathi.shared.audit.AuditEntry;
import com.saathi.shared.audit.AuditRecorder;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.outbox.OutboxRecorder;
import com.saathi.shared.tenant.PrincipalKind;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The one writer of a status edge: the column, the {@code order_event} row, the {@code audit_event}
 * row, the outbox message, and the consent request an ending order can no longer be holding.
 *
 * <p>Every edge in §6.3 is the same writes with different actors, and the failure mode of letting
 * each action method repeat them is the one this kernel cannot afford — an order whose status moved
 * but whose timeline did not is exactly the "what did the customer see" question that §13.1 promises
 * an answer to. A caller that forgets one of them now has to go out of its way to do it.
 *
 * <p>An edge whose {@code to} equals the current status is §6.3's T13 and §6.2's reselect: the
 * status column does not move, so the transition trigger has nothing to refuse, and the row is a
 * progress milestone rather than a change. It is still written here, because a milestone is a
 * timeline entry and a timeline entry is this class's job.
 *
 * <p>The audit row is here rather than at each call site because §13.1's rule is that a write
 * commits with its audit row or does not commit. An edge that could be recorded without one would
 * be an edge somebody eventually takes without one.
 *
 * <p>{@code MANDATORY} because a timeline entry outside the mutation's transaction is a lie about
 * ordering: the event would be able to commit while the status rolled back.
 */
@Service
@RequiredArgsConstructor
public class OrderTrail {

    /** §13.2's order-domain action for an edge with nothing more specific to call it. */
    public static final String STATE_TRANSITION = "STATE_TRANSITION";

    private final OrderRepository orders;
    private final OrderEventRepository events;
    private final OutboxRecorder outbox;
    private final AuditRecorder audit;
    private final QuoteService quotes;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * One edge. {@code order} must already carry whatever fields this transition owns —
     * {@code acceptedAt}, a reason code — because the trail writes them in the same flush and the
     * event then describes them.
     *
     * @param outboxType null when the edge is timeline-only (T6 and T11 have no subscriber)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderEvent record(Edge edge) {
        Order order = edge.order();
        Instant at = clock.instant();
        OrderStatus from = order.getStatus();
        // order_event_self_edge: a row that changed nothing is only evidence when it is progress.
        // Checked here as well as in the database so the caller that passed the wrong kind gets its
        // own name in the message rather than a constraint violation from a flush.
        if (from == edge.to() && edge.kind() != OrderEventKind.FULFILMENT_PROGRESS) {
            throw new IllegalArgumentException("saathi: order " + order.getCode()
                + " records a " + edge.kind() + " event that changes no status");
        }
        order.setStatus(edge.to());
        orders.saveAndFlush(order);
        // §6.3's terminal edges release the stock and end the order; a consent request still
        // ACTIVE on one of them is a total somebody could still accept on an order that is gone.
        if (terminal(edge.to())) {
            quotes.supersedeOrder(order.getId());
        }

        OrderEvent event = OrderEvent.builder()
            .id(Ulid.next(clock))
            .tenantId(order.getTenantId())
            .orderId(order.getId())
            .fromStatus(from)
            .toStatus(edge.to())
            .kind(edge.kind())
            .actorType(edge.actor())
            .actorId(edge.actorId())
            .permissionUsed(edge.permission())
            .occurredAt(at)
            .note(edge.note())
            .requestId(edge.requestId())
            .build();
        events.saveAndFlush(event);

        audit(edge, order, from, at);

        if (edge.outboxType() != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("order_id", order.getId());
            payload.put("code", order.getCode());
            payload.put("status", edge.to().name());
            payload.put("total_paise", order.getTotalPaise());
            if (edge.extras() != null) {
                payload.putAll(edge.extras());
            }
            outbox.record(order.getTenantId(), "order", order.getId(), edge.outboxType(),
                json.writeValueAsString(payload));
        }
        return event;
    }

    /**
     * §13.1's evidence row. {@code before_json}/{@code after_json} default to the status pair, which
     * is what every edge changes; an edge that also moves money or a mode passes its own snapshot so
     * the row says what else the action did (§13.3's field allowlist is per action, and a status-only
     * snapshot of a fulfilment change would be an incomplete answer to a real dispute).
     */
    private void audit(Edge edge, Order order, OrderStatus from, Instant at) {
        audit(order, edge.actor(), edge.actorId(),
            edge.auditAction() == null ? STATE_TRANSITION : edge.auditAction(),
            edge.auditBefore() == null ? Map.of("status", from.name()) : edge.auditBefore(),
            edge.auditAfter() == null ? Map.of("status", edge.to().name()) : edge.auditAfter(),
            edge.reasonCode(), edge.requestId(), at);
    }

    /**
     * The same evidence row for the write that is not an edge: {@code POST /orders} inserts the
     * order at {@code CREATED}, which is a row coming into existence rather than a status moving.
     * Exposed rather than duplicated, because §13.1's rule does not have an exception for the
     * placement and a second builder of these rows is how two of them start disagreeing about the
     * entity name.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void audit(Order order, PrincipalKind actor, String actorId, String action,
                      Map<String, Object> before, Map<String, Object> after, String reasonCode,
                      String requestId, Instant at) {
        AuditEntry entry = AuditEntry.builder()
            .tenantId(order.getTenantId())
            .actorType(actor)
            .actorId(actorId)
            .action(action)
            .entity("order")
            .entityId(order.getId())
            .beforeJson(before == null ? null : json.writeValueAsString(before))
            .afterJson(after == null ? null : json.writeValueAsString(after))
            // §6.3 forces a reason where the action has one; the ⚑ actions re-assert it here so a
            // caller that skipped its own gate cannot write the row at all.
            .reasonCode(reasonCode)
            .requestId(requestId)
            .occurredAt(at)
            .build();
        entry.requireReasonCode();
        audit.record(entry);
    }

    /** §6.1's three end states. Nothing after them is an edge, only a ticket (§6.7). */
    private static boolean terminal(OrderStatus status) {
        return status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED
            || status == OrderStatus.DELIVERED;
    }

    /**
     * @param to        the status after the edge; equal to the order's current status for a
     *                  milestone, which requires {@code kind} to be {@code FULFILMENT_PROGRESS}
     * @param outboxType null for an edge with no subscriber yet (§6.3 names one for some edges only)
     */
    @Builder
    public record Edge(
        Order order,
        OrderStatus to,
        OrderEventKind kind,
        PrincipalKind actor,
        String actorId,
        String permission,
        String note,
        String requestId,
        String outboxType,
        Map<String, Object> extras,
        String auditAction,
        String reasonCode,
        Map<String, Object> auditBefore,
        Map<String, Object> auditAfter) {
    }
}
