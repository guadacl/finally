# Keycloak — FinAlly login & registration

Keycloak 26 provides the login, self-registration and password-reset pages. The app (Spring Boot) is an
OIDC client of the realm `finally` and keeps the login in its own session cookie.

| What | Where |
|---|---|
| Login / Register | open the app (http://localhost:8000) → you are sent to Keycloak |
| Admin console | http://localhost:8180/admin — `admin` / `admin` (`KEYCLOAK_ADMIN_PASSWORD` in `.env`) |
| Demo user | `demo` / `demo1234` |
| Realm definition | `realm/finally-realm.json` (imported on first start only) |

## Run

```bash
docker compose up -d                 # whole stack, Keycloak included
docker compose up -d keycloak        # Keycloak only
```

Without Docker, the same realm works with the Keycloak zip distribution (Java 21):

```bash
export KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=admin KC_HOSTNAME=http://localhost:8180
cp realm/finally-realm.json keycloak-26.8.1/data/import/
keycloak-26.8.1/bin/kc.sh start-dev --http-port 8180 --import-realm
```

## How it fits together

- Browser → `http://localhost:8000/markets` without a session → Spring redirects to Keycloak's login page.
- After login (or registration) Keycloak redirects to `/login/oauth2/code/keycloak`; Spring exchanges the code
  (server to server, `KEYCLOAK_INTERNAL_URL`) and creates the session. A new user gets $10,000 and the default watchlist.
- `KC_HOSTNAME` is fixed to the browser URL so tokens always carry the issuer `http://localhost:8180/realms/finally`,
  even when the app container talks to `http://keycloak:8080`.
- The realm file uses placeholders: `${KEYCLOAK_CLIENT_SECRET:finally-dev-secret}` and `${APP_PORT:8000}` (redirect URIs).
  Changing them later needs a fresh import (`docker compose down` + `docker volume rm finally-keycloak`) or the admin console.

## Change before going beyond local dev

`KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`, remove the `demo` user, use HTTPS (`sslRequired`), and run
Keycloak with `start` (production mode) and a real database instead of `start-dev`.
