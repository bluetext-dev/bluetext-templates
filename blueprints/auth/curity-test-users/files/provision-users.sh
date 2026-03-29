#!/bin/sh
# Provision test users with roles via Curity SCIM API
# Usage: sh provision-users.sh <namespace> <users>
#   users: comma-separated username:role pairs (e.g. "alice:admin,bob:moderator")
# Users are created without passwords — they set their own via registration/login.

NAMESPACE="$1"
USERS="$2"
BASE_URL="http://curity.${NAMESPACE}.bluetext.localhost"

# Get management token
TOKEN=$(curl -sf -X POST "${BASE_URL}/oauth/v2/oauth-token" \
  -d "grant_type=client_credentials&client_id=um-admin-client&client_secret=um-admin-secret" | \
  sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [ -z "$TOKEN" ]; then
  echo "Failed to get management token. Is Curity running with the User Management profile?"
  exit 1
fi

# Process each user:role pair
echo "$USERS" | tr ',' '\n' | while IFS=: read -r USERNAME ROLE; do
  if [ -z "$USERNAME" ] || [ -z "$ROLE" ]; then
    echo "Skipping invalid entry: ${USERNAME}:${ROLE}"
    continue
  fi

  EMAIL="${USERNAME}@test.local"

  # Create account (no password — set via registration flow)
  ACCOUNT_ID=$(curl -sf -X POST "${BASE_URL}/um/Users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"schemas\": [\"urn:ietf:params:scim:schemas:core:2.0:User\"],
      \"userName\": \"${USERNAME}\",
      \"emails\": [{\"value\": \"${EMAIL}\", \"primary\": true}],
      \"name\": {\"givenName\": \"${USERNAME}\", \"familyName\": \"test\"}
    }" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

  if [ -z "$ACCOUNT_ID" ]; then
    echo "[${USERNAME}] Failed to create account (may already exist)"
    continue
  fi

  # Activate account and assign role
  curl -sf -X PATCH "${BASE_URL}/um/Users/${ACCOUNT_ID}" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],
      \"Operations\": [
        {\"op\": \"replace\", \"path\": \"active\", \"value\": true},
        {\"op\": \"add\", \"path\": \"roles\", \"value\": [{\"value\": \"${ROLE}\", \"primary\": true}]}
      ]
    }" > /dev/null 2>&1

  echo "[${USERNAME}] Created with role '${ROLE}'"
done
