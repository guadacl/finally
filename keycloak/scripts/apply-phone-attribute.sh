#!/usr/bin/env bash
# Adds the optional "phone_number" user-profile attribute and the "phone number" protocol mapper (claim
# phone_number in ID/access tokens and userinfo of client finally-app) to an EXISTING Keycloak realm.
#
# keycloak/realm/finally-realm.json only applies on a fresh Keycloak (first import); this script brings a running
# realm to the same state. Idempotent and additive: it never removes or rewrites other attributes, mappers,
# identity providers or settings; running it twice changes nothing.
#
# Usage (from the repo root, stack running; Git Bash on Windows works too):
#   keycloak/scripts/apply-phone-attribute.sh
# Optional environment: KEYCLOAK_CONTAINER (finally-keycloak), KEYCLOAK_REALM (finally),
#   KEYCLOAK_CLIENT_ID (finally-app), KEYCLOAK_ADMIN (admin), KEYCLOAK_ADMIN_PASSWORD (admin)
# Needs: docker; node on PATH (or it runs node from the node:22-alpine image) to edit JSON.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep /opt/... container paths as they are

CONTAINER="${KEYCLOAK_CONTAINER:-finally-keycloak}"
REALM="${KEYCLOAK_REALM:-finally}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-finally-app}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC="$HERE/phone-attribute.json"
KC_CONFIG=/tmp/kcadm-finally-phone.config

kcadm() { docker exec -i "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@" --config "$KC_CONFIG"; }

nodejs() {
  if command -v node >/dev/null 2>&1; then node "$@"; else docker run --rm -i node:22-alpine node "$@"; fi
}

[ -f "$SPEC" ] || { echo "missing $SPEC" >&2; exit 1; }
SPEC_JSON="$(cat "$SPEC")"

echo "Logging in to Keycloak in container $CONTAINER ..."
kcadm config credentials --server http://localhost:8080 --realm master --user "$ADMIN_USER" --password "$ADMIN_PASSWORD" >/dev/null

# 1) user-profile attribute phone_number (view + edit for the user, optional, E.164 pattern)
PROFILE="$(kcadm get "realms/$REALM/users/profile")"
UPDATED="$(printf '%s' "$PROFILE" | SPEC_JSON="$SPEC_JSON" nodejs -e '
  let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
    const profile = JSON.parse(s);
    const { attribute } = JSON.parse(process.env.SPEC_JSON);
    profile.attributes = profile.attributes || [];
    if (profile.attributes.some(a => a.name === attribute.name)) { process.stdout.write(""); return; }
    profile.attributes.push(attribute);
    process.stdout.write(JSON.stringify(profile));
  });')"
if [ -z "$UPDATED" ]; then
  echo "User-profile attribute phone_number: already present (unchanged)"
else
  printf '%s' "$UPDATED" | kcadm update "realms/$REALM/users/profile" -f - >/dev/null
  echo "User-profile attribute phone_number: added"
fi

# 2) protocol mapper on client finally-app: user attribute phone_number -> claim phone_number
CID="$(kcadm get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r' | head -n1)"
[ -n "$CID" ] || { echo "client $CLIENT_ID not found in realm $REALM" >&2; exit 1; }
MAPPERS="$(kcadm get "clients/$CID/protocol-mappers/models" -r "$REALM" --fields name --format csv --noquotes | tr -d '\r')"
if printf '%s\n' "$MAPPERS" | grep -qx "phone number"; then
  echo "Protocol mapper 'phone number' on $CLIENT_ID: already present (unchanged)"
else
  printf '%s' "$SPEC_JSON" | nodejs -e '
    let s = ""; process.stdin.on("data", d => s += d).on("end", () => process.stdout.write(JSON.stringify(JSON.parse(s).mapper)));' \
    | kcadm create "clients/$CID/protocol-mappers/models" -r "$REALM" -f - >/dev/null
  echo "Protocol mapper 'phone number' on $CLIENT_ID: added"
fi

docker exec "$CONTAINER" rm -f "$KC_CONFIG" >/dev/null 2>&1 || true
echo "Done. Users set their number in the Account Console (http://localhost:${KEYCLOAK_HOST_PORT:-8180}/realms/$REALM/account)"
echo "and log in to Tradex again so the new phone_number claim reaches the app."
