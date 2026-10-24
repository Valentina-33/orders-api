package edu.escuelaing.orders.application;

import edu.escuelaing.orders.domain.CreateOrderRequest;
import edu.escuelaing.orders.domain.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The application logic of the Orders API.
 *
 * It knows about orders, customers, products and quantities. It does not know about HTTP,
 * API Gateway, Lambda, status codes or JSON. Failures are reported with
 * {@link InvalidOrderException} and {@link OrderNotFoundException}; translating them into
 * HTTP responses is the job of whoever calls this class.
 */
public class OrderService {

    private final OrderRepository repository;
    private final Supplier<String> idGenerator;

    public OrderService(OrderRepository repository, Supplier<String> idGenerator) {
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    public Order createOrder(CreateOrderRequest request) {
        if (request == null) {
            throw new InvalidOrderException("Order data is required");
        }
        List<String> problems = new ArrayList<>();
        if (isBlank(request.getCustomerId())) {
            problems.add("customerId is required");
        }
        if (isBlank(request.getProduct())) {
            problems.add("product is required");
        }
        if (request.getQuantity() == null) {
            problems.add("quantity is required");
        } else if (request.getQuantity() < 1) {
            problems.add("quantity must be greater than 0");
        }
        if (!problems.isEmpty()) {
            throw new InvalidOrderException(String.join("; ", problems));
        }

        Order order = new Order(idGenerator.get(), request.getCustomerId().trim(),
                request.getProduct().trim(), request.getQuantity());
        repository.save(order);
        return order;
    }

    public Order getOrder(String id) {
        requireText(id, "id");
        return repository.findById(id.trim())
                .orElseThrow(() -> new OrderNotFoundException(id.trim()));
    }

    /** Returns the orders of a customer. A customer without orders gets an empty list, not an error. */
    public List<Order> getOrdersByCustomer(String customerId) {
        requireText(customerId, "customerId");
        return repository.findByCustomerId(customerId.trim());
    }

    /** Deletes an order. Deleting an order that does not exist is an error, not a silent no-op. */
    public void deleteOrder(String id) {
        requireText(id, "id");
        if (!repository.deleteById(id.trim())) {
            throw new OrderNotFoundException(id.trim());
        }
    }

    private static void requireText(String value, String name) {
        if (isBlank(value)) {
            throw new InvalidOrderException(name + " is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
