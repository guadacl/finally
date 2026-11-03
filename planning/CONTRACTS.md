# FinAlly — Build Contracts

This file is the **binding contract** between the four build agents (DB, Camunda, Backend, Frontend).
Where `PLAN.md` still mentions Python, FastAPI, uv, LiteLLM, Next.js, SQLite or pytest, **this file wins**.

## 1. Stack (pinned decisions)

| Area | Choice |
|---|---|
| Frontend | Angular (latest stable major, pinned in `package.json`), standalone components, signals, Tailwind CSS, `lightweight-charts` for charts |
| Backend | Java 21, Spring Boot 3.5.x, Maven multi-module, `spring-boot-starter-web` (servlet) |
| API | **Spec-first OpenAPI**: `backend/openapi/openapi.yaml` is the source of truth. Backend generates Spring interfaces from it; frontend writes a typed client that matches it |
| Database | Postgres 16, Flyway migrations, Spring `JdbcClient` (no JPA) |
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
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now())

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
  armed                 BOOLEAN NOT NULL DEFAULT TRUE,   -- alert re-arm state
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
- Adding an alert also creates kanban cards: `lowerLimit` → a SUPPORT card, `upperLimit` → a RESISTANCE card (frontend, via `POST /api/kanban`; deleting the alert leaves the cards).

Indexes: `trades(user_id, executed_at)`, `portfolio_snapshots(user_id, recorded_at)`, `chat_messages(user_id, created_at)`, `kanban_cards(user_id, column_name, sort_order)`.

Seed (a separate migration, idempotent with `ON CONFLICT DO NOTHING`): profile `default` with 10000 cash; watchlist AAPL, GOOGL, MSFT, AMZN, TSLA, NVDA, META, JPM, V, NFLX. No seeded kanban cards.

## 5. Kanban & alert rules

- **Display names**: column `SUPPORT` is shown to users as **Support**, `RESISTANCE` as **Resistance** (UI labels and notification texts). The codes in the API, database and Camunda process are unchanged. The board is shown on both the **Portfolio** and the **Alerts** page (same cards; the Alerts page shows it below the upper/lower-limit alerts list).
- A card is `(ticker, column ∈ {SUPPORT, RESISTANCE}, targetPrice)`. One ticker may have several cards. The ticker does not need to be on the watchlist; creating a card adds the ticker to the price source so it gets live prices (same mechanism as watchlist add).
- Alert trigger: SUPPORT fires when `price <= target`; RESISTANCE fires when `price >= target`.
- Fire only when `armed = true`; then set `armed = false`. Re-arm (`armed = true`) when the condition becomes false again, when the target price is edited, or when the card moves column.
- The **Backend** evaluates alerts on each price update (in memory, persisting `armed` changes) and calls `KanbanWorkflow.targetPriceHit(...)`.
- The DB is the source of truth for a card's column; Camunda mirrors it in the process variable `column`.

## 6. Camunda contract (Camunda agent implements)

- BPMN process id: `kanban-card`. BPMN file(s) live in `camunda/bpmn/` and are deployed by the `workflow` module at startup (the module's `pom.xml` adds `../../camunda/bpmn` as a resource directory).
- Correlation key for all messages: `cardId` (UUID string).
- Process variables: `cardId`, `ticker`, `column`, `targetPrice` (number), `price` (number, when known).
- Messages: `card-moved` (vars: `column`, `targetPrice`), `target-price-hit` (vars: `price`), `card-deleted` (ends the instance).
- Must contain an **exclusive gateway on `column`** after the start event, routing to notification tasks for "card created in SUPPORT" / "card created in RESISTANCE".
- All notifications are sent by a **service task with job type `finally-notify`**; a Spring job worker in `workflow` handles it and calls `NotificationPublisher.publish(...)`. The worker must be idempotent-safe (Camunda may retry).
- `KanbanWorkflow` implementation (`com.finallyapp.workflow`): every method must catch Camunda errors, log them, and **fall back to publishing the notification directly** via `NotificationPublisher` so alerts still reach the browser if Camunda is down. Methods must not block the caller for more than ~2 s.
- When property `finally.workflow.enabled=false`, a no-op-to-Camunda implementation (which still publishes notifications directly) is used instead. Backend tests run with this set to `false`.

## 7. Java contracts (`backend/contracts`, package `com.finallyapp.contracts`)

Already written by the orchestrator — read them, don't edit:
- `KanbanColumn` — enum `SUPPORT`, `RESISTANCE`
- `KanbanCardRef` — record `(UUID id, String ticker, KanbanColumn column, BigDecimal targetPrice)`
- `KanbanWorkflow` — implemented by `workflow`, called by `app`
- `Notification`, `NotificationType` — the notification payload
- `NotificationPublisher` — implemented by `app` (pushes to SSE), called by `workflow`

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
`type` ∈ `CARD_CREATED | CARD_MOVED | TARGET_HIT`. Both streams send a comment heartbeat (`: ping`) every 15 s.

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
| `LLM_MOCK` | app | `false` |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | postgres, app | `finally` / `finally` / `finally` |
| `SPRING_DATASOURCE_URL` | app | `jdbc:postgresql://postgres:5432/finally` |
| `CAMUNDA_GRPC_ADDRESS` | app | `http://camunda:26500` |
| `CAMUNDA_REST_ADDRESS` | app | `http://camunda:8080` |
| `FINALLY_WORKFLOW_ENABLED` | app | `true` |

Ports: app `8000` (public), Keycloak `8180 → 8080` (loopback only), Camunda web apps (Operate/Tasklist) `8081 → 8080` on host, Postgres `5432` (dev only).

For local dev outside Docker, `localhost` replaces the service names; `docker compose up postgres camunda` starts just the infrastructure.

## 11. Authentication (Keycloak)

- **Keycloak 26** runs as the compose service `keycloak` (`keycloak/compose.keycloak.yml`); realm `finally` is imported from `keycloak/realm/finally-realm.json` on first start. Self-registration, password reset and "remember me" are on. Dev user: `demo` / `demo1234`.
- **Backend for frontend**: Spring Security (`spring-boot-starter-oauth2-client`) runs the OIDC authorization-code flow with the confidential client `finally-app` and keeps the login in the server session cookie (`JSESSIONID`). The SPA never handles tokens or passwords; the `EventSource` streams are authenticated by the same cookie.
- **Routing**: browser page loads without a session → `302` to `/oauth2/authorization/keycloak` → Keycloak login page (with "Register"). `/api/**` without a session → `401 {"error":"UNAUTHENTICATED"}`; the SPA then navigates to the login. `/api/health` is public.
- **CSRF**: cookie `XSRF-TOKEN` (readable by JS), echoed as header `X-XSRF-TOKEN` (Angular does this automatically) or form field `_csrf`. Required on every POST/PATCH/DELETE, including `POST /logout`.
- **Logout**: `POST /logout` ends the Spring session and the Keycloak session (RP-initiated logout), then returns to the app, which shows the login page again.
- **Per-user data**: `user_id` in every table = the Keycloak subject (`sub`). `GET /api/me` returns `{id, username, email, name, authEnabled}`. A user's first login creates `users_profile` ($10,000) and the 10 default watchlist tickers. Prices are shared; the market data source tracks the tickers of all users. Kanban notifications are delivered only to their owner's notification stream (`userId` in `KanbanCardRef`, `Notification` and the Camunda process variables).
- **Backend code** reads the user with `com.finallyapp.security.CurrentUser.id()`; background jobs use `CurrentUser.runAs(userId, ...)`. With auth enabled there is no silent default user.
