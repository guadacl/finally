# Tradex — AI Trading Workstation

## Project Specification

`planning/CONTRACTS.md` is the binding build contract (stack, schema, API, module ownership); this document
describes the product and how it is built. The API is spec-first: `backend/openapi/openapi.yaml`.

## 1. Vision

Tradex is a visually stunning AI-powered trading workstation that streams live market data, lets users trade a simulated portfolio, and integrates an LLM chat assistant that can analyze positions and execute trades on the user's behalf. It looks and feels like a modern Bloomberg terminal with an AI copilot.

This is the capstone project for an agentic AI coding course. It is built entirely by Coding Agents demonstrating how orchestrated AI agents can produce a production-quality full-stack application. Agents interact through files in `planning/`.

## Implementation Status (2026-11-06)

All features below are implemented and covered by tests (backend JUnit + Testcontainers, frontend Vitest,
Playwright E2E). Renamed from "FinAlly" to **Tradex** (user-visible branding only; technical ids such as the
`com.finallyapp` packages, `finally-*` containers, the `finally` database and Keycloak realm are unchanged).

| Area | What is in place |
|---|---|
| Login | Keycloak 26, self-registration, Google / Facebook brokering, Tradex login theme; one portfolio, watchlist, alerts, board and chat per account |
| Markets | Watchlist with live prices, flash, change %, sparklines; main chart (Lightweight Charts); trade bar (market orders) |
| Watchlists | Add by **company name or ticker** (search via Massive's MCP server, pick from a list when several match); company **Name** column; funnel **filter** |
| Portfolio | Heatmap, portfolio value chart, positions table (with Name and search), Support / Resistance kanban board |
| Alerts | Price alerts table + Support table + Resistance table, each with Name, live price, day low/high, last-session volume, RSI (14), funnel filter |
| Workflow | Camunda 8 `kanban-card` process per card: created / moved / target hit (notify once) / deleted → toast, email and SMS |
| Notifications | Browser toasts over SSE; emails and SMS through the notification MCP server (`email-mcp`), Mailpit locally |
| AI chat | OpenRouter `openai/gpt-oss-120b` on Cerebras with JSON-schema structured output; auto-executes trades and watchlist changes |
| Data rules | One row per ticker in every table (unique constraints, adding again updates the row) |

## 2. User Experience

### First Launch

The user runs a single Docker command (or a provided start script). A browser opens to `http://localhost:8000` and is sent to the **Keycloak login page**, where they log in or **register** a new account (see `planning/CONTRACTS.md` §11). A new account starts with **$10,000** and the **10 default tickers** (AAPL, GOOGL, MSFT, AMZN, TSLA, NVDA, META, JPM, V, NFLX).

**Login page**: Keycloak login theme `finally` (`keycloak/themes/finally/login`) in the dark trading-terminal look: yellow "T" logo and **Trade**x wordmark, username/email + password, "remember me", "Forgot password?", a purple **Sign in** button, **"or continue with" Google / Facebook** buttons, and "New to Tradex? Create account". Google and Facebook use Keycloak identity brokering and appear only when their OAuth client ID/secret are configured (`keycloak/README.md`); a social login whose email matches an existing account links to it after the user confirms that account's password. All login logic stays in Keycloak; the theme only changes CSS and texts.

**Menu**: **Markets** (watchlist + chart + trade bar), **Watchlists** (manage tickers), **Portfolio** (heatmap, value chart, positions, Support/Resistance board) and **Alerts** (price alerts, Support and Resistance tables). The AI chat panel is docked on every page; the header shows total value, cash and the connection dot.

### What the User Can Do

- **Watch prices stream** — prices flash green (uptick) or red (downtick) with subtle CSS animations that fade
- **View sparkline mini-charts** — accumulated on the frontend from the SSE stream since page load
- **Click a ticker** to see a larger detailed chart in the main chart area
- **Buy and sell shares** — market orders only, instant fill at current price, no fees, no confirmation dialog
- **Monitor their portfolio** — a heatmap (treemap) sized by weight and colored by P&L, plus a portfolio value chart
- **View a positions table** — ticker, company name, quantity, average cost, current price, value, unrealized P&L, % change
- **Manage the watchlist** — add/remove tickers manually or via the AI chat
  - **Add by name or ticker**: the add box searches US stocks by company name or ticker (e.g. "enphase" → ENPH Enphase Energy, Inc.) through **Massive's (formerly Polygon.io) MCP server** (`mcp_massive`, service `massive-mcp`, tool `call_api` on `/v3/reference/tickers`) via `GET /api/tickers/search?q=`. Suggestions appear while typing (≥ 2 characters, short pause); one match + Enter adds it; several matches open a list to pick from (arrow keys / click); stocks already watched are greyed out. Results are cached 10 minutes per query (the free Massive plan allows few calls per minute). Without a Massive key, or when the MCP server is down or rate-limited, the search answers 503 and a valid ticker symbol is added as typed.
  - Company names come from `TickerNames`: built in for ~45 common tickers, otherwise looked up once (Massive, then Finnhub) and cached; "—" until known.
- **Set Support / Resistance targets** (kanban cards) on the Portfolio board or the Alerts tables
  - A **Support** card alerts when price ≤ target, a **Resistance** card when price ≥ target. Each card notifies **only once**: on the first hit its status becomes **NOTIFIED** (also the Camunda variable `status`) and no further alerts are sent; editing the target or moving the card makes it **ACTIVE** again. No trade is executed automatically.
  - The backend checks targets on every price update; a hit publishes `target-price-hit` to the card's Camunda process instance.
  - **Board**: two columns ordered by |DIST| (closest to triggering on top, re-sorts live), drag & drop between columns, inline-editable target and comment (≤ 500 chars; Enter/blur saves, Esc cancels; a comment edit does not re-arm). A Resistance target already below the price is highlighted in accent yellow.
  - **Alerts page tables**: Support on top, Resistance below, columns Ticker, Name, Price, Day low, Day high, Vol (last session), RSI (14), Target, Dist, Status, Comment, Added; ⇅ moves a card to the other table.
- **Use price alerts** (Alerts page) — pick a watchlist stock (dropdown shows "TICKER — Company name") and set a lower and/or upper limit plus a comment. Rows show ticker, name, live price, day low/high, last-session volume, **RSI (14)** (Wilder, daily closes, live during the session; ≥ 70 red, ≤ 30 green, "—" without a Massive key), limits, status and when added. Adding an alert also creates a Support card at the lower limit and a Resistance card at the upper limit with the alert's comment (deleting the alert keeps the cards).
- **One row per ticker in every table** — a ticker appears at most once in price alerts, once in Support and once in Resistance (watchlist and positions likewise). Adding a ticker already there **updates that row** (alert: limits replaced; card: new target, re-armed; comment replaced only when given; API 200 instead of 201) and the add forms say so. Moving a card into a column that already has the ticker is refused (409 `TICKER_EXISTS`).
- **Filter tables** — watchlist, price alerts, Support and Resistance tables have a **funnel icon** in their top-right corner (shared `FilterButtonComponent`): a small frontend-only filter by ticker or name; the icon is yellow while active; Esc or × clears it. The positions table has an inline search box.
- **Receive notifications** — a card created, deleted or hitting its target produces:
  - a **toast** in the browser (SSE `/api/stream/notifications`, top-right, ~6 s, accent-yellow border for alerts);
  - an **email** to the account's Keycloak address and an **SMS** to its phone (if set), sent by Camunda service tasks (`email-notify`, `sms-notify`) through the notification MCP server's `send_email` / `send_sms` tools. Email goes to any SMTP provider (`MAIL_*` in `.env`) or the local **Mailpit** (`http://localhost:8025`); SMS uses `SMS_PROVIDER` (default `log`, no real SMS). When Camunda is down the app sends each toast / email once itself. Moving a card does not notify.
- **Chat with the AI assistant** — ask about the portfolio, get analysis, and have the AI execute trades and manage the watchlist

### Visual Design

- **Dark theme**: backgrounds around `#0d1117` or `#1a1a2e`, muted gray borders, no pure black
- **Price flash animations**: brief green/red background highlight on price change, fading over ~500ms via CSS transitions
- **Connection status indicator**: a small colored dot (green = connected, yellow = reconnecting, red = disconnected) in the header
- **Professional, data-dense layout**: inspired by Bloomberg/trading terminals — every pixel earns its place
- **Responsive but desktop-first**: optimized for wide screens, functional on tablet

### Color Scheme
- Accent Yellow: `#ecad0a`
- Blue Primary: `#209dd7`
- Purple Secondary: `#753991` (submit buttons)

## 3. Architecture Overview

### Docker Compose

The whole stack starts with `docker compose up` (compose project `finally`; one included compose file per service). The browser talks to `app` on port 8000 and to Keycloak for login; everything else is internal.

```
┌──────────────────────────────── docker compose ─────────────────────────────────┐
│  app (8000)  Spring Boot 3.5 / Java 21: /api/* REST, /api/stream/* SSE,          │
│              Angular build, market data, AI chat, Camunda job workers            │
│     │            │                │               │                │             │
│  postgres     camunda (+ ES)   keycloak (8180)   email-mcp ──▶ mailpit (8025)    │
│  (Flyway)     kanban-card BPMN  login / users    send_email / send_sms           │
│                                                  massive-mcp ──▶ api.massive.com │
└──────────────────────────────────────────────────────────────────────────────────┘
```

- **Frontend**: Angular 21 (standalone components, signals, zoneless), Tailwind CSS 4, Lightweight Charts 5, Angular CDK (drag & drop)
- **Backend**: Spring Boot 3.5, Java 21, Maven modules `contracts`, `db` (Flyway), `workflow` (Camunda), `app`; controllers implement interfaces generated from `backend/openapi/openapi.yaml`; Spring Data JPA
- **Database**: Postgres 16, named volume `finally-pgdata`
- **Workflow**: Camunda 8.8 (`camunda/bpmn/kanban-card.bpmn`): one process instance per card; a gateway sends the "card created" notification; messages `card-moved`, `target-price-hit`, `card-deleted`; job workers `finally-notify` (toast), `email-notify`, `sms-notify`
- **Auth**: Keycloak 26 (OIDC, backend-for-frontend session); `user_id` everywhere is the Keycloak subject. `AUTH_ENABLED=false` runs single-user as `default`
- **Real-time data**: Server-Sent Events (SSE)
- **AI integration**: OpenRouter chat completions from Java (`OpenRouterLlmClient`, Spring `RestClient`), model `openai/gpt-oss-120b` on Cerebras, `response_format: json_schema`
- **Market data**: Finnhub real-time quotes when `FINNHUB_API_KEY` is set, else Massive REST polling when `MASSIVE_API_KEY` is set, else the built-in simulator. Massive end-of-day data also supplies last-session volume and the daily closes for the RSI
- **MCP servers**: `email-mcp` (Node, notifications) and `massive-mcp` (Massive's `mcp_massive`, ticker search), both over Streamable HTTP, called with the Java MCP SDK (`McpToolClient`)

### Why These Choices

| Decision | Rationale |
|---|---|
| SSE over WebSockets | One-way push is all we need; simpler, universal browser support |
| Angular build served by Spring Boot | Single origin, no CORS issues, one exposed app port |
| Postgres + Flyway | Real relational database; schema applied automatically on startup |
| Camunda 8 | Card lifecycle and notifications as an explicit, observable workflow (Operate) |
| Keycloak | Registration, password reset and social login without writing auth code |
| MCP servers for side effects | Email/SMS and ticker search are tools the app (and agents) call through one protocol |
| Market orders only | No order book, limit orders or partial fills — much simpler portfolio math |

---

## 4. Directory Structure

```
├── frontend/            # Angular app (built into the app image)
├── backend/
│   ├── openapi/         # openapi.yaml — the API contract (spec-first)
│   ├── contracts/       # Java interfaces shared by app and workflow (KanbanWorkflow, notifications)
│   ├── db/              # Flyway migrations V1–V7 + migration tests
│   ├── workflow/        # Camunda client, BPMN deployer, job workers
│   └── app/             # Spring Boot application (REST, SSE, market data, chat, kanban, alerts, security, mail)
├── camunda/             # BPMN + compose file for Camunda 8.8 and Elasticsearch
├── keycloak/            # realm import, login theme, scripts, compose file
├── mcp/
│   ├── email-mcp-server/    # Node MCP server: send_email (SMTP), send_sms
│   └── massive-mcp-server/  # Massive (Polygon.io) mcp_massive image + serve.py
├── db/, mailpit/        # compose files for postgres and mailpit
├── planning/            # PLAN.md (this file), CONTRACTS.md, agent docs
├── scripts/             # start/stop scripts (macOS/Linux and Windows)
├── test/                # Playwright E2E specs + docker-compose.test.yml
├── Dockerfile           # app image: Angular build → Maven build → JRE
└── docker-compose.yml   # includes all service compose files
```

### Key Boundaries

- **`frontend/`** talks to the backend only through `/api/*` and `/api/stream/*` (same origin).
- **`backend/`** owns all server logic; the API shape is defined in `backend/openapi/openapi.yaml` first.
- **`backend/db/`** holds the schema: applied Flyway migrations are never edited (checksums), changes go in a new `V<n>__*.sql`.
- **Persistence** lives in named Docker volumes (`finally-pgdata`, `finally-camunda`, `finally-camunda-es`, `finally-keycloak`).
- **`planning/`** is the shared contract for all agents.
- **`test/`** holds E2E tests; unit tests live in `frontend/` and `backend/`.

---

## 5. Environment Variables

`.env` in the project root (gitignored; `.env.example` documents every variable). The main ones:

```bash
OPENROUTER_API_KEY=        # AI chat (required unless LLM_MOCK=true)
LLM_MOCK=false             # true -> deterministic mock replies (tests)
FINNHUB_API_KEY=           # real-time quotes (takes priority)
MASSIVE_API_KEY=           # Massive (Polygon.io): quotes, volume, RSI history, ticker search
AUTH_ENABLED=true          # false -> no login, single "default" user
KEYCLOAK_CLIENT_SECRET=... # plus KEYCLOAK_GOOGLE_* / KEYCLOAK_FACEBOOK_* for social login
MAIL_HOST= MAIL_PORT= MAIL_USERNAME= MAIL_PASSWORD= MAIL_FROM=   # SMTP provider (default: Mailpit)
```

Optional: `MAIL_ENABLED`, `SMS_ENABLED`, `SMS_PROVIDER`, `EMAIL_MCP_URL`, `MASSIVE_MCP_URL`, `APP_PORT`, `KEYCLOAK_HOST_PORT`, `POSTGRES_*`.

---

## 6. Market Data

### Sources, One Interface

All sources implement `MarketDataSource` and write to the shared `PriceCache`; SSE, alerts and the frontend are agnostic to the source. Selection: **Finnhub** (`FINNHUB_API_KEY`) → **Massive** (`MASSIVE_API_KEY`) → **simulator**.

- **Simulator** — geometric Brownian motion per ticker, ~500 ms updates, correlated moves, occasional 2–5% "events", realistic seed prices; in-process, no dependencies.
- **Finnhub** — REST quotes per tracked ticker, calls spaced to stay within the free limit; also gives day high/low.
- **Massive** — REST snapshot polling of all tracked tickers (15 s on the free tier).
- **Massive end-of-day** (with a Massive key, whatever the live source): last-session volume (grouped daily) and daily closes for RSI (14).

Only tickers somebody needs (watchlists, positions, cards, alerts) are tracked.

### SSE Streaming

- `GET /api/stream/prices` — price updates (~500 ms cadence): ticker, price, previous price, timestamp, direction
- `GET /api/stream/notifications` — notification events for the logged-in user (toasts)
- Clients use `EventSource` (automatic reconnection)

---

## 7. Database

### Postgres with Automatic Migrations

On startup the app runs the Flyway migrations from `backend/db` (V1 schema, V2 seed, V3 price alerts, V4 card comments, V5 user email, V6 user phone, V7 one row per ticker). Fresh volumes start with a clean, seeded database.

### Schema (Postgres types: UUID ids, NUMERIC amounts, TIMESTAMPTZ times)

Every user-owned table has `user_id TEXT` = the Keycloak subject (`'default'` when auth is off).

| Table | Purpose | Unique |
|---|---|---|
| `users_profile` | id (= user id), cash balance, email, phone, created_at | id |
| `watchlist` | watched tickers | (user_id, ticker) |
| `positions` | holdings: quantity (fractional), avg cost | (user_id, ticker) |
| `trades` | append-only trade log (buy/sell, quantity, price) | — |
| `portfolio_snapshots` | total value every 30 s and after each trade | — |
| `chat_messages` | conversation; `actions` JSONB for executed trades / watchlist changes | — |
| `kanban_cards` | Support/Resistance targets: column, target price, sort order, armed, comment, Camunda process key | (user_id, ticker, column_name) |
| `price_alerts` | upper/lower limits + comment | (user_id, ticker) |
| `kanban_discarded_processes` | Camunda instances of duplicate cards removed by V7, cancelled silently at startup | — |

### Seed Data

V2 seeds the `default` profile ($10,000) and its 10-ticker watchlist. Every new Keycloak user is provisioned on first login with $10,000 and the same 10 tickers.

---

## 8. API Endpoints

Defined in `backend/openapi/openapi.yaml`; errors are `{error, message}`.

| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | Health check (public) |
| GET | `/api/me` | Logged-in user (401 when not logged in) |
| GET | `/api/stream/prices` | SSE price stream |
| GET | `/api/stream/notifications` | SSE notifications (toasts) |
| GET | `/api/portfolio` | Cash, positions (with names), total value, unrealized P&L |
| POST | `/api/portfolio/trade` | Market order `{ticker, quantity, side}` |
| GET | `/api/portfolio/history` | Portfolio value snapshots |
| GET / POST | `/api/watchlist` | List (with prices and names) / add `{ticker}` (409 if present) |
| DELETE | `/api/watchlist/{ticker}` | Remove a ticker |
| GET | `/api/tickers/search?q=` | Search stocks by name or ticker (Massive MCP; 503 when unavailable) |
| POST | `/api/chat` | Send a message; reply + executed actions |
| GET | `/api/chat/history` | Conversation history |
| GET / POST | `/api/alerts` | Price alerts / add (201, or 200 when the ticker's alert was updated) |
| DELETE | `/api/alerts/{id}` | Delete an alert |
| GET / POST | `/api/kanban` | Cards / add (201, or 200 when the ticker's card in that column was updated) |
| PATCH / DELETE | `/api/kanban/{id}` | Move / edit target or comment (409 `TICKER_EXISTS` on a clashing move) / delete |

---

## 9. LLM Integration

The chat uses OpenRouter with model `openai/gpt-oss-120b` and **Cerebras** as provider (the settings of the `cerebras-inference` skill), implemented in Java (`OpenRouterLlmClient`) with **structured outputs** (`response_format: json_schema`). Key: `OPENROUTER_API_KEY` in `.env`.

### How It Works

1. Load the user's portfolio context (cash, positions with P&L, watchlist with live prices, total value)
2. Load recent history from `chat_messages`
3. Build the prompt: system message ("You are Tradex, an AI trading assistant…"), context, history, new message
4. Call OpenRouter with the JSON schema below; parse the reply
5. Auto-execute trades and watchlist changes (same validation as manual actions; failures are reported in the reply)
6. Store the message and executed actions; return the complete JSON (no token streaming — a loading indicator is enough)

```json
{
  "message": "Your conversational response to the user",
  "trades": [{"ticker": "AAPL", "side": "buy", "quantity": 10}],
  "watchlist_changes": [{"ticker": "PYPL", "action": "add"}]
}
```

Trades execute without confirmation on purpose: simulated money, a fluid demo, and it shows agentic AI. The assistant analyzes composition, concentration and P&L, suggests trades with reasoning, manages the watchlist and stays concise.

**Mock mode**: `LLM_MOCK=true` returns deterministic replies (E2E tests, development without a key, CI).

---

## 10. Frontend Design

### Layout

Single-page app with a dense, terminal-inspired layout:

- **Header** — Tradex wordmark, menu, live total value, cash, connection dot, user menu
- **Watchlist** — ticker, (name on the Watchlists page), price with flash, change %, sparkline; search-and-add box; funnel filter
- **Main chart** — selected ticker's price over time
- **Trade bar** — ticker, quantity, Buy / Sell
- **Portfolio** — heatmap, value chart, positions table, Support / Resistance board
- **Alerts** — price alerts, Support and Resistance tables (Name, market columns, RSI, filters)
- **AI chat panel** — docked/collapsible; history, loading indicator, executed actions inline
- **Toasts** — top-right notifications

### Technical Notes

- `EventSource` for `/api/stream/prices` and `/api/stream/notifications`; prices held in signals
- Lightweight Charts (canvas) for the charts; the heatmap is a custom squarified treemap of positioned elements
- Price flash: a CSS class with a background transition, removed after the animation
- Same-origin `/api/*` calls (no CORS); Tailwind with a custom dark theme

---

## 11. Docker & Deployment

### Docker Compose (`docker-compose.yml`, project `finally`)

| Service | Image | Ports | Volume | Notes |
|---|---|---|---|---|
| `app` | built from `Dockerfile` | `8000` | — | `env_file: .env`; waits for postgres and camunda (healthy) |
| `postgres` | `postgres:16` | `127.0.0.1:5432` | `finally-pgdata` | healthcheck `pg_isready` |
| `camunda` | Camunda 8.8 Run | `127.0.0.1:26500`, Operate `127.0.0.1:8081` | `finally-camunda` | healthcheck on its REST status |
| `camunda-elasticsearch` | `elasticsearch:8.19` | internal | `finally-camunda-es` | storage for Operate/Tasklist |
| `keycloak` | `quay.io/keycloak/keycloak:26.8.1` | `127.0.0.1:8180` | `finally-keycloak` | realm import on first start, login theme mounted from `keycloak/themes/finally` |
| `email-mcp` | built from `mcp/email-mcp-server` | internal `3001` | — | `send_email` / `send_sms`; only the `MAIL_*` / `SMS_*` settings |
| `massive-mcp` | built from `mcp/massive-mcp-server` | internal `8000` | — | `mcp_massive` v0.10.0; only `MASSIVE_API_KEY` |
| `mailpit` | `axllent/mailpit:v1.27` | `127.0.0.1:8025` web, `127.0.0.1:1025` SMTP | — | local catch-all mailbox |

The app reaches the others by service name (`postgres:5432`, `camunda:26500`, `keycloak:8080`, `email-mcp:3001/mcp`, `massive-mcp:8000/mcp`).

### App Image (`Dockerfile`)

1. `node:22-slim` — `npm ci` + Angular production build
2. `maven:3.9-eclipse-temurin-21` — Maven build with the Angular output in `static/`
3. `eclipse-temurin:21-jre-alpine` — `java -jar /app/app.jar` on port 8000

### Persistence

Named volumes keep data across `docker compose down` / `up`: `finally-pgdata`, `finally-camunda`, `finally-camunda-es`, `finally-keycloak`.

### Start/Stop Scripts

`scripts/start_mac.sh` / `start_windows.ps1` run `docker compose up -d` (`--build` optional), wait for `/api/health`, print the URL and optionally open the browser. `stop_mac.sh` / `stop_windows.ps1` run `docker compose down` without `-v` (data persists). All are idempotent.

### Optional Cloud Deployment

The app image can run on any container platform against managed Postgres, Camunda SaaS and Keycloak; a Terraform setup in `deploy/` remains a stretch goal.

---

## 12. Testing Strategy

### Unit / Integration Tests

**Backend (JUnit 5, Mockito, MockMvc, Testcontainers Postgres)**:
- Market data: simulator/GBM, Finnhub and Massive parsing, source selection, day range, volumes, RSI, ticker names, ticker search (CSV, ranking, cache, errors)
- Portfolio: trades, P&L, edge cases (insufficient cash/shares, selling at a loss)
- Kanban & alerts: notify-once rules, upsert per ticker, move conflicts, workflow calls
- LLM: structured output parsing, malformed replies, trade validation in chat
- Migrations: clean apply, constraints, V7 de-duplication; full-app integration test on a real Postgres
- MCP clients (email/SMS, search) and security (login, 401 JSON, logout)

**Frontend (Vitest, Angular TestBed)**: components with mock data, price flash, watchlist add/search/filter, alerts and target tables, portfolio calculations, chat rendering and loading state.

### E2E Tests (`test/`, Playwright)

`test/docker-compose.test.yml` starts the stack plus a Playwright container, with `LLM_MOCK=true`. Specs: `markets` (default watchlist, streaming, add/remove ticker), `trading` (buy/sell, portfolio updates, heatmap and chart), `kanban` (cards, alerts), `chat` (mocked reply with an inline trade). SSE reconnection is covered by the frontend unit tests.
