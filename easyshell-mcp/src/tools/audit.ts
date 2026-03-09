import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

type QueryAuditLogsParams = {
  userId?: number;
  resourceType?: string;
  action?: string;
  page?: number;
  size?: number;
};

export function registerAuditTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "query_audit_logs",
    "Query audit logs for compliance and operational history. Can filter by user, resource type, or action.",
    {
      userId: z.number().optional(),
      resourceType: z.string().optional(),
      action: z.string().optional(),
      page: z.number().default(0).optional(),
      size: z.number().default(20).optional(),
    },
    async ({ userId, resourceType, action, page, size }: QueryAuditLogsParams) => {
      try {
        const query: Record<string, string> = {};
        if (typeof userId === "number") {
          query.userId = String(userId);
        }
        if (typeof resourceType === "string") {
          query.resourceType = resourceType;
        }
        if (typeof action === "string") {
          query.action = action;
        }
        if (typeof page === "number") {
          query.page = String(page);
        }
        if (typeof size === "number") {
          query.size = String(size);
        }

        const result = await client.get("/api/v1/audit/list", query);

        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "approve_task",
    "Approve a high-risk task that is pending approval.",
    {
      taskId: z.string().describe("Task ID to approve"),
    },
    async ({ taskId }: { taskId: string }) => {
      try {
        const result = await client.post(`/api/v1/ai/chat/approve/${taskId}`);
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
