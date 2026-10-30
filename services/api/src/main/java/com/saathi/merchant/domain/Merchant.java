package com.saathi.merchant.domain;

import com.saathi.tenancy.domain.FulfilmentMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One shop inside one city (§5.3).
 *
 * <p>Two fields decide whether it is on the storefront, and they are independent on purpose: the
 * {@code state} is the operator's judgement about the business, and {@code hiddenReason} is the
 * platform's decision to keep an approved shop out of search (PRD §5.4). A shop with
 * {@code state = APPROVED} and a hidden reason is invisible and still open for existing orders.
 *
 * <p>{@code operatingHoursJson} and {@code selfDeliverZonesJson} are stored as text and parsed by
 * the service that reads them. Hibernate's JSON mapping would need a Jackson 2 format mapper while
 * this application serialises with Jackson 3, and a column that silently fails to round-trip is a
 * worse trade than an explicit parse.
 */
@Entity
@Table(name = "merchant")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Merchant {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "trade_name", nullable = false)
    private String tradeName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_id", length = 26, nullable = false)
    private String categoryId;

    /** Null until an owner accepts an invitation (§12.3); an applicant is not yet an account. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "owner_user_id", length = 26)
    private String ownerUserId;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "address_text", nullable = false)
    private String addressText;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ward_id", length = 26)
    private String wardId;

    @Column(name = "lat")
    private BigDecimal lat;

    @Column(name = "lng")
    private BigDecimal lng;

    @Column(name = "operating_hours_json")
    private String operatingHoursJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private MerchantState state;

    @Column(name = "hidden_reason")
    private String hiddenReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_capability", nullable = false)
    private FulfilmentCapability fulfilmentCapability;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_fulfilment_mode", nullable = false)
    private FulfilmentMode defaultFulfilmentMode;

    @Column(name = "customer_choice_allowed", nullable = false)
    private boolean customerChoiceAllowed;

    @Column(name = "self_deliver_zones_json")
    private String selfDeliverZonesJson;

    @Column(name = "licence_ref")
    private String licenceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "licence_state", nullable = false)
    private LicenceState licenceState;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approved_by", length = 26)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** PRD §8.2's status visibility: approved and not hidden. */
    public boolean isVisible() {
        return state == MerchantState.APPROVED && hiddenReason == null;
    }

    public boolean deliversItself() {
        return fulfilmentCapability != FulfilmentCapability.PLATFORM_ONLY;
    }

    public boolean platformDelivers() {
        return fulfilmentCapability != FulfilmentCapability.MERCHANT_ONLY;
    }
}
