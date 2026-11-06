import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { createEmailServer } from "../src/server.js";
import { createHttpServer } from "../src/http.js";
import { mailConfigFromEnv, type MailTransport } from "../src/mailer.js";

const FROM = "Tradex <alerts@finally.local>";

function mockTransport(): MailTransport & { sendMail: ReturnType<typeof vi.fn> } {
  return {
    sendMail: vi.fn(async (msg: { to: string }) => ({ messageId: "<abc@finally>", accepted: [msg.to], rejected: [] })),
  };
}

async function inMemoryClient(transport: MailTransport) {
  const server = createEmailServer({ transport, from: FROM });
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await server.connect(serverSide);
  const client = new Client({ name: "test", version: "1.0.0" });
  await client.connect(clientSide);
  return client;
}

describe("send_email tool", () => {
  it("is the only tool and declares its schema", async () => {
    const client = await inMemoryClient(mockTransport());
    const { tools } = await client.listTools();
    expect(tools.map((t) => t.name)).toEqual(["send_email"]);
    const schema = tools[0].inputSchema as { required?: string[]; properties: Record<string, unknown> };
    expect(schema.required?.sort()).toEqual(["subject", "text", "to"]);
    expect(Object.keys(schema.properties).sort()).toEqual(["html", "subject", "text", "to"]);
  });

  it("sends via SMTP and returns the message id", async () => {
    const smtp = mockTransport();
    const client = await inMemoryClient(smtp);
    const result = await client.callTool({
      name: "send_email",
      arguments: { to: "demo@finally.local", subject: "Tradex alert: AAPL hit Support 185.00", text: "body" },
    });
    expect(result.isError).toBeFalsy();
    expect(result.structuredContent).toEqual({ messageId: "<abc@finally>", accepted: ["demo@finally.local"], rejected: [] });
    expect(smtp.sendMail).toHaveBeenCalledWith({
      from: FROM,
      to: "demo@finally.local",
      subject: "Tradex alert: AAPL hit Support 185.00",
      text: "body",
    });
  });

  it("passes the optional html body", async () => {
    const smtp = mockTransport();
    const client = await inMemoryClient(smtp);
    await client.callTool({ name: "send_email", arguments: { to: "a@b.co", subject: "s", text: "t", html: "<p>t</p>" } });
    expect(smtp.sendMail.mock.calls[0][0].html).toBe("<p>t</p>");
  });

  it("reports SMTP failures as isError results", async () => {
    const smtp = mockTransport();
    smtp.sendMail.mockRejectedValueOnce(new Error("connect ECONNREFUSED"));
    const client = await inMemoryClient(smtp);
    const result = await client.callTool({ name: "send_email", arguments: { to: "a@b.co", subject: "s", text: "t" } });
    expect(result.isError).toBe(true);
    expect(JSON.stringify(result.content)).toContain("ECONNREFUSED");
  });

  it("rejects an invalid recipient without calling SMTP", async () => {
    const smtp = mockTransport();
    const client = await inMemoryClient(smtp);
    const result = await client
      .callTool({ name: "send_email", arguments: { to: "not-an-email", subject: "s", text: "t" } })
      .catch((e: Error) => ({ isError: true, content: [{ type: "text", text: e.message }] }));
    expect(result.isError).toBe(true);
    expect(smtp.sendMail).not.toHaveBeenCalled();
  });
});

describe("Streamable HTTP endpoint", () => {
  let http: Server;
  let base: string;
  const smtp = mockTransport();

  beforeEach(async () => {
    http = createHttpServer({ serverFactory: () => createEmailServer({ transport: smtp, from: FROM }) });
    await new Promise<void>((r) => http.listen(0, "127.0.0.1", () => r()));
    base = `http://127.0.0.1:${(http.address() as AddressInfo).port}`;
  });

  afterEach(async () => {
    await new Promise<void>((r) => http.close(() => r()));
  });

  it("serves /health", async () => {
    const res = await fetch(`${base}/health`);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: "UP" });
  });

  it("handles a full client session over POST /mcp", async () => {
    const client = new Client({ name: "http-test", version: "1.0.0" });
    await client.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`)));
    const result = await client.callTool({ name: "send_email", arguments: { to: "demo@finally.local", subject: "s", text: "t" } });
    expect(result.isError).toBeFalsy();
    expect((result.structuredContent as { messageId: string }).messageId).toBe("<abc@finally>");
    await client.close();
  });

  it("answers GET /mcp with 405 (stateless)", async () => {
    expect((await fetch(`${base}/mcp`)).status).toBe(405);
  });
});

describe("mail config", () => {
  it("reads the MAIL_* variables", () => {
    const c = mailConfigFromEnv({ MAIL_HOST: "smtp.example.com", MAIL_PORT: "587", MAIL_USERNAME: "u", MAIL_PASSWORD: "p", MAIL_STARTTLS: "true" });
    expect(c).toMatchObject({ host: "smtp.example.com", port: 587, username: "u", password: "p", startTls: true, secure: false });
  });

  it("defaults to Mailpit-style local SMTP", () => {
    expect(mailConfigFromEnv({})).toMatchObject({ host: "localhost", port: 1025, startTls: false, from: FROM });
  });
});
