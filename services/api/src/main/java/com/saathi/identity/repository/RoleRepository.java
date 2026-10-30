package com.saathi.identity.repository;

import com.saathi.identity.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Permissions are resolved as keys, not as entities: {@code TenantScope} holds a
 * {@code Set<String>} and the only question ever asked of a role is "what does this let me do".
 * The projection also means one query for a principal's whole role set, instead of one per role.
 */
public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByKey(String key);

    List<Role> findByIdIn(Collection<String> ids);

    @Query("select distinct p.key from Role r join r.permissions p where r.id in :roleIds order by p.key")
    List<String> findPermissionKeys(@Param("roleIds") Collection<String> roleIds);
}
