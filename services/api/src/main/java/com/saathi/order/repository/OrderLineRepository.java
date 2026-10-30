package com.saathi.order.repository;

import com.saathi.order.domain.OrderLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** A placed order's lines, in the order they were quoted. */
public interface OrderLineRepository extends JpaRepository<OrderLine, String> {

    List<OrderLine> findByOrderIdOrderByIdAsc(String orderId);
}
