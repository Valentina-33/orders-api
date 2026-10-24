package edu.escuelaing.orders.lambda.direct;

/** Lambda input {@code {"customerId": "CUST-42"}}. In Architecture A, API Gateway builds it from the query string. */
public class CustomerQuery {

    private String customerId;

    public CustomerQuery() {
    }

    public CustomerQuery(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
}
