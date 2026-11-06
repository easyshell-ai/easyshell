import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { idParam } from "./params.js";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

type AiChatParams = {
  message: string;
  sessionId?: string;
  targetAgentIds?: string[];
  skipPlanning?: boolean;
};

export function registerAiChatTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "ai_chat",
    "Send a natural language message to EasyShell's AI engine for task orchestration. The AI can analyze your intent, create execution plans, dispatch scripts to hosts, and return structured analysis. This is the most powerful tool — use it for complex multi-host operations described in natural language.",
    {
      message: z
        .string()
        .describe(
          "Natural language instruction, e.g. 'Check disk usage on all production hosts'"
        ),
      sessionId: idParam("Session ID for conversation continuity").optional(),
      targetAgentIds: z.array(idParam("Host ID")).optional().describe("Limit execution to specific hosts"),
      skipPlanning: z
        .boolean()
        .default(false)
        .optional()
        .describe("Skip the planning phase for simple commands"),
    },
    async ({ message, sessionId, targetAgentIds, skipPlanning }: AiChatParams) => {
      try {
        const result = await client.post("/api/v1/ai/chat", {
          message,
          sessionId,
          targetAgentIds,
          skipPlanning,
          enableTools: true,
        });

        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
