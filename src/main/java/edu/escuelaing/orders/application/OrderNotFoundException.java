package edu.escuelaing.orders.application;

/** Thrown when an order with the given id does not exist. */
public class OrderNotFoundException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super("Order " + orderId + " does not exist");
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}
