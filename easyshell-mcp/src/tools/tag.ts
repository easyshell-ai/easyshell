import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

type CreateTagParams = { name: string; color?: string; description?: string };
type AssignTagParams = { tagId: number; agentId: string };

export function registerTagTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "manage_tags",
    "Manage host tags: list all tags, create a new tag, delete a tag, assign a tag to a host, or remove a tag from a host. Use the 'action' parameter to choose the operation.",
    {
      action: z
        .enum(["list", "create", "delete", "assign", "unassign"])
        .describe("Tag operation to perform"),
      name: z.string().optional().describe("Tag name (for create)"),
      color: z.string().optional().describe("Tag color hex (for create)"),
      description: z.string().optional().describe("Tag description (for create)"),
      tagId: z.number().optional().describe("Tag ID (for delete, assign, unassign)"),
      agentId: z
        .string()
        .optional()
        .describe("Host/agent ID (for assign, unassign, or list tags for a host)"),
    },
    async ({
      action,
      name,
      color,
      description,
      tagId,
      agentId,
    }: {
      action: "list" | "create" | "delete" | "assign" | "unassign";
      name?: string;
      color?: string;
      description?: string;
      tagId?: number;
      agentId?: string;
    }) => {
      try {
        let result: unknown;

        switch (action) {
          case "list": {
            if (typeof agentId === "string") {
              result = await client.get(`/api/v1/tag/agent/${agentId}`);
            } else {
              result = await client.get("/api/v1/tag/list");
            }
            break;
          }
          case "create": {
            if (!name) {
              return {
                content: [{ type: "text" as const, text: "Error: 'name' is required for create" }],
                isError: true,
              };
            }
            const body: CreateTagParams = { name };
            if (color) body.color = color;
            if (description) body.description = description;
            result = await client.post("/api/v1/tag", body);
            break;
          }
          case "delete": {
            if (typeof tagId !== "number") {
              return {
                content: [{ type: "text" as const, text: "Error: 'tagId' is required for delete" }],
                isError: true,
              };
            }
            result = await client.delete(`/api/v1/tag/${tagId}`);
            break;
          }
          case "assign": {
            if (typeof tagId !== "number" || typeof agentId !== "string") {
              return {
                content: [
                  {
                    type: "text" as const,
                    text: "Error: 'tagId' and 'agentId' are required for assign",
                  },
                ],
                isError: true,
              };
            }
            result = await client.post(`/api/v1/tag/${tagId}/agent/${agentId}`);
            break;
          }
          case "unassign": {
            if (typeof tagId !== "number" || typeof agentId !== "string") {
              return {
                content: [
                  {
                    type: "text" as const,
                    text: "Error: 'tagId' and 'agentId' are required for unassign",
                  },
                ],
                isError: true,
              };
            }
            result = await client.delete(`/api/v1/tag/${tagId}/agent/${agentId}`);
            break;
          }
        }

        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
