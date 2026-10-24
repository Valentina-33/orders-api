package edu.escuelaing.orders.application;

import edu.escuelaing.orders.domain.CreateOrderRequest;
import edu.escuelaing.orders.domain.Order;
import edu.escuelaing.orders.persistence.InMemoryOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderServiceTest {

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(InMemoryOrderRepository.withSampleData(), new SequentialOrderIdGenerator(4));
    }

    @Test
    void createsAValidOrderWithAGeneratedId() {
        Order order = service.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 1));

        assertEquals(new Order("ORD-004", "CUST-42", "MacBook Air", 1), order);
    }

    @Test
    void createdOrderCanBeRetrievedAfterwards() {
        Order created = service.createOrder(new CreateOrderRequest("CUST-100", "Monitor", 3));

        assertEquals(created, service.getOrder(created.getId()));
    }

    @Test
    void trimsTextFieldsWhenCreating() {
        Order order = service.createOrder(new CreateOrderRequest("  CUST-9 ", " Mouse  ", 2));

        assertEquals("CUST-9", order.getCustomerId());
        assertEquals("Mouse", order.getProduct());
    }

    @Test
    void retrievesAnExistingOrder() {
        Order order = service.getOrder("ORD-003");

        assertEquals(new Order("ORD-003", "CUST-7", "Keyboard", 2), order);
    }

    @Test
    void failsWhenTheOrderDoesNotExist() {
        OrderNotFoundException e = assertThrows(OrderNotFoundException.class, () -> service.getOrder("ORD-999"));

        assertEquals("Order ORD-999 does not exist", e.getMessage());
        assertEquals("ORD-999", e.getOrderId());
    }

    @Test
    void findsTheOrdersOfACustomer() {
        List<Order> orders = service.getOrdersByCustomer("CUST-42");

        assertEquals(List.of("ORD-001", "ORD-002"), orders.stream().map(Order::getId).toList());
    }

    @Test
    void includesNewOrdersInTheCustomerSearch() {
        service.createOrder(new CreateOrderRequest("CUST-7", "Mouse", 1));

        assertEquals(2, service.getOrdersByCustomer("CUST-7").size());
    }

    @Test
    void returnsAnEmptyListForACustomerWithoutOrders() {
        assertTrue(service.getOrdersByCustomer("CUST-100").isEmpty());
    }

    @Test
    void rejectsAMissingRequest() {
        assertThrows(InvalidOrderException.class, () -> service.createOrder(null));
    }

    @Test
    void rejectsMissingFieldsAndReportsAllOfThem() {
        InvalidOrderException e = assertThrows(InvalidOrderException.class,
                () -> service.createOrder(new CreateOrderRequest(" ", null, null)));

        assertEquals("customerId is required; product is required; quantity is required", e.getMessage());
    }

    @Test
    void rejectsQuantitiesLowerThanOne() {
        assertThrows(InvalidOrderException.class,
                () -> service.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 0)));
        assertThrows(InvalidOrderException.class,
                () -> service.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", -3)));
    }

    @Test
    void doesNotStoreInvalidOrders() {
        assertThrows(InvalidOrderException.class,
                () -> service.createOrder(new CreateOrderRequest("CUST-55", "Phone", 0)));

        assertTrue(service.getOrdersByCustomer("CUST-55").isEmpty());
    }

    @Test
    void rejectsABlankOrderId() {
        assertThrows(InvalidOrderException.class, () -> service.getOrder(" "));
        assertThrows(InvalidOrderException.class, () -> service.getOrder(null));
    }

    @Test
    void deletesAnExistingOrder() {
        service.deleteOrder("ORD-002");

        assertThrows(OrderNotFoundException.class, () -> service.getOrder("ORD-002"));
        assertEquals(List.of("ORD-001"), service.getOrdersByCustomer("CUST-42").stream().map(Order::getId).toList());
    }

    @Test
    void failsWhenDeletingAMissingOrder() {
        OrderNotFoundException e = assertThrows(OrderNotFoundException.class, () -> service.deleteOrder("ORD-999"));

        assertEquals("Order ORD-999 does not exist", e.getMessage());
    }

    @Test
    void rejectsABlankIdWhenDeleting() {
        assertThrows(InvalidOrderException.class, () -> service.deleteOrder(" "));
    }

    @Test
    void rejectsABlankCustomerIdInTheSearch() {
        assertThrows(InvalidOrderException.class, () -> service.getOrdersByCustomer(""));
        assertThrows(InvalidOrderException.class, () -> service.getOrdersByCustomer(null));
    }
}
