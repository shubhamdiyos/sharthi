package com.saathi.order.repository;

import com.saathi.order.domain.OrderEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The append-only timeline (§5.5). Nothing here updates or deletes a row. */
public interface OrderEventRepository extends JpaRepository<OrderEvent, String> {

    List<OrderEvent> findByOrderIdOrderByOccurredAtAscIdAsc(String orderId);
}
