package edu.escuelaing.orders.lambda.direct;

import edu.escuelaing.orders.OrdersApplication;
import edu.escuelaing.orders.application.InvalidOrderException;
import edu.escuelaing.orders.application.OrderNotFoundException;
import edu.escuelaing.orders.application.OrderService;
import edu.escuelaing.orders.domain.CreateOrderRequest;
import edu.escuelaing.orders.domain.Order;

import java.util.List;
import java.util.function.Supplier;

/**
 * Lambda entry points for Part II and Architecture A (API Gateway non-proxy integration).
 *
 * Each public method is a separate Lambda handler, for example
 * {@code edu.escuelaing.orders.lambda.direct.DirectOrderFunctions::getOrder}.
 * The methods receive and return plain Java objects: the Lambda runtime converts the JSON
 * event into the parameter type and the return value back into JSON. This class does not
 * implement any AWS interface and knows nothing about HTTP.
 */
public class DirectOrderFunctions {

    private final OrderService service;

    public DirectOrderFunctions() {
        this(OrdersApplication.sharedService());
    }

    public DirectOrderFunctions(OrderService service) {
        this.service = service;
    }

    /** Input: {@code {"customerId": "CUST-42", "product": "MacBook Air", "quantity": 1}} */
    public Order createOrder(CreateOrderRequest request) {
        return translateErrors(() -> service.createOrder(request));
    }

    /** Input: {@code {"id": "ORD-001"}} */
    public Order getOrder(OrderIdInput input) {
        return translateErrors(() -> service.getOrder(input == null ? null : input.getId()));
    }

    /** Input: {@code {"customerId": "CUST-42"}} */
    public List<Order> findOrdersByCustomer(CustomerQuery query) {
        return translateErrors(() -> service.getOrdersByCustomer(query == null ? null : query.getCustomerId()));
    }

    /** Input: {@code {"id": "ORD-001"}}. Returns nothing; API Gateway maps the success to 204. */
    public void deleteOrder(OrderIdInput input) {
        translateErrors(() -> {
            service.deleteOrder(input == null ? null : input.getId());
            return null;
        });
    }

    // Unexpected exceptions are not caught: their message has no known code, so the
    // API Gateway mapping sends them to the 500 response.
    private static <T> T translateErrors(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (OrderNotFoundException e) {
            throw new DirectFunctionException("ORDER_NOT_FOUND", e.getMessage());
        } catch (InvalidOrderException e) {
            throw new DirectFunctionException("INVALID_REQUEST", e.getMessage());
        }
    }
}
