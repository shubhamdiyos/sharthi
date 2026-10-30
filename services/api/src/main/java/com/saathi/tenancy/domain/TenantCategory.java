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
 * One city's activation of one global taxonomy node (§4.1).
 *
 * <p>"Kirana" in Siwan and "General Store" in Mashaun are the same {@code category_id} with two
 * {@code display_name}s. That is the whole of §4.1's argument for splitting this table out: a
 * city may rename and reorder what it sells, but it may not invent a node, because the moment it
 * does, a platform report about "bakery" stops meaning anything.
 */
@Entity
@Table(name = "tenant_category")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class TenantCategory {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_id", length = 26, nullable = false)
    private String categoryId;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "sort_override")
    private Integer sortOverride;

    @Column(name = "min_licence_state")
    private String minLicenceState;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "activated_by", length = 26)
    private String activatedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;
}
