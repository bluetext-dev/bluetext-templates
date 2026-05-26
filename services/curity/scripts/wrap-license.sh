#!/usr/bin/env bash
# wrap-license.sh — normalize Curity license input into the wrapped form
# Curity reads at startup: `{"License":"<header>.<payload>.<signature>"}`.
#
# Accepts three input shapes on stdin:
#   1. Full portal envelope JSON:
#        {"Company":"...","Tier":"...","Issued":"...","License":"a.b.c"}
#      → extracts the License field
#   2. Raw JWT (the License field value): `a.b.c` (exactly two `.`)
#      → wraps as-is
#   3. Payload-only blob (zero `.`, base64 of the JWT claims object)
#      → fails loud — that's the recurring bug
#
# Emits the wrapped form on stdout:
#   {"License":"<full-jwt>"}
#
# Designed to be piped from $CURITY_LICENSE_KEY or `jq -r .License <file>`.
# Curity rejects payload-only input at boot with the misleading error
# `LicenseKeyValidationCallback - License was the wrong issuer or had not
# subject`; this script catches it at secret-set time instead.

set -euo pipefail

INPUT=$(cat)

case "$INPUT" in
  \{*\})
    if ! command -v jq >/dev/null 2>&1; then
      echo "wrap-license.sh: input looks like JSON but jq isn't on PATH; install jq or pass the raw JWT instead" >&2
      exit 2
    fi
    JWT=$(printf '%s' "$INPUT" | jq -r 'if type == "object" and has("License") then .License else empty end')
    if [ -z "$JWT" ]; then
      echo "wrap-license.sh: input is a JSON object but has no \`License\` field. The portal envelope looks like {\"Company\":\"...\",\"License\":\"<jwt>\"} — pass that file's contents or just the License value." >&2
      exit 1
    fi
    ;;
  *)
    JWT="$INPUT"
    ;;
esac

# Trim a trailing newline if the caller piped from `echo` etc.
JWT="${JWT%$'\n'}"

DOTS=$(printf '%s' "$JWT" | tr -cd '.' | wc -c | tr -d ' ')
if [ "$DOTS" != "2" ]; then
  cat >&2 <<EOF
wrap-license.sh: input is not a Curity license JWT (expected 3 base64
segments separated by 2 \`.\` — found $DOTS \`.\`).

Common cause: the env var or paste contains only the JWT *payload*
(the middle base64 segment). It base64-decodes to a JSON object with
\`iss\`/\`sub\` claims, which makes it look right — but without the
header + signature Curity rejects it at boot with the misleading error
\`LicenseKeyValidationCallback - License was the wrong issuer or had
not subject\`, and the pod CrashLoopBackOffs.

Fix: extract the License field from the Curity-issued JSON file:
  export CURITY_LICENSE_KEY=\$(jq -r .License /path/to/cillers.com_Trial_*.json)
  echo -n "\$CURITY_LICENSE_KEY" | tr -cd '.' | wc -c   # must print 2
EOF
  exit 1
fi

printf '{"License":"%s"}' "$JWT"
