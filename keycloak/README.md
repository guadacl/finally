# Keycloak — Tradex login & registration

Keycloak 26 provides the login, self-registration and password-reset pages. The app (Spring Boot) is an
OIDC client of the realm `finally` and keeps the login in its own session cookie.

| What | Where |
|---|---|
| Login / Register | open the app (http://localhost:8000) → you are sent to Keycloak |
| Admin console | http://localhost:8180/admin — `admin` / `admin` (`KEYCLOAK_ADMIN_PASSWORD` in `.env`) |
| Demo user | `demo` / `demo1234` |
| Realm definition | `realm/finally-realm.json` (imported on first start only) |
| Social login | "Sign in with Google / Facebook", see [Social login](#social-login-google--facebook) |

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

## Custom login page (theme `finally`)

The login, registration, password-reset and social first-login pages use the **Tradex login theme** in
`themes/finally/login/`: Keycloak's classic login pages restyled in the trading-terminal look (dark grid background,
yellow "T" logo, purple submit button, white Google / blue Facebook buttons under "or continue with").

- Only CSS (`resources/css/finally.css`) and texts (`messages/messages_en.properties`) are customised; the page
  templates are Keycloak's own, so every login feature (errors, remember me, reset password, OTP, account linking)
  keeps working after Keycloak upgrades.
- The Google / Facebook buttons appear automatically for each **enabled** identity provider (see below).
- Wiring: `compose.keycloak.yml` mounts `./themes/finally` at `/opt/keycloak/themes/finally`, and the realm sets
  `"loginTheme": "finally"`. `start-dev` disables theme caching: edit the CSS and reload the page.
- Existing realm (imported before the theme existed): Admin console → Realm settings → Themes → Login theme →
  `finally`, or `kcadm.sh update realms/finally -s loginTheme=finally`.
- Revert to Keycloak's default page: set the login theme back to empty (`-s loginTheme=`).

## Social login (Google / Facebook)

The realm has two identity providers, `google` and `facebook` (Keycloak Identity Brokering). Each one is
**enabled only when its client ID and secret are set** in `.env`; otherwise its button is hidden. Username/password
login and registration always keep working.

| Provider | Redirect URI to register at the provider (exact) |
|---|---|
| Google | `http://localhost:8180/realms/finally/broker/google/endpoint` |
| Facebook | `http://localhost:8180/realms/finally/broker/facebook/endpoint` |

(`8180` = `KEYCLOAK_HOST_PORT`; the URI is `KC_HOSTNAME` + `/realms/finally/broker/<alias>/endpoint`.)

### 1. Google (Google Cloud Console)

1. https://console.cloud.google.com → select or create a project.
2. **Google Auth Platform → Branding** (OAuth consent screen): app name `Tradex`, your support email.
   **Audience**: User type *External*, publishing status *Testing*, and add your own Google account under
   **Test users** (in Testing mode only test users can sign in). **Data access**: the scopes `openid`, `.../auth/userinfo.email`,
   `.../auth/userinfo.profile` (non-sensitive, no verification needed).
3. **Clients → Create client** → Application type *Web application*, name `Tradex Keycloak`.
   **Authorized redirect URIs**: `http://localhost:8180/realms/finally/broker/google/endpoint`
   (Google accepts plain `http` for `localhost`). No JavaScript origins needed.
4. Copy the **Client ID** (`...apps.googleusercontent.com`) and **Client secret** (`GOCSPX-...`) into `.env`:
   `KEYCLOAK_GOOGLE_CLIENT_ID=...` and `KEYCLOAK_GOOGLE_CLIENT_SECRET=...`

### 2. Facebook (Meta for Developers)

1. https://developers.facebook.com/apps → **Create app** → use case *Authenticate and request data from users with
   Facebook Login* (app type *Consumer* if asked).
2. Use case **Facebook Login → Customize → Permissions**: add **`email`** (`public_profile` is there by default).
   Both have standard access, which is enough in Development mode.
3. **Facebook Login → Settings**: *Client OAuth login* and *Web OAuth login* on, and under **Valid OAuth Redirect URIs**
   enter `http://localhost:8180/realms/finally/broker/facebook/endpoint`.
   Facebook enforces HTTPS for redirect URIs, **except `http://localhost`, which is allowed while the app is in
   Development mode** (it may say localhost redirects are allowed automatically; adding it does no harm).
4. Keep the app in **Development mode**: only people with a role on the app (you as admin; add others under
   **App roles → Roles** as Testers) can log in. Going *Live* needs a privacy policy URL, business verification and an
   HTTPS public Keycloak URL, which is beyond local dev.
5. **App settings → Basic**: copy **App ID** and **App secret** into `.env`:
   `KEYCLOAK_FACEBOOK_CLIENT_ID=...` and `KEYCLOAK_FACEBOOK_CLIENT_SECRET=...`

### 3. Apply to Keycloak

- **Existing Keycloak** (the realm was imported before, volume `finally-keycloak` already has data): run the
  idempotent script, which creates or updates both providers from `.env` and skips a provider whose ID or secret is
  empty. No restart is needed; reload the login page.

  ```bash
  ./keycloak/scripts/apply-social-idps.sh                                        # Git Bash / macOS / Linux
  powershell -ExecutionPolicy Bypass -File keycloak\scripts\apply-social-idps.ps1  # Windows (uses Git Bash)
  ```

  Options: `KEYCLOAK_CONTAINER` (default `finally-keycloak`), `ENV_FILE` (default `<repo>/.env`); PowerShell:
  `-Container`, `-EnvFile`. It logs in with `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` and passes the secrets on stdin.
  To turn a provider off again: admin console → realm `finally` → Identity providers → toggle *Enabled* (the script
  never disables or deletes anything).
- **Fresh Keycloak** (empty volume): the realm import reads the same variables (`compose.keycloak.yml` passes them
  in), so nothing else to do. The next `docker compose up -d` recreates the Keycloak container with the new
  variables; data in the volume is kept.

### Broker settings and account linking

| Setting | Google | Facebook |
|---|---|---|
| Scopes | `openid profile email` | `email,public_profile` |
| Trust email | yes (Google only returns verified addresses) | no |
| Sync mode | `IMPORT`: email, first and last name are copied on the first login; afterwards the Keycloak profile is the source of truth (Facebook may not always return an email, so updating on every login could wipe it) | same |
| First login flow | `first broker login` (Keycloak default) | same |

What happens on the first "Sign in with Google/Facebook":

1. **Review profile** (only if something is missing): e.g. a Facebook account without an email, or one the user
   declined to share, must enter an email here. The email is required, so every user has one for the alert emails
   (`users_profile.email` comes from the OIDC `email` claim as for password users).
2. **No Tradex account with that email** → a new Keycloak user is created (username taken from the provider profile, new `sub`, so a
   fresh $10,000 portfolio).
3. **An account with that email already exists** (`duplicateEmailsAllowed` is off) → Keycloak asks *"Link Google to
   your existing account?"* and then the user **re-enters that account's password** (email verification would be used
   instead if the realm had SMTP configured; it doesn't). After that the provider is linked: both password and
   Google/Facebook log into the **same user and the same portfolio**, no duplicate.
   We deliberately do not auto-link by email without re-authentication: an unverified Facebook email could otherwise
   take over someone else's account.

A user created via Google/Facebook has no password; they can set one via *Forgot password* (needs realm SMTP) or the
account console (`http://localhost:8180/realms/finally/account`). Linked providers can be removed there too.

## Change before going beyond local dev

`KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`, remove the `demo` user, use HTTPS (`sslRequired`), and run
Keycloak with `start` (production mode) and a real database instead of `start-dev`.
