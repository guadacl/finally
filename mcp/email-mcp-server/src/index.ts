#!/usr/bin/env node

/**
 * FinAlly email MCP server (scaffolded with @modelcontextprotocol/create-server, then rewritten).
 * One tool, `send_email {to, subject, text, html?}`, delivered over SMTP with nodemailer.
 *
 *   node build/index.js            Streamable HTTP on MCP_PORT (3001) at MCP_PATH (/mcp)  - default, used by Docker
 *   node build/index.js --stdio    stdio transport (MCP Inspector, desktop clients)
 *
 * SMTP: MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM, MAIL_STARTTLS (MAIL_SECURE for port 465).
 */

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createHttpServer } from "./http.js";
import { createSmtpTransport, describe, mailConfigFromEnv } from "./mailer.js";
import { createEmailServer } from "./server.js";

// stdout belongs to the stdio protocol: always log to stderr
const log = (message: string) => console.error(`[email-mcp-server] ${message}`);

const config = mailConfigFromEnv();
const transport = createSmtpTransport(config);
const factory = () => createEmailServer({ transport, from: config.from, log });

const useStdio = process.argv.includes("--stdio") || process.env.MCP_TRANSPORT === "stdio";

if (useStdio) {
  await factory().connect(new StdioServerTransport());
  log(`stdio transport ready, SMTP ${describe(config)}`);
} else {
  const port = Number.parseInt(process.env.MCP_PORT ?? "3001", 10);
  const path = process.env.MCP_PATH ?? "/mcp";
  const http = createHttpServer({ serverFactory: factory, path, log });
  http.listen(port, "0.0.0.0", () => log(`Streamable HTTP on :${port}${path}, SMTP ${describe(config)}`));
  const stop = () => http.close(() => process.exit(0));
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);
}
