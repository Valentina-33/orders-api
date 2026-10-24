#!/usr/bin/env bash
# Deploys the Orders API with the AWS CLI (works in AWS CloudShell or any shell with the CLI).
#
# Usage (from the repository root, after `mvn package`):
#   ./deploy/deploy.sh lambdas   # create or update every Lambda function
#   ./deploy/deploy.sh api-a     # import Architecture A (mapping templates) and deploy stage
#   ./deploy/deploy.sh api-b     # import Architecture B (proxy integration) and deploy stage
#   ./deploy/deploy.sh all       # the three steps above
#
# Environment variables:
#   AWS_REGION       default: the CLI's configured region, or us-east-1
#   LAMBDA_ROLE_ARN  default: arn:aws:iam::<account>:role/LabRole (AWS Academy Learner Lab)
#   STAGE            default: Alpha_dev
#
# Each api-a / api-b run creates a NEW REST API. Delete old ones in the console if needed.
set -euo pipefail
cd "$(dirname "$0")/.."

REGION="${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
REGION="${REGION:-us-east-1}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ROLE_ARN="${LAMBDA_ROLE_ARN:-arn:aws:iam::${ACCOUNT_ID}:role/LabRole}"
STAGE="${STAGE:-Alpha_dev}"
JAR="target/orders-api-1.0.0.jar"
DIRECT="edu.escuelaing.orders.lambda.direct.DirectOrderFunctions"
PROXY="edu.escuelaing.orders.lambda.proxy.OrdersApiHandler"

A_FUNCTIONS=(orders-create-order orders-get-order orders-find-orders orders-delete-order)
B_FUNCTIONS=(orders-api-proxy)

deploy_function() {
  local name="$1" handler="$2"
  if aws lambda get-function --function-name "$name" --region "$REGION" >/dev/null 2>&1; then
    echo "Updating $name ($handler)"
    aws lambda update-function-code --function-name "$name" --zip-file "fileb://$JAR" \
      --region "$REGION" >/dev/null
    aws lambda wait function-updated-v2 --function-name "$name" --region "$REGION"
    aws lambda update-function-configuration --function-name "$name" --handler "$handler" \
      --region "$REGION" >/dev/null
    aws lambda wait function-updated-v2 --function-name "$name" --region "$REGION"
  else
    echo "Creating $name ($handler)"
    aws lambda create-function --function-name "$name" --runtime java17 --role "$ROLE_ARN" \
      --handler "$handler" --zip-file "fileb://$JAR" --memory-size 512 --timeout 15 \
      --region "$REGION" >/dev/null
    aws lambda wait function-active-v2 --function-name "$name" --region "$REGION"
  fi
}

deploy_lambdas() {
  [[ -f "$JAR" ]] || { echo "Missing $JAR. Run 'mvn package' first." >&2; exit 1; }
  deploy_function orders-create-order "$DIRECT::createOrder"
  deploy_function orders-get-order "$DIRECT::getOrder"
  deploy_function orders-find-orders "$DIRECT::findOrdersByCustomer"
  deploy_function orders-delete-order "$DIRECT::deleteOrder"
  deploy_function orders-api-proxy "$PROXY::handleRequest"
}

# import_api <openapi file> <function names...>
import_api() {
  local spec="$1"; shift
  local rendered="target/$(basename "$spec")"
  mkdir -p target
  sed -e "s/REGION_PLACEHOLDER/$REGION/g" -e "s/ACCOUNT_ID_PLACEHOLDER/$ACCOUNT_ID/g" "$spec" > "$rendered"

  local api_id
  api_id="$(aws apigateway import-rest-api --parameters endpointConfigurationTypes=REGIONAL \
    --body "fileb://$rendered" --region "$REGION" --query id --output text)"

  # API Gateway needs permission to invoke each Lambda.
  for fn in "$@"; do
    aws lambda add-permission --function-name "$fn" --statement-id "apigateway-$api_id" \
      --action lambda:InvokeFunction --principal apigateway.amazonaws.com \
      --source-arn "arn:aws:execute-api:$REGION:$ACCOUNT_ID:$api_id/*/*/*" \
      --region "$REGION" >/dev/null
  done

  aws apigateway create-deployment --rest-api-id "$api_id" --stage-name "$STAGE" \
    --region "$REGION" >/dev/null
  echo "Deployed $(basename "$spec"): https://$api_id.execute-api.$REGION.amazonaws.com/$STAGE"
}

case "${1:-}" in
  lambdas) deploy_lambdas ;;
  api-a) import_api apigateway/architecture-a-mapping-openapi.yaml "${A_FUNCTIONS[@]}" ;;
  api-b) import_api apigateway/architecture-b-proxy-openapi.yaml "${B_FUNCTIONS[@]}" ;;
  all)
    deploy_lambdas
    import_api apigateway/architecture-a-mapping-openapi.yaml "${A_FUNCTIONS[@]}"
    import_api apigateway/architecture-b-proxy-openapi.yaml "${B_FUNCTIONS[@]}"
    ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
