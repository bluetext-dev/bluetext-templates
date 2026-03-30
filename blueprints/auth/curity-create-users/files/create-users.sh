#!/bin/sh
# Create user accounts via Curity SCIM API
# Usage: sh create-users.sh <namespace> <users> <password>
#   users: comma-separated usernames (e.g. "alice,bob,carol")

NAMESPACE="$1"
USERS="$2"
PASSWORD="$3"
BASE_URL="http://curity.${NAMESPACE}.bluetext.localhost"

TOKEN=$(curl -sf -X POST "${BASE_URL}/oauth/v2/oauth-token" \
  -d "grant_type=client_credentials&client_id=um-admin-client&client_secret=um-admin-secret" | \
  sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [ -z "$TOKEN" ]; then
  echo "Failed to get management token. Is Curity running with the User Management profile?"
  exit 1
fi

echo "$USERS" | tr ',' '\n' | while read -r USERNAME; do
  USERNAME=$(echo "$USERNAME" | tr -d ' ')
  [ -z "$USERNAME" ] && continue

  EMAIL="${USERNAME}@test.local"

  RESULT=$(curl -s -X POST "${BASE_URL}/um/Users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"schemas\": [\"urn:ietf:params:scim:schemas:core:2.0:User\"],
      \"userName\": \"${USERNAME}\",
      \"password\": \"${PASSWORD}\",
      \"active\": true,
      \"emails\": [{\"value\": \"${EMAIL}\", \"primary\": true}],
      \"name\": {\"givenName\": \"${USERNAME}\", \"familyName\": \"test\"}
    }" 2>&1)

  if echo "$RESULT" | grep -q '"userName"'; then
    echo "[${USERNAME}] Created (password: ${PASSWORD})"
  else
    ERROR=$(echo "$RESULT" | sed -n 's/.*"detail":"\([^"]*\)".*/\1/p')
    echo "[${USERNAME}] Failed: ${ERROR:-$RESULT}"
  fi
done
