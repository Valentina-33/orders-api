package edu.escuelaing.orders.lambda.proxy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.escuelaing.orders.OrdersApplication;
import edu.escuelaing.orders.application.OrderRepository;
import edu.escuelaing.orders.application.OrderService;
import edu.escuelaing.orders.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrdersApiHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private OrdersApiHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrdersApiHandler(OrdersApplication.createWithSampleData());
    }

    @Test
    void postCreatesAnOrderAndReturns201WithLocation() throws IOException {
        APIGatewayProxyResponseEvent response = handle(request("POST", "/orders")
                .withBody("{\"customerId\":\"CUST-42\",\"product\":\"MacBook Air\",\"quantity\":1}"));

        assertEquals(201, response.getStatusCode());
        assertEquals("/orders/ORD-004", response.getHeaders().get("Location"));
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        JsonNode body = JSON.readTree(response.getBody());
        assertEquals("ORD-004", body.get("id").asText());
        assertEquals("CUST-42", body.get("customerId").asText());
        assertEquals("MacBook Air", body.get("product").asText());
        assertEquals(1, body.get("quantity").asInt());
    }

    @Test
    void postWithInvalidDataReturns400() throws IOException {
        APIGatewayProxyResponseEvent response = handle(request("POST", "/orders")
                .withBody("{\"customerId\":\"CUST-42\",\"product\":\"MacBook Air\",\"quantity\":0}"));

        assertError(response, 400, "INVALID_REQUEST", "quantity must be greater than 0");
    }

    @Test
    void postWithMalformedJsonReturns400() throws IOException {
        APIGatewayProxyResponseEvent response = handle(request("POST", "/orders").withBody("{not json"));

        assertError(response, 400, "MALFORMED_JSON", "Request body is not valid JSON for this operation");
    }

    @Test
    void postWithoutBodyReturns400() throws IOException {
        assertError(handle(request("POST", "/orders")), 400, "MALFORMED_JSON", "Request body is required");
    }

    @Test
    void postAcceptsBase64EncodedBodies() {
        APIGatewayProxyResponseEvent response = handle(request("POST", "/orders")
                .withIsBase64Encoded(true)
                .withBody("eyJjdXN0b21lcklkIjoiQ1VTVC00MiIsInByb2R1Y3QiOiJNYWNCb29rIEFpciIsInF1YW50aXR5IjoxfQ=="));

        assertEquals(201, response.getStatusCode());
    }

    @Test
    void getReturnsAnExistingOrder() throws IOException {
        APIGatewayProxyResponseEvent response = handle(request("GET", "/orders/{id}")
                .withPathParameters(Map.of("id", "ORD-003")));

        assertEquals(200, response.getStatusCode());
        assertEquals(JSON.readTree("{\"id\":\"ORD-003\",\"customerId\":\"CUST-7\",\"product\":\"Keyboard\",\"quantity\":2}"),
                JSON.readTree(response.getBody()));
    }

    @Test
    void getReturns404ForAMissingOrder() throws IOException {
        APIGatewayProxyResponseEvent response = handle(request("GET", "/orders/{id}")
                .withPathParameters(Map.of("id", "ORD-999")));

        assertError(response, 404, "ORDER_NOT_FOUND", "Order ORD-999 does not exist");
    }

    @Test
    void searchReturnsTheOrdersOfACustomer() throws IOException {
        APIGatewayProxyResponseEvent response = handle(request("GET", "/orders")
                .withQueryStringParameters(Map.of("customerId", "CUST-42")));

        assertEquals(200, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertEquals(2, body.size());
        assertEquals("ORD-001", body.get(0).get("id").asText());
    }

    @Test
    void searchReturnsAnEmptyArrayForACustomerWithoutOrders() {
        APIGatewayProxyResponseEvent response = handle(request("GET", "/orders")
                .withQueryStringParameters(Map.of("customerId", "CUST-100")));

        assertEquals(200, response.getStatusCode());
        assertEquals("[]", response.getBody());
    }

    @Test
    void searchWithoutCustomerIdReturns400() throws IOException {
        assertError(handle(request("GET", "/orders")), 400, "INVALID_REQUEST", "customerId is required");
    }

    @Test
    void unknownRouteReturns404() throws IOException {
        assertError(handle(request("PATCH", "/orders/{id}")), 404, "ROUTE_NOT_FOUND", "No route for PATCH /orders/{id}");
    }

    @Test
    void unexpectedFailuresReturn500WithoutInternalDetails() throws IOException {
        OrdersApiHandler failing = new OrdersApiHandler(new OrderService(new BrokenRepository(), () -> "ORD-1"));

        APIGatewayProxyResponseEvent response = failing.handleRequest(
                request("GET", "/orders/{id}").withPathParameters(Map.of("id", "ORD-001")), null);

        assertError(response, 500, "INTERNAL_ERROR", "An unexpected error occurred");
        assertFalse(response.getBody().contains("disk on fire"));
    }

    @Test
    void handlesTheSavedApiGatewayEvents() throws IOException {
        assertEquals(200, handle(readEvent("events/proxy-get-order.json")).getStatusCode());
        assertEquals(200, handle(readEvent("events/proxy-find-orders.json")).getStatusCode());
        assertEquals(201, handle(readEvent("events/proxy-post-order.json")).getStatusCode());
    }

    private APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent request) {
        return handler.handleRequest(request, null);
    }

    private static APIGatewayProxyRequestEvent request(String method, String resource) {
        return new APIGatewayProxyRequestEvent().withHttpMethod(method).withResource(resource);
    }

    private static APIGatewayProxyRequestEvent readEvent(String path) throws IOException {
        return JSON.readValue(new File(path), APIGatewayProxyRequestEvent.class);
    }

    private static void assertError(APIGatewayProxyResponseEvent response, int status, String code, String message)
            throws IOException {
        assertEquals(status, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertEquals(code, body.get("error").asText());
        assertEquals(message, body.get("message").asText());
    }

    private static class BrokenRepository implements OrderRepository {
        @Override
        public void save(Order order) {
            throw new IllegalStateException("disk on fire");
        }

        @Override
        public Optional<Order> findById(String id) {
            throw new IllegalStateException("disk on fire");
        }

        @Override
        public List<Order> findByCustomerId(String customerId) {
            throw new IllegalStateException("disk on fire");
        }
    }
}
