import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { idParam, pageParam, sizeParam } from "./params.js";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

export function registerTaskTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "get_task_detail",
    "Get detailed task execution results including per-host job status, output, and timing. Use after execute_script to check results.",
    {
      taskId: idParam("The task ID, e.g. task_1a2b3c4d5e6f7890"),
    },
    async ({ taskId }: { taskId: string }) => {
      try {
        const result = await client.get(`/api/v1/task/${taskId}`);
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "list_recent_tasks",
    "List recent tasks with optional status filter and pagination.",
    {
      status: z
        .number()
        .optional()
        .describe("Filter: 0=pending, 1=running, 2=success, 3=partial, 4=failed"),
      page: pageParam(),
      size: sizeParam(),
    },
    async ({ status, page, size }: { status?: number; page?: number; size?: number }) => {
      try {
        const query: Record<string, string> = {};
        if (typeof status === "number") {
          query.status = String(status);
        }
        if (typeof page === "number") {
          query.page = String(page);
        }
        if (typeof size === "number") {
          query.size = String(size);
        }

        const result = await client.get("/api/v1/task/page", query);

        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
