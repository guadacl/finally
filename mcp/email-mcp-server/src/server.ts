import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { MailTransport } from "./mailer.js";

export const SERVER_NAME = "email-mcp-server";
export const SERVER_VERSION = "0.2.0";

/** Input schema of the send_email tool. */
export const sendEmailInput = {
  to: z.string().email().describe("Recipient email address"),
  subject: z.string().min(1).max(998).describe("Subject line"),
  text: z.string().min(1).describe("Plain-text body"),
  html: z.string().optional().describe("Optional HTML body (sent as multipart/alternative with the text)"),
};

export interface ServerOptions {
  transport: MailTransport;
  from: string;
  log?: (message: string) => void;
}

/**
 * MCP server with exactly one tool, `send_email`. On success the result text is `sent <messageId>` and
 * `structuredContent` is `{ messageId, accepted, rejected }`; SMTP failures come back as `isError: true`
 * results (never as protocol errors), so callers can log and continue.
 */
export function createEmailServer({ transport, from, log = () => {} }: ServerOptions): McpServer {
  const server = new McpServer({ name: SERVER_NAME, version: SERVER_VERSION });

  server.registerTool(
    "send_email",
    {
      title: "Send email",
      description: "Send an email over SMTP. Returns the SMTP message id.",
      inputSchema: sendEmailInput,
      outputSchema: {
        messageId: z.string(),
        accepted: z.array(z.string()),
        rejected: z.array(z.string()),
      },
    },
    async ({ to, subject, text, html }) => {
      try {
        const info = await transport.sendMail({ from, to, subject, text, ...(html ? { html } : {}) });
        const messageId = info.messageId ?? "";
        const accepted = (info.accepted ?? []).map(String);
        const rejected = (info.rejected ?? []).map(String);
        log(`sent "${subject}" to ${to} (${messageId})`);
        return {
          content: [{ type: "text", text: `sent ${messageId}` }],
          structuredContent: { messageId, accepted, rejected },
        };
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        log(`send_email to ${to} failed: ${message}`);
        return {
          isError: true,
          content: [{ type: "text", text: `SMTP error: ${message}` }],
        };
      }
    },
  );

  return server;
}
