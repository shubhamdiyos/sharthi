package com.saathi.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One ward of one city (§5.2, D8).
 *
 * <p>{@code (tenant_id, ward_no)} is unique and {@code ward_no} means nothing on its own: Ward 12
 * in Siwan and Ward 12 in Mashaun are different places, and the public API therefore always names
 * a city alongside a ward number. {@code id} is the internal key that {@code ward_fee_rule} and
 * the session's {@code ward_scope} reference.
 */
@Entity
@Table(name = "service_ward")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class ServiceWard {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @Column(name = "ward_no", nullable = false)
    private int wardNo;

    @Column(name = "name", nullable = false)
    private String name;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Screen order, per city (§10.5.2). Not the ward number: launching Ward 12 last must not
     * put it twelfth in a picker. */
    @Builder.Default
    @Column(name = "sort", nullable = false)
    private int sort = 0;

    @Column(name = "note")
    private String note;

    @Column(name = "launched_at")
    private Instant launchedAt;
}
