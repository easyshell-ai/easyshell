[![npm version](https://img.shields.io/npm/v/@easyshell/mcp-server)](https://www.npmjs.com/package/@easyshell/mcp-server)
[![MCP Registry](https://img.shields.io/badge/MCP-Registry-blue)](https://registry.modelcontextprotocol.io/servers/io.github.easyshell/mcp-server)
[![Smithery](https://smithery.ai/badge/@easyshell/mcp-server)](https://smithery.ai/server/@easyshell/mcp-server)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)

# EasyShell MCP Server

Model Context Protocol server for EasyShell: manage hosts, execute scripts, orchestrate multi-host tasks, schedule AI inspections, and access infrastructure operations from AI assistants.

## Features

- 19 tools across 3 tiers
- Tier 1 Core (6): `list_hosts`, `get_host_detail`, `execute_script`, `get_task_detail`, `list_scripts`, `ai_chat`
- Tier 2 Management (8): `list_recent_tasks`, `create_script`, `list_clusters`, `get_dashboard_stats`, `get_host_metrics`, `list_scheduled_tasks`, `trigger_inspection`, `get_inspect_reports`
- Tier 3 Advanced (5): `manage_tags`, `query_audit_logs`, `approve_task`, `send_notification`, `search_knowledge`

## Quick Start

### Claude Desktop

Add to your MCP configuration:

```json
{
  "mcpServers": {
    "easyshell": {
      "command": "npx",
      "args": ["-y", "@easyshell/mcp-server"],
      "env": {
        "EASYSHELL_URL": "http://localhost:18080",
        "EASYSHELL_USER": "easyshell",
        "EASYSHELL_PASS": "easyshell@changeme"
      }
    }
  }
}
```

### Cursor

Use the same MCP server block in your Cursor MCP settings.

### OpenCode

Use the same MCP server block in your OpenCode MCP configuration.

## Configuration

| Variable | Required | Description |
|---|---:|---|
| `EASYSHELL_URL` | Yes | EasyShell server API endpoint (for example `http://localhost:18080`). |
| `EASYSHELL_USER` | Yes | EasyShell username. |
| `EASYSHELL_PASS` | Yes | EasyShell password. |

## Full Tool Reference

| Tier | Tool | Purpose |
|---|---|---|
| Core | `list_hosts` | List hosts and filter by status. |
| Core | `get_host_detail` | Fetch detailed host information. |
| Core | `execute_script` | Execute script content on selected hosts. |
| Core | `get_task_detail` | Retrieve detailed task execution results. |
| Core | `list_scripts` | List scripts from script library. |
| Core | `ai_chat` | Run natural-language AI operations orchestration. |
| Management | `list_recent_tasks` | Query recent execution tasks. |
| Management | `create_script` | Create and save reusable scripts. |
| Management | `list_clusters` | List host clusters/groups. |
| Management | `get_dashboard_stats` | Get platform KPI and overview stats. |
| Management | `get_host_metrics` | Get host metrics history. |
| Management | `list_scheduled_tasks` | List configured scheduled inspections. |
| Management | `trigger_inspection` | Trigger an inspection immediately. |
| Management | `get_inspect_reports` | Read AI inspection analysis reports. |
| Advanced | `manage_tags` | Manage host tags and assignments. |
| Advanced | `query_audit_logs` | Search audit and compliance logs. |
| Advanced | `approve_task` | Approve or reject high-risk operations. |
| Advanced | `send_notification` | Send notifications to configured channels. |
| Advanced | `search_knowledge` | Search operational knowledge base. |

## Auto-Install

```bash
npx @easyshell/mcp-server --install
```

## Development

```bash
npm ci
npm run build
npm run test
npm run inspector
```

## Links

- Documentation: https://docs.easyshell.ai
- Discord: https://discord.gg/WqFD9VQe
- GitHub: https://github.com/easyshell-ai/easyshell
