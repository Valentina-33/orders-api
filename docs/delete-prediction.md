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

The assistant's proposal matched the prediction on the main points: same classes, same place for the logic (`OrderService`), same split of the 204/404 decision, a new function only in Architecture A, and a gateway change in both.

| Topic | Prediction | Agent's proposal / final implementation | Comment |
|---|---|---|---|
| Repository method | `boolean deleteById(String id)` | Same | Returning `boolean` avoids a separate `findById` + `delete` (two steps that could race). |
| Service signature | `void deleteOrder(String id)` throwing not found | Same | An alternative was returning the deleted `Order` (or `boolean`). The agent kept `void` + exception to be consistent with `getOrder`, so both adapters reuse their existing 404 mapping. |
| Architecture A: Lambda return type | `void` | `void` | The runtime returns `null`. **Not predicted:** the agent pointed out that the `default` integration response needs an explicit (empty) template; otherwise the default passthrough would send the text `null` as the body of the 204. |
| Architecture A: 404 | Regex `ORDER_NOT_FOUND: .*` | Same, plus the 400 and catch-all 500 regexes copied again | This copy-paste is the "30 operations" problem in small. |
| Architecture B: 204 body | No body | `withStatusCode(204)` with no body and no `Content-Type` header | Not predicted in detail: a 204 must not have a body, so the JSON content-type header is also left out. |
| Tests | service + adapter + direct | Same, plus the saved event `events/proxy-delete-order.json` run through the handler | The extra event keeps the documented examples honest. |
| Idempotency | Not considered | The agent raised whether the second `DELETE` should be 204 (idempotent "it's gone") or 404 | We kept 404 because the workshop asks for it, and documented why it still respects HTTP idempotency (same server state). |

What surprised me: the amount of work was very different between the architectures. B was one `case` line plus one Java method; A needed a new function, a new permission and ~60 lines of YAML, even though the business logic was identical.
