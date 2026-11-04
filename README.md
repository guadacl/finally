# FinAlly — AI Trading Workstation

A Bloomberg-style trading workstation in the browser: live market data, a simulated $10,000 portfolio, an AI
copilot that analyses positions and trades on your behalf, and Support / Resistance price targets that notify you
through a Camunda workflow by in-app pop-up and email.

Built entirely by coding agents as a capstone project for an agentic AI coding course. The product spec is
[`planning/PLAN.md`](planning/PLAN.md); the binding technical contract is [`planning/CONTRACTS.md`](planning/CONTRACTS.md).

## Features

**Markets & trading**
- **Live prices** streamed over SSE with green/red flash animations and sparklines; click a ticker for a larger chart.
  Sources: Finnhub real-time quotes, Massive (Polygon.io) or the built-in GBM simulator.
- **Simulated portfolio** — $10,000 virtual cash, market orders, instant fills, no fees.
- **Portfolio views** — heatmap (treemap) sized by weight and colored by P&L, portfolio value chart, positions table.
- **Watchlists** — add/remove tickers manually or through the AI chat.
- **AI copilot** — chat panel (LiteLLM → OpenRouter, Cerebras) that analyses holdings and auto-executes trades and
  watchlist changes.

**Alerts page**
- **Price alerts table** — a watchlist stock between a lower and an upper limit, with live price, day low/high,
  last-session volume, **RSI (14)** (≥ 70 overbought in red, ≤ 30 oversold in green) and an in/above/below-range status.
- Adding an alert also creates a **Support** card at the lower limit and a **Resistance** card at the upper limit
  (the alert's comment is copied onto the cards).

**Support / Resistance board** (Alerts and Portfolio pages)
- Two kanban columns: **Support** alerts when the price falls to the target, **Resistance** when it rises to it;
  each card notifies **only once**, then shows **NOTIFIED** (also the Camunda process status); editing the target or
  moving the card re-arms it.
- Cards show the live price, the distance to target (**DIST**) and an **inline-editable target price and comment**.
- Each column is sorted by |DIST| — the card closest to triggering is on top, re-sorting live.
- A Resistance card whose target is already below the current price is highlighted in accent yellow.
- Drag cards between columns.

**Notifications (Camunda 8)**
- Every card runs a Camunda `kanban-card` process. **Card created**, **card deleted** and **target hit** each show an
  in-app pop-up and send an **email** to the account's address; card moved shows a pop-up only.
- Emails go Camunda "Send email" task → Spring job worker → **email MCP server** (`send_email` tool) → SMTP
  (any provider, e.g. Brevo, SendGrid, Mailgun). Without provider settings they land in a local **Mailpit** inbox.

**Accounts (Keycloak)**
- Each user has their own portfolio, watchlist, alerts, board and chat.
- **Custom FinAlly login page** (Keycloak theme): username/password, "remember me", password reset, self-registration,
  and **Sign in with Google / Facebook** once their OAuth credentials are configured.

## Architecture

Everything runs with Docker Compose; the browser only talks to the app on port 8000 (plus Keycloak's login page).

| Service | What it is | Host port |
|---|---|---|
| `app` | Spring Boot (Java 21) — REST API (spec-first OpenAPI), SSE streams, market data, AI chat, Camunda job workers; serves the Angular build | `8000` |
| `postgres` | Postgres 16 — app data, Flyway migrations (`backend/db`) | `127.0.0.1:5432` (dev) |
| `camunda` + `camunda-elasticsearch` | Camunda 8.8 (Zeebe + Operate/Tasklist) running the `kanban-card` BPMN (`camunda/bpmn`) | `127.0.0.1:8081` (Operate) |
| `keycloak` | Keycloak 26 — login, registration, Google/Facebook brokering, FinAlly login theme | `127.0.0.1:8180` |
| `email-mcp` | Node MCP server (`mcp/email-mcp-server`) exposing `send_email` over Streamable HTTP, relaying to SMTP | internal |
| `mailpit` | Local catch-all SMTP + web inbox, used when no mail provider is configured | `127.0.0.1:8025` |

- **Frontend**: Angular (signals, standalone components), Tailwind CSS, canvas charts, native `EventSource`.
- **Backend**: Maven multi-module — `contracts`, `db`, `workflow` (Camunda client, BPMN, job workers), `app`.

## Quick start

```bash
cp .env.example .env          # add OPENROUTER_API_KEY (and optional market-data / mail / social-login settings)
docker compose up -d --build  # or scripts/start_windows.ps1 / scripts/start_mac.sh
# open http://localhost:8000 → log in (demo / demo1234) or create an account
```

| URL | |
|---|---|
| http://localhost:8000 | FinAlly |
| http://localhost:8180/admin | Keycloak admin console (`admin` / `admin` by default) |
| http://localhost:8081 | Camunda Operate (process instances) |
| http://localhost:8025 | Mailpit inbox (emails when no SMTP provider is set) |

Stop with `docker compose down` (data is kept in named volumes; add `-v` only to wipe it).

## Configuration (`.env`)

| Variable | Required | Description |
|---|---|---|
| `OPENROUTER_API_KEY` | yes | AI chat (OpenRouter, Cerebras inference) |
| `LLM_MOCK` | no | `true` = deterministic mock AI replies (tests) |
| `FINNHUB_API_KEY` | no | Finnhub real-time quotes (takes priority) |
| `MASSIVE_API_KEY` | no | Massive (Polygon.io) quotes; also day volume and the RSI's daily closes. Without Finnhub and Massive the simulator runs |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_STARTTLS`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | no | SMTP provider for alert emails (examples for Brevo, SendGrid, Mailgun in `.env.example`). Unset = Mailpit |
| `MAIL_ENABLED` | no | `false` disables alert emails |
| `KEYCLOAK_GOOGLE_CLIENT_ID` / `_SECRET` | no | Enables "Sign in with Google" |
| `KEYCLOAK_FACEBOOK_CLIENT_ID` / `_SECRET` | no | Enables "Sign in with Facebook" |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_CLIENT_SECRET` | no | Keycloak admin login and the app's OIDC client secret |

After changing only mail settings, `docker compose up -d email-mcp` is enough. Social-login setup (Google Cloud
Console / Meta for Developers, redirect URIs, apply script for an existing realm) and the login theme are described in
[`keycloak/README.md`](keycloak/README.md).

## Project structure

```
finally/
├── frontend/                 # Angular app (Markets, Watchlists, Portfolio, Alerts, AI chat)
├── backend/                  # Maven: contracts, db (Flyway), workflow (Camunda), app (Spring Boot)
│   └── openapi/openapi.yaml  # API spec (spec-first)
├── camunda/bpmn/             # kanban-card process (gateway on column, notify + email tasks)
├── keycloak/                 # realm import, FinAlly login theme, social-login scripts
├── mcp/email-mcp-server/     # MCP server with the send_email tool (SMTP)
├── mailpit/                  # local mail catcher (compose include)
├── planning/                 # PLAN.md (spec), CONTRACTS.md (build contract), agent docs
├── scripts/                  # start/stop helpers (macOS/Linux, Windows)
├── test/                     # Playwright E2E tests + docker-compose.test.yml
├── Dockerfile                # Angular build → Maven build → JRE image
└── docker-compose.yml        # includes the service compose files above
```

## Tests

- Frontend: `cd frontend && npx ng test --watch=false` (Vitest)
- Backend: `cd backend && mvn test` (integration tests use Testcontainers; needs Docker)
- Email MCP server: `cd mcp/email-mcp-server && npm test`
- E2E: `test/docker-compose.test.yml` (Playwright, `LLM_MOCK=true`)

## License

See [LICENSE](LICENSE).
