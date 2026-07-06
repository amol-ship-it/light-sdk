#!/usr/bin/env bash
# Registers (or re-registers) this fleet application's domain with Tesla so
# vehicles will trust the virtual key hosted there. Safe to re-run: Tesla's
# partner_accounts endpoint is idempotent and returns the existing
# registration if the domain is already registered.
#
# Required env vars: CLIENT_ID, CLIENT_SECRET, DOMAIN
# Optional: REGION (na or eu; default na)
set -euo pipefail

: "${CLIENT_ID:?CLIENT_ID env var is required}"
: "${CLIENT_SECRET:?CLIENT_SECRET env var is required}"
: "${DOMAIN:?DOMAIN env var is required (the domain hosting your public key)}"
REGION="${REGION:-na}"

case "$REGION" in
  na|eu) ;;
  *) echo "REGION must be 'na' or 'eu' (got: $REGION)" >&2; exit 1 ;;
esac

FLEET_API_BASE="https://fleet-api.prd.${REGION}.vn.cloud.tesla.com"
TOKEN_URL="https://auth.tesla.com/oauth2/v3/token"

echo "Requesting partner (client-credentials) token for audience $FLEET_API_BASE ..." >&2

TOKEN_RESPONSE="$(curl -sS -X POST "$TOKEN_URL" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "client_secret=${CLIENT_SECRET}" \
  --data-urlencode "scope=openid vehicle_device_data vehicle_cmds vehicle_charging_cmds" \
  --data-urlencode "audience=${FLEET_API_BASE}")"

ACCESS_TOKEN="$(python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except json.JSONDecodeError:
    print("ERROR: token endpoint returned non-JSON response", file=sys.stderr)
    sys.exit(1)
tok = data.get("access_token")
if not tok:
    print("ERROR: no access_token in response: %r" % {k: v for k, v in data.items()}, file=sys.stderr)
    sys.exit(1)
print(tok)
' <<< "$TOKEN_RESPONSE")"

if [ -z "$ACCESS_TOKEN" ]; then
  echo "Failed to obtain partner token. Full response:" >&2
  echo "$TOKEN_RESPONSE" >&2
  exit 1
fi

echo "Got partner token. Registering domain '$DOMAIN' with $FLEET_API_BASE/api/1/partner_accounts ..." >&2

BODY="$(python3 -c 'import json,sys; print(json.dumps({"domain": sys.argv[1]}))' "$DOMAIN")"

HTTP_RESPONSE="$(curl -sS -w $'\n%{http_code}' -X POST "$FLEET_API_BASE/api/1/partner_accounts" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$BODY")"

HTTP_STATUS=$(printf '%s' "$HTTP_RESPONSE" | tail -n1)
HTTP_BODY=$(printf '%s' "$HTTP_RESPONSE" | sed '$d')

printf '%s\n' "$HTTP_BODY" | python3 -m json.tool 2>/dev/null || printf '%s\n' "$HTTP_BODY"

if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then
  echo "ERROR: partner registration failed (HTTP $HTTP_STATUS)" >&2
  exit 1
fi

echo
echo "Partner registration succeeded — Tesla now trusts $DOMAIN as your fleet application's key domain."
echo "Next step: run login.py --region $REGION --client-id \$CLIENT_ID --domain $DOMAIN ..."
echo "to complete OAuth and produce the setup QR for the tool."
