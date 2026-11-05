# FinAlly — AI Trading Workstation

## Project Specification

## 1. Vision

FinAlly (Finance Ally) is a visually stunning AI-powered trading workstation that streams live market data, lets users trade a simulated portfolio, and integrates an LLM chat assistant that can analyze positions and execute trades on the user's behalf. It looks and feels like a modern Bloomberg terminal with an AI copilot.


This is the capstone project for an agentic AI coding course. It is built entirely by Coding Agents demonstrating how orchestrated AI agents can produce a production-quality full-stack application. Agents interact through files in `planning/`.

## 2. User Experience

### First Launch

The user runs a single Docker command (or a provided start script). A browser opens to `http://localhost:8000` and is sent to the **Keycloak login page**, where they log in or **register** a new account (see `planning/CONTRACTS.md` §11). Each account has its own portfolio, watchlist, kanban board and chat.

**Login page**: a custom FinAlly-styled page (Keycloak login theme `finally`, `keycloak/themes/finally/login`) in the dark trading-terminal look: yellow "F" logo, username/email + password, "remember me", "Forgot password?", a purple **Sign in** button, **"or continue with" Google / Facebook** buttons, and "New to FinAlly? Create account". Google and Facebook use Keycloak identity brokering and appear only when their OAuth client ID/secret are configured (`keycloak/README.md`); a social login whose email matches an existing account links to it after the user confirms that account's password. All login logic (passwords, reset, registration, social flow) stays in Keycloak; the theme only changes CSS and texts.

After logging in they see:

- A watchlist of 10 default tickers with live-updating prices in a grid
- $10,000 in virtual cash
- A dark, data-rich trading terminal aesthetic
- An AI chat panel ready to assist

The menu of the web application contain markets watchlists portafolio 

Implemented menu: **Markets** (watchlist + chart + trade bar), **Watchlists** (manage tickers, with each company name), **Portfolio** (heatmap, value chart, positions, Support/Resistance board) and **Alerts** (price alerts table, then a Support table and a Resistance table); the AI chat panel is docked on every page.



### What the User Can Do

- **Watch prices stream** — prices flash green (uptick) or red (downtick) with subtle CSS animations that fade
- **View sparkline mini-charts** — price action beside each ticker in the watchlist, accumulated on the frontend from the SSE stream since page load (sparklines fill in progressively)
- **Click a ticker** to see a larger detailed chart in the main chart area
- **Buy and sell shares** — market orders only, instant fill at current price, no fees, no confirmation dialog
- **Monitor their portfolio** — a heatmap (treemap) showing positions sized by weight and colored by P&L, plus a P&L chart tracking total portfolio value over time
- **View a positions table** — ticker, quantity, average cost, current price, unrealized P&L, % change
- **In portafolio I want a kamban dashboard to create move delete stocks in 2 separate kamban column callee: support and resistence.Each item in the kamban dashboard must contain a label with real time price name and an editable field that must be a price to buy or to sell.
  - **Target price alerts**: when the live price hits a card's target price, the user receives a notification. A **Support** card triggers when price ≤ target; a **Resistance** card triggers when price ≥ target. Each card notifies **only once**: when its target is first reached, its status becomes **NOTIFIED** (also in the Camunda process, variable `status`) and no further alerts are sent, even if the price moves back and crosses the target again. Editing the target price or moving the card to the other column makes it **ACTIVE** again. The card shows a "Notified" badge. No trade is executed automatically.
  - The backend checks targets against the price cache on every price update. When a target is hit, it publishes a `target-price-hit` message to the card's Camunda process instance, and the process runs the notification task.
  - **Board details (implemented)**: the cards appear as a kanban board on the **Portfolio** page and, on the **Alerts** page, as two full-width tables stacked one above the other (**Support** on top, **Resistance** below) with the same columns as the price alerts table (Ticker, Name, Price, Day low, Day high, Vol (last session), RSI (14)) plus Target, Dist, Status, Comment and Added; ⇅ moves a card to the other table. Each card shows the ticker, live price, **DIST** (% from the current price to the target), an inline-editable **target price** and an inline-editable **comment** (≤ 500 chars; Enter or leaving the field saves, Esc cancels; a comment edit does not re-arm the card). Each column is ordered by |DIST| ascending, so the card closest to triggering is on top, and re-sorts live; cards without a price go last. Drag & drop moves a card between columns (order inside a column is automatic). A **Resistance** card whose target is already below the current price is highlighted with an accent-yellow border and glow.
  - **Notification channel: in-app pop-up (toast)** in the browser. The Camunda notification task is handled by a Spring job worker that pushes a `notification` event to the browser over SSE (`/api/stream/notifications`). The frontend shows it as a toast in the top-right corner (ticker, column, target price, current price), auto-dismissing after ~6 seconds, styled with the dark theme (Accent Yellow border for alerts). The same channel delivers the "card created" notification from the Camunda gateway. Toasts only reach an open browser tab.
  - **Emails**: in addition to the toast, the card owner gets a plain-text email at their Keycloak account address when a Support/Resistance card is **created**, **deleted**, or **hits its target** (not when it is moved), with ticker, Support/Resistance, target price, current price (target hit), time and a link to `/alerts`. Emails go **through Camunda**: after each of those toast tasks, the `kanban-card` process runs a "Send email" service task (job type `email-notify`); its Spring job worker calls the `send_email` tool of the **email MCP server** (`mcp/email-mcp-server`, compose service `email-mcp`, MCP over Streamable HTTP), which delivers it over SMTP to any provider (SendGrid, Brevo, Mailgun, ... via `MAIL_*` in `.env`). Without provider settings a local **Mailpit** catch-all receives them (web UI `http://localhost:8025`). When Camunda is down, the fallback still sends each toast and email once. Mail failures never affect the toast or the workflow. No Slack or OS-level notification.
- **Use the Alerts page** — a **price alerts** table: pick a watchlist stock and set a lower and/or upper limit (plus an optional comment). Each row shows ticker, company name (built in for common tickers, otherwise looked up once from Massive or Finnhub; "—" until known), live price, day low / day high, last-session volume, **RSI (14)** (Wilder, on daily closes, live during the session; ≥ 70 overbought in red, ≤ 30 oversold in green, "—" without a Massive key), lower and upper limit, status (in range / above upper / below lower) and when it was added. Adding an alert also creates a **Support** card at the lower limit and a **Resistance** card at the upper limit, carrying the alert's comment (deleting the alert keeps the cards). The comment is shown on the cards, not in the alerts table. The Support and Resistance tables are shown below it.
- **One row per ticker in every table** — a ticker appears at most once in the price alerts table, once in Support and once in Resistance (watchlist and positions were already unique); enforced by unique constraints (migration V7). Adding a ticker that is already there **updates the existing row** instead (alert: limits replaced; card: new target, re-armed; the comment is replaced only when a new one is given; API answers 200 instead of 201), and the add forms say so. Moving a card to the other column when that column already has the ticker is refused (409 `TICKER_EXISTS`). V7 removed existing duplicates by keeping the most recently edited row; the Camunda processes of the removed cards are cancelled silently at startup (no "card deleted" toast/email).
- **Search on every table** — each table (watchlist, price alerts, Support, Resistance, positions) has a search box filtering by ticker or company name.
- **Chat with the AI assistant** — ask about their portfolio, get analysis, and have the AI execute trades and manage the watchlist through natural language
- **Manage the watchlist** — add/remove tickers manually or via the AI chat

### Visual Design

- **Dark theme**: backgrounds around `#0d1117` or `#1a1a2e`, muted gray borders, no pure black
- **Price flash animations**: brief green/red background highlight on price change, fading over ~500ms via CSS transitions
- **Connection status indicator**: a small colored dot (green = connected, yellow = reconnecting, red = disconnected) visible in the header
- **Professional, data-dense layout**: inspired by Bloomberg/trading terminals — every pixel earns its place
- **Responsive but desktop-first**: optimized for wide screens, functional on tablet

### Color Scheme
- Accent Yellow: `#ecad0a`
- Blue Primary: `#209dd7`
- Purple Secondary: `#753991` (submit buttons)

## 3. Architecture Overview

### Docker Compose, Three Services

The whole stack is started with a single `docker compose up`. The browser only talks to the `app` service on port 8000; Postgres and Camunda are internal services on the compose network.

```
┌──────────────────────── docker compose ────────────────────────┐
│                                                                │
│  app  (port 8000, exposed)                                     │
│  Spring Boot (Java 21, Maven)                                  │
│  ├── /api/*          REST endpoints (OpenAPI)                  │
│  ├── /api/stream/*   SSE streaming                             │
│  └── /*              Static file serving (Angular build)       │
│  Background task: market data polling/sim                      │
│  Camunda job workers (notifications)                           │
│        │                         │                             │
│        ▼                         ▼                             │
│  postgres (5432, internal)   camunda (Camunda 8 Run)           │
│  volume: finally-pgdata      gRPC 26500 / REST 8080 (internal) │
│                              Operate UI on 8081 (optional)     │
│                              volume: finally-camunda           │
└────────────────────────────────────────────────────────────────┘
```

- **Frontend**: Angular last version
- **Backend**: Java Spring Application, Open API 
- **Database**: Postgres 16, running as the `postgres` service in Docker Compose, data persisted in the named volume `finally-pgdata`
- **workflow**: Andled using Camunda. For instance when you create a new stock in Kamunda dashboard the column SUPPORT of RESISTENCE status must be in the Camunda workflow, In Camunda create a gateway that send notification when I create a new task/stock in the kamban dashboard. Camunda must run as docker container
- **Real-time data**: Server-Sent Events (SSE) — simpler than WebSockets, one-way server→client push, works everywhere
- **AI integration**: LiteLLM → OpenRouter (Cerebras for fast inference), with structured outputs for trade execution
- **Market data**: Environment-variable driven — simulator by default, real data via Massive API if key provided (implemented: Finnhub real-time quotes take priority when `FINNHUB_API_KEY` is set; Massive end-of-day data also supplies last-session volume and the daily closes for the RSI)

**Services as implemented** (the diagram above shows the original three; `docker-compose.yml` includes one compose file per service):

| Service | Purpose |
|---|---|
| `app` | Spring Boot: REST + SSE, market data, AI chat, Camunda job workers (`finally-notify`, `email-notify`), serves the Angular build |
| `postgres` | Postgres 16 (Flyway migrations V1–V5) |
| `camunda` + `camunda-elasticsearch` | Camunda 8.8 with Operate/Tasklist; runs the `kanban-card` BPMN |
| `keycloak` | Keycloak 26: login/registration, Google/Facebook brokering, FinAlly login theme |
| `email-mcp` | Email MCP server (`send_email` tool over Streamable HTTP) relaying to the SMTP provider |
| `mailpit` | Local mail catcher used when no SMTP provider is configured |

### Why These Choices

| Decision | Rationale |
|---|---|
| SSE over WebSockets | One-way push is all we need; simpler, no bidirectional complexity, universal browser support |
| Angular build served by Spring Boot | Single origin, no CORS issues, one exposed port |
| Postgres | Real relational database, shared with Camunda-related state; runs as a compose service |
| Docker Compose | Postgres and Camunda are separate servers; compose still gives users one command (`docker compose up`) |
| Maven for Java | Standard Java build tool; reproducible builds; Spring Boot starter support |
| Market orders only | Eliminates order book, limit order logic, partial fills — dramatically simpler portfolio math |

---

## 4. Directory Structure

```
finally/
├── frontend/                 # Angularproject (static export)
├── backend/                  # OpenAPI  (JAVA) and Springboot
│   └── db/                   # Schema definitions, seed data, migration logic
├── planning/                 # Project-wide documentation for agents
│   ├── PLAN.md               # This document
│   └── ...                   # Additional agent reference docs
├── scripts/
│   ├── start_mac.sh          # docker compose up (macOS/Linux)
│   ├── stop_mac.sh           # docker compose down (macOS/Linux)
│   ├── start_windows.ps1     # docker compose up (Windows PowerShell)
│   └── stop_windows.ps1      # docker compose down (Windows PowerShell)
├── test/                     # Playwright E2E tests + docker-compose.test.yml
├── Dockerfile                # Multi-stage build for the app service (Angular → Java)
├── docker-compose.yml        # Defines app, postgres, camunda services + named volumes
├── .env                      # Environment variables (gitignored, .env.example committed)
└── .gitignore
```

### Key Boundaries

- **`frontend/`** is a self-contained Next.js project. It knows nothing about Angular. It talks to the backend via `/api/*` endpoints and `/api/stream/*` SSE endpoints. Internal structure is up to the Frontend Engineer agent.
- **`backend/`** is a self-contained uv project with its own `pyproject.toml`. It owns all server logic including database initialization, schema, seed data, API routes, SSE streaming, market data, and LLM integration. Internal structure is up to the Backend/Market Data agents.
- **`backend/db/`** contains schema SQL definitions and seed logic. On startup the backend connects to the `postgres` service and applies migrations/seed data if the tables are missing.
- **Persistence** lives in named Docker volumes (`finally-pgdata` for Postgres, `finally-camunda` for Camunda), not in a project directory.
- **`planning/`** contains project-wide documentation, including this plan. All agents reference files here as the shared contract.
- **`test/`** contains Playwright E2E tests and supporting infrastructure (e.g., `docker-compose.test.yml`). Unit tests live within `frontend/` and `backend/` respectively, following each framework's conventions.
- **`scripts/`** contains start/stop scripts that wrap Docker commands.

---

## 5. Environment Variables

```bash
# Required: OpenRouter API key for LLM chat functionality
OPENROUTER_API_KEY=your-openrouter-api-key-here

# Optional: Massive (Polygon.io) API key for real market data
# If not set, the built-in market simulator is used (recommended for most users)
MASSIVE_API_KEY=

# Optional: Set to "true" for deterministic mock LLM responses (testing)
LLM_MOCK=false
```

### Behavior

- If `MASSIVE_API_KEY` is set and non-empty → backend uses Massive REST API for market data
- If `MASSIVE_API_KEY` is absent or empty → backend uses the built-in market simulator
- If `LLM_MOCK=true` → backend returns deterministic mock LLM responses (for E2E tests)
- The backend reads `.env` from the project root (mounted into the container or read via docker `--env-file`)

---

## 6. Market Data

### Two Implementations, One Interface

Both the simulator and the Massive client implement the same abstract interface. The backend selects which to use based on the environment variable. All downstream code (SSE streaming, price cache, frontend) is agnostic to the source.

### Simulator (Default)

- Generates prices using geometric Brownian motion (GBM) with configurable drift and volatility per ticker
- Updates at ~500ms intervals
- Correlated moves across tickers (e.g., tech stocks move together)
- Occasional random "events" — sudden 2-5% moves on a ticker for drama
- Starts from realistic seed prices (e.g., AAPL ~$190, GOOGL ~$175, etc.)
- Runs as an in-process background task — no external dependencies

### Massive API (Optional)

- REST API polling (not WebSocket) — simpler, works on all tiers
- Polls for the union of all watched tickers on a configurable interval
- Free tier (5 calls/min): poll every 15 seconds
- Paid tiers: poll every 2-15 seconds depending on tier
- Parses REST response into the same format as the simulator

### Shared Price Cache

- A single background task (simulator or Massive poller) writes to an in-memory price cache
- The cache holds the latest price, previous price, and timestamp for each ticker
- SSE streams read from this cache and push updates to connected clients
- This architecture supports future multi-user scenarios without changes to the data layer

### SSE Streaming

- Endpoint: `GET /api/stream/prices`
- Long-lived SSE connection; client uses native `EventSource` API
- Server pushes price updates for all tickers known to the system at a regular cadence (~500ms) — in the single-user model this is equivalent to the user's watchlist
- Each SSE event contains ticker, price, previous price, timestamp, and change direction
- Client handles reconnection automatically (EventSource has built-in retry)

---

## 7. Database

### Postgres with Automatic Migrations

On startup the backend connects to the `postgres` compose service and runs Flyway migrations. If the tables are missing, it creates the schema and seeds default data. This means:

- No separate migration step
- No manual database setup
- Fresh Docker volumes start with a clean, seeded database automatically

### Schema

All tables include a `user_id` column defaulting to `"default"`. This is hardcoded for now (single-user) but enables future multi-user support without schema migration.

**users_profile** — User state (cash balance)
- `id` TEXT PRIMARY KEY (default: `"default"`)
- `cash_balance` REAL (default: `10000.0`)
- `created_at` TEXT (ISO timestamp)

**watchlist** — Tickers the user is watching
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `ticker` TEXT
- `added_at` TEXT (ISO timestamp)
- UNIQUE constraint on `(user_id, ticker)`

**positions** — Current holdings (one row per ticker per user)
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `ticker` TEXT
- `quantity` REAL (fractional shares supported)
- `avg_cost` REAL
- `updated_at` TEXT (ISO timestamp)
- UNIQUE constraint on `(user_id, ticker)`

**trades** — Trade history (append-only log)
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `ticker` TEXT
- `side` TEXT (`"buy"` or `"sell"`)
- `quantity` REAL (fractional shares supported)
- `price` REAL
- `executed_at` TEXT (ISO timestamp)

**portfolio_snapshots** — Portfolio value over time (for P&L chart). Recorded every 30 seconds by a background task, and immediately after each trade execution.
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `total_value` REAL
- `recorded_at` TEXT (ISO timestamp)

**chat_messages** — Conversation history with LLM
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `role` TEXT (`"user"` or `"assistant"`)
- `content` TEXT
- `actions` TEXT (JSON — trades executed, watchlist changes made; null for user messages)
- `created_at` TEXT (ISO timestamp)

### Default Seed Data

- One user profile: `id="default"`, `cash_balance=10000.0`
- Ten watchlist entries: AAPL, GOOGL, MSFT, AMZN, TSLA, NVDA, META, JPM, V, NFLX

---

## 8. API Endpoints

### Market Data
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/stream/prices` | SSE stream of live price updates |
| GET | `/api/stream/notifications` | SSE stream of notification events (target price hit, kanban card created) shown as browser pop-ups |

### Portfolio
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/portfolio` | Current positions, cash balance, total value, unrealized P&L |
| POST | `/api/portfolio/trade` | Execute a trade: `{ticker, quantity, side}` |
| GET | `/api/portfolio/history` | Portfolio value snapshots over time (for P&L chart) |

### Watchlist
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/watchlist` | Current watchlist tickers with latest prices |
| POST | `/api/watchlist` | Add a ticker: `{ticker}` |
| DELETE | `/api/watchlist/{ticker}` | Remove a ticker |

### Chat
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | Send a message, receive complete JSON response (message + executed actions) |

### System
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Health check (for Docker/deployment) |

---

## 9. LLM Integration

When writing code to make calls to LLMs, use cerebras-inference skill to use LiteLLM via OpenRouter to the `openrouter/openai/gpt-oss-120b` model with Cerebras as the inference provider. Structured Outputs should be used to interpret the results.

There is an OPENROUTER_API_KEY in the .env file in the project root.

### How It Works

When the user sends a chat message, the backend:

1. Loads the user's current portfolio context (cash, positions with P&L, watchlist with live prices, total portfolio value)
2. Loads recent conversation history from the `chat_messages` table
3. Constructs a prompt with a system message, portfolio context, conversation history, and the user's new message
4. Calls the LLM via LiteLLM → OpenRouter, requesting structured output, using the cerebras-inference skill
5. Parses the complete structured JSON response
6. Auto-executes any trades or watchlist changes specified in the response
7. Stores the message and executed actions in `chat_messages`
8. Returns the complete JSON response to the frontend (no token-by-token streaming — Cerebras inference is fast enough that a loading indicator is sufficient)

### Structured Output Schema

The LLM is instructed to respond with JSON matching this schema:

```json
{
  "message": "Your conversational response to the user",
  "trades": [
    {"ticker": "AAPL", "side": "buy", "quantity": 10}
  ],
  "watchlist_changes": [
    {"ticker": "PYPL", "action": "add"}
  ]
}
```

- `message` (required): The conversational text shown to the user
- `trades` (optional): Array of trades to auto-execute. Each trade goes through the same validation as manual trades (sufficient cash for buys, sufficient shares for sells)
- `watchlist_changes` (optional): Array of watchlist modifications

### Auto-Execution

Trades specified by the LLM execute automatically — no confirmation dialog. This is a deliberate design choice:
- It's a simulated environment with fake money, so the stakes are zero
- It creates an impressive, fluid demo experience
- It demonstrates agentic AI capabilities — the core theme of the course

If a trade fails validation (e.g., insufficient cash), the error is included in the chat response so the LLM can inform the user.

### System Prompt Guidance

The LLM should be prompted as "FinAlly, an AI trading assistant" with instructions to:
- Analyze portfolio composition, risk concentration, and P&L
- Suggest trades with reasoning
- Execute trades when the user asks or agrees
- Manage the watchlist proactively
- Be concise and data-driven in responses
- Always respond with valid structured JSON

### LLM Mock Mode

When `LLM_MOCK=true`, the backend returns deterministic mock responses instead of calling OpenRouter. This enables:
- Fast, free, reproducible E2E tests
- Development without an API key
- CI/CD pipelines

---

## 10. Frontend Design

### Layout

The frontend is a single-page application with a dense, terminal-inspired layout. The specific component architecture and layout system is up to the Frontend Engineer, but the UI should include these elements:

- **Watchlist panel** — grid/table of watched tickers with: ticker symbol, current price (flashing green/red on change), daily change %, and a sparkline mini-chart (accumulated from SSE since page load)
- **Main chart area** — larger chart for the currently selected ticker, with at minimum price over time. Clicking a ticker in the watchlist selects it here.
- **Portfolio heatmap** — treemap visualization where each rectangle is a position, sized by portfolio weight, colored by P&L (green = profit, red = loss)
- **P&L chart** — line chart showing total portfolio value over time, using data from `portfolio_snapshots`
- **Positions table** — tabular view of all positions: ticker, quantity, avg cost, current price, unrealized P&L, % change
- **Trade bar** — simple input area: ticker field, quantity field, buy button, sell button. Market orders, instant fill.
- **AI chat panel** — docked/collapsible sidebar. Message input, scrolling conversation history, loading indicator while waiting for LLM response. Trade executions and watchlist changes shown inline as confirmations.
- **Header** — portfolio total value (updating live), connection status indicator, cash balance

### Technical Notes

- Use `EventSource` for SSE connection to `/api/stream/prices`
- Canvas-based charting library preferred (Lightweight Charts or Recharts) for performance
- Price flash effect: on receiving a new price, briefly apply a CSS class with background color transition, then remove it
- All API calls go to the same origin (`/api/*`) — no CORS configuration needed
- Tailwind CSS for styling with a custom dark theme

---

## 11. Docker & Deployment

### Docker Compose (`docker-compose.yml`)

| Service | Image | Ports | Volume | Notes |
|---|---|---|---|---|
| `app` | built from `Dockerfile` | `8000:8000` | — | `env_file: .env`; `depends_on` postgres and camunda (healthy) |
| `postgres` | `postgres:16` | internal only (optionally `5432:5432` for dev) | `finally-pgdata:/var/lib/postgresql/data` | healthcheck `pg_isready` |
| `camunda` | Camunda 8 Run | internal 26500 / 8080; `8081` for the Operate UI (optional) | `finally-camunda` | healthcheck on its REST status endpoint |
| `camunda-elasticsearch` | `elasticsearch:8.19` | internal only | `finally-camunda-es` | secondary storage for Operate/Tasklist |
| `keycloak` | `quay.io/keycloak/keycloak:26.8.1` | `127.0.0.1:8180` | `finally-keycloak` | realm import from `keycloak/realm`, login theme mounted from `keycloak/themes/finally`; Google/Facebook client IDs from `.env` |
| `email-mcp` | built from `mcp/email-mcp-server` | internal `3001` (`/mcp`, `/health`) | — | receives only the `MAIL_*` settings; depends on mailpit |
| `mailpit` | `axllent/mailpit:v1.27` | `127.0.0.1:8025` (web), `127.0.0.1:1025` (SMTP) | — | local catch-all; not used once an SMTP provider is set |

The `app` service reaches the others by service name (`jdbc:postgresql://postgres:5432/finally`, `camunda:26500`, `http://keycloak:8080`, `http://email-mcp:3001/mcp`).

### Multi-Stage Dockerfile (app service)

```
Stage 1: Node 20 slim
  - Copy frontend/
  - npm ci && npm run build (Angular production build)

Stage 2: Maven + JDK 21
  - Copy backend/
  - Copy Angular build output into src/main/resources/static/
  - mvn -B package -DskipTests

Stage 3: JRE 21 slim
  - Copy the Spring Boot jar
  - Expose port 8000
  - CMD: java -jar app.jar
```

Spring Boot serves the Angular static files and all API routes on port 8000.

### Persistence

Named volumes defined in `docker-compose.yml` keep data across `docker compose down` / `up`:
- `finally-pgdata` — Postgres data
- `finally-camunda` — Camunda state

### Start/Stop Scripts

**`scripts/start_mac.sh`** (macOS/Linux):
- Runs `docker compose up -d` (adds `--build` if the `--build` flag is passed)
- Waits for the `app` health check (`/api/health`)
- Prints the URL to access the app
- Optionally opens the browser

**`scripts/stop_mac.sh`** (macOS/Linux):
- Runs `docker compose down`
- Does NOT pass `-v`, so the volumes (data) persist

**`scripts/start_windows.ps1`** / **`scripts/stop_windows.ps1`**: PowerShell equivalents for Windows.

All scripts should be idempotent — safe to run multiple times.

### Optional Cloud Deployment

The `app` image can deploy to any container platform, pointed at a managed Postgres and Camunda SaaS instead of the compose services. A Terraform configuration for App Runner may be provided in a `deploy/` directory as a stretch goal, but is not part of the core build.

---

## 12. Testing Strategy

### Unit Tests (within `frontend/` and `backend/`)

**Backend (pytest)**:
- Market data: simulator generates valid prices, GBM math is correct, Massive API response parsing works, both implementations conform to the abstract interface
- Portfolio: trade execution logic, P&L calculations, edge cases (selling more than owned, buying with insufficient cash, selling at a loss)
- LLM: structured output parsing handles all valid schemas, graceful handling of malformed responses, trade validation within chat flow
- API routes: correct status codes, response shapes, error handling

**Frontend (React Testing Library or similar)**:
- Component rendering with mock data
- Price flash animation triggers correctly on price changes
- Watchlist CRUD operations
- Portfolio display calculations
- Chat message rendering and loading state

### E2E Tests (in `test/`)

**Infrastructure**: A separate `docker-compose.test.yml` in `test/` that spins up the app, postgres and camunda services plus a Playwright container. This keeps browser dependencies out of the production image.

**Environment**: Tests run with `LLM_MOCK=true` by default for speed and determinism.

**Key Scenarios**:
- Fresh start: default watchlist appears, $10k balance shown, prices are streaming
- Add and remove a ticker from the watchlist
- Buy shares: cash decreases, position appears, portfolio updates
- Sell shares: cash increases, position updates or disappears
- Portfolio visualization: heatmap renders with correct colors, P&L chart has data points
- AI chat (mocked): send a message, receive a response, trade execution appears inline
- SSE resilience: disconnect and verify reconnection
