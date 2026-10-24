# DELETE /orders/{id}: prediction before implementing

Written and committed **before** asking the AI assistant to implement the feature.

Expected behavior:

| Request | Response |
|---|---|
| `DELETE /orders/ORD-002` (exists) | `204 No Content`, empty body |
| `DELETE /orders/ORD-999` (does not exist) | `404 Not Found`, `{"error": "ORDER_NOT_FOUND", "message": "Order ORD-999 does not exist"}` |

## Prediction

**Which Java classes should change?**
- `OrderRepository`: new method `boolean deleteById(String id)` (true if something was removed).
- `InMemoryOrderRepository`: implement it with `map.remove(id) != null`.
- `OrderService`: new `void deleteOrder(String id)`; validates the id and throws `OrderNotFoundException` when nothing was deleted.
- `OrdersApiHandler` (Architecture B): new route `"DELETE /orders/{id}"` that returns 204 with no body.
- `DirectOrderFunctions` (Architecture A): new method `deleteOrder(OrderIdInput)` returning `void`.
- No change to `Order`, `CreateOrderRequest`, `ErrorResponse` or the exceptions: "not found" already exists and already maps to 404 in both architectures.

**Do you need a new Lambda function?**
- Architecture A: **yes**, `orders-delete-order` (handler `DirectOrderFunctions::deleteOrder`), because there is one function per operation.
- Architecture B: **no**. The same `orders-api-proxy` function gets the new route; only its code is redeployed.

**Does API Gateway configuration need to change?**
Yes in both, but very differently:
- A: new `DELETE` method on `/orders/{id}` with a request template (`{"id": ...}`), a default integration response mapped to **204** with an empty body, the `ORDER_NOT_FOUND` regex → 404, the 400 and the catch-all 500 regexes, plus permission for API Gateway to invoke the new function.
- B: just a new `DELETE` method on `/orders/{id}` with proxy integration to the existing function. No templates.

**Where should the deletion logic reside?**
In `OrderService.deleteOrder` (the rule "you cannot delete what does not exist" is business logic), with the actual removal in the repository.

**Where should the decision to return 204 or 404 reside?**
- The decision *"did it exist?"* is in `OrderService` (it either returns normally or throws `OrderNotFoundException`).
- The translation into **204/404** is an HTTP concern: in `OrdersApiHandler` for B, and in the API Gateway integration responses for A. The service never mentions 204 or 404.

**Which tests should be added?**
- `OrderServiceTest`: deleting an existing order removes it (a later `getOrder` throws not found, it disappears from the customer search); deleting a missing order throws `OrderNotFoundException`; blank id is invalid.
- `OrdersApiHandlerTest`: `DELETE` of an existing order → 204 with empty body; `DELETE` of a missing order → 404 with the error JSON; deleting twice → 204 then 404.
- `DirectOrderFunctionsTest`: missing order → `ORDER_NOT_FOUND: ...` message.

## Comparison with the agent's proposal

_Filled in after the implementation (see the next commit)._
