#!/usr/bin/env node

/**
 * Tradex notification MCP server (scaffolded with @modelcontextprotocol/create-server, then rewritten).
 * Tools: `send_email {to, subject, text, html?}` over SMTP (nodemailer) and `send_sms {to, text}` via a pluggable
 * SMS provider (SMS_PROVIDER, default "log": no real SMS, one log line each).
 *
 *   node build/index.js            Streamable HTTP on MCP_PORT (3001) at MCP_PATH (/mcp)  - default, used by Docker
 *   node build/index.js --stdio    stdio transport (MCP Inspector, desktop clients)
 *
 * SMTP: MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM, MAIL_STARTTLS (MAIL_SECURE for port 465).
 * HTTP mode also serves GET /health and GET /sms/outbox (last 100 SMS, masked numbers; internal port only).
 */

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createHttpServer } from "./http.js";
import { createSmtpTransport, describe, mailConfigFromEnv } from "./mailer.js";
import { createEmailServer } from "./server.js";
import { createSmsProvider, SmsOutbox } from "./sms.js";

// stdout belongs to the stdio protocol: always log to stderr
const log = (message: string) => console.error(`[email-mcp-server] ${message}`);

const config = mailConfigFromEnv();
const transport = createSmtpTransport(config);
// shared by every (stateless) MCP request
const sms = { provider: createSmsProvider(process.env, (line) => console.error(line)), outbox: new SmsOutbox(100) };
const factory = () => createEmailServer({ transport, from: config.from, sms, log });

const useStdio = process.argv.includes("--stdio") || process.env.MCP_TRANSPORT === "stdio";
const ready = `SMTP ${describe(config)}, SMS provider ${sms.provider.name}`;

if (useStdio) {
  await factory().connect(new StdioServerTransport());
  log(`stdio transport ready, ${ready}`);
} else {
  const port = Number.parseInt(process.env.MCP_PORT ?? "3001", 10);
  const path = process.env.MCP_PATH ?? "/mcp";
  const http = createHttpServer({ serverFactory: factory, path, log, smsOutbox: sms.outbox });
  http.listen(port, "0.0.0.0", () => log(`Streamable HTTP on :${port}${path}, ${ready}`));
  const stop = () => http.close(() => process.exit(0));
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);
}
