import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { idParam } from "./params.js";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

export function registerMonitoringTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "get_dashboard_stats",
    "Get platform-wide dashboard statistics: host counts, online rates, average resource usage, task success rates, and recent tasks.",
    {},
    async () => {
      try {
        const result = await client.get("/api/v1/host/dashboard/stats");
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "get_host_metrics",
    "Get historical CPU, memory, and disk metrics for a specific host.",
    {
      hostId: idParam("The agent/host ID"),
      range: z.enum(["1h", "6h", "24h", "7d", "30d"]).default("1h").optional().describe("Time range"),
    },
    async ({ hostId, range }: { hostId: string; range?: "1h" | "6h" | "24h" | "7d" | "30d" }) => {
      try {
        const query = typeof range === "string" ? { range } : undefined;
        const result = await client.get(`/api/v1/host/${hostId}/metrics`, query);
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
