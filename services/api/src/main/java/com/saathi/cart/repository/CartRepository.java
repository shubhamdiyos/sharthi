package com.saathi.cart.repository;

import com.saathi.cart.domain.Cart;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** One cart per customer per city, which is the unique key the database enforces. */
public interface CartRepository extends JpaRepository<Cart, String> {

    Optional<Cart> findByTenantIdAndCustomerId(String tenantId, String customerId);

    Optional<Cart> findByIdAndTenantId(String id, String tenantId);
}
