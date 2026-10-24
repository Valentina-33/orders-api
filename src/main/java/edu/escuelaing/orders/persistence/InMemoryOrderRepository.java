package edu.escuelaing.orders.persistence;

import edu.escuelaing.orders.application.OrderRepository;
import edu.escuelaing.orders.domain.Order;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps orders in a map.
 *
 * On Lambda this map lives only as long as the execution environment: a cold start begins
 * with the sample data again, and two environments (or two functions) never see each
 * other's orders. It is a stand-in for a database, not persistent storage.
 */
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public static InMemoryOrderRepository withSampleData() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        repository.save(new Order("ORD-001", "CUST-42", "MacBook Air", 1));
        repository.save(new Order("ORD-002", "CUST-42", "USB-C Hub", 2));
        repository.save(new Order("ORD-003", "CUST-7", "Keyboard", 2));
        return repository;
    }

    @Override
    public void save(Order order) {
        orders.put(order.getId(), order);
    }

    @Override
    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public List<Order> findByCustomerId(String customerId) {
        return orders.values().stream()
                .filter(order -> order.getCustomerId().equals(customerId))
                .sorted(Comparator.comparing(Order::getId))
                .toList();
    }

    @Override
    public boolean deleteById(String id) {
        return orders.remove(id) != null;
    }
}
