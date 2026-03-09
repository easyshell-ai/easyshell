import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

export function registerKnowledgeTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "search_knowledge",
    "Search EasyShell's operational knowledge base. This includes AI-extracted SOPs (Standard Operating Procedures) learned from past sessions and AI session memory summaries. Use to find reusable operational patterns and historical context.",
    {
      source: z
        .enum(["sop", "memory", "all"])
        .default("all")
        .optional()
        .describe("Knowledge source: sop (learned procedures), memory (session summaries), all"),
      category: z
        .string()
        .optional()
        .describe("Filter SOPs by category (only for sop source)"),
      page: z.number().default(0).optional(),
      size: z.number().default(20).optional(),
    },
    async ({
      source,
      category,
      page,
      size,
    }: {
      source?: "sop" | "memory" | "all";
      category?: string;
      page?: number;
      size?: number;
    }) => {
      try {
        const query: Record<string, string> = {};
        if (typeof page === "number") {
          query.page = String(page);
        }
        if (typeof size === "number") {
          query.size = String(size);
        }

        const effectiveSource = source ?? "all";
        const results: Record<string, unknown> = {};

        if (effectiveSource === "sop" || effectiveSource === "all") {
          const sopQuery = { ...query };
          if (typeof category === "string") {
            sopQuery.category = category;
          }
          results.sops = await client.get("/api/sop", sopQuery);
        }

        if (effectiveSource === "memory" || effectiveSource === "all") {
          results.memories = await client.get("/api/memory", query);
        }

        return {
          content: [{ type: "text" as const, text: JSON.stringify(results, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
