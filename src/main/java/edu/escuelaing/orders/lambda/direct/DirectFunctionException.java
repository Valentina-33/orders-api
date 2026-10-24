package edu.escuelaing.orders.lambda.direct;

/**
 * The error a direct (non-proxy) Lambda throws.
 *
 * The Lambda runtime turns it into {@code {"errorMessage": "...", "errorType": "...", ...}}.
 * API Gateway can only look at {@code errorMessage} to pick an HTTP status, so the message
 * starts with a stable code: {@code "ORDER_NOT_FOUND: Order ORD-999 does not exist"}.
 */
public class DirectFunctionException extends RuntimeException {

    private final String code;

    public DirectFunctionException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
