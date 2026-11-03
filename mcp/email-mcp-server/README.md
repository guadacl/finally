# email-mcp-server

FinAlly's email MCP server. It exposes one tool, `send_email`, and delivers the message over SMTP with
nodemailer to any SMTP provider (SendGrid, Brevo, Mailgun, ... or the bundled Mailpit). The FinAlly app calls it
as an MCP client (Java MCP SDK, Streamable HTTP) from the Camunda `email-notify` tasks when a Support / Resistance
card is created, deleted, or hits its target price.

```
Camunda kanban-card process ── email-notify job ──▶ app (EmailNotifyJobWorker → NotificationMailer → McpEmailGateway)
                                                         │  MCP tools/call send_email  (POST http://email-mcp:3001/mcp)
                                                         ▼
                                               email-mcp-server ── SMTP ──▶ Mailpit (dev) / real SMTP server
```

Scaffolded with `npx @modelcontextprotocol/create-server email-mcp-server` (create-server 0.3.1, which npm marks
as deprecated). The generated notes example (SDK 0.6, stdio only) was replaced: SDK 1.x `McpServer`, a single
tool, and Streamable HTTP as the default transport, with stdio still available.

## Tool

`send_email`

| Argument | Type | Required | Notes |
|---|---|---|---|
| `to` | string (email) | yes | recipient |
| `subject` | string | yes | 1–998 chars |
| `text` | string | yes | plain-text body |
| `html` | string | no | optional HTML alternative |

Result on success: text content `sent <messageId>` and `structuredContent`
`{ "messageId": "<…>", "accepted": ["…"], "rejected": [] }`.
SMTP failures are returned as a tool result with `isError: true` (text `SMTP error: …`), not as protocol errors.
Invalid arguments (e.g. a malformed address) are rejected before SMTP is contacted.

## Transports

| Mode | Command | Endpoint |
|---|---|---|
| Streamable HTTP (default, Docker) | `node build/index.js` | `POST http://<host>:3001/mcp` (stateless; `GET /health` for healthchecks) |
| stdio | `node build/index.js --stdio` (or `MCP_TRANSPORT=stdio`) | stdin/stdout |

Stateless HTTP: every POST gets its own server instance, so no sessions are kept; `GET`/`DELETE /mcp` answer 405.

## Configuration (environment)

| Variable | Default | |
|---|---|---|
| `MCP_PORT` / `MCP_PATH` | `3001` / `/mcp` | HTTP listener |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | SMTP server (compose: `mailpit` / `1025`) |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | empty | SMTP login (sent only when a username is set) |
| `MAIL_STARTTLS` | `false` | `true` → require STARTTLS (e.g. port 587) |
| `MAIL_SECURE` | `true` on port 465 | implicit TLS |
| `MAIL_FROM` | `FinAlly <alerts@finally.local>` | sender |

## Development

```bash
npm install
npm run build
npm test                      # vitest: tool via in-memory transport + a real Streamable HTTP round trip (SMTP mocked)
MAIL_HOST=localhost MAIL_PORT=1025 npm start
npm run inspector             # MCP Inspector over stdio
```

## Docker

`compose.email-mcp.yml` (included by the root `docker-compose.yml`) builds this directory as service `email-mcp`:
internal only (no host port), depends on `mailpit`, reads `MAIL_*` from the root `.env`, and has a healthcheck on
`/health`. Read the caught emails in Mailpit at http://localhost:8025.
