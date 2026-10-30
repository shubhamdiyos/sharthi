package com.saathi.identity.domain;

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
 * One capability (§5.1).
 *
 * <p>Empty in the reference data on purpose. §5.2's rule is that every permission is a capability
 * an endpoint checks, so a permission row arrives in the migration of the module that implements
 * the check — a permission nothing enforces is a string that lies about what a role can do.
 */
@Entity
@Table(name = "permission")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Permission {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    /** {@code ORDER_ACCEPT}. The wire value: {@code TenantScope.require} compares on it. */
    @Column(name = "key", nullable = false, unique = true)
    private String key;

    @Column(name = "description", nullable = false)
    private String description;
}
