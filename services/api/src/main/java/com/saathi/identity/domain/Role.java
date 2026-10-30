package com.saathi.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A named bundle of permissions (§5.1). The six keys V3 seeds — {@code CUSTOMER}, {@code RIDER},
 * {@code MERCHANT_STAFF}, {@code OPERATOR}, {@code SUPPORT}, {@code SUPER_ADMIN} — are stable;
 * what is inside each one is configuration a super admin changes, which is why the platform has
 * {@code role} rows instead of constants.
 *
 * <p>The join table is {@code role_permission}. It has no id and no timestamps, so it stays a
 * mapping rather than becoming an entity with a lifecycle nothing owns.
 */
@Entity
@Table(name = "role")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Role {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "key", nullable = false, unique = true)
    private String key;

    @Column(name = "description", nullable = false)
    private String description;

    /**
     * Lazily fetched, and read only inside a transaction: {@code /me} resolves a principal's
     * permissions through a projection query, never by walking this set on a detached entity.
     */
    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "role_permission",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();
}
