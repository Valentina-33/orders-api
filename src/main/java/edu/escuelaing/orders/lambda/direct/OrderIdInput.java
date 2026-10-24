package edu.escuelaing.orders.lambda.direct;

/** Lambda input {@code {"id": "ORD-001"}}. In Architecture A, API Gateway builds it from the path. */
public class OrderIdInput {

    private String id;

    public OrderIdInput() {
    }

    public OrderIdInput(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
