package com.saathi.quote.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One quoted line. The name, unit and public code are snapshots (§5.5): a catalogue edit after
 * the quote was shown must not change the artifact the customer is consenting to.
 */
@Entity
@Table(name = "price_quote_line")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class PriceQuoteLine {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_id", length = 26, nullable = false)
    private String quoteId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "product_id", length = 26, nullable = false)
    private String productId;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "name_snapshot", nullable = false)
    private String nameSnapshot;

    @Column(name = "unit_label_snapshot", nullable = false)
    private String unitLabelSnapshot;

    @Column(name = "qty_ordered", nullable = false)
    private BigDecimal qtyOrdered;

    @Column(name = "qty_weighed")
    private BigDecimal qtyWeighed;

    @Column(name = "unit_price_paise", nullable = false)
    private long unitPricePaise;

    @Column(name = "line_paise", nullable = false)
    private long linePaise;

    @Column(name = "price_delta_paise", nullable = false)
    private long priceDeltaPaise;

    @Column(name = "consent_required", nullable = false)
    private boolean consentRequired;
}
