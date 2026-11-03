import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

export interface HttpOptions {
  /** Builds a fresh MCP server per request (stateless Streamable HTTP). */
  serverFactory: () => McpServer;
  path?: string;
  log?: (message: string) => void;
}

const MAX_BODY = 1024 * 1024;

function readJson(req: IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error("request body too large"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : undefined);
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(body));
}

/**
 * Streamable HTTP endpoint (default `POST /mcp`) in stateless mode: every POST gets its own server + transport,
 * so no session state is kept and the container can restart at any time. `GET /health` is the healthcheck.
 */
export function createHttpServer({ serverFactory, path = "/mcp", log = () => {} }: HttpOptions): Server {
  return createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    if (url.pathname === "/health" && req.method === "GET") {
      json(res, 200, { status: "UP" });
      return;
    }
    if (url.pathname !== path) {
      json(res, 404, { error: "NOT_FOUND", message: `use ${path}` });
      return;
    }
    if (req.method !== "POST") {
      // stateless server: no standalone SSE stream (GET) and no sessions to delete (DELETE)
      json(res, 405, { jsonrpc: "2.0", error: { code: -32000, message: "Method not allowed" }, id: null });
      return;
    }
    let body: unknown;
    try {
      body = await readJson(req);
    } catch (e) {
      json(res, 400, { jsonrpc: "2.0", error: { code: -32700, message: "Parse error" }, id: null });
      return;
    }
    const server = serverFactory();
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on("close", () => {
      void transport.close();
      void server.close();
    });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, body);
    } catch (e) {
      log(`MCP request failed: ${e instanceof Error ? e.message : String(e)}`);
      if (!res.headersSent) {
        json(res, 500, { jsonrpc: "2.0", error: { code: -32603, message: "Internal server error" }, id: null });
      }
    }
  });
}
