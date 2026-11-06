# Tradex E2E tests (Playwright)

The app must run with `LLM_MOCK=true` (deterministic chat: "buy 2 AAPL" buys 2 AAPL, "sell 500 TSLA" fails,
"add PYPL to my watchlist" adds PYPL, any other message gets a fixed analysis reply).

## Run in Docker (recommended)

```bash
docker compose -f test/docker-compose.test.yml up --build --abort-on-container-exit --exit-code-from playwright
docker compose -f test/docker-compose.test.yml down -v
```

This starts an isolated stack (`finally-e2e` project: app + postgres + camunda, no host ports) plus a Playwright
container that targets `http://app:8000`. Reports land in `test/playwright-report/`.

## Run against a local stack

```bash
# start the app with LLM_MOCK=true in .env, e.g. ./scripts/start_mac.sh
cd test
npm install
npx playwright install chromium
BASE_URL=http://localhost:8000 npx playwright test
```

Specs share the single default user, so they run serially (`workers: 1`) and reset what they need through the API
(sell all positions, delete kanban cards, remove PYPL). The first spec assumes a fresh database for the
"$10,000 cash" check only loosely; use `down -v` between runs for a clean slate.

## Specs

| Spec | Covers |
|---|---|
| `markets.spec.ts` | Fresh start (10 default tickers, cash, live prices), SPA deep links, watchlist add/duplicate/remove, chart selection, SSE reconnection |
| `trading.spec.ts` | Buy (cash down, position appears), sell (cash up, position removed), rejected trades, heatmap + P&L chart |
| `chat.spec.ts` | Mocked AI chat: trade executed inline, watchlist change via chat, failed action, history after reload |
| `kanban.spec.ts` | Kanban create / edit target / drag between columns / delete, validation errors, notification toast + alert disarm |

## `data-testid` selectors relied on

| Selector | Element |
|---|---|
| `connection-status` (+ `data-status` = `connected` \| `reconnecting` \| `disconnected`) | Header connection dot |
| `header-cash`, `header-total` | Header cash balance and total portfolio value |
| `nav-markets`, `nav-watchlists`, `nav-portfolio` | Main menu |
| `watchlist-row-<TICKER>` | Watchlist row; contains `watchlist-price` |
| `watchlist-add-input`, `watchlist-add-button`, `watchlist-remove-<TICKER>`, `watchlist-error` | Watchlist management |
| `chart-ticker`, `main-chart` | Selected-ticker chart |
| `trade-ticker`, `trade-quantity`, `trade-buy`, `trade-sell`, `trade-success`, `trade-error` | Trade bar |
| `positions-table`, `positions-row-<TICKER>` | Positions table |
| `heatmap-cell-<TICKER>` | Portfolio heatmap rectangle |
| `pnl-chart` | P&L chart |
| `chat-toggle`, `chat-input`, `chat-send`, `chat-messages`, `chat-message`, `chat-loading`, `chat-error` | AI chat panel |
| `chat-action` (+ `data-status` = `executed` \| `failed`) | Inline trade / watchlist confirmation |
| `kanban-column-SUPPORT`, `kanban-column-RESISTANCE` | Kanban columns |
| `kanban-add-ticker`, `kanban-add-column` (select), `kanban-add-price`, `kanban-add-button`, `kanban-add-error` | New-card form |
| `kanban-card-<id>` (+ `data-armed` = `true` \| `false`) | Kanban card |
| `kanban-card-live-<id>`, `kanban-card-price-<id>`, `kanban-card-error-<id>`, `kanban-card-delete-<id>` | Card live price, editable target, edit error, delete button |
| `toast`, `toast-close` | Notification pop-up |
| `nav-alerts`, `alerts-add-button`, `alerts-form`, `alerts-ticker`, `alerts-upper`, `alerts-lower`, `alerts-comment`, `alerts-save`, `alerts-cancel`, `alerts-error`, `alerts-empty` | Alerts page: + button and the add form |
| `alerts-row-<id>` (with `data-status=above|below|inside|unknown`), `alerts-delete-<id>` | Alert rows |
