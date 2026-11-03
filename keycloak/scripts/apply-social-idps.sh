#!/usr/bin/env bash
# Creates or updates the Google / Facebook identity providers ("Sign in with ...") on an EXISTING realm.
# The realm JSON is imported only on a fresh Keycloak volume; this script brings a running one up to date.
#
#   ./keycloak/scripts/apply-social-idps.sh            (reads .env from the project root)
#   ENV_FILE=/path/.env KEYCLOAK_CONTAINER=other ./keycloak/scripts/apply-social-idps.sh
#
# Reads from .env (or the environment, which wins):
#   KEYCLOAK_GOOGLE_CLIENT_ID / KEYCLOAK_GOOGLE_CLIENT_SECRET
#   KEYCLOAK_FACEBOOK_CLIENT_ID / KEYCLOAK_FACEBOOK_CLIENT_SECRET
#   KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD   (default admin / admin)
# A provider whose ID or secret is empty is skipped (left as it is). Safe to run any number of times.
# Secrets are passed on stdin, never on a command line.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
CONTAINER="${KEYCLOAK_CONTAINER:-finally-keycloak}"
REALM="${KEYCLOAK_REALM:-finally}"

# Read KEY=VALUE lines from .env without executing it; variables already set in the environment win.
read_env() {
  local key="$1" line val
  if [ -n "${!key:-}" ]; then printf '%s' "${!key}"; return; fi
  [ -f "$ENV_FILE" ] || return 0
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  val="${line#*=}"
  val="${val%$'\r'}"
  val="${val#\"}"; val="${val%\"}"
  val="${val#\'}"; val="${val%\'}"
  printf '%s' "$val"
}

ADMIN_USER="$(read_env KEYCLOAK_ADMIN)";            ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="$(read_env KEYCLOAK_ADMIN_PASSWORD)";   ADMIN_PASS="${ADMIN_PASS:-admin}"
GOOGLE_ID="$(read_env KEYCLOAK_GOOGLE_CLIENT_ID)"
GOOGLE_SECRET="$(read_env KEYCLOAK_GOOGLE_CLIENT_SECRET)"
FACEBOOK_ID="$(read_env KEYCLOAK_FACEBOOK_CLIENT_ID)"
FACEBOOK_SECRET="$(read_env KEYCLOAK_FACEBOOK_CLIENT_SECRET)"
KC_PORT="$(read_env KEYCLOAK_HOST_PORT)";           KC_PORT="${KC_PORT:-8180}"

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
  echo "Container '$CONTAINER' is not running (start it with: docker compose up -d keycloak)" >&2
  exit 1
fi

# Everything below the variable header runs inside the Keycloak container (bash + kcadm.sh).
{
  printf 'ADMIN_USER=%q\nADMIN_PASS=%q\nREALM=%q\nKC_PORT=%q\n' "$ADMIN_USER" "$ADMIN_PASS" "$REALM" "$KC_PORT"
  printf 'GOOGLE_ID=%q\nGOOGLE_SECRET=%q\nFACEBOOK_ID=%q\nFACEBOOK_SECRET=%q\n' \
    "$GOOGLE_ID" "$GOOGLE_SECRET" "$FACEBOOK_ID" "$FACEBOOK_SECRET"
  cat <<'INNER'
set -euo pipefail
KC=/opt/keycloak/bin/kcadm.sh
export KC_OPTS="-Xms32m -Xmx128m"
CFG=$(mktemp)
trap 'rm -f "$CFG"' EXIT
# password through the environment (KC_CLI_PASSWORD), never as a --password argument
KC_CLI_PASSWORD="$ADMIN_PASS" $KC config credentials --config "$CFG" --server http://localhost:8080 \
  --realm master --user "$ADMIN_USER" >/dev/null

json_escape() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; printf '%s' "$s"; }

# apply <alias> <displayName> <clientId> <secret> <trustEmail> <defaultScope> <guiOrder>
apply() {
  local alias="$1" name="$2" id="$3" secret="$4" trust="$5" scope="$6" order="$7"
  if [ -z "$id" ] || [ -z "$secret" ]; then
    echo "[$alias] skipped: client ID or secret not set"
    return 0
  fi
  local body
  body=$(cat <<JSON
{
  "alias": "$alias",
  "displayName": "$name",
  "providerId": "$alias",
  "enabled": true,
  "trustEmail": $trust,
  "storeToken": false,
  "addReadTokenRoleOnCreate": false,
  "linkOnly": false,
  "hideOnLogin": false,
  "firstBrokerLoginFlowAlias": "first broker login",
  "config": {
    "clientId": "$(json_escape "$id")",
    "clientSecret": "$(json_escape "$secret")",
    "defaultScope": "$scope",
    "syncMode": "IMPORT",
    "guiOrder": "$order"
  }
}
JSON
)
  if $KC get "identity-provider/instances/$alias" --config "$CFG" -r "$REALM" >/dev/null 2>&1; then
    printf '%s' "$body" | $KC update "identity-provider/instances/$alias" --config "$CFG" -r "$REALM" -f - >/dev/null
    echo "[$alias] updated and enabled"
  else
    printf '%s' "$body" | $KC create identity-provider/instances --config "$CFG" -r "$REALM" -f - -i >/dev/null
    echo "[$alias] created and enabled"
  fi
}

apply google   Google   "$GOOGLE_ID"   "$GOOGLE_SECRET"   true  "openid profile email" 1
apply facebook Facebook "$FACEBOOK_ID" "$FACEBOOK_SECRET" false "email,public_profile" 2
echo "Redirect URIs to register at the providers:"
echo "  Google:   http://localhost:$KC_PORT/realms/$REALM/broker/google/endpoint"
echo "  Facebook: http://localhost:$KC_PORT/realms/$REALM/broker/facebook/endpoint"
INNER
} | MSYS_NO_PATHCONV=1 docker exec -i "$CONTAINER" bash -s
