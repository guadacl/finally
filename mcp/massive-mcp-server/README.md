# Massive (Polygon.io) MCP server

Compose service `massive-mcp`: Massive's official MCP server
([`mcp_massive`](https://github.com/massive-com/mcp_massive), formerly `polygon-io/mcp_polygon`), pinned to
`v0.10.0`, served over **Streamable HTTP** at `http://massive-mcp:8000/mcp` (internal only, no host port).

Tradex uses it for the **watchlist search**: `GET /api/tickers/search?q=enphase` → `TickerSearchService` calls
the MCP tool `call_api` with `{path: "/v3/reference/tickers", params: {search, market: "stocks", active: "true",
limit: 100}}`, parses the CSV it returns and ranks the matches (exact ticker, ticker prefix, name prefix).

## Configuration

| Variable | Where | Default | Meaning |
|---|---|---|---|
| `MASSIVE_API_KEY` | root `.env` | — | Massive key (the same one used for market data). Without it the search answers 503. |
| `MASSIVE_MCP_URL` | app | `http://massive-mcp:8000/mcp` | Where the app finds this server. Blank disables the search. |
| `MASSIVE_MCP_TIMEOUT` | app | `15s` | Deadline of one search call. |
| `TICKER_SEARCH_CACHE_TTL` | app | `10m` | How long a query's results are reused (saves API calls on the free plan). |

## Why `serve.py`

`mcp_massive` creates its FastMCP server with the SDK defaults: it binds `127.0.0.1` and its DNS-rebinding
protection accepts only `localhost` Host headers, so another container could not reach it. `serve.py` sets the bind
address/port (`MCP_HOST`, `MCP_PORT`) and allows the compose host name (`MCP_ALLOWED_HOSTS=massive-mcp`) before
starting the server; the protection stays on. The image also pins the MCP Python SDK to 1.x (`mcp<2`): v0.10.0
imports `mcp.server.fastmcp`, which SDK 2.x removed.

## Check it

```bash
docker compose up -d --build massive-mcp
docker logs finally-massive-mcp        # "Uvicorn running on http://0.0.0.0:8000"
```
