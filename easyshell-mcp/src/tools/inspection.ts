import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { idNumberParam } from "./params.js";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

export function registerInspectionTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "list_scheduled_tasks",
    "List all scheduled inspection tasks with their cron expressions, status, and last run time.",
    {},
    async () => {
      try {
        const result = await client.get("/api/v1/ai/scheduled-tasks");
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "trigger_inspection",
    "Manually trigger a scheduled inspection task to run immediately.",
    {
      taskId: idNumberParam("Scheduled task ID"),
    },
    {
      title: "Trigger inspection",
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openWorldHint: true,
    },
    async ({ taskId }: { taskId: number }) => {
      try {
        const result = await client.post(`/api/v1/ai/scheduled-tasks/${taskId}/run`);
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "get_inspect_reports",
    "Get AI-generated inspection reports with analysis results.",
    {
      page: z.number().default(0).optional(),
      size: z.number().default(20).optional(),
    },
    async ({ page, size }: { page?: number; size?: number }) => {
      try {
        const query: Record<string, string> = {};
        if (typeof page === "number") {
          query.page = String(page);
        }
        if (typeof size === "number") {
          query.size = String(size);
        }

        const result = await client.get("/api/v1/ai/inspect/reports", query);
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
