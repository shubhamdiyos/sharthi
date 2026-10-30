package com.saathi.tenancy.repository;

import com.saathi.tenancy.domain.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** The taxonomy is global, so it is the one table here that is queried without a tenant (§4.1). */
public interface CategoryRepository extends JpaRepository<Category, String> {

    Optional<Category> findBySlug(String slug);

    List<Category> findByIdInAndActiveTrue(List<String> ids);
}
