#!/usr/bin/env bash
# Calls every operation of a deployed Orders API and prints status, headers and body.
#
# Usage: ./examples/curl-examples.sh https://<api-id>.execute-api.<region>.amazonaws.com/Alpha_dev
#
# Works against both architectures. With Architecture A the POST and the GETs run in
# different Lambda functions, so the order created here is NOT found by the next GET
# (each function has its own in-memory data). With Architecture B it is found, as long
# as the same warm execution environment serves both requests.
set -u
BASE_URL="${1:?Usage: $0 <invoke URL>}"

call() {
  echo
  echo "### $*"
  curl -s -i "$@"
  echo
}

call -X POST "$BASE_URL/orders" -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-42", "product": "MacBook Air", "quantity": 1}'
call "$BASE_URL/orders/ORD-001"
call "$BASE_URL/orders/ORD-004"
call "$BASE_URL/orders/ORD-999"
call "$BASE_URL/orders?customerId=CUST-42"
call "$BASE_URL/orders?customerId=CUST-100"
call "$BASE_URL/orders"
call -X POST "$BASE_URL/orders" -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-42", "product": "", "quantity": 0}'
call -X POST "$BASE_URL/orders" -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-42", "product": "Mouse", "quantity": "abc"}'
call -X DELETE "$BASE_URL/orders/ORD-002"
call -X DELETE "$BASE_URL/orders/ORD-002"
call -X DELETE "$BASE_URL/orders/ORD-999"
