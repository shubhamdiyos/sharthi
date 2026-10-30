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
 * A node of the global taxonomy (§4.1).
 *
 * <p>Global on purpose. Every city that sells chicken sells the same <em>kind</em> of thing, and
 * if the category rows were per tenant then a cross-city report would be a UNION of six different
 * vocabularies. What varies per city is which nodes are switched on and what they are called —
 * {@link TenantCategory}.
 */
@Entity
@Table(name = "category")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Category {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    /** The public handle: {@code meat-fish}. Never the id (§4.3-6). */
    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "parent_id", length = 26)
    private String parentId;

    /** A licence is required before a shop may list in this node (§5.3, PRD §8.3). */
    @Builder.Default
    @Column(name = "requires_licence", nullable = false)
    private boolean requiresLicence = false;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "sort", nullable = false)
    private int sort = 0;
}
