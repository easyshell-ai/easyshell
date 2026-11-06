import { existsSync } from "node:fs";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { dirname, join } from "node:path";
import { homedir } from "node:os";
import process from "node:process";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import { EasyShellClient } from "./client.js";
import { loadConfig } from "./config.js";
import { registerHostTools } from "./tools/host.js";
import { registerScriptTools } from "./tools/script.js";
import { registerTaskTools } from "./tools/task.js";
import { registerClusterTools } from "./tools/cluster.js";
import { registerMonitoringTools } from "./tools/monitoring.js";
import { registerInspectionTools } from "./tools/inspection.js";
import { registerAiChatTools } from "./tools/ai-chat.js";
import { registerAuditTools } from "./tools/audit.js";
import { registerNotificationTools } from "./tools/notification.js";
import { registerTagTools } from "./tools/tag.js";
import { registerKnowledgeTools } from "./tools/knowledge.js";

type InstallArgs = {
  url: string;
  user: string;
  pass: string;
};

type InstallTarget =
  | { kind: "opencode"; path: string }
  | { kind: "cursor"; path: string }
  | { kind: "claude"; path: string };

type JsonObject = Record<string, unknown>;

function parseArgs(argv: string[]): Map<string, string | boolean> {
  const parsed = new Map<string, string | boolean>();
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) {
      continue;
    }

    const body = arg.slice(2);
    const eq = body.indexOf("=");
    if (eq > 0) {
      // --key=value form, unambiguous even when the value starts with "--"
      parsed.set(body.slice(0, eq), body.slice(eq + 1));
      continue;
    }

    const next = argv[i + 1];
    if (next && !next.startsWith("--")) {
      parsed.set(body, next);
      i += 1;
    } else {
      parsed.set(body, true);
    }
  }
  return parsed;
}

async function loadPackageVersion(): Promise<string> {
  const npmVersion = process.env.npm_package_version?.trim();
  if (npmVersion) {
    return npmVersion;
  }

  try {
    const fileUrl = new URL("../package.json", import.meta.url);
    const text = await readFile(fileUrl, "utf-8");
    const parsed = JSON.parse(text) as { version?: unknown };
    if (typeof parsed.version === "string" && parsed.version.trim()) {
      return parsed.version;
    }
  } catch {
    return "1.0.0";
  }

  return "1.0.0";
}

function usage(): string {
  return [
    "EasyShell MCP Server",
    "",
    "Usage:",
    "  easyshell-mcp                 Start MCP server over stdio",
    "  easyshell-mcp --port 3000     Start MCP server over HTTP (SSE + Streamable HTTP)",
    "  easyshell-mcp --install       Auto-install MCP config for supported AI clients",
    "  easyshell-mcp --help          Show this help",
    "  easyshell-mcp --version       Show version",
    "",
    "Server options:",
    "  --port            HTTP port number (enables SSE + Streamable HTTP transport)",
    "  --host            Bind address (default: 127.0.0.1)",
    "  --token           Bearer token required by HTTP clients (or MCP_AUTH_TOKEN)",
    "  --allow-remote    Permit binding to a non-loopback address (requires --token)",
    "  --allowed-origin  Origin allowed for browser CORS (repeatable, or MCP_ALLOWED_ORIGINS)",
    "",
    "Install options:",
    "  --url   EasyShell server URL (default: http://localhost:18080)",
    "  --user  EasyShell username (default: easyshell)",
    "  --pass  EasyShell password (required for install; prefer MCP_PASSWORD env)",
  ].join("\n");
}

function detectInstallTarget(cwd: string): InstallTarget {
  const openCodePath = join(cwd, "opencode.json");
  if (existsSync(openCodePath)) {
    return { kind: "opencode", path: openCodePath };
  }

  const cursorPath = join(cwd, ".cursor", "mcp.json");
  if (existsSync(cursorPath)) {
    return { kind: "cursor", path: cursorPath };
  }

  const platform = process.platform;
  const candidates: string[] = [];

  if (platform === "darwin") {
    candidates.push(join(homedir(), "Library", "Application Support", "Claude", "claude_desktop_config.json"));
  } else if (platform === "win32") {
    const appData = process.env.APPDATA;
    if (appData) {
      candidates.push(join(appData, "Claude", "claude_desktop_config.json"));
    }
  } else {
    candidates.push(join(homedir(), ".config", "Claude", "claude_desktop_config.json"));
  }

  for (const path of candidates) {
    if (existsSync(path)) {
      return { kind: "claude", path };
    }
  }

  return { kind: "opencode", path: openCodePath };
}

function parseInstallArgs(args: Map<string, string | boolean>): InstallArgs {
  const url = (typeof args.get("url") === "string" ? (args.get("url") as string) : "http://localhost:18080").trim();
  const user = (typeof args.get("user") === "string" ? (args.get("user") as string) : "easyshell").trim();
  // Prefer the environment variable: values passed on the command line are visible in `ps`.
  const envPass = process.env.MCP_PASSWORD?.trim() ?? "";
  const argPass = typeof args.get("pass") === "string" ? (args.get("pass") as string).trim() : "";
  const pass = envPass || argPass;

  if (!pass) {
    throw new Error("--pass or MCP_PASSWORD is required for --install");
  }

  if (!url.startsWith("http://") && !url.startsWith("https://")) {
    throw new Error("--url must start with http:// or https://");
  }

  return {
    url: url.replace(/\/+$/, ""),
    user,
    pass,
  };
}

async function readJsonFile(path: string): Promise<JsonObject> {
  if (!existsSync(path)) {
    return {};
  }

  const raw = await readFile(path, "utf-8");
  if (!raw.trim()) {
    return {};
  }

  const parsed = JSON.parse(raw) as unknown;
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error(`Invalid JSON object in ${path}`);
  }

  return parsed as JsonObject;
}

async function writeJsonFile(path: string, data: JsonObject): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  // The file contains the EasyShell password: keep it owner-only.
  await writeFile(path, `${JSON.stringify(data, null, 2)}\n`, { encoding: "utf-8", mode: 0o600 });
}

async function runInstall(args: Map<string, string | boolean>): Promise<void> {
  const parsed = parseInstallArgs(args);
  const target = detectInstallTarget(process.cwd());
  const config = await readJsonFile(target.path);

  const entry = {
    command: "npx",
    args: ["-y", "@easyshell/mcp-server"],
    env: {
      EASYSHELL_URL: parsed.url,
      EASYSHELL_USER: parsed.user,
      EASYSHELL_PASS: parsed.pass,
    },
  };

  if (target.kind === "opencode") {
    const mcp = (typeof config.mcp === "object" && config.mcp !== null && !Array.isArray(config.mcp)
      ? (config.mcp as JsonObject)
      : {}) as JsonObject;
    mcp.easyshell = {
      type: "local",
      ...entry,
    };
    config.mcp = mcp;
  } else {
    const mcpServers =
      typeof config.mcpServers === "object" && config.mcpServers !== null && !Array.isArray(config.mcpServers)
        ? (config.mcpServers as JsonObject)
        : {};
    mcpServers.easyshell = entry;
    config.mcpServers = mcpServers;
  }

  await writeJsonFile(target.path, config);
  try {
    await chmod(target.path, 0o600);
  } catch {
    // best effort - the file may pre-date this change with different ownership
  }

  console.log(`✅ EasyShell MCP configured for ${target.kind}.`);
  console.log(`Config file: ${target.path}`);
  console.log("Restart your AI client or reload MCP settings to apply changes.");
}

async function createMcpServer(): Promise<McpServer> {
  const config = loadConfig();
  const version = await loadPackageVersion();

  const server = new McpServer({
    name: "easyshell",
    version,
  });

  const client = new EasyShellClient(config);
  registerHostTools(server, client);
  registerScriptTools(server, client);
  registerTaskTools(server, client);
  registerClusterTools(server, client);
  registerMonitoringTools(server, client);
  registerInspectionTools(server, client);
  registerAiChatTools(server, client);
  registerAuditTools(server, client);
  registerNotificationTools(server, client);
  registerTagTools(server, client);
  registerKnowledgeTools(server, client);

  return server;
}

async function runServer(): Promise<void> {
  const server = await createMcpServer();
  const transport = new StdioServerTransport();
  await server.connect(transport);

  process.on("SIGINT", async () => {
    await server.close();
    process.exit(0);
  });
}

async function runHttpServer(
  port: number,
  host: string,
  authToken: string,
  allowedOrigins: string[],
): Promise<void> {
  const transports: Record<string, Transport> = {};
  const transportCreatedAt: Record<string, number> = {};

  const MAX_BODY_BYTES = 1024 * 1024; // 1 MiB
  const MAX_TRANSPORTS = 32;
  const TRANSPORT_IDLE_MS = 30 * 60 * 1000;

  const isLoopbackHost = host === "127.0.0.1" || host === "::1" || host === "localhost";
  if (!isLoopbackHost && !authToken) {
    // Binding to a non-loopback address with no authentication would expose a server that
    // holds the operator's EasyShell credentials to the whole network.
    throw new Error(
      "Refusing to listen on a non-loopback address without --token: set a bearer token or bind to 127.0.0.1",
    );
  }

  const expectedHosts = new Set<string>([host]);
  for (const origin of allowedOrigins) {
    try {
      expectedHosts.add(new URL(origin).host);
    } catch {
      // ignore malformed entries
    }
  }

  function pruneIdleTransports(): void {
    const now = Date.now();
    for (const sid of Object.keys(transports)) {
      if (now - (transportCreatedAt[sid] ?? now) > TRANSPORT_IDLE_MS) {
        try {
          void transports[sid].close();
        } catch {
          // ignore
        }
        delete transports[sid];
        delete transportCreatedAt[sid];
      }
    }
  }

  function setCorsHeaders(req: IncomingMessage, res: ServerResponse): void {
    const origin = req.headers.origin;
    if (typeof origin === "string" && allowedOrigins.includes(origin)) {
      res.setHeader("Access-Control-Allow-Origin", origin);
      res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
      res.setHeader("Access-Control-Allow-Headers", "Content-Type, mcp-session-id, Authorization");
      res.setHeader("Access-Control-Expose-Headers", "mcp-session-id");
      res.setHeader("Vary", "Origin");
    }
  }

  function sendJson(res: ServerResponse, status: number, body: unknown): void {
    const payload = JSON.stringify(body);
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(payload);
  }

  function unauthorized(res: ServerResponse): void {
    sendJson(res, 401, { error: "Unauthorized: bearer token required" });
  }

  function isAuthorized(req: IncomingMessage): boolean {
    if (!authToken) {
      // Loopback-only deployment without a token: still not open to the network.
      return true;
    }
    const header = req.headers.authorization;
    if (typeof header !== "string" || !header.startsWith("Bearer ")) {
      return false;
    }
    const provided = header.slice(7).trim();
    if (provided.length !== authToken.length) {
      return false;
    }
    let diff = 0;
    for (let i = 0; i < provided.length; i += 1) {
      diff |= provided.charCodeAt(i) ^ authToken.charCodeAt(i);
    }
    return diff === 0;
  }

  /**
   * Host header validation (DNS rebinding defence). The SDK option is deprecated, so the
   * check is done here where it cannot be bypassed by transport internals.
   */
  function isHostAllowed(req: IncomingMessage): boolean {
    const hostHeader = req.headers.host;
    if (typeof hostHeader !== "string" || hostHeader.trim().length === 0) {
      return false;
    }
    return expectedHosts.has(hostHeader.trim().toLowerCase());
  }

  function readBody(req: IncomingMessage): Promise<unknown> {
    return new Promise((resolve, reject) => {
      const chunks: Buffer[] = [];
      let size = 0;
      req.on("data", (chunk: Buffer) => {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          reject(new Error("Request body too large"));
          req.destroy();
          return;
        }
        chunks.push(chunk);
      });
      req.on("end", () => {
        const raw = Buffer.concat(chunks).toString("utf-8");
        if (!raw.trim()) {
          resolve(undefined);
          return;
        }
        try {
          resolve(JSON.parse(raw));
        } catch (err) {
          reject(err);
        }
      });
      req.on("error", reject);
    });
  }

  async function handleMcp(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const body = req.method === "POST" ? await readBody(req) : undefined;
    const sessionId = req.headers["mcp-session-id"] as string | undefined;
    let transport: StreamableHTTPServerTransport | undefined;

    if (sessionId && transports[sessionId]) {
      const existing = transports[sessionId];
      if (existing instanceof StreamableHTTPServerTransport) {
        transport = existing;
      } else {
        sendJson(res, 400, {
          jsonrpc: "2.0",
          error: { code: -32000, message: "Session uses a different transport protocol" },
          id: null,
        });
        return;
      }
    } else if (!sessionId && req.method === "POST" && isInitializeRequest(body)) {
      if (Object.keys(transports).length >= MAX_TRANSPORTS) {
        pruneIdleTransports();
      }
      if (Object.keys(transports).length >= MAX_TRANSPORTS) {
        sendJson(res, 503, { error: "Too many active sessions" });
        return;
      }

      transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (sid: string) => {
          transports[sid] = transport!;
          transportCreatedAt[sid] = Date.now();
        },
        // Belt and braces: the Host/Origin check above is the primary defence.
        enableDnsRebindingProtection: true,
        allowedHosts: Array.from(expectedHosts),
        allowedOrigins,
      });

      transport.onclose = () => {
        const sid = transport!.sessionId;
        if (sid && transports[sid]) {
          delete transports[sid];
          delete transportCreatedAt[sid];
        }
      };

      const server = await createMcpServer();
      await server.connect(transport);
    } else {
      sendJson(res, 400, {
        jsonrpc: "2.0",
        error: { code: -32000, message: "Bad Request: No valid session ID provided" },
        id: null,
      });
      return;
    }

    await transport.handleRequest(req, res, body);
  }

  // Legacy SSE transport (protocol version 2024-11-05)
  async function handleSse(_req: IncomingMessage, res: ServerResponse): Promise<void> {
    if (Object.keys(transports).length >= MAX_TRANSPORTS) {
      pruneIdleTransports();
    }
    if (Object.keys(transports).length >= MAX_TRANSPORTS) {
      sendJson(res, 503, { error: "Too many active sessions" });
      return;
    }

    const transport = new SSEServerTransport("/messages", res);
    transports[transport.sessionId] = transport;
    transportCreatedAt[transport.sessionId] = Date.now();

    res.on("close", () => {
      delete transports[transport.sessionId];
      delete transportCreatedAt[transport.sessionId];
    });

    const server = await createMcpServer();
    await server.connect(transport);
  }

  async function handleMessages(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
    const sessionId = url.searchParams.get("sessionId") ?? undefined;

    if (!sessionId || !transports[sessionId]) {
      sendJson(res, 400, {
        jsonrpc: "2.0",
        error: { code: -32000, message: "No transport found for sessionId" },
        id: null,
      });
      return;
    }

    const existing = transports[sessionId];
    if (!(existing instanceof SSEServerTransport)) {
      sendJson(res, 400, {
        jsonrpc: "2.0",
        error: { code: -32000, message: "Session uses a different transport protocol" },
        id: null,
      });
      return;
    }

    const body = await readBody(req);
    await existing.handlePostMessage(req, res, body);
  }

  const httpServer = createServer(async (req, res) => {
    setCorsHeaders(req, res);

    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    const pathname = (req.url ?? "/").split("?")[0];

    try {
      if (pathname === "/health" && req.method === "GET") {
        sendJson(res, 200, { status: "ok" });
        return;
      }

      // Everything below carries EasyShell credentials, so it is authenticated and
      // restricted to expected Host headers.
      if (!isHostAllowed(req)) {
        sendJson(res, 421, { error: "Misdirected Request: unexpected Host header" });
        return;
      }
      if (!isAuthorized(req)) {
        unauthorized(res);
        return;
      }

      if (pathname === "/mcp") {
        await handleMcp(req, res);
      } else if (pathname === "/sse" && req.method === "GET") {
        await handleSse(req, res);
      } else if (pathname === "/messages" && req.method === "POST") {
        await handleMessages(req, res);
      } else {
        sendJson(res, 404, { error: "Not found" });
      }
    } catch (err) {
      if (!res.headersSent) {
        sendJson(res, err instanceof Error && err.message === "Request body too large" ? 413 : 500, {
          jsonrpc: "2.0",
          error: {
            code: -32603,
            message: err instanceof Error && err.message === "Request body too large"
              ? "Request body too large"
              : "Internal server error",
          },
          id: null,
        });
      }
    }
  });

  const idleSweeper = setInterval(pruneIdleTransports, 60 * 1000);
  idleSweeper.unref?.();

  httpServer.listen(port, host, () => {
    console.log(`EasyShell MCP server listening on http://${host}:${port}`);
    console.log("");
    console.log("Transport endpoints:");
    console.log(`  Streamable HTTP  POST/GET/DELETE  http://${host}:${port}/mcp`);
    console.log(`  Legacy SSE       GET              http://${host}:${port}/sse`);
    console.log(`  Legacy SSE       POST             http://${host}:${port}/messages?sessionId=<id>`);
    console.log(`  Health check     GET              http://${host}:${port}/health`);
  });

  process.on("SIGINT", async () => {
    clearInterval(idleSweeper);
    for (const sid of Object.keys(transports)) {
      try {
        await transports[sid].close();
      } catch {
        // empty — intentional: ignore close errors during shutdown
      }
      delete transports[sid];
    }

    httpServer.close(() => {
      process.exit(0);
    });
  });
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));

  if (args.has("help")) {
    console.log(usage());
    return;
  }

  if (args.has("version")) {
    console.log(await loadPackageVersion());
    return;
  }

  if (args.has("install")) {
    await runInstall(args);
    return;
  }

  if (args.has("port")) {
    const portValue = args.get("port");
    const port = typeof portValue === "string" ? Number.parseInt(portValue, 10) : 3000;
    if (Number.isNaN(port) || port < 1 || port > 65535) {
      console.error("--port must be a valid port number (1-65535)");
      process.exit(1);
    }
    const hostValue = args.get("host");
    const host = typeof hostValue === "string" ? hostValue : "127.0.0.1";

    const isLoopback = host === "127.0.0.1" || host === "::1" || host === "localhost";
    if (!isLoopback && args.get("allow-remote") !== true) {
      console.error(
        `Refusing to bind to ${host}: pass --allow-remote (together with --token) to expose the HTTP transport`,
      );
      process.exit(1);
    }

    const tokenValue = args.get("token");
    const authToken =
      (typeof tokenValue === "string" && tokenValue.trim()) ||
      process.env.MCP_AUTH_TOKEN?.trim() ||
      "";
    if (!isLoopback && !authToken) {
      console.error("A bearer token is required for non-loopback bindings (--token or MCP_AUTH_TOKEN)");
      process.exit(1);
    }

    const origins = new Set<string>();
    const originArg = args.get("allowed-origin");
    if (typeof originArg === "string") {
      origins.add(originArg);
    }
    for (const value of (process.env.MCP_ALLOWED_ORIGINS ?? "").split(",")) {
      if (value.trim()) {
        origins.add(value.trim());
      }
    }
    const normalizedOrigins: string[] = [];
    for (const origin of origins) {
      try {
        normalizedOrigins.push(new URL(origin).origin);
      } catch {
        console.error(`Ignoring invalid --allowed-origin value: ${origin}`);
      }
    }

    await runHttpServer(port, host, authToken, normalizedOrigins);
    return;
  }

  await runServer();
}

void main().catch((error: unknown) => {
  if (error instanceof Error) {
    console.error(error.message);
  } else {
    console.error("Unexpected error");
  }
  process.exit(1);
});
