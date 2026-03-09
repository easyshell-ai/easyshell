import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

export function registerNotificationTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "send_notification",
    "Send a notification message through EasyShell's AI chat. The AI will route it to configured bot channels (Telegram, Discord, Slack, DingTalk, Feishu, WeCom).",
    {
      message: z.string().describe("Notification message to send"),
    },
    async ({ message }: { message: string }) => {
      try {
        const result = await client.post("/api/v1/ai/chat", {
          message: `Send notification: ${message}`,
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
