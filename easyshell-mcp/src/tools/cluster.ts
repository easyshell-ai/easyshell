import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { idNumberParam } from "./params.js";
import type { EasyShellClient } from "../client.js";
import { toMcpError } from "../errors.js";

export function registerClusterTools(server: McpServer, client: EasyShellClient) {
  server.tool(
    "list_clusters",
    "List all host clusters/groups.",
    {},
    async () => {
      try {
        const result = await client.get("/api/v1/cluster/list");
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );

  server.tool(
    "get_cluster_detail",
    "Get cluster details including member hosts.",
    {
      clusterId: idNumberParam("The cluster ID"),
    },
    async ({ clusterId }: { clusterId: number }) => {
      try {
        const result = await client.get(`/api/v1/cluster/${clusterId}`);
        return {
          content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        };
      } catch (error) {
        return toMcpError(error);
      }
    }
  );
}
