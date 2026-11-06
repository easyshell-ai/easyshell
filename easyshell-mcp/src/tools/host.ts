import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { idParam } from "./params.js";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function getHostStatusValue(host: unknown): number | undefined {
  if (!isRecord(host)) {
    return undefined;
  }

  const status = host["status"];
  return typeof status === "number" ? status : undefined;
}

export function registerHostTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "list_hosts",
    "List all managed hosts with their status, IP, OS, and resource usage. Use this to find target hosts for script execution.",
    {
      status: z
        .enum(["online", "offline", "all"])
        .optional()
        .describe("Filter by status"),
    },
    async ({ status }: { status?: "online" | "offline" | "all" }) => {
      try {
        const result = await client.get("/api/v1/host/list");
        const hosts = Array.isArray(result) ? result : [];

        const filteredHosts =
          status === "online"
            ? hosts.filter((host) => getHostStatusValue(host) === 1)
            : status === "offline"
              ? hosts.filter((host) => getHostStatusValue(host) === 0)
              : hosts;

        return {
          content: [
            { type: "text" as const, text: JSON.stringify(filteredHosts, null, 2) },
          ],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "get_host_detail",
    "Get detailed information about a specific host including tags, metrics, and system info.",
    {
      hostId: idParam("The agent/host ID"),
    },
    async ({ hostId }: { hostId: string }) => {
      try {
        const result = await client.get(`/api/v1/host/${hostId}`);
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
