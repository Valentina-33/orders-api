package edu.escuelaing.orders.domain;

/**
 * The data a client provides to create an order. The server assigns the id.
 *
 * It has a no-arg constructor and setters so that both the Lambda runtime and Jackson can
 * build it from JSON. {@code quantity} is an {@link Integer} so a missing value arrives as
 * {@code null} instead of silently becoming 0.
 */
public class CreateOrderRequest {

    private String customerId;
    private String product;
    private Integer quantity;

    public CreateOrderRequest() {
    }

    public CreateOrderRequest(String customerId, String product, Integer quantity) {
        this.customerId = customerId;
        this.product = product;
        this.quantity = quantity;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
