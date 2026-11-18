#!/usr/bin/env bash
# Mints an HS256 token for the local gateway.
#   scripts/token.sh <user-id> [roles, comma separated] [ttl seconds]
# The secret is the development one from application.yml unless
# GATEWAY_HMAC_SECRET is set.
set -euo pipefail

sub="${1:?usage: token.sh <user-id> [roles] [ttl-seconds]}"
roles="${2:-customer}"
ttl="${3:-3600}"
secret="${GATEWAY_HMAC_SECRET:-dev-only-secret-change-me-0123456789abcdef}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
roles_json=$(printf '%s' "$roles" | awk -F, '{for (i=1;i<=NF;i++) printf "%s\"%s\"", (i>1?",":""), $i}')
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"https://auth.example.local","sub":"%s","roles":[%s],"iat":%d,"exp":%d}' \
  "$sub" "$roles_json" "$now" "$((now + ttl))" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
