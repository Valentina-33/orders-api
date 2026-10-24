package edu.escuelaing.orders.lambda.proxy;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.escuelaing.orders.OrdersApplication;
import edu.escuelaing.orders.application.InvalidOrderException;
import edu.escuelaing.orders.application.OrderNotFoundException;
import edu.escuelaing.orders.application.OrderService;
import edu.escuelaing.orders.domain.CreateOrderRequest;
import edu.escuelaing.orders.domain.Order;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP adapter for Architecture B (API Gateway Lambda proxy integration).
 *
 * One Lambda function serves the whole Orders API. API Gateway forwards the full HTTP
 * request as an {@link APIGatewayProxyRequestEvent}; this class reads the method, the
 * resource, the path and query parameters and the body, calls {@link OrderService}, and
 * turns the result (or the exception) into a status code, headers and a JSON body.
 *
 * This is the only class that knows about HTTP. {@link OrderService} stays plain Java.
 */
public class OrdersApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OrderService service;

    public OrdersApiHandler() {
        this(OrdersApplication.sharedService());
    }

    public OrdersApiHandler(OrderService service) {
        this.service = service;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            return route(request);
        } catch (InvalidOrderException e) {
            return error(400, "INVALID_REQUEST", e.getMessage());
        } catch (MalformedBodyException e) {
            return error(400, "MALFORMED_JSON", e.getMessage());
        } catch (OrderNotFoundException e) {
            return error(404, "ORDER_NOT_FOUND", e.getMessage());
        } catch (RuntimeException e) {
            // The details go to CloudWatch Logs, never to the client.
            log(context, "Unexpected error: " + e);
            return error(500, "INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    private APIGatewayProxyResponseEvent route(APIGatewayProxyRequestEvent request) {
        // "resource" is the route template that API Gateway matched, e.g. "/orders/{id}".
        String route = request.getHttpMethod() + " " + request.getResource();
        return switch (route) {
            case "POST /orders" -> createOrder(request);
            case "GET /orders" -> findOrdersByCustomer(request);
            case "GET /orders/{id}" -> getOrder(request);
            default -> error(404, "ROUTE_NOT_FOUND", "No route for " + route);
        };
    }

    private APIGatewayProxyResponseEvent createOrder(APIGatewayProxyRequestEvent request) {
        Order order = service.createOrder(readBody(request, CreateOrderRequest.class));
        return json(201, order).withHeaders(Map.of(
                "Content-Type", "application/json",
                "Location", "/orders/" + order.getId()));
    }

    private APIGatewayProxyResponseEvent getOrder(APIGatewayProxyRequestEvent request) {
        return json(200, service.getOrder(param(request.getPathParameters(), "id")));
    }

    private APIGatewayProxyResponseEvent findOrdersByCustomer(APIGatewayProxyRequestEvent request) {
        return json(200, service.getOrdersByCustomer(param(request.getQueryStringParameters(), "customerId")));
    }

    private static String param(Map<String, String> params, String name) {
        return params == null ? null : params.get(name);
    }

    private static <T> T readBody(APIGatewayProxyRequestEvent request, Class<T> type) {
        String body = request.getBody();
        if (body == null || body.isBlank()) {
            throw new MalformedBodyException("Request body is required");
        }
        if (Boolean.TRUE.equals(request.getIsBase64Encoded())) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        try {
            return JSON.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new MalformedBodyException("Request body is not valid JSON for this operation");
        }
    }

    private static APIGatewayProxyResponseEvent error(int status, String code, String message) {
        return json(status, new ErrorResponse(code, message));
    }

    private static APIGatewayProxyResponseEvent json(int status, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(status)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(JSON.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize response", e);
        }
    }

    private static void log(Context context, String message) {
        if (context != null) {
            context.getLogger().log(message);
        } else {
            System.err.println(message);
        }
    }

    /** The body is missing or is not JSON with the expected shape. */
    static class MalformedBodyException extends RuntimeException {
        MalformedBodyException(String message) {
            super(message);
        }
    }
}
