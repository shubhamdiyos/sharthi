package com.saathi.order.domain;

import com.saathi.shared.tenant.PrincipalKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the order's timeline (§5.5).
 *
 * <p>The trail is append-only: a transition that is not recorded here is a transition support
 * cannot explain. {@code fromStatus} is null only for the creation event, and a self-edge is legal
 * only for {@code FULFILMENT_PROGRESS} — the same two rules the database repeats as constraints.
 */
@Entity
@Table(name = "order_event")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class OrderEvent {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_id", length = 26, nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private OrderEventKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private PrincipalKind actorType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "actor_id", length = 26)
    private String actorId;

    @Column(name = "permission_used")
    private String permissionUsed;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "note")
    private String note;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", length = 26)
    private String requestId;
}
