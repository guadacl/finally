# FinAlly — Build Contracts

This file is the **binding contract** between the four build agents (DB, Camunda, Backend, Frontend).
Where `PLAN.md` still mentions Python, FastAPI, uv, LiteLLM, Next.js, SQLite or pytest, **this file wins**.

## 1. Stack (pinned decisions)

| Area | Choice |
|---|---|
| Frontend | Angular (latest stable major, pinned in `package.json`), standalone components, signals, Tailwind CSS, `lightweight-charts` for charts |
| Backend | Java 21, Spring Boot 3.5.x, Maven multi-module, `spring-boot-starter-web` (servlet) |
| API | **Spec-first OpenAPI**: `backend/openapi/openapi.yaml` is the source of truth. Backend generates Spring interfaces from it; frontend writes a typed client that matches it |
| Database | Postgres 16, Flyway migrations (schema owner), **JPA (Hibernate) + Spring Data JPA** repositories (`com.finallyapp.persistence`, `ddl-auto=validate`); no `JdbcClient` / raw JDBC in application code |
| Workflow | Camunda 8 (Camunda 8 Run image), `io.camunda:camunda-spring-boot-starter` 8.8.x |
| LLM | OpenRouter REST API called from Java (no LiteLLM) |
| Runtime | Docker Compose: `app`, `postgres`, `camunda` |
| Java base package | `com.finallyapp` (`finally` is a Java keyword — never use it as a package name) |

## 2. Ownership (who may write where)

Agents must **only create/edit files in the paths they own**. If you need a change in a path you don't own, write it under "Requests to other agents" in your final report instead.

| Agent | Owns |
|---|---|
| **DB** | `backend/db/**` (Maven module `finally-db`), top-level `db/**` |
| **Camunda** | `backend/workflow/**` (Maven module `finally-workflow`), top-level `camunda/**` |
| **Backend** | `backend/app/**` (Maven module `finally-app`), `scripts/**`, `test/**` |
| **Frontend** | `frontend/**` |
| **Orchestrator (main session)** | `planning/**`, `backend/pom.xml`, `backend/contracts/**`, `backend/openapi/**`, `docker-compose.yml`, `Dockerfile`, `.env.example`, `.gitignore`, `CLAUDE.md` |

Compose: the root `docker-compose.yml` (orchestrator) `include:`s `db/compose.postgres.yml` (DB agent, service `postgres`, must have a healthcheck, named volume `finally-pgdata`) and `camunda/compose.camunda.yml` (Camunda agent, service `camunda`, must have a healthcheck, named volume `finally-camunda`, host port `8081` → web apps). The `app` service, `Dockerfile` and `.env.example` are already written. The app jar must be built as `backend/app/target/finally-app.jar` (`<finalName>finally-app</finalName>`), and Angular must output to `frontend/dist/frontend/browser/`.

Build rules for the shared Maven reactor (`backend/`):
- Build your own module with `mvn -q -f backend/pom.xml -pl <your-module> -am install -DskipTests` then run your tests with `mvn -f backend/pom.xml -pl <your-module> test`.
- Never run `mvn clean` on the whole reactor or on modules you don't own.
- Don't edit `backend/pom.xml` or `backend/contracts`. Your own module's `pom.xml` is yours (add dependencies there).

## 3. Maven modules

```
backend/
├── pom.xml            parent (packaging pom) — orchestrator
├── openapi/openapi.yaml   API contract — orchestrator
├── contracts/         finally-contracts: shared interfaces + records — orchestrator
├── db/                finally-db: Flyway SQL under src/main/resources/db/migration — DB agent
├── workflow/          finally-workflow: Camunda client, BPMN deploy, job workers — Camunda agent
└── app/               finally-app: Spring Boot application (the runnable jar) — Backend agent
```

Dependencies: `app → contracts, db, workflow`; `workflow → contracts`; `db` → none.
The Spring Boot main class is `com.finallyapp.FinallyApplication` in `app`; component scan covers `com.finallyapp.**`, so beans in `workflow` are picked up automatically.

## 4. Database schema (DB agent implements, Backend agent consumes)

Flyway location `classpath:db/migration` (files live in `backend/db/src/main/resources/db/migration/`). Postgres types are fixed as below; the Backend agent writes SQL against exactly these names.

```sql
users_profile(
  id            TEXT PRIMARY KEY DEFAULT 'default',
  cash_balance  NUMERIC(18,4) NOT NULL DEFAULT 10000.0000,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  email         TEXT NULL)          -- V5: Keycloak account email, synced by the app on the user's requests

watchlist(
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id   TEXT NOT NULL DEFAULT 'default',
  ticker    TEXT NOT NULL,
  added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, ticker))

positions(
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT NOT NULL DEFAULT 'default',
  ticker      TEXT NOT NULL,
  quantity    NUMERIC(18,6) NOT NULL,
  avg_cost    NUMERIC(18,4) NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, ticker))

trades(
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      TEXT NOT NULL DEFAULT 'default',
  ticker       TEXT NOT NULL,
  side         TEXT NOT NULL CHECK (side IN ('buy','sell')),
  quantity     NUMERIC(18,6) NOT NULL CHECK (quantity > 0),
  price        NUMERIC(18,4) NOT NULL,
  executed_at  TIMESTAMPTZ NOT NULL DEFAULT now())

portfolio_snapshots(
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      TEXT NOT NULL DEFAULT 'default',
  total_value  NUMERIC(18,4) NOT NULL,
  recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now())

chat_messages(
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT NOT NULL DEFAULT 'default',
  role        TEXT NOT NULL CHECK (role IN ('user','assistant')),
  content     TEXT NOT NULL,
  actions     JSONB NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now())

kanban_cards(
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               TEXT NOT NULL DEFAULT 'default',
  ticker                TEXT NOT NULL,
  column_name           TEXT NOT NULL CHECK (column_name IN ('SUPPORT','RESISTANCE')),
  target_price          NUMERIC(18,4) NOT NULL CHECK (target_price > 0),
  sort_order            INTEGER NOT NULL DEFAULT 0,
  armed                 BOOLEAN NOT NULL DEFAULT TRUE,   -- true = ACTIVE, false = NOTIFIED (alert sent once)
  process_instance_key  BIGINT NULL,                     -- Camunda process instance
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now())
```

```sql
price_alerts(                                     -- V3: Alerts page
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      TEXT NOT NULL DEFAULT 'default',
  ticker       TEXT NOT NULL,                     -- must be on the user's watchlist when created
  upper_limit  NUMERIC(18,4) NULL CHECK (> 0),
  lower_limit  NUMERIC(18,4) NULL CHECK (> 0),    -- at least one limit; lower < upper
  comment      TEXT NULL,                         -- max 500 chars
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now())
```

```
API: GET /api/alerts, POST /api/alerts {ticker, upperLimit?, lowerLimit?, comment?}, DELETE /api/alerts/{id}
```

Alerts page behaviour:
- Each row also shows `rsi`: RSI(14), Wilder smoothing, on daily closes from Massive end-of-day aggregates (`DailyCloses`, ~150 calendar days, one ticker per 20 s, cached per day), with the live price as today's close during the session. `null` without `MASSIVE_API_KEY` or until the history has loaded. UI: ≥ 70 overbought, ≤ 30 oversold.
- Adding an alert also creates kanban cards: `lowerLimit` → a SUPPORT card, `upperLimit` → a RESISTANCE card (frontend, via `POST /api/kanban`; deleting the alert leaves the cards). The alert form's comment is copied onto those cards.
- The alerts list does **not** show a comment column; comments live on the kanban cards (`kanban_cards.comment`, V4, ≤ 500 chars). Each card's target price and comment are editable inline (`PATCH /api/kanban/{id}` with `targetPrice` and/or `comment`; `comment: ""` clears it; a comment edit does not re-arm the card or notify Camunda). `price_alerts.comment` is still stored but no longer displayed.

Indexes: `trades(user_id, executed_at)`, `portfolio_snapshots(user_id, recorded_at)`, `chat_messages(user_id, created_at)`, `kanban_cards(user_id, column_name, sort_order)`.

Seed (a separate migration, idempotent with `ON CONFLICT DO NOTHING`): profile `default` with 10000 cash; watchlist AAPL, GOOGL, MSFT, AMZN, TSLA, NVDA, META, JPM, V, NFLX. No seeded kanban cards.

Persistence mapping (`backend/app/src/main/java/com/finallyapp/persistence`): one JPA entity per table — `UserProfileEntity` (users_profile), `WatchlistEntryEntity` (watchlist), `PositionEntity`, `TradeEntity`, `PortfolioSnapshotEntity`, `ChatMessageEntity` (`actions` jsonb as JSON text), `KanbanCardEntity`, `PriceAlertEntity` — and one Spring Data JPA repository per entity (`…Repository extends JpaRepository`). Rules: UUID ids generated by Hibernate, timestamps set in `@PrePersist` (UTC; `hibernate.timezone.default_storage=NORMALIZE_UTC`); every user-owned query filters by `userId` (= `CurrentUser.id()`); the trade transaction locks the profile with `@Lock(PESSIMISTIC_WRITE)`; `users_profile` and `kanban_cards` use `@DynamicUpdate` and targeted `@Modifying` JPQL updates (email/phone, `armed`, process instance key) so concurrent writers never overwrite each other's columns; the only native SQL is the race-free provisioning `INSERT … ON CONFLICT DO NOTHING` (`UserProfileRepository.insertIfMissing`, `WatchlistRepository.insertIfMissing`) and the health `SELECT 1`. `KanbanRepository` / `PortfolioRepository` are thin data-access classes over these repositories that hand out immutable row records. Spring Session keeps its own JDBC session tables (framework-managed).

## 5. Kanban & alert rules

- **Display names**: column `SUPPORT` is shown to users as **Support**, `RESISTANCE` as **Resistance** (UI labels and notification texts). The codes in the API, database and Camunda process are unchanged. The cards are shown as a kanban board on the **Portfolio** page and, on the **Alerts** page, as two full-width tables stacked below the price alerts table: **Support** on top, **Resistance** below (component `TargetTableComponent`). The tables have the price alerts columns (Ticker, Price, Day low, Day high, Vol (last session), RSI (14)) plus Target (inline-editable), Dist, Status (ACTIVE / NOTIFIED), Comment (inline-editable) and Added; a ⇅ button moves a card to the other table. `KanbanCard` therefore also carries `dayHigh`, `dayLow`, `lastSessionVolume`, `lastSessionDate` and `rsi` (same sources as `PriceAlert`, via `MarketStats`).
- **Board display (frontend only)**: DIST = `(targetPrice − livePrice) / livePrice × 100` (`cardDistance`). Each column is ordered by |DIST| ascending (`sortByDistance`; cards without a price last, ties keep `sortOrder`) and re-sorts as prices stream; within-column drag reordering is disabled, drag between columns still PATCHes `column`. A RESISTANCE card with `targetPrice < livePrice` gets `data-broken="true"` and the accent-yellow highlight. Saving one inline field (target or comment) never resets the other field being typed.
- A card is `(ticker, column ∈ {SUPPORT, RESISTANCE}, targetPrice)`. One ticker may have several cards. The ticker does not need to be on the watchlist; creating a card adds the ticker to the price source so it gets live prices (same mechanism as watchlist add).
- Alert trigger: SUPPORT fires when `price <= target`; RESISTANCE fires when `price >= target`.
- **Notify once**: fire only when `armed = true`; then set `armed = false` = status **NOTIFIED** (API `KanbanCard.status`: `ACTIVE` | `NOTIFIED`, derived from `armed`). A NOTIFIED card does **not** re-arm when the price moves back and crosses again; it re-arms (`armed = true`, status `ACTIVE`) only when the user edits the target price or moves the card to the other column. A comment edit does not re-arm.
- **Camunda mirror**: process variable `status` (`"ACTIVE"` at instance start and in every `card-moved` message). The target-hit event subprocess first evaluates `Gateway_AlreadyNotified` (`=is defined(status) and status = "NOTIFIED" and is defined(notifiedColumn) and notifiedColumn = column and notifiedTargetPrice = targetPrice` → `EndEvent_DuplicateIgnored`, no notification); otherwise the script task `Task_SetStatusNotified` sets `status = "NOTIFIED"`, `notifiedColumn` and `notifiedTargetPrice` **before** the toast, email and SMS run. The card-moved subprocess runs `Task_SetStatusActive` (`status = "ACTIVE"`) first. So a duplicate `target-price-hit` message never notifies twice.
- **The app's `armed` is the source of truth**: every `target-price-hit` message carries the card's current `column` and `targetPrice`. If a `card-moved` correlation was lost (Camunda down during the edit, fallback toast only), Camunda still holds the old NOTIFIED, but the hit for the new target differs from `notifiedColumn` / `notifiedTargetPrice` and notifies; it also refreshes the process's column / target for the toast and email.
- The **Backend** evaluates alerts on each price update in memory and records the hit with a DB compare-and-set (`KanbanRepository.markNotified`: `armed` true → false only if the card still has the hit column and target). Only when that wins (or the DB is unreachable) does it call `KanbanWorkflow.targetPriceHit(...)`; an edit, move or delete that committed first wins and the stale hit is dropped. Edits run under a row lock (`SELECT ... FOR UPDATE`) and write `armed = true` only when the column or target changes, so a comment edit or reorder never overwrites a NOTIFIED set meanwhile. Since the app sends at most one hit per arming, cards whose Camunda instance runs a process version from before notify-once are also notified only once.
- The DB is the source of truth for a card's column; Camunda mirrors it in the process variable `column`.
- **Emails** (toasts unchanged): the card owner is emailed at `users_profile.email` for **`CARD_CREATED`, `CARD_DELETED` and `TARGET_HIT`** — never `CARD_MOVED` (`NotificationEmailer.EMAILED`).
  - *Camunda path*: every emailed event runs its toast task (`finally-notify`) and then an **`email-notify`** service task with the same `notificationType` header: after "Notify: card created in Support/Resistance" (both gateway branches), after "Notify: card deleted" (main flow, after the `card-deleted` catch event, before the end event) and after "Notify: target price hit" (event subprocess). `EmailNotifyJobWorker` (workflow module) builds the `Notification` from the job variables and calls the contract `NotificationEmailer.send(...)`; it **always completes the job** (no incidents, no retries on mail failure).
  - *Deletion*: `KanbanService.delete` reads the card first and calls `KanbanWorkflow.cardDeleted(card, processInstanceKey)`; the `card-deleted` message carries `ticker`, `column`, `targetPrice`, `userId`. Instances of an older process version (created before deletion emails existed) end without emailing; after a successful correlation the workflow checks the instance's process definition (cached) and, for such versions, sends the deletion toast + email directly. If the message cannot be correlated, the fallback sends them and the instance is cancelled by its key.
  - *Fallback path* (Camunda down / correlation failed): `DirectKanbanWorkflow` publishes the toast and calls `NotificationEmailer` for created / deleted / target hit. So each event is emailed exactly once on either path; Camunda job re-deliveries keep the notification id (derived from the job key) and are de-duplicated.
  - `NotificationMailer` (app, implements `NotificationEmailer`): asynchronous (one background thread, bounded queue), never throws; skipped when `MAIL_ENABLED=false`, `EMAIL_MCP_URL` is empty, there is no owner, or the owner has no email. It calls `McpEmailGateway`, an MCP client (Java MCP SDK 2.1.0, `mcp-core` + `mcp-json-jackson2`, Streamable HTTP transport) for the tool **`send_email {to, subject, text, html?}`** of the email MCP server (`mcp/email-mcp-server`, Node + `@modelcontextprotocol/sdk` + nodemailer, provider-agnostic SMTP). Each call is bounded by `EMAIL_MCP_TIMEOUT` (10 s).
  - Subjects: `FinAlly: new Support card AAPL @ 185.00`, `FinAlly: Support card AAPL @ 185.00 deleted`, `FinAlly alert: AAPL hit Support 185.00`. Plain-text body with ticker, Support/Resistance (and its rule), target, current price (target hit), time (UTC) and a link to `${APP_PUBLIC_URL}/alerts`.
  - Address: copied from the OIDC `email` claim into `users_profile.email` by `UserEmailSyncFilter` on the user's requests (written only when it changes); it is not fetched from the Keycloak admin API.

## 6. Camunda contract (Camunda agent implements)

- BPMN process id: `kanban-card`. BPMN file(s) live in `camunda/bpmn/` and are deployed by the `workflow` module at startup (the module's `pom.xml` adds `../../camunda/bpmn` as a resource directory).
- Correlation key for all messages: `cardId` (UUID string).
- Process variables: `cardId`, `ticker`, `column`, `targetPrice` (number), `price` (number, when known).
- Messages: `card-moved` (vars: `column`, `targetPrice`), `target-price-hit` (vars: `price`, `column`, `targetPrice`), `card-deleted` (vars: `ticker`, `column`, `targetPrice`, `userId`; the main flow then sends the CARD_DELETED toast and email and ends the instance).
- Must contain an **exclusive gateway on `column`** after the start event, routing to notification tasks for "card created in SUPPORT" / "card created in RESISTANCE".
- All notifications are sent by a **service task with job type `finally-notify`**; a Spring job worker in `workflow` handles it and calls `NotificationPublisher.publish(...)`. The worker must be idempotent-safe (Camunda may retry).
- Card created (both gateway branches), card deleted and target price hit each run `finally-notify` (toast) and then **`email-notify`** (same `notificationType` header), handled by `EmailNotifyJobWorker` → `NotificationEmailer` (§5). Card moved has no `email-notify` task. The deletion email task has the element id `Task_EmailCardDeleted`.
- `KanbanWorkflow` implementation (`com.finallyapp.workflow`): every method must catch Camunda errors, log them, and **fall back to publishing the notification directly** via `NotificationPublisher` so alerts still reach the browser if Camunda is down. Methods must not block the caller for more than ~2 s.
- When property `finally.workflow.enabled=false`, a no-op-to-Camunda implementation (which still publishes notifications directly) is used instead. Backend tests run with this set to `false`.

## 7. Java contracts (`backend/contracts`, package `com.finallyapp.contracts`)

Already written by the orchestrator — read them, don't edit:
- `KanbanColumn` — enum `SUPPORT`, `RESISTANCE`
- `KanbanCardRef` — record `(UUID id, String ticker, KanbanColumn column, BigDecimal targetPrice)`
- `KanbanWorkflow` — implemented by `workflow`, called by `app`
- `Notification`, `NotificationType` — the notification payload
- `NotificationPublisher` — implemented by `app` (pushes to SSE), called by `workflow`
- `NotificationEmailer` — implemented by `app` (`NotificationMailer`: email via the email MCP server), called by `workflow` (`email-notify` worker and the direct fallback); non-blocking, never throws; emails `CARD_CREATED`, `CARD_DELETED`, `TARGET_HIT`
- `KanbanWorkflow.cardDeleted(KanbanCardRef card, Long processInstanceKey)` — called with the card read before its row is deleted

## 8. SSE events

`GET /api/stream/prices` — `text/event-stream`, every ~500 ms one event per ticker, event name `price`, data:
```json
{"ticker":"AAPL","price":190.12,"previousPrice":190.05,"timestamp":"2026-11-02T10:00:00.500Z","direction":"up"}
```
`direction` ∈ `up | down | flat`. Prices are JSON numbers.

`GET /api/stream/notifications` — event name `notification`, data = `Notification` JSON:
```json
{"id":"uuid","type":"TARGET_HIT","cardId":"uuid","ticker":"AAPL","column":"SUPPORT","targetPrice":185.0,"price":184.97,"message":"AAPL hit support 185.00 (now 184.97)","createdAt":"2026-11-02T10:00:00Z"}
```
`type` ∈ `CARD_CREATED | CARD_MOVED | TARGET_HIT | CARD_DELETED` (CARD_DELETED message e.g. `Support card deleted: AAPL @ 185.00`). Both streams send a comment heartbeat (`: ping`) every 15 s.

## 9. LLM (Backend agent)

- `POST https://openrouter.ai/api/v1/chat/completions`, header `Authorization: Bearer ${OPENROUTER_API_KEY}`.
- Body: `"model": "openai/gpt-oss-120b"`, `"provider": {"order": ["cerebras"]}`, `"reasoning": {"effort": "low"}`, `"response_format": {"type": "json_schema", "json_schema": {"name": "finally_response", "strict": true, "schema": {...}}}`.
- Response schema: `{message: string, trades: [{ticker, side: buy|sell, quantity: number}], watchlist_changes: [{ticker, action: add|remove}]}` (arrays required, may be empty, for strict mode).
- `LLM_MOCK=true` → deterministic responses without network (e.g. message containing "buy" → buys 1 AAPL).

## 10. Environment & ports

| Var | Used by | Default |
|---|---|---|
| `OPENROUTER_API_KEY` | app | — |
| `FINNHUB_API_KEY` | app | empty; when set, Finnhub real-time quotes (takes priority over Massive) |
| `FINNHUB_BASE_URL` / `FINNHUB_CALL_SPACING` | app | `https://finnhub.io/api/v1` / `1100ms` (one quote per call, round-robin) |
| `MASSIVE_API_KEY` | app | empty → simulator (when Finnhub is also empty) |
| `AUTH_ENABLED` | app | `true`; `false` = no login, everyone is user `default` (tests only) |
| `KEYCLOAK_URL` | app | `http://localhost:8180` — Keycloak as seen by the browser (also the token issuer) |
| `KEYCLOAK_INTERNAL_URL` | app | empty → `KEYCLOAK_URL`; in Docker `http://keycloak:8080` |
| `KEYCLOAK_REALM` / `KEYCLOAK_CLIENT_ID` / `KEYCLOAK_CLIENT_SECRET` | app, keycloak | `finally` / `finally-app` / `finally-dev-secret` |
| `KEYCLOAK_HOST_PORT` / `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | keycloak | `8180` / `admin` / `admin` |
| `KEYCLOAK_GOOGLE_CLIENT_ID` / `KEYCLOAK_GOOGLE_CLIENT_SECRET` / `KEYCLOAK_FACEBOOK_CLIENT_ID` / `KEYCLOAK_FACEBOOK_CLIENT_SECRET` | keycloak (realm import), `keycloak/scripts/apply-social-idps.*` | empty → that "Sign in with ..." button is hidden (IdP disabled); see `keycloak/README.md` "Social login" |
| `LLM_MOCK` | app | `false` |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | postgres, app | `finally` / `finally` / `finally` |
| `SPRING_DATASOURCE_URL` | app | `jdbc:postgresql://postgres:5432/finally` |
| `CAMUNDA_GRPC_ADDRESS` | app | `http://camunda:26500` |
| `CAMUNDA_REST_ADDRESS` | app | `http://camunda:8080` |
| `FINALLY_WORKFLOW_ENABLED` | app | `true` |
| `MAIL_ENABLED` | app | `true`; `false` = never send alert emails |
| `EMAIL_MCP_URL` / `EMAIL_MCP_TIMEOUT` | app | empty → no emails (local run); compose `http://email-mcp:3001/mcp` / `10s` |
| `APP_PUBLIC_URL` | app | `http://localhost:8000` (link in alert emails; compose: `http://localhost:${APP_PORT}`) |
| `MAIL_HOST` / `MAIL_PORT` | email-mcp | `localhost` / `1025`; compose `mailpit` / `1025` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_STARTTLS` / `MAIL_SECURE` | email-mcp | empty / empty / `false` / `true` on port 465 |
| `MAIL_FROM` | email-mcp | `FinAlly <alerts@finally.local>` |
| `MCP_PORT` / `MCP_PATH` | email-mcp | `3001` / `/mcp` |
| `MAILPIT_WEB_HOST_PORT` / `MAILPIT_SMTP_HOST_PORT` | mailpit | `8025` / `1025` |

Ports: app `8000` (public), Keycloak `8180 → 8080` (loopback only), Mailpit web UI `8025` and SMTP `1025` (loopback only, service `mailpit` in `mailpit/compose.mailpit.yml`), email MCP server `3001` (internal only, service `email-mcp` in `mcp/email-mcp-server/compose.email-mcp.yml`), Camunda web apps (Operate/Tasklist) `8081 → 8080` on host, Postgres `5432` (dev only).

For local dev outside Docker, `localhost` replaces the service names; `docker compose up postgres camunda` starts just the infrastructure.

## 11. Authentication (Keycloak)

- **Keycloak 26** runs as the compose service `keycloak` (`keycloak/compose.keycloak.yml`); realm `finally` is imported from `keycloak/realm/finally-realm.json` on first start. Self-registration, password reset and "remember me" are on. Dev user: `demo` / `demo1234`. Optional social login (Identity Brokering): IdPs `google` and `facebook`, enabled only when their client ID/secret are set; a brokered user is a normal Keycloak user (own `sub`, email imported from the provider), and a login whose email matches an existing account links to it after the user re-enters that account's password (default "first broker login" flow).
- **Login page**: realm `"loginTheme": "finally"` — theme `keycloak/themes/finally/login` (parent `keycloak`, mounted at `/opt/keycloak/themes/finally`), CSS (`resources/css/finally.css`) and texts (`messages/messages_en.properties`) only; templates are Keycloak's own, so every login feature keeps working. Social buttons are Keycloak's `#social-google` / `#social-facebook` links, rendered for each enabled IdP. `start-dev` disables theme caching. An existing realm is switched with `kcadm.sh update realms/finally -s loginTheme=finally`.
- **Backend for frontend**: Spring Security (`spring-boot-starter-oauth2-client`) runs the OIDC authorization-code flow with the confidential client `finally-app` and keeps the login in the server session cookie (`JSESSIONID`). The SPA never handles tokens or passwords; the `EventSource` streams are authenticated by the same cookie.
- **Routing**: browser page loads without a session → `302` to `/oauth2/authorization/keycloak` → Keycloak login page (with "Register"). `/api/**` without a session → `401 {"error":"UNAUTHENTICATED"}`; the SPA then navigates to the login. `/api/health` is public.
- **CSRF**: cookie `XSRF-TOKEN` (readable by JS), echoed as header `X-XSRF-TOKEN` (Angular does this automatically) or form field `_csrf`. Required on every POST/PATCH/DELETE, including `POST /logout`.
- **Logout**: `POST /logout` ends the Spring session and the Keycloak session (RP-initiated logout), then returns to the app, which shows the login page again.
- **Per-user data**: `user_id` in every table = the Keycloak subject (`sub`). `GET /api/me` returns `{id, username, email, name, authEnabled}`. A user's first login creates `users_profile` ($10,000) and the 10 default watchlist tickers. Prices are shared; the market data source tracks the tickers of all users. Kanban notifications are delivered only to their owner's notification stream (`userId` in `KanbanCardRef`, `Notification` and the Camunda process variables). The account's email (OIDC `email` claim) is stored in `users_profile.email` for the alert emails (card created / deleted / target hit, §5).
- **Backend code** reads the user with `com.finallyapp.security.CurrentUser.id()`; background jobs use `CurrentUser.runAs(userId, ...)`. With auth enabled there is no silent default user.
