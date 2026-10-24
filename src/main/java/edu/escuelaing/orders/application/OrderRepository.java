package edu.escuelaing.orders.application;

import edu.escuelaing.orders.domain.Order;

import java.util.List;
import java.util.Optional;

/** Storage for orders. The service only knows this interface, not where orders are kept. */
public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(String id);

    List<Order> findByCustomerId(String customerId);

    /** Removes the order. Returns {@code false} if there was no order with that id. */
    boolean deleteById(String id);
}
