import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { createEmailServer } from "../src/server.js";
import { createHttpServer } from "../src/http.js";
import type { MailTransport } from "../src/mailer.js";
import {
  createSmsProvider,
  LogSmsProvider,
  maskPhone,
  SmsOutbox,
  truncateSms,
  UnsupportedSmsProvider,
} from "../src/sms.js";

const smtp: MailTransport = { sendMail: vi.fn(async () => ({ messageId: "<m>" })) };

function setup() {
  const lines: string[] = [];
  const outbox = new SmsOutbox(100);
  const sms = { provider: new LogSmsProvider((l) => lines.push(l)), outbox };
  return { lines, outbox, sms };
}

async function client(sms: ReturnType<typeof setup>["sms"]) {
  const server = createEmailServer({ transport: smtp, from: "Tradex <a@b.co>", sms });
  const [c, s] = InMemoryTransport.createLinkedPair();
  await server.connect(s);
  const cl = new Client({ name: "t", version: "1" });
  await cl.connect(c);
  return cl;
}

describe("send_sms tool", () => {
  it("is listed next to send_email with its schema", async () => {
    const cl = await client(setup().sms);
    const { tools } = await cl.listTools();
    expect(tools.map((t) => t.name).sort()).toEqual(["send_email", "send_sms"]);
    const schema = tools.find((t) => t.name === "send_sms")!.inputSchema as { required?: string[] };
    expect(schema.required?.sort()).toEqual(["text", "to"]);
  });

  it("log provider writes one masked log line and keeps the message in the outbox", async () => {
    const { lines, outbox, sms } = setup();
    const cl = await client(sms);
    const result = await cl.callTool({
      name: "send_sms",
      arguments: { to: "+32470123456", text: "Tradex: AAPL hit Support 185.00 (now 184.97)" },
    });
    expect(result.isError).toBeFalsy();
    const structured = result.structuredContent as { messageId: string; provider: string; truncated: boolean };
    expect(structured.provider).toBe("log");
    expect(structured.truncated).toBe(false);
    expect(structured.messageId).toMatch(/^log-/);
    expect(lines).toEqual(["[sms] to +3247*****56 : Tradex: AAPL hit Support 185.00 (now 184.97)"]);
    expect(lines[0]).not.toContain("+32470123456");
    expect(outbox.list()).toEqual([
      expect.objectContaining({ to: "+3247*****56", text: "Tradex: AAPL hit Support 185.00 (now 184.97)", provider: "log" }),
    ]);
  });

  it("rejects numbers that are not E.164 without sending", async () => {
    const { lines, sms } = setup();
    const cl = await client(sms);
    for (const to of ["0470123456", "+0470123456", "+12345", "32470123456", "+32 470 12 34 56"]) {
      const result = await cl
        .callTool({ name: "send_sms", arguments: { to, text: "x" } })
        .catch((e: Error) => ({ isError: true, content: [{ type: "text", text: e.message }] }));
      expect(result.isError, to).toBe(true);
    }
    expect(lines).toEqual([]);
  });

  it("truncates texts longer than 160 characters with …", async () => {
    const { outbox, sms } = setup();
    const cl = await client(sms);
    const result = await cl.callTool({ name: "send_sms", arguments: { to: "+14155550100", text: "y".repeat(300) } });
    expect((result.structuredContent as { truncated: boolean }).truncated).toBe(true);
    const text = outbox.list()[0].text;
    expect(text).toHaveLength(160);
    expect(text.endsWith("…")).toBe(true);
  });

  it("reports an unimplemented provider as isError", async () => {
    const outbox = new SmsOutbox();
    const cl = await client({ provider: new UnsupportedSmsProvider("twilio"), outbox });
    const result = await cl.callTool({ name: "send_sms", arguments: { to: "+32470123456", text: "x" } });
    expect(result.isError).toBe(true);
    const content = result.content as Array<{ type: string; text: string }>;
    expect(content[0].text).toContain('SMS_PROVIDER "twilio" is not implemented');
    expect(outbox.list()).toEqual([]);
  });
});

describe("sms helpers", () => {
  it("masks the middle digits", () => {
    expect(maskPhone("+32470123456")).toBe("+3247*****56");
    expect(maskPhone("+14155550100")).toBe("+1415*****00");
  });

  it("truncates to 160", () => {
    expect(truncateSms("short")).toBe("short");
    expect(truncateSms("z".repeat(161))).toHaveLength(160);
  });

  it("picks the provider from SMS_PROVIDER (log by default)", () => {
    expect(createSmsProvider({}).name).toBe("log");
    expect(createSmsProvider({ SMS_PROVIDER: "LOG" }).name).toBe("log");
    expect(createSmsProvider({ SMS_PROVIDER: "brevo" })).toBeInstanceOf(UnsupportedSmsProvider);
  });

  it("outbox keeps the newest N", () => {
    const box = new SmsOutbox(2);
    for (const i of [1, 2, 3]) box.add({ id: `${i}`, to: "x", text: `${i}`, provider: "log", createdAt: "" });
    expect(box.list().map((e) => e.id)).toEqual(["3", "2"]);
  });
});

describe("GET /sms/outbox", () => {
  let http: Server;
  let base: string;
  const { sms } = setup();

  beforeEach(async () => {
    http = createHttpServer({
      serverFactory: () => createEmailServer({ transport: smtp, from: "a@b.co", sms }),
      smsOutbox: sms.outbox,
    });
    await new Promise<void>((r) => http.listen(0, "127.0.0.1", () => r()));
    base = `http://127.0.0.1:${(http.address() as AddressInfo).port}`;
  });

  afterEach(async () => {
    await new Promise<void>((r) => http.close(() => r()));
  });

  it("shows SMS sent over Streamable HTTP and is read-only", async () => {
    const cl = new Client({ name: "http", version: "1" });
    await cl.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`)));
    await cl.callTool({ name: "send_sms", arguments: { to: "+32470123456", text: "Tradex: new Support card AAPL @ 185.00" } });
    await cl.close();

    const res = await fetch(`${base}/sms/outbox`);
    expect(res.status).toBe(200);
    const body = (await res.json()) as { count: number; messages: Array<{ to: string; text: string }> };
    expect(body.count).toBe(1);
    expect(body.messages[0]).toMatchObject({ to: "+3247*****56", text: "Tradex: new Support card AAPL @ 185.00" });
    expect((await fetch(`${base}/sms/outbox`, { method: "DELETE" })).status).toBe(405);
  });
});
