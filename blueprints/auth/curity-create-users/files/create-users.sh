#!/bin/sh
# Create user accounts via Curity SCIM API
# Usage: sh create-users.sh <namespace> <users>
#   users: comma-separated username:password pairs (e.g. "alice:Pass1,bob:Pass2")

NAMESPACE="$1"
USERS="$2"
BASE_URL="http://curity.${NAMESPACE}.bluetext.localhost"

# Retry getting management token — Curity may still be loading profiles after restart
for i in $(seq 1 30); do
  TOKEN=$(curl -sf -X POST "${BASE_URL}/oauth/v2/oauth-token" \
    -d "grant_type=client_credentials&client_id=um-admin-client&client_secret=um-admin-secret" | \
    sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
  [ -n "$TOKEN" ] && break
  echo "Waiting for User Management profile to be ready... (attempt $i/30)"
  sleep 2
done

if [ -z "$TOKEN" ]; then
  echo "Failed to get management token after 30 attempts. Is Curity running with the User Management profile?"
  exit 1
fi

echo "$USERS" | tr ',' '\n' | while IFS=: read -r USERNAME PASSWORD; do
  USERNAME=$(echo "$USERNAME" | tr -d ' ')
  PASSWORD=$(echo "$PASSWORD" | tr -d ' ')
  if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    echo "Skipping invalid entry (expected username:password): ${USERNAME}"
    continue
  fi

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
    echo "[${USERNAME}] Created"
  else
    ERROR=$(echo "$RESULT" | sed -n 's/.*"detail":"\([^"]*\)".*/\1/p')
    echo "[${USERNAME}] Failed: ${ERROR:-$RESULT}"
  fi
done
