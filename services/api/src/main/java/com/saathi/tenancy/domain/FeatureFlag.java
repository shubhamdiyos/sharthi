package com.saathi.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A PRD §12 kill switch (§5.2).
 *
 * <p>{@code tenantId == null} is the platform default and a tenant row overrides it — which is why
 * the migration declares {@code UNIQUE NULLS NOT DISTINCT (key, tenant_id)}: plain UNIQUE would
 * treat two NULL-tenant rows as different scopes and let someone insert a second global default.
 */
@Entity
@Table(name = "feature_flag")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class FeatureFlag {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "key", nullable = false)
    private String key;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26)
    private String tenantId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** A flag that depends on another is refused at write time rather than half-enabled (§5.2). */
    @Column(name = "depends_on")
    private String dependsOn;

    @Column(name = "description", nullable = false)
    private String description;
}
