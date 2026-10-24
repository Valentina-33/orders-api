package edu.escuelaing.orders;

import edu.escuelaing.orders.application.OrderService;
import edu.escuelaing.orders.application.SequentialOrderIdGenerator;
import edu.escuelaing.orders.persistence.InMemoryOrderRepository;

/**
 * Wires the application together. Plain Java: no AWS classes here.
 *
 * The Lambda handlers use {@link #sharedService()}. The static field is created once per
 * Lambda execution environment (on the cold start) and reused by every warm invocation.
 */
public final class OrdersApplication {

    private static final OrderService SHARED_SERVICE = createWithSampleData();

    private OrdersApplication() {
    }

    public static OrderService sharedService() {
        return SHARED_SERVICE;
    }

    public static OrderService createWithSampleData() {
        return new OrderService(InMemoryOrderRepository.withSampleData(), new SequentialOrderIdGenerator(4));
    }
}
