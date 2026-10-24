# Orders API: Serverless REST with AWS Lambda and API Gateway

A small **Order Management API** written in plain Java 17, tested with JUnit, packaged with Maven and exposed through **Amazon API Gateway** and **AWS Lambda** in two different ways:

- **Architecture A**: API Gateway non-proxy integration. API Gateway does the HTTP work with mapping templates.
- **Architecture B**: Lambda proxy integration. A thin Java HTTP adapter does the HTTP work.

The same business logic (`OrderService`) is used by both. It never sees HTTP, API Gateway or Lambda.

## Contents

1. [Quick start](#1-quick-start)
2. [Project structure](#2-project-structure)
3. [API contract](#3-api-contract)
4. [Part I: the application in plain Java](#4-part-i-the-application-in-plain-java)
5. [Part II: Java function on AWS Lambda](#5-part-ii-java-function-on-aws-lambda)
6. [Parts III and IV: Architecture A, API Gateway mapping](#6-parts-iii-and-iv-architecture-a-api-gateway-mapping)
7. [Part V: Architecture B, Lambda proxy integration](#7-part-v-architecture-b-lambda-proxy-integration)
8. [Comparing the two architectures](#8-comparing-the-two-architectures)
9. [Final challenge: DELETE /orders/{id}](#9-final-challenge-delete-ordersid)
10. [Final architecture diagram](#10-final-architecture-diagram)
11. [AI-assisted development and critique of the agent](#11-ai-assisted-development-and-critique-of-the-agent)
12. [How to deploy](#12-how-to-deploy)
13. [Demo video checklist](#13-demo-video-checklist)

---

## 1. Quick start

Requirements: Java 17 or newer, Maven 3.9+.

```bash
mvn test
```

```bash
mvn package
```

`mvn package` runs the tests again and creates `target/orders-api-1.0.0.jar`, a single "uber JAR" (about 3.5 MB) with our classes plus the Lambda and Jackson libraries. That one JAR is uploaded to every Lambda function; each function just points to a different handler inside it.

To deploy, see [section 12](#12-how-to-deploy).

## 2. Project structure

```
orders-api/
├── pom.xml
├── README.md
├── apigateway/
│   ├── architecture-a-mapping-openapi.yaml   API Gateway config, non-proxy + mapping templates
│   └── architecture-b-proxy-openapi.yaml     API Gateway config, proxy integration
├── deploy/deploy.sh                          AWS CLI script: Lambdas + both APIs
├── docs/delete-prediction.md                 Prediction written before implementing DELETE
├── events/                                   JSON used to invoke the Lambdas by hand
│   ├── direct-*.json                         inputs for the direct (Part II / Architecture A) functions
│   └── proxy-*.json                          API Gateway proxy events (Architecture B)
├── examples/curl-examples.sh                 calls every endpoint of a deployed API
└── src/
    ├── main/java/edu/escuelaing/orders/
    │   ├── domain/            Order, CreateOrderRequest                       plain Java
    │   ├── application/       OrderService, OrderRepository, exceptions        plain Java
    │   ├── persistence/       InMemoryOrderRepository                          plain Java
    │   ├── OrdersApplication  wires the service (composition root)             plain Java
    │   └── lambda/
    │       ├── direct/        DirectOrderFunctions + input DTOs  (Architecture A, no AWS imports)
    │       └── proxy/         OrdersApiHandler, ErrorResponse    (Architecture B, AWS events)
    └── test/java/...          JUnit 5 tests for every layer
```

The dependency direction is always inwards: `lambda.*` → `application` → `domain`. Nothing in `domain`, `application` or `persistence` imports an AWS, HTTP or Jackson class.

## 3. API contract

The contract was written before the code.

### Resource representation

```json
{
  "id": "ORD-001",
  "customerId": "CUST-42",
  "product": "MacBook Air",
  "quantity": 1
}
```

| Field | Type | Rules |
|---|---|---|
| `id` | string | Assigned by the server (`ORD-004`, `ORD-005`, ...). Clients never send it. |
| `customerId` | string | Required, not blank. |
| `product` | string | Required, not blank. |
| `quantity` | integer | Required, `>= 1`. |

Sample data loaded on every cold start: `ORD-001` (CUST-42, MacBook Air, 1), `ORD-002` (CUST-42, USB-C Hub, 2), `ORD-003` (CUST-7, Keyboard, 2).

### Error representation

Every error, from the application or from API Gateway itself, has the same shape:

```json
{
  "error": "ORDER_NOT_FOUND",
  "message": "Order ORD-999 does not exist"
}
```

- `error`: a stable, machine-readable code. Clients write `if` statements against it.
- `message`: a human-readable explanation. It can change wording without breaking clients.

| `error` code | HTTP status | When |
|---|---|---|
| `INVALID_REQUEST` | 400 | A business rule fails: missing field, `quantity < 1`, missing `customerId` in the search |
| `MALFORMED_JSON` | 400 | The body is missing, is not JSON, or has the wrong types (`"quantity": "abc"`) |
| `ORDER_NOT_FOUND` | 404 | No order with that id |
| `ROUTE_NOT_FOUND` | 404 | Method + path combination that the adapter does not know (Architecture B) |
| `INTERNAL_ERROR` | 500 | Anything unexpected. The message is generic on purpose; details go to CloudWatch Logs |

### Operations

#### Create an order

| | |
|---|---|
| Method / URL | `POST /orders` |
| Path params | none |
| Query params | none |
| Request body | `{"customerId": "CUST-42", "product": "MacBook Air", "quantity": 1}` |
| Success | `201 Created`, body = the created order, header `Location: /orders/ORD-004` |
| Errors | `400 INVALID_REQUEST`, `400 MALFORMED_JSON`, `500 INTERNAL_ERROR` |

#### Retrieve an order

| | |
|---|---|
| Method / URL | `GET /orders/{id}` |
| Path params | `id`, e.g. `ORD-001` |
| Query params | none |
| Request body | none |
| Success | `200 OK`, body = the order |
| Errors | `404 ORDER_NOT_FOUND`, `500 INTERNAL_ERROR` |

#### Find the orders of a customer

| | |
|---|---|
| Method / URL | `GET /orders?customerId={customerId}` |
| Path params | none |
| Query params | `customerId` (required) |
| Request body | none |
| Success | `200 OK`, body = JSON array of orders. A customer without orders gets `[]`, not 404 |
| Errors | `400 INVALID_REQUEST` (no `customerId`), `500 INTERNAL_ERROR` |

#### Delete an order (final challenge)

| | |
|---|---|
| Method / URL | `DELETE /orders/{id}` |
| Path params | `id` |
| Request body | none |
| Success | `204 No Content`, empty body |
| Errors | `404 ORDER_NOT_FOUND`, `500 INTERNAL_ERROR` |

### Design questions

**What are the resources in your API?**
There is one collection resource, `/orders` (all orders), and one item resource, `/orders/{id}` (a single order). Customers and products appear only as attributes of an order; they are not resources of this API because we do not create, read or delete them here.

**Why did you choose `/orders/{id}` to retrieve a particular order?**
In REST a URL identifies a resource. An individual order is a member of the `orders` collection, so its natural address is the collection plus its identifier. The id is part of the identity of the thing, which is exactly what the path is for. The same URL is then reused with other methods (`DELETE /orders/{id}`, and a future `PUT`), so the verb says *what* to do and the URL says *to what*.

**Why is `customerId` represented as a query parameter in the search operation?**
Because we are not addressing a new resource, we are *filtering* the `/orders` collection. Query parameters are the standard way to filter, sort or page a collection, they are optional by nature and they combine well (`?customerId=CUST-42&product=Mouse` later). A path like `/customers/{id}/orders` would also be valid REST, but it would introduce `customers` as a resource that this API does not otherwise manage.

**What should `POST /orders` return when an order is successfully created?**
`201 Created`, the created order in the body (so the client learns the generated `id` without a second call), and a `Location: /orders/ORD-004` header pointing to the new resource.

**What should the API return when an order does not exist?**
`404 Not Found` with `{"error": "ORDER_NOT_FOUND", "message": "Order ORD-999 does not exist"}`. Not `200` with an empty body and not `500`: the request was fine, the resource just is not there. A *search* with no results is different: it returns `200` with `[]`, because the collection exists, it just has no matching members.

**What information should an error response contain?**
A stable error code for programs, a readable message for people, and the HTTP status (in the status line, not repeated in the body). It must **not** contain stack traces, class names or other internal details. For 400 errors the message says which field is wrong (`quantity must be greater than 0`) so the client can fix the request.

## 4. Part I: the application in plain Java

The core is ordinary Java:

```java
public class OrderService {
    public Order createOrder(CreateOrderRequest request)          // throws InvalidOrderException
    public Order getOrder(String id)                              // throws OrderNotFoundException
    public List<Order> getOrdersByCustomer(String customerId)     // [] when there are none
}
```

- Validation lives in `OrderService`, because "quantity must be at least 1" is a business rule, not an HTTP rule. It reports *all* problems at once: `customerId is required; product is required; quantity is required`.
- Failures are domain exceptions (`InvalidOrderException`, `OrderNotFoundException`). They do not carry HTTP status codes or error codes; mapping them to HTTP is the adapters' job.
- Storage is behind the `OrderRepository` interface. `InMemoryOrderRepository` is a `ConcurrentHashMap` seeded with three orders. **It is not persistent**: Lambda environments are created and destroyed at any time, each one has its own copy of the map, and a cold start begins again from the sample data. Replacing it with DynamoDB would mean writing one new class that implements `OrderRepository`.
- Ids are generated by an injected `Supplier<String>` (`SequentialOrderIdGenerator`), so tests can control them.

### Tests

`mvn test` runs 40 tests (33 before the DELETE extension):

| Test class | What it covers |
|---|---|
| `OrderServiceTest` | valid creation, retrieval, missing order, search by customer, empty search, every invalid input, trimming, invalid orders are not stored |
| `DirectOrderFunctionsTest` | the Lambda entry points of Architecture A and the `CODE: message` format that API Gateway regexes depend on |
| `OrdersApiHandlerTest` | the HTTP adapter of Architecture B: 200, 201 + `Location`, 400 (rule and malformed JSON), 404, 500 without leaking details, and the saved API Gateway events in `events/` |

### Checkpoint 1

| Question | Answer |
|---|---|
| Can the functionality be tested without AWS? | Yes. All the tests (33 at this point, 40 at the end) run on a laptop with `mvn test`, no AWS account, no network. Even the Lambda handlers are tested by calling them as ordinary Java methods. |
| Does it require a web server? | No. There is no Tomcat, Netty or socket. In AWS, API Gateway receives HTTP and Lambda calls our methods. |
| Does it require Spring Boot? | No. There is no framework at all; `OrdersApplication` wires three objects by hand. |
| What does the generated JAR contain? | Our compiled classes (`domain`, `application`, `persistence`, `lambda`) plus, because of the Shade plugin, the classes of `aws-lambda-java-core`, `aws-lambda-java-events`, `jackson-*` and `joda-time` (a dependency of the events library). No `main` method and no server. `target/original-orders-api-1.0.0.jar` (20 KB) is the same thing without dependencies. |
| Which parts know about HTTP? | At the end of Part I: none. After Part V: only `lambda.proxy.OrdersApiHandler` (and its `ErrorResponse`). In Architecture A the HTTP knowledge is in API Gateway's configuration, not in Java. |

## 5. Part II: Java function on AWS Lambda

The class `lambda.direct.DirectOrderFunctions` exposes each operation as a Lambda handler. It does not implement any AWS interface: it is a normal class with normal methods that receive and return Java objects.

| Lambda function | Handler string | Input example |
|---|---|---|
| `orders-create-order` | `edu.escuelaing.orders.lambda.direct.DirectOrderFunctions::createOrder` | `events/direct-create-order.json` |
| `orders-get-order` | `edu.escuelaing.orders.lambda.direct.DirectOrderFunctions::getOrder` | `events/direct-get-order.json` |
| `orders-find-orders` | `edu.escuelaing.orders.lambda.direct.DirectOrderFunctions::findOrdersByCustomer` | `events/direct-find-orders.json` |
| `orders-delete-order` (final challenge) | `edu.escuelaing.orders.lambda.direct.DirectOrderFunctions::deleteOrder` | `events/direct-delete-order.json` |

Runtime: **Java 17**, memory 512 MB, timeout 15 s, code: `target/orders-api-1.0.0.jar`.

### Invoking it by hand

In the Lambda console: *Test* tab → paste the JSON → *Test*. With the CLI:

```bash
aws lambda invoke --function-name orders-create-order --cli-binary-format raw-in-base64-out --payload file://events/direct-create-order.json out.json
```

| Input | Expected output |
|---|---|
| `{"customerId": "CUST-42", "product": "MacBook Air", "quantity": 1}` | `{"id":"ORD-004","customerId":"CUST-42","product":"MacBook Air","quantity":1}` (first call after a cold start) |
| `{"customerId": "CUST-100", "product": "Mechanical Keyboard", "quantity": 3}` | `{"id":"ORD-005",...}` if the same warm environment handles it, `ORD-004` again after a cold start |
| `{"customerId": "CUST-42", "product": "", "quantity": 0}` | Function error: `{"errorMessage":"INVALID_REQUEST: product is required; quantity must be greater than 0","errorType":"edu.escuelaing.orders.lambda.direct.DirectFunctionException", ...}` |
| `{"customerId": "CUST-42", "product": "Monitor", "quantity": "2", "color": "black"}` | Created normally: `"2"` is converted to the number 2 and the unknown field `color` is ignored |
| `{"id": "ORD-999"}` (to `orders-get-order`) | Function error: `"errorMessage":"ORDER_NOT_FOUND: Order ORD-999 does not exist"` |

> **Evidence:** add screenshots of your own invocations to `docs/evidence/` (the second row is a nice way to *see* that in-memory state survives only while the environment is warm).

### Investigation: how does Lambda turn JSON into Java objects and back?

What we found (first asked an LLM, then checked against the deployed function):

1. **The handler string tells the runtime what to call.** `Class::method` makes the runtime load the class from our JAR, create **one instance** with the public no-arg constructor (on the cold start, not per request) and find the method by name. The method can have the form `(Input)`, `(Input, Context)`, or implement `RequestHandler<I, O>` / `RequestStreamHandler`; none of that is required.
2. **Input.** The runtime looks at the declared parameter type. If it is `InputStream` it passes the raw bytes; if it is `String`, `Integer`, `Map`, `List` it converts directly; if it is a POJO, it uses its built-in serializer (`aws-lambda-java-serialization`, an internal, shaded **Jackson**) to map JSON fields to properties by name, using the no-arg constructor and setters. So `{"id": "ORD-001"}` becomes `new OrderIdInput()` + `setId("ORD-001")`.
3. **Lenient mapping.** Unknown JSON fields are ignored (`color` above), missing fields stay `null` (that is why `quantity` is an `Integer` and not an `int`: a missing quantity is `null` and is rejected, instead of silently becoming `0`), and simple coercions like `"2"` → `2` work. JSON that cannot be mapped (e.g. `"quantity": "abc"`) fails **before our code runs**, with an error like `An error occurred during JSON parsing`.
4. **Output.** The return value goes through the same serializer: public getters become JSON fields (`getCustomerId()` → `"customerId"`). A `List<Order>` becomes a JSON array. A `void` method returns `null`.
5. **Errors.** An uncaught exception becomes `{"errorMessage": e.getMessage(), "errorType": <class name>, "stackTrace": [...]}` and the invocation is marked as failed (the CLI shows `"FunctionError": "Unhandled"`). This is what Architecture A uses to pick HTTP status codes.
6. **Special types.** Classes from `aws-lambda-java-events` (such as `APIGatewayProxyRequestEvent`) are recognized by the runtime and deserialized with their own rules. That is what Architecture B relies on.

Practical consequences for our design: classes that cross the Lambda boundary are plain classes with getters (and setters for inputs), not Java records, because we cannot control which Jackson version and settings the runtime's internal serializer uses.

### Checkpoint 2: what actually happens

```mermaid
flowchart LR
    T["Test event / aws lambda invoke<br/>{&quot;id&quot;: &quot;ORD-001&quot;}"] -->|JSON| R["AWS Lambda Java 17 runtime<br/>(internal Jackson serializer)"]
    R -->|"new OrderIdInput()<br/>setId(&quot;ORD-001&quot;)"| F["DirectOrderFunctions::getOrder<br/>(plain Java)"]
    F -->|"getOrder(&quot;ORD-001&quot;)"| S["OrderService<br/>(plain Java)"]
    S --> M[("InMemoryOrderRepository<br/>per execution environment")]
    S -->|Order| F
    F -->|Order object| R
    R -->|"{&quot;id&quot;:&quot;ORD-001&quot;,...}<br/>or {&quot;errorMessage&quot;:...}"| T
```

## 6. Parts III and IV: Architecture A, API Gateway mapping

Config: [`apigateway/architecture-a-mapping-openapi.yaml`](apigateway/architecture-a-mapping-openapi.yaml) (an OpenAPI file with `x-amazon-apigateway-integration` extensions that can be imported directly into API Gateway).

```mermaid
flowchart LR
    C[Client] -->|HTTP| G["API Gateway (REST API)<br/>routing · request templates<br/>regex on errorMessage · response templates"]
    G -->|"{&quot;customerId&quot;,...}"| L1["λ orders-create-order<br/>::createOrder"]
    G -->|"{&quot;id&quot;: &quot;ORD-42&quot;}"| L2["λ orders-get-order<br/>::getOrder"]
    G -->|"{&quot;customerId&quot;: &quot;CUST-42&quot;}"| L3["λ orders-find-orders<br/>::findOrdersByCustomer"]
    G -->|"{&quot;id&quot;: &quot;ORD-42&quot;}"| L4["λ orders-delete-order<br/>::deleteOrder"]
    L1 & L2 & L3 & L4 --> S["OrderService (plain Java)"]
```

### Request mapping

`GET /orders/ORD-42`: API Gateway reads `{id}` from the path and builds the Lambda input:

```velocity
{
  "id": "$util.escapeJavaScript($input.params().path.get('id'))"
}
```

`GET /orders?customerId=CUST-42`:

```velocity
{
  "customerId": "$util.escapeJavaScript($input.params().querystring.get('customerId'))"
}
```

If `customerId` is missing, the template produces `""` and `OrderService` rejects it with `INVALID_REQUEST`. (We chose not to mark it as required in an API Gateway request validator so that the rule lives in one place.)

`POST /orders`: the body already has the shape of `CreateOrderRequest`, so the template is `$input.json('$')`.

The Java code never sees a URL, a header or a query string. `DirectOrderFunctions::getOrder` only receives `OrderIdInput{id="ORD-42"}`.

### Response and error mapping

A non-proxy Lambda cannot return a status code. It either returns a value (success) or throws (error). API Gateway decides the status:

- **Success** → the `default` integration response: `200` for GETs, `201` for POST. For POST the template also sets the `Location` header:
  ```velocity
  #set($context.responseOverride.header.Location = "/orders/$input.path('$.id')")
  $input.json('$')
  ```
- **Errors** → API Gateway matches a regex against the Lambda's `errorMessage`. That is why `DirectOrderFunctions` rethrows domain exceptions as `DirectFunctionException("ORDER_NOT_FOUND", ...)`, whose message is `ORDER_NOT_FOUND: Order ORD-42 does not exist`.

| Selection pattern (regex on `errorMessage`) | Status | Body built by the template |
|---|---|---|
| `ORDER_NOT_FOUND: .*` | 404 | `{"error": "ORDER_NOT_FOUND", "message": "<text after the code>"}` |
| `INVALID_REQUEST: .*` | 400 | `{"error": "INVALID_REQUEST", "message": "<text after the code>"}` |
| `(?s)An error occurred during JSON parsing.*` (POST only) | 400 | `{"error": "MALFORMED_JSON", ...}` |
| `(?s)(?!INVALID_REQUEST: \|ORDER_NOT_FOUND: ...).+` | 500 | `{"error": "INTERNAL_ERROR", "message": "An unexpected error occurred"}` |

The error template:

```velocity
#set($msg = $input.path('$.errorMessage'))
#set($start = $msg.indexOf(': ') + 2)
{
  "error": "ORDER_NOT_FOUND",
  "message": "$util.escapeJavaScript($msg.substring($start))"
}
```

> **Trap we found:** without the last "catch-all" regex, an unexpected exception (one whose message does not match any pattern) falls into the `default` response, so the client gets **`200 OK` with the Lambda error JSON as the body**. In this architecture *getting a 500* is something you have to configure.

### Testing it

```bash
curl -i https://<api-id>.execute-api.<region>.amazonaws.com/dev/orders/ORD-001
curl -i https://<api-id>.execute-api.<region>.amazonaws.com/dev/orders/ORD-999
curl -i "https://<api-id>.execute-api.<region>.amazonaws.com/dev/orders?customerId=CUST-42"
curl -i "https://<api-id>.execute-api.<region>.amazonaws.com/dev/orders?customerId=CUST-100"
curl -i -X POST https://<api-id>.execute-api.<region>.amazonaws.com/dev/orders -H "Content-Type: application/json" -d '{"customerId":"CUST-42","product":"MacBook Air","quantity":1}'
curl -i -X POST https://<api-id>.execute-api.<region>.amazonaws.com/dev/orders -H "Content-Type: application/json" -d '{"customerId":"CUST-42","product":"","quantity":0}'
```

Or run everything at once: `./examples/curl-examples.sh <invoke URL>`.

Expected results:

| Request | Status | Body |
|---|---|---|
| `GET /orders/ORD-001` | 200 | `{"id":"ORD-001","customerId":"CUST-42","product":"MacBook Air","quantity":1}` |
| `GET /orders/ORD-999` | 404 | `{"error":"ORDER_NOT_FOUND","message":"Order ORD-999 does not exist"}` |
| `GET /orders?customerId=CUST-42` | 200 | `[{"id":"ORD-001",...},{"id":"ORD-002",...}]` |
| `GET /orders?customerId=CUST-100` | 200 | `[]` |
| `GET /orders` | 400 | `{"error":"INVALID_REQUEST","message":"customerId is required"}` |
| `POST /orders` valid | 201 | the new order, `Location: /orders/ORD-004` |
| `POST /orders` with `quantity: 0` | 400 | `{"error":"INVALID_REQUEST","message":"product is required; quantity must be greater than 0"}` |
| `DELETE /orders/ORD-002` | 204 | empty |
| `DELETE /orders/ORD-002` again | 404 | `{"error":"ORDER_NOT_FOUND","message":"Order ORD-002 does not exist"}` |

> **Important side effect of one Lambda per operation:** after `POST /orders` creates `ORD-004` in `orders-create-order`, `GET /orders/ORD-004` returns **404**, because `orders-get-order` is a different function with its own memory. The sample orders work in every function because each one loads them on its cold start. This is the in-memory limitation made visible; with a real database it disappears.

### Checkpoint 3: what is API Gateway responsible for in Architecture A?

| Responsibility | Where |
|---|---|
| Routing (`GET /orders/{id}` → `orders-get-order`) | API Gateway resources + methods |
| Extracting path parameters | request template (`$input.params().path`) |
| Extracting query parameters | request template (`$input.params().querystring`) |
| Transforming the request into the Lambda's input JSON | request templates |
| Transforming the response | response templates (`$input.json('$')`) |
| Choosing the success status code (200 vs 201) | `default` integration response per method |
| Choosing error status codes | regexes on `errorMessage` |
| Building the error JSON | error response templates |
| Response headers (`Location`) | `$context.responseOverride` in the template |
| Hiding internal errors (500 instead of 200) | catch-all regex |
| Gateway-level errors in our format | `x-amazon-apigateway-gateway-responses` |

Java is responsible only for the business rules and for putting a stable code at the start of exception messages.

**Advantages**

- The Java code is as pure as it gets: `DirectOrderFunctions` has no AWS imports and no idea that HTTP exists. The same functions can be called from Step Functions, SQS or another Lambda without changes.
- The HTTP contract is declared in one place (the OpenAPI file). Changing a status code or renaming a field in the response does not require recompiling or redeploying Java.
- Each operation is a separate function: independent memory/timeout settings, independent permissions, and a bug in one cannot break another.
- API Gateway can reject bad requests before paying for a Lambda invocation (with request validators).

**Disadvantages**

- The HTTP behavior is written in Velocity templates and regexes inside YAML. It is hard to read, it has no compiler and **it cannot be unit tested locally**: every mistake is discovered after a deploy.
- There is a hidden contract between two places: Java must produce `"ORDER_NOT_FOUND: ..."` and the regex must match it. Renaming a code in Java breaks the API silently (it becomes a 500 or, worse, a 200).
- The defaults are dangerous (unmatched errors become `200 OK`).
- It is verbose: the same error templates are repeated in every method.
- Lambda errors are reported as function failures in CloudWatch metrics, even when they are an ordinary "not found".
- It ties the HTTP layer to API Gateway REST APIs specifically (HTTP APIs do not support mapping templates).

## 7. Part V: Architecture B, Lambda proxy integration

Config: [`apigateway/architecture-b-proxy-openapi.yaml`](apigateway/architecture-b-proxy-openapi.yaml). Every route uses `type: aws_proxy` and points to **one** function:

| Lambda function | Handler |
|---|---|
| `orders-api-proxy` | `edu.escuelaing.orders.lambda.proxy.OrdersApiHandler::handleRequest` |

There are no request templates, no integration responses and no regexes.

### The event API Gateway sends

Saved examples: [`events/proxy-get-order.json`](events/proxy-get-order.json), [`events/proxy-find-orders.json`](events/proxy-find-orders.json), [`events/proxy-post-order.json`](events/proxy-post-order.json). A test (`handlesTheSavedApiGatewayEvents`) feeds them to the handler, so they cannot silently get out of date.

| Information | Field in the event | Example |
|---|---|---|
| HTTP method | `httpMethod` (also `requestContext.httpMethod`) | `"GET"` |
| Path | `path` (real path) and `resource` (matched template) | `"/orders/ORD-001"`, `"/orders/{id}"` |
| Path parameters | `pathParameters` | `{"id": "ORD-001"}` |
| Query parameters | `queryStringParameters` (+ `multiValueQueryStringParameters`) | `{"customerId": "CUST-42"}` |
| Headers | `headers` (+ `multiValueHeaders`) | `{"Content-Type": "application/json", ...}` |
| Request body | `body`, **as a string** (base64 if `isBase64Encoded` is true) | `"{\"customerId\":\"CUST-42\",...}"` |
| Stage, request id, caller IP... | `requestContext` | `"stage": "dev"` |

Note that the body is a JSON *string inside* the JSON event, so the handler has to parse it itself. Absent parameters are `null`, not empty maps.

The handler must answer with this shape (API Gateway turns it into the real HTTP response):

```json
{ "statusCode": 201, "headers": {"Content-Type": "application/json", "Location": "/orders/ORD-004"}, "body": "{\"id\":\"ORD-004\",...}" }
```

### The HTTP adapter

```mermaid
flowchart LR
    C[Client] -->|HTTP| G["API Gateway<br/>routing only"]
    G -->|"APIGatewayProxyRequestEvent<br/>(method, resource, params, headers, body)"| H["λ orders-api-proxy<br/>OrdersApiHandler (HTTP adapter)"]
    H -->|"createOrder(CreateOrderRequest)<br/>getOrder(id)<br/>getOrdersByCustomer(customerId)"| S["OrderService (plain Java)"]
    S -->|"Order / List&lt;Order&gt; / exception"| H
    H -->|"statusCode + headers + JSON body"| G
```

`OrdersApiHandler` is deliberately thin:

1. **Route** on `httpMethod + " " + resource` (`"GET /orders/{id}"`). Using `resource` instead of parsing `path` reuses the routing API Gateway already did.
2. **Extract** the inputs (`pathParameters.id`, `queryStringParameters.customerId`, body parsed with Jackson into `CreateOrderRequest`).
3. **Call** `OrderService`.
4. **Translate** the outcome into HTTP:

| Outcome | Status | Body / headers |
|---|---|---|
| `Order` from `createOrder` | 201 | order JSON, `Location: /orders/{id}` |
| `Order` / `List<Order>` from a GET | 200 | JSON |
| `deleteOrder` returns normally | 204 | no body |
| `InvalidOrderException` | 400 | `INVALID_REQUEST` |
| body missing / not JSON / wrong types | 400 | `MALFORMED_JSON` |
| `OrderNotFoundException` | 404 | `ORDER_NOT_FOUND` |
| unknown method + resource | 404 | `ROUTE_NOT_FOUND` |
| any other `RuntimeException` | 500 | `INTERNAL_ERROR`, generic message; the real exception is logged to CloudWatch |

**Is a `Location` header appropriate after creating?** Yes. RFC 9110 says a `201 Created` response identifies the new resource, usually through `Location`. Since we also return the full order in the body, the header is not strictly necessary for our own clients, but it is the standard signal and generic HTTP tools understand it. Both architectures send it. We use a relative URL (`/orders/ORD-004`) because the handler does not know the stage name or the public domain; building an absolute URL would mean reading `requestContext` and would break behind a custom domain.

**Unexpected internal errors.** In B the handler catches every `RuntimeException`, logs it and returns `500` with the generic `INTERNAL_ERROR` body. If something escapes even that (for example `OutOfMemoryError`, a timeout, or a failure during class initialization), the Lambda invocation fails and API Gateway itself answers `502 Bad Gateway` ("Internal server error"); our `DEFAULT_5XX` gateway response still gives it the `{"error", "message"}` shape. In A, the catch-all regex gives `500 INTERNAL_ERROR`; Lambda timeouts become `504` from API Gateway.

### Testing it

The same `curl` commands as in Architecture A work against the proxy API, with the same status codes and bodies. The difference you can see: `POST` and then `GET /orders/ORD-004` **does** find the new order, because both requests go to the same function (as long as the same warm environment serves them).

## 8. Comparing the two architectures

```
Architecture A                              Architecture B
HTTP request                                HTTP request
   │                                           │
API Gateway                                 API Gateway
 - routing                                   - routing
 - parameter extraction (VTL)                  │
 - request transformation (VTL)             Lambda HTTP adapter (OrdersApiHandler)
 - status codes (regex)                      - parameter extraction
 - response/error mapping (VTL)              - status codes, headers, error JSON
   │                                           │
Lambda per operation (DirectOrderFunctions) OrderService
   │
OrderService
```

| Question | Architecture A (mapping) | Architecture B (proxy) |
|---|---|---|
| Which required more API Gateway configuration? | **A, by far.** About 250 lines of OpenAPI with request templates, four regexes per method and repeated error templates. | About 60 lines: three routes, all `aws_proxy` to the same function. |
| Which required more Java infrastructure code? | Little: `DirectOrderFunctions` (~20 real lines) and two tiny input DTOs. | **B.** `OrdersApiHandler` (~110 lines) with routing, parsing, error translation and serialization. |
| Which was easier to test locally? | The Java side is trivial to test, but the HTTP behavior (status codes, templates, regexes) **cannot** be tested without deploying. | **B.** Every status code and header is covered by JUnit (`OrdersApiHandlerTest`), and the saved real events can be replayed. |
| Where are HTTP status codes defined? | In API Gateway: `default` integration responses (200/201) and selection-pattern regexes (400/404/500). | In Java, in `OrdersApiHandler` (`json(201, ...)`, `catch (OrderNotFoundException e) → 404`). |
| What happens at 30 operations? | 30 functions (or 30 integrations) × (request template + 3–4 regexes + error templates). Lots of copy-paste in YAML, many deploy-only bugs. Cold starts multiply (each function warms up separately). | Still one function and a 30-case routing table; error translation is written once. The handler grows and would need to be split (one small class per resource, or a tiny router library). Deploying one function means redeploying all routes. |
| Which makes the business logic cleaner? | **Both are equally clean**: `OrderService` is identical. A even keeps the Lambda entry points free of AWS imports. | Same `OrderService`. The AWS-specific code is confined to one adapter class. |
| Which is easier for another developer to understand? | Harder. The behavior is split between Java and VTL/regex in YAML, connected by a string convention (`"CODE: message"`). | **Easier.** One request → one Java method you can read top to bottom, step through in a debugger, and test. |
| Which would I choose for a larger system? | For very simple pass-through endpoints, or when the gateway must integrate directly with AWS services (no Lambda at all, e.g. API Gateway → DynamoDB/SQS). | **B**, explained below. |

**Which would I choose and why.** For a larger system I would choose **Architecture B** (proxy integration with a thin adapter), because:

1. The HTTP contract is *behavior*, and behavior should be in code we can test. In A, the most error-prone part of an API (status codes and error shapes) is exactly the part that cannot be unit tested.
2. It does not create a hidden string-matching contract between Java and the gateway.
3. It keeps the business logic just as clean as A, as long as the adapter stays thin. The discipline is "the adapter translates, the service decides".
4. It is portable: the same adapter idea works with HTTP APIs, Function URLs or an Application Load Balancer, and the service could be put behind Spring or Javalin later without changes.

I would not use one function for 30 operations forever: I would group by resource or bounded context (`orders-api`, `customers-api`...), each a proxy Lambda with its own adapter. And I would keep using API Gateway for what it is genuinely good at: authentication (Cognito/JWT authorizers), throttling, API keys, CORS and request size limits.

## 9. Final challenge: DELETE /orders/{id}

The prediction is in [`docs/delete-prediction.md`](docs/delete-prediction.md). It was committed *before* the implementation (`git log` shows the prediction commit first); the comparison with what was actually done is at the end of that file.

What changed:

| Layer | Change |
|---|---|
| `OrderRepository` / `InMemoryOrderRepository` | `boolean deleteById(String id)` |
| `OrderService` | `void deleteOrder(String id)`: throws `OrderNotFoundException` if nothing was deleted |
| Architecture A | new function `orders-delete-order` → `DirectOrderFunctions::deleteOrder` (returns `void`); new `DELETE /orders/{id}` method with a request template, `default` → **204** with an empty template, and the same 404/400/500 regexes |
| Architecture B | one `case "DELETE /orders/{id}"` in `OrdersApiHandler` returning 204; one new method in the OpenAPI file pointing to the **existing** function |
| Tests | 7 new tests (service, direct function, adapter, saved `events/proxy-delete-order.json`) |

The 404 case needed **no new code** in either architecture: "not found" was already an exception in the service and already mapped to 404 by both adapters. That is the payoff of keeping the decision ("did it exist?") in the service and the translation (204/404) at the edge.

Note on idempotency: `DELETE` is idempotent in HTTP terms because the *state* after one or many calls is the same (the order is gone). Returning 404 on the second call does not break that; it just reports that there was nothing to delete.

## 10. Final architecture diagram

```mermaid
flowchart TB
    Client(["Client<br/>curl / Postman"])

    subgraph AWS["AWS (managed, depends on AWS)"]
        direction TB
        GW["Amazon API Gateway (REST API)<br/>stage: dev"]
        subgraph A["Architecture A: non-proxy"]
            LA["λ orders-create-order<br/>λ orders-get-order<br/>λ orders-find-orders<br/>λ orders-delete-order"]
        end
        subgraph B["Architecture B: proxy"]
            LB["λ orders-api-proxy"]
        end
        CW[("CloudWatch Logs")]
    end

    subgraph JAR["orders-api-1.0.0.jar (same JAR in every function)"]
        direction TB
        subgraph AWSJAVA["Java that depends on AWS libraries"]
            H["lambda.proxy.OrdersApiHandler<br/>HTTP adapter: routing, params,<br/>status codes, headers, JSON"]
        end
        subgraph PLAIN["Ordinary Java (no AWS, no HTTP)"]
            D["lambda.direct.DirectOrderFunctions<br/>Lambda entry points, POJO in/out"]
            S["application.OrderService<br/>business rules + validation"]
            R["persistence.InMemoryOrderRepository"]
            M["domain.Order / CreateOrderRequest"]
        end
    end

    Client -->|"HTTPS request"| GW
    GW -->|"A: mapped JSON<br/>(VTL templates)"| LA
    GW -->|"B: full proxy event"| LB
    LA -->|invokes| D
    LB -->|invokes| H
    D --> S
    H --> S
    S --> R
    S -.uses.-> M
    H -.logs.-> CW
    GW -->|"HTTPS response"| Client
```

Arrows show the direction of calls; responses travel back along the same path. Everything in the "Ordinary Java" box compiles and runs without AWS; only `OrdersApiHandler` imports AWS classes (`aws-lambda-java-core` / `-events`).

## 11. AI-assisted development and critique of the agent

I used **Claude (Claude Code)** as an engineering collaborator. The prompt was in the spirit of the one suggested by the workshop: describe the API and the constraint "keep the application logic independent from AWS", and ask for the architecture, the files and where HTTP responsibilities would live *before* any code. I then reviewed each decision.

> Before submitting, adapt this section with your own opinion. The workshop evaluates *your* judgment of the agent, not the agent's opinion of itself.

### Decision 1: one Lambda per operation in A, one Lambda for the whole API in B

- **Decision made by the agent:** In Architecture A, deploy each operation as its own function (`orders-get-order`, ...), each pointing to a different method of the same class. In Architecture B, a single function for all routes.
- **Why it proposed this:** Non-proxy integrations map one method to one integration with its own templates, and the workshop's Part II asks to deploy "one Java operation". For the proxy, one function with a router is the common pattern.
- **Do I agree?** Partially. It is the natural fit for each style, and the agent did point out the consequence: with in-memory storage, **separate functions do not share data**, so an order created by `orders-create-order` is invisible to `orders-get-order`. That makes the A demo confusing (POST works, GET of the new order returns 404), and it is easy to mistake for a bug.
- **Alternative:** A single "dispatcher" Lambda for A where the mapping template adds an `"action"` field. That shares memory but turns the Lambda into a router driven by gateway-supplied strings, which mixes the two architectures.
- **What I implemented and why:** Kept one-function-per-operation in A, and documented the side effect (README section 6) and demonstrated it using the seed data. The limitation is caused by in-memory storage, not by the architecture; with DynamoDB it disappears, so hiding it with a dispatcher would teach the wrong lesson.

### Decision 2: where validation and error codes live

- **Decision considered by the agent:** A base `OrderException` carrying an error code (`"ORDER_NOT_FOUND"`, `"INVALID_REQUEST"`), so that both adapters could just read `e.getCode()` instead of each mapping exception types.
- **Why it proposed this:** It removes duplicated mapping code from the two adapters, and it is a very common pattern (it is one step away from Spring's `ResponseStatusException`, which also carries the HTTP status).
- **Do I agree?** No. The codes are part of the *API contract*, not of the business rules; once they are in `application`, the next natural step is to add the HTTP status too, which is exactly what the workshop asks to avoid. The agent ended up choosing the alternative itself before writing the code.
- **Alternative:** Plain domain exceptions (`OrderNotFoundException`, `InvalidOrderException`) and a translation step in each adapter.
- **What I implemented and why:** Plain domain exceptions without codes or statuses. `OrdersApiHandler` maps them to 400/404/500; `DirectOrderFunctions` maps them to the `CODE: message` prefix that the API Gateway regexes read. Validation stays in `OrderService` (business rules), and only *syntax* problems (malformed JSON) are detected in the adapter. The cost is two small mapping blocks instead of one, which is worth the clean dependency direction.

### Decision 3: use AWS event classes (`APIGatewayProxyRequestEvent`) vs. a `Map` or our own types

- **Decision made by the agent:** Implement `RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>` from `aws-lambda-java-events`.
- **Why it proposed this:** It is the documented, typed way; the runtime knows how to deserialize it, and the IDE autocompletes the fields.
- **Do I agree?** Yes, *for the adapter only*. The alternatives were `Map<String, Object>` (untyped, lots of casting) or `RequestStreamHandler` with our own DTO (more control, more code). Since the adapter is supposed to be the AWS-aware part, depending on AWS types there is fine.
- **What I implemented and why:** The event classes in `lambda.proxy` only. The tests build events with the fluent `withX()` methods and also load the saved real events, so the handler is fully testable without AWS.

### Decision 4: Velocity regex conventions for errors in Architecture A

- **Decision made by the agent:** Encode the error code at the start of the exception message (`ORDER_NOT_FOUND: ...`) and use regex selection patterns, plus a catch-all regex for 500.
- **Why it proposed this:** API Gateway only exposes `errorMessage` to selection patterns; there is no other channel for a non-proxy Lambda to signal "which error".
- **Do I agree?** Yes, it is the standard technique, and the catch-all 500 regex is something many tutorials forget. But it is fragile, so I asked for a unit test (`DirectOrderFunctionsTest`) that pins the exact message format, so a refactor in Java cannot break the gateway contract without a failing test.
- **Alternative:** Throw the whole error JSON as the message and use `$input.path('$.errorMessage')` directly as the body. Fewer template lines, but JSON inside an exception message is uglier and easier to break.

### Decision 5: in-memory id generation

- **Decision made by the agent:** Sequential ids (`ORD-004`, `ORD-005`) via an `AtomicInteger`.
- **Do I agree?** For a demo, yes (readable, predictable in tests because it is injected). In production it is wrong: two concurrent Lambda environments would both generate `ORD-004`. With a real database I would use UUIDs or a database-generated key. This is documented in `SequentialOrderIdGenerator`.

## 12. How to deploy

### Option 1: script (AWS CLI / CloudShell)

1. `mvn package`
2. Open AWS CloudShell (or any shell with the AWS CLI and credentials) and upload the repository, or at least `target/orders-api-1.0.0.jar`, `apigateway/` and `deploy/`.
3. Run:

```bash
./deploy/deploy.sh all
```

It creates the five Lambda functions (Java 17, role `LabRole` by default, override with `LAMBDA_ROLE_ARN`), imports both OpenAPI files as two REST APIs, grants API Gateway permission to invoke the functions and deploys the `dev` stage. It prints both invoke URLs.

### Option 2: console, by hand

**Lambda** (repeat per function in the tables of sections 5 and 7):
1. Lambda → *Create function* → *Author from scratch*, runtime **Java 17**, execution role: existing role (`LabRole` in the Learner Lab).
2. *Code* → *Upload from* → *.zip or .jar file* → `target/orders-api-1.0.0.jar`.
3. *Runtime settings* → *Edit* → *Handler*: e.g. `edu.escuelaing.orders.lambda.direct.DirectOrderFunctions::getOrder`.
4. *Configuration* → *General* → memory 512 MB, timeout 15 s.
5. *Test* with the matching file from `events/`.

**API Gateway, quickest:** *Create API* → *REST API* → *Import* → paste the OpenAPI file after replacing `REGION_PLACEHOLDER` and `ACCOUNT_ID_PLACEHOLDER`. Then for each function run `aws lambda add-permission ...` (see the script) or open each method's *Integration request* → *Edit* → *Save*, which makes the console offer to add the permission. *Deploy API* → new stage `dev`.

**API Gateway, step by step (Architecture A):**
1. *Create resource* `/orders`, then child `/{id}`.
2. `GET` on `/{id}` → integration type *Lambda function*, **Lambda proxy integration off**, function `orders-get-order`.
3. *Integration request* → *Mapping templates* → content type `application/json` → template from section 6.
4. *Method response* → add 404, 400, 500. *Integration response* → add one entry per row of the regex table in section 6, each with its template.
5. Same for `GET /orders` (`orders-find-orders`), `POST /orders` (`orders-create-order`, default response 201 + `Location`), `DELETE /orders/{id}` (`orders-delete-order`, default response 204).
6. *Deploy API*.

**Architecture B:** create the same resources, and for every method choose *Lambda function* with **Lambda proxy integration on**, function `orders-api-proxy`. Nothing else. *Deploy API*.

## 13. Demo video checklist

1. `mvn test` (all tests green).
2. `mvn package` (show `target/orders-api-1.0.0.jar`).
3. Lambda console: invoke `orders-create-order` with `events/direct-create-order.json` and `events/direct-create-order-2.json`.
4. `curl` against the deployed API (`examples/curl-examples.sh`):
   - successful `POST /orders` → 201 with `Location`
   - successful `GET /orders/ORD-001` → 200
   - `GET /orders/ORD-999` → 404
   - `POST /orders` with `quantity: 0` → 400
   - `DELETE /orders/ORD-002` → 204, then again → 404
5. Show the architecture diagram (section 10).
