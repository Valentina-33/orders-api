package edu.escuelaing.orders.lambda.direct;

import edu.escuelaing.orders.OrdersApplication;
import edu.escuelaing.orders.domain.CreateOrderRequest;
import edu.escuelaing.orders.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectOrderFunctionsTest {

    private DirectOrderFunctions functions;

    @BeforeEach
    void setUp() {
        functions = new DirectOrderFunctions(OrdersApplication.createWithSampleData());
    }

    @Test
    void createsAnOrder() {
        Order order = functions.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 1));

        assertEquals("ORD-004", order.getId());
    }

    @Test
    void getsAnOrderById() {
        assertEquals("Keyboard", functions.getOrder(new OrderIdInput("ORD-003")).getProduct());
    }

    @Test
    void findsOrdersByCustomer() {
        assertEquals(2, functions.findOrdersByCustomer(new CustomerQuery("CUST-42")).size());
    }

    // API Gateway picks the HTTP status by matching a regex against these messages,
    // so the "CODE: " prefix is part of the contract with the gateway configuration.
    @Test
    void prefixesMissingOrdersWithOrderNotFound() {
        DirectFunctionException e = assertThrows(DirectFunctionException.class,
                () -> functions.getOrder(new OrderIdInput("ORD-999")));

        assertEquals("ORDER_NOT_FOUND: Order ORD-999 does not exist", e.getMessage());
    }

    @Test
    void prefixesInvalidInputWithInvalidRequest() {
        DirectFunctionException e = assertThrows(DirectFunctionException.class,
                () -> functions.createOrder(new CreateOrderRequest("CUST-42", "", 0)));

        assertEquals("INVALID_REQUEST: product is required; quantity must be greater than 0", e.getMessage());
    }

    @Test
    void deleteOfAMissingOrderIsPrefixedWithOrderNotFound() {
        functions.deleteOrder(new OrderIdInput("ORD-001"));

        DirectFunctionException e = assertThrows(DirectFunctionException.class,
                () -> functions.deleteOrder(new OrderIdInput("ORD-001")));

        assertEquals("ORDER_NOT_FOUND: Order ORD-001 does not exist", e.getMessage());
    }

    @Test
    void treatsAMissingInputObjectAsInvalid() {
        DirectFunctionException e = assertThrows(DirectFunctionException.class,
                () -> functions.findOrdersByCustomer(null));

        assertEquals("INVALID_REQUEST", e.getCode());
    }
}
