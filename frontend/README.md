# FinAlly — Angular frontend

Angular 21 (standalone components, signals, zoneless), Tailwind CSS 4, Angular CDK drag & drop, lightweight-charts 5.

```bash
npm ci
npm start                    # dev server on :4200, proxies /api -> http://localhost:8000 (proxy.conf.json)
npm run build                # production build -> dist/frontend/browser/ (copied into the Spring Boot jar)
npm test -- --watch=false    # Vitest + jsdom, headless
```

Layout:

- `src/app/api/` — typed models + `ApiClient` matching `backend/openapi/openapi.yaml`
- `src/app/core/` — `PriceStreamService` (SSE prices), `NotificationStreamService` (SSE notifications), `PortfolioService`, `WatchlistService`, `KanbanService`, `ChatService`, `ToastService`
- `src/app/layout/` — header, AI chat sidebar, toasts
- `src/app/pages/` — `markets`, `watchlists`, `portfolio` (heatmap, P&L chart, positions, kanban)
- `src/app/shared/` — watchlist table, trade bar, sparkline, line chart, price-flash directive, format pipes

Node: Angular 21 is used because Angular 22 requires Node >= 22.22.3.
