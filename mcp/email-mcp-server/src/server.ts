import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { MailTransport } from "./mailer.js";
import { E164, maskPhone, SmsOutbox, type SmsProvider, truncateSms } from "./sms.js";

export const SERVER_NAME = "email-mcp-server";
export const SERVER_VERSION = "0.3.0";

/** Input schema of the send_email tool. */
export const sendEmailInput = {
  to: z.string().email().describe("Recipient email address"),
  subject: z.string().min(1).max(998).describe("Subject line"),
  text: z.string().min(1).describe("Plain-text body"),
  html: z.string().optional().describe("Optional HTML body (sent as multipart/alternative with the text)"),
};

/** Input schema of the send_sms tool. */
export const sendSmsInput = {
  to: z.string().regex(E164, "must be an E.164 number, e.g. +32470123456").describe("Recipient, E.164 (+32470123456)"),
  text: z.string().min(1).describe("Message text; longer than 160 characters is truncated with …"),
};

export interface ServerOptions {
  transport: MailTransport;
  from: string;
  /** Optional so email-only setups (and older tests) keep working; send_sms is registered when given. */
  sms?: { provider: SmsProvider; outbox: SmsOutbox };
  log?: (message: string) => void;
}

/**
 * The notification MCP server: tools `send_email` (SMTP) and `send_sms` (pluggable provider, default: log only).
 * On success the result text is `sent <messageId>` with `structuredContent`; delivery failures come back as
 * `isError: true` results (never as protocol errors), so callers can log and continue.
 */
export function createEmailServer({ transport, from, sms, log = () => {} }: ServerOptions): McpServer {
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

  if (sms) {
    server.registerTool(
      "send_sms",
      {
        title: "Send SMS",
        description:
          "Send a text message (max 160 characters, longer texts are truncated with …) to an E.164 number. " +
          `Provider: ${sms.provider.name}. Returns the provider message id.`,
        inputSchema: sendSmsInput,
        outputSchema: {
          messageId: z.string(),
          provider: z.string(),
          truncated: z.boolean(),
        },
      },
      async ({ to, text }) => {
        const body = truncateSms(text);
        try {
          const result = await sms.provider.send(to, body);
          sms.outbox.add({
            id: result.messageId,
            to: maskPhone(to),
            text: body,
            provider: result.provider,
            createdAt: new Date().toISOString(),
          });
          return {
            content: [{ type: "text", text: `sent ${result.messageId}` }],
            structuredContent: { messageId: result.messageId, provider: result.provider, truncated: body !== text },
          };
        } catch (err) {
          const message = err instanceof Error ? err.message : String(err);
          log(`send_sms to ${maskPhone(to)} failed: ${message}`);
          return { isError: true, content: [{ type: "text", text: `SMS error: ${message}` }] };
        }
      },
    );
  }

  return server;
}
