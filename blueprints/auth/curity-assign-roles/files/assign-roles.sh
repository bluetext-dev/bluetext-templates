#!/bin/sh
# Assign roles to existing accounts via Curity SCIM API
# Usage: sh assign-roles.sh <namespace> <users>
#   users: comma-separated username:role pairs (e.g. "alice:admin,bob:moderator")

NAMESPACE="$1"
USERS="$2"
BASE_URL="http://curity.${NAMESPACE}.bluetext.localhost"

TOKEN=$(curl -sf -X POST "${BASE_URL}/oauth/v2/oauth-token" \
  -d "grant_type=client_credentials&client_id=um-admin-client&client_secret=um-admin-secret" | \
  sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [ -z "$TOKEN" ]; then
  echo "Failed to get management token. Is Curity running with the User Management profile?"
  exit 1
fi

echo "$USERS" | tr ',' '\n' | while IFS=: read -r USERNAME ROLE; do
  USERNAME=$(echo "$USERNAME" | tr -d ' ')
  ROLE=$(echo "$ROLE" | tr -d ' ')
  if [ -z "$USERNAME" ] || [ -z "$ROLE" ]; then
    echo "Skipping invalid entry: ${USERNAME}:${ROLE}"
    continue
  fi

  ACCOUNT_ID=$(curl -sf "${BASE_URL}/um/Users?filter=userName+eq+%22${USERNAME}%22" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/json" | \
    sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

  if [ -z "$ACCOUNT_ID" ]; then
    echo "[${USERNAME}] Not found — register first"
    continue
  fi

  RESULT=$(curl -s -X PATCH "${BASE_URL}/um/Users/${ACCOUNT_ID}" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],
      \"Operations\": [
        {\"op\": \"replace\", \"path\": \"active\", \"value\": true},
        {\"op\": \"add\", \"path\": \"roles\", \"value\": [{\"value\": \"${ROLE}\", \"primary\": true}]}
      ]
    }" 2>&1)

  if echo "$RESULT" | grep -q "\"roles\""; then
    echo "[${USERNAME}] Role '${ROLE}' assigned"
  else
    ERROR=$(echo "$RESULT" | sed -n 's/.*"detail":"\([^"]*\)".*/\1/p')
    echo "[${USERNAME}] Failed: ${ERROR:-$RESULT}"
  fi
done
