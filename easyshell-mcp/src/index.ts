import { existsSync } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
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

    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith("--")) {
      parsed.set(key, next);
      i += 1;
    } else {
      parsed.set(key, true);
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
    "  --port  HTTP port number (enables SSE + Streamable HTTP transport)",
    "  --host  Bind address (default: 127.0.0.1, use 0.0.0.0 for remote access)",
    "",
    "Install options:",
    "  --url   EasyShell server URL (default: http://localhost:18080)",
    "  --user  EasyShell username (default: easyshell)",
    "  --pass  EasyShell password (required for install)",
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
  const pass = typeof args.get("pass") === "string" ? (args.get("pass") as string) : "";

  if (!pass.trim()) {
    throw new Error("--pass is required for --install");
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
  await writeFile(path, `${JSON.stringify(data, null, 2)}\n`, "utf-8");
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

async function runHttpServer(port: number, host: string): Promise<void> {
  const transports: Record<string, Transport> = {};

  function setCorsHeaders(res: ServerResponse): void {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type, mcp-session-id");
    res.setHeader("Access-Control-Expose-Headers", "mcp-session-id");
  }

  function sendJson(res: ServerResponse, status: number, body: unknown): void {
    const payload = JSON.stringify(body);
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(payload);
  }

  function readBody(req: IncomingMessage): Promise<unknown> {
    return new Promise((resolve, reject) => {
      const chunks: Buffer[] = [];
      req.on("data", (chunk: Buffer) => chunks.push(chunk));
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
      transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (sid: string) => {
          transports[sid] = transport!;
        },
      });

      transport.onclose = () => {
        const sid = transport!.sessionId;
        if (sid && transports[sid]) {
          delete transports[sid];
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
    const transport = new SSEServerTransport("/messages", res);
    transports[transport.sessionId] = transport;

    res.on("close", () => {
      delete transports[transport.sessionId];
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
    setCorsHeaders(res);

    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    const pathname = (req.url ?? "/").split("?")[0];

    try {
      if (pathname === "/health" && req.method === "GET") {
        sendJson(res, 200, { status: "ok" });
      } else if (pathname === "/mcp") {
        await handleMcp(req, res);
      } else if (pathname === "/sse" && req.method === "GET") {
        await handleSse(req, res);
      } else if (pathname === "/messages" && req.method === "POST") {
        await handleMessages(req, res);
      } else {
        sendJson(res, 404, { error: "Not found" });
      }
    } catch {
      if (!res.headersSent) {
        sendJson(res, 500, {
          jsonrpc: "2.0",
          error: { code: -32603, message: "Internal server error" },
          id: null,
        });
      }
    }
  });

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
    await runHttpServer(port, host);
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
