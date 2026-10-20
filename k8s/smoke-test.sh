#!/usr/bin/env bash
# Smoke test for a Ledgerline deployment on Kubernetes.
# Proves the deployed system works end to end: probes, authentication, transfers,
# reconciliation, and the outbox -> Kafka -> consumer path.
#
# Needs: kubectl (pointing at the cluster), curl, jq, openssl.
# JWT_SECRET must equal the jwt-secret in the ledgerline-secrets Secret, because this
# script signs its own tokens. The dev token endpoint is (correctly) off in Kubernetes.
set -euo pipefail

NS="${NAMESPACE:-ledgerline}"
: "${JWT_SECRET:?Set JWT_SECRET to the same value as the jwt-secret in ledgerline-secrets}"
BASE="http://localhost:8080"
FUNDING="00000000-0000-0000-0000-000000000001"

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

echo "Waiting for rollouts..."
kubectl -n "$NS" rollout status statefulset/postgres --timeout=180s
kubectl -n "$NS" rollout status deployment/kafka --timeout=180s
kubectl -n "$NS" rollout status deployment/ledgerline --timeout=300s

kubectl -n "$NS" port-forward svc/ledgerline 8080:80 >/tmp/ledgerline-port-forward.log 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -fsS "$BASE/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 1
done

# ---- Sign tokens with HS256, exactly as the app expects (iss, aud, sub, exp, scope) ----
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
token() {
  local now exp header payload sig
  now=$(date +%s); exp=$((now + 600))
  header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
  payload=$(printf '{"iss":"ledgerline-dev","aud":["ledgerline-api"],"sub":"%s","iat":%d,"exp":%d,"scope":"%s"}' \
    "$1" "$now" "$exp" "$2" | b64url)
  sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)
  printf '%s.%s.%s' "$header" "$payload" "$sig"
}
USER_TOKEN=$(token smoke-user "accounts:read accounts:write transfers:write")
ADMIN_TOKEN=$(token smoke-admin "accounts:read transfers:write ledger:admin")

new_key() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

create_account() {
  curl -fsS -X POST "$BASE/accounts" -H "Authorization: Bearer $USER_TOKEN" \
    -H "Content-Type: application/json" -d "{\"name\":\"$1\",\"currency\":\"USD\"}" | jq -r .id
}
transfer() {   # token, from, to, amount
  curl -fsS -X POST "$BASE/transfers" -H "Authorization: Bearer $1" -H "Idempotency-Key: $(new_key)" \
    -H "Content-Type: application/json" \
    -d "{\"fromAccountId\":\"$2\",\"toAccountId\":\"$3\",\"amount\":$4}"
}
balance() {
  curl -fsS "$BASE/accounts/$1" -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r .balance
}

# ---- Checks ----
[ "$(status "$BASE/actuator/health/readiness")" = "200" ] && pass "readiness probe is UP" || fail "readiness probe"

[ "$(status -X POST "$BASE/accounts" -H "Content-Type: application/json" -d '{"name":"x","currency":"USD"}')" = "401" ] \
  && pass "request without a token is rejected (401)" || fail "request without a token was not rejected"

A=$(create_account "Smoke A")
B=$(create_account "Smoke B")
pass "created accounts $A and $B"

transfer "$ADMIN_TOKEN" "$FUNDING" "$A" 10000 >/dev/null
TRANSFER_ID=$(transfer "$USER_TOKEN" "$A" "$B" 2500 | jq -r .transferId)
pass "deposit and transfer succeeded (transfer $TRANSFER_ID)"

[ "$(balance "$A")" = "7500" ] && [ "$(balance "$B")" = "2500" ] \
  && pass "balances are correct (7500 / 2500)" || fail "balances are wrong"

[ "$(curl -fsS "$BASE/reconciliation" -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r .balanced)" = "true" ] \
  && pass "reconciliation: the books balance" || fail "reconciliation failed"

# The transfer's event must travel outbox -> relay -> Kafka -> consumer -> notifications.
count=0
for _ in $(seq 1 30); do
  count=$(kubectl -n "$NS" exec postgres-0 -- psql -U ledgerline -d ledgerline -tAc \
    "SELECT COUNT(*) FROM notifications n JOIN outbox_events o ON o.id = n.event_id WHERE o.aggregate_id = '$TRANSFER_ID'")
  [ "$count" = "2" ] && break
  sleep 2
done
[ "$count" = "2" ] && pass "event delivered through Kafka; both notifications written" \
  || fail "expected 2 notifications for the transfer, found $count"

echo "All smoke tests passed."