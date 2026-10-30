package com.saathi.cart.repository;

import com.saathi.cart.domain.CartLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Basket lines, in the order the customer added them. */
public interface CartLineRepository extends JpaRepository<CartLine, String> {

    List<CartLine> findByCartIdOrderByAddedAtAsc(String cartId);

    Optional<CartLine> findByCartIdAndProductId(String cartId, String productId);

    Optional<CartLine> findByIdAndCartId(String id, String cartId);

    long countByCartId(String cartId);

    void deleteByCartId(String cartId);
}
