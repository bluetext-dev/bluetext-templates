#!/bin/sh
# Create or update user accounts via Curity SCIM API (idempotent upsert).
# Usage: sh create-users.sh <namespace> <users>
#   users: comma-separated username:password pairs (e.g. "alice:Pass1,bob:Pass2")
#
# Re-running heals a credential store that was populated before BCrypt
# hashing was configured on the data source. For each user the script looks
# up the account by userName: if it exists, the password is re-set via SCIM
# PATCH; if not, the account is created. Either write goes through Curity's
# credential-manager, which hashes the password with BCrypt on write — so a
# re-run replaces any plaintext value left by an earlier run with a `$2a$`
# hash. Creation and update are therefore both idempotent and self-healing.

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

  # Look the account up by userName. The SCIM ListResponse's first "id" is
  # the matched user's resource id (the totalResults/startIndex preamble
  # carries no "id"). Absent or filter-unsupported → empty → create path.
  EXISTING_ID=$(curl -sf -G "${BASE_URL}/um/Users" \
    -H "Authorization: Bearer $TOKEN" \
    --data-urlencode "filter=userName eq \"${USERNAME}\"" 2>/dev/null | \
    grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"\([^"]*\)"/\1/')

  if [ -n "$EXISTING_ID" ]; then
    # Re-set the password. Curity hashes it on write, so a plaintext value
    # stored before the credential-manager declared <BCrypt> is replaced.
    STATUS=$(curl -s -o /tmp/curity-patch-body -w '%{http_code}' \
      -X PATCH "${BASE_URL}/um/Users/${EXISTING_ID}" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
        \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],
        \"Operations\": [{\"op\": \"replace\", \"path\": \"password\", \"value\": \"${PASSWORD}\"}]
      }")
    case "$STATUS" in
      2*) echo "[${USERNAME}] Password re-set (hashed)" ;;
      *)  ERROR=$(sed -n 's/.*"detail":"\([^"]*\)".*/\1/p' /tmp/curity-patch-body)
          echo "[${USERNAME}] Update failed (HTTP ${STATUS}): ${ERROR:-see response}" ;;
    esac
  else
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
  fi
done
