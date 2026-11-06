import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { idNumberParam, idParam, timeoutSecondsParam } from "./params.js";
import type { EasyShellClient } from "../client.js";
import { EasyShellError, toMcpError } from "../errors.js";

type ExecuteScriptParams = {
  name: string;
  agentIds?: string[];
  clusterIds?: number[];
  tagIds?: number[];
  scriptId?: number;
  scriptContent?: string;
  timeoutSeconds?: number;
};

/**
 * The server signals "needs human approval" with HTTP 449 and the machine readable code
 * APPROVAL_REQUIRED. EasyShellError keeps the HTTP status in `statusCode` and the code in
 * `code`, so checking `value["code"] === 449` can never match - that branch was dead code and
 * silently swallowed the approval requirement.
 */
function requiresApproval(error: unknown): error is EasyShellError {
  return (
    error instanceof EasyShellError &&
    (error.statusCode === 449 || error.code === "APPROVAL_REQUIRED")
  );
}

export function registerScriptTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "list_scripts",
    "List all scripts in the script library. Includes templates and user-created scripts.",
    {},
    async () => {
      try {
        const result = await client.get("/api/v1/script/list");
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "create_script",
    "Create a new script in the script library.",
    {
      name: z.string().max(128),
      content: z.string().max(256_000, "script content is limited to 256 KB"),
      description: z.string().max(2000).optional(),
      scriptType: z.enum(["shell", "python", "perl"]).default("shell").optional(),
    },
    async ({
      name,
      content,
      description,
      scriptType,
    }: {
      name: string;
      content: string;
      description?: string;
      scriptType?: "shell" | "python" | "perl";
    }) => {
      try {
        const result = await client.post("/api/v1/script", {
          name,
          content,
          description,
          scriptType,
        });

        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "execute_script",
    "Execute a script on one or more hosts. Returns a task ID that can be used with get_task_detail to check results. Can specify either scriptId (from library) or scriptContent (inline). At least one scope (agentIds, clusterIds or tagIds) must be given explicitly - the tool never runs on every host implicitly.",
    {
      name: z.string().describe("Task name"),
      agentIds: z.array(idParam("Host ID")).optional().describe("Host IDs to execute on"),
      clusterIds: z
        .array(idNumberParam("Cluster ID"))
        .optional()
        .describe("Cluster IDs"),
      tagIds: z
        .array(idNumberParam("Tag ID"))
        .optional()
        .describe("Tag IDs — execute on all hosts with these tags"),
      scriptId: idNumberParam("Script ID from library").optional(),
      scriptContent: z
        .string()
        .max(64_000, "inline script content is limited to 64 KB")
        .optional()
        .describe("Inline script content"),
      timeoutSeconds: timeoutSecondsParam(),
    },
    {
      title: "Execute script on hosts",
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openWorldHint: true,
    },
    async ({
      name,
      agentIds,
      clusterIds,
      tagIds,
      scriptId,
      scriptContent,
      timeoutSeconds,
    }: ExecuteScriptParams) => {
      // Exactly one script source, and an explicit scope: this tool runs shell commands on
      // production hosts, so an ambiguous request is refused instead of guessed.
      if ((scriptId == null) === (scriptContent == null)) {
        return {
          isError: true as const,
          content: [
            {
              type: "text" as const,
              text: "Error: provide exactly one of 'scriptId' or 'scriptContent'.",
            },
          ],
        };
      }

      const scopeCount = [agentIds, clusterIds, tagIds].filter(
        (scope) => Array.isArray(scope) && scope.length > 0,
      ).length;
      if (scopeCount !== 1) {
        return {
          isError: true as const,
          content: [
            {
              type: "text" as const,
              text: "Error: specify exactly one scope - 'agentIds', 'clusterIds' or 'tagIds'. Refusing to run on every host.",
            },
          ],
        };
      }

      try {
        const result = await client.post("/api/v1/task", {
          name,
          agentIds,
          clusterIds,
          tagIds,
          scriptId,
          scriptContent,
          timeoutSeconds,
        });

        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        if (requiresApproval(error)) {
          // Must be flagged as an error, otherwise the model assumes the script ran.
          return {
            isError: true as const,
            content: [
              {
                type: "text" as const,
                text:
                  "This script requires human approval before it can run (risk control 449 / APPROVAL_REQUIRED). " +
                  "Nothing was executed. Ask an administrator to approve the task in the EasyShell UI.",
              },
            ],
          };
        }
        return toMcpError(error);
      }
    }
  );
}
