#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:80}"
BASE_URL="${BASE_URL%/}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

UNIQUE="$(date +%s)-$$"
EMAIL="smoke-${UNIQUE}@livevote.local"
PASSWORD="SmokePass_${UNIQUE}!"
DEVICE_1="smoke-dev-1-${UNIQUE}"
DEVICE_2="smoke-dev-2-${UNIQUE}"

RESP_CODE=""
RESP_BODY=""

http_req() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  shift 2
  if [[ $# -gt 0 ]]; then
    shift
  fi

  local out_file="${TMP_DIR}/resp.body"
  if [[ -n "$body" ]]; then
    RESP_CODE="$(curl -sS -o "$out_file" -w '%{http_code}' -X "$method" "${BASE_URL}${path}" \
      -H 'Content-Type: application/json' "$@" -d "$body")"
  else
    RESP_CODE="$(curl -sS -o "$out_file" -w '%{http_code}' -X "$method" "${BASE_URL}${path}" "$@")"
  fi
  RESP_BODY="$(cat "$out_file")"
}

assert_status() {
  local label="$1"
  local expected="$2"
  if [[ "$RESP_CODE" != "$expected" ]]; then
    echo "FAIL ${label}: expected HTTP ${expected}, got ${RESP_CODE}. Body: ${RESP_BODY}" >&2
    exit 1
  fi
  echo "PASS ${label} (HTTP ${RESP_CODE})"
}

# 1. Public healthz
http_req GET /healthz
assert_status "[1/9] GET /healthz" "200"

# 2. Gateway strips spoofed X-User-Id and rejects unauthenticated protected route
http_req GET /api/polls/mine "" -H 'X-User-Id: 00000000-0000-0000-0000-000000000001'
assert_status "[2/9] Spoofed X-User-Id rejected" "401"

# 3. Register user
http_req POST /api/auth/register "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}"
assert_status "[3/9] POST /api/auth/register" "201"

# 4. Login user
http_req POST /api/auth/login "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}"
assert_status "[4/9] POST /api/auth/login" "200"
TOKEN="$(printf '%s' "$RESP_BODY" | grep -o '"token":"[^"]*"' | head -n1 | cut -d'"' -f4 || true)"
if [[ -z "$TOKEN" ]]; then
  echo "FAIL: missing JWT token in login response: ${RESP_BODY}" >&2
  exit 1
fi

# 5. Create poll
http_req POST /api/polls '{"title":"Smoke Prod Poll","options":["Alpha","Beta"]}' -H "Authorization: Bearer ${TOKEN}"
assert_status "[5/9] POST /api/polls" "201"
POLL_ID="$(printf '%s' "$RESP_BODY" | grep -o '"id":"[^"]*"' | sed -n '1p' | cut -d'"' -f4 || true)"
OPTION_A="$(printf '%s' "$RESP_BODY" | grep -o '"id":"[^"]*"' | sed -n '2p' | cut -d'"' -f4 || true)"
if [[ -z "$POLL_ID" || -z "$OPTION_A" ]]; then
  echo "FAIL: missing pollId or optionA: ${RESP_BODY}" >&2
  exit 1
fi

# 6. Wait for initial projection (GET /api/results/{id})
PROJ_OK=0
for _ in $(seq 1 30); do
  http_req GET "/api/results/${POLL_ID}"
  if [[ "$RESP_CODE" == "200" ]]; then
    PROJ_OK=1
    break
  fi
  sleep 0.5
done
if [[ "$PROJ_OK" -ne 1 ]]; then
  echo "FAIL [6/9] Projection not ready in time (last HTTP ${RESP_CODE})" >&2
  exit 1
fi
echo "PASS [6/9] Initial results projection ready (HTTP 200)"

# 7. Cast vote + verify results totalVotes=1 + verify SSE snapshot
http_req POST "/api/polls/${POLL_ID}/votes" "{\"optionId\":\"${OPTION_A}\",\"deviceId\":\"${DEVICE_1}\"}"
assert_status "[7a/9] POST vote" "201"

VOTES_OK=0
for _ in $(seq 1 30); do
  http_req GET "/api/results/${POLL_ID}"
  if [[ "$RESP_CODE" == "200" ]] && printf '%s' "$RESP_BODY" | grep -q '"totalVotes":1'; then
    VOTES_OK=1
    break
  fi
  sleep 0.5
done
if [[ "$VOTES_OK" -ne 1 ]]; then
  echo "FAIL [7b/9] totalVotes did not reconcile to 1: ${RESP_BODY}" >&2
  exit 1
fi

SSE_FILE="${TMP_DIR}/sse.out"
curl -N -sS --max-time 4 "${BASE_URL}/api/results/${POLL_ID}/stream" > "$SSE_FILE" 2>/dev/null || true
if ! grep -q 'event:results' "$SSE_FILE" || ! grep -q '"totalVotes":1' "$SSE_FILE"; then
  echo "FAIL [7c/9] SSE snapshot did not emit totalVotes:1" >&2
  exit 1
fi
echo "PASS [7/9] Vote projected (totalVotes=1) and live SSE stream verified"

# 8. Duplicate vote rejected (409)
http_req POST "/api/polls/${POLL_ID}/votes" "{\"optionId\":\"${OPTION_A}\",\"deviceId\":\"${DEVICE_1}\"}"
assert_status "[8/9] Duplicate vote rejected" "409"

# 9. Close poll -> new vote rejected (422)
http_req POST "/api/polls/${POLL_ID}/close" "" -H "Authorization: Bearer ${TOKEN}"
assert_status "[9a/9] Close poll" "200"
http_req POST "/api/polls/${POLL_ID}/votes" "{\"optionId\":\"${OPTION_A}\",\"deviceId\":\"${DEVICE_2}\"}"
assert_status "[9b/9] Vote after close rejected" "422"

echo "ALL 9 SMOKE CHECKS PASSED against ${BASE_URL}"
