# Spec: EasyShell → Skill / MCP 封装方案

> **状态**: 探索 / 可行性分析 → **规范化工程设计**  
> **日期**: 2026-03-09  
> **目标**: 将 EasyShell 封装为可对外发布的 Skill + MCP Server，支持 AI 自动安装配置，并在主流平台上架。

---

## 目录

1. [项目现状总结](#1-项目现状总结)
2. [封装形式分析](#2-封装形式分析skill-vs-mcp-vs-混合)
3. [MCP Server 工程规范](#3-mcp-server-工程规范)
4. [Skill 工程规范](#4-skill-工程规范)
5. [AI 自动安装方案](#5-ai-自动安装方案)
6. [发布与上架指南](#6-发布与上架指南)
7. [可行性评估](#7-可行性评估)
8. [实现路线图](#8-实现路线图)
9. [关键决策点](#9-关键决策点)

---

## 1. 项目现状总结

### 1.1 EasyShell 是什么

EasyShell 是一个 **AI-Native Server Operations Platform**，核心能力：

| 能力 | 说明 |
|------|------|
| **AI 脚本助手** | 自然语言描述需求 → AI 生成 Shell 脚本 → Diff 审查 → 一键应用 |
| **AI 任务编排** | 自然语言目标 → AI 分解为 DAG 执行计划 → 多主机并行执行 → 结构化报告 |
| **AI 定时巡检** | Cron → 脚本执行 → AI 分析输出 → 智能决定是否告警 → Bot 推送 |
| **Web SSH** | 浏览器内终端（多标签、文件管理器、搜索） |
| **主机管理** | 统一主机视图、集群分组、Agent 生命周期管理、监控 |
| **安全管控** | 审批流、审计日志、风险等级管控、敏感命令封禁 |

### 1.2 技术架构

```
┌─────────────────┐     HTTP/WS      ┌──────────────────┐
│  EasyShell Agent │◄────────────────►│  EasyShell Server │
│    (Go 1.24)    │                   │ (Spring Boot 3.5) │
│  单二进制，零依赖  │                   │  Java 17 + Spring AI │
└─────────────────┘                   └────────┬─────────┘
                                               │ REST API + WebSocket
                                      ┌────────┴─────────┐
                                      │  EasyShell Web    │
                                      │  (React 19 + Vite)│
                                      └──────────────────┘
基础设施: MySQL 8.0 + Redis 7 (Docker Compose 部署)
```

### 1.3 现有 AI 工具体系 (22 个 Tool Classes)

| 分类 | 工具 |
|------|------|
| **主机运维** | HostListTool, HostManageTool, HostTagTool, SoftwareDetectTool |
| **脚本执行** | ScriptExecuteTool, ScriptManageTool |
| **任务管理** | TaskManageTool |
| **集群管理** | ClusterManageTool |
| **监控统计** | MonitoringTool |
| **巡检调度** | ScheduledTaskTool |
| **审计审批** | AuditQueryTool, ApprovalTool |
| **AI 子代理** | SubAgentTool |
| **通用工具** | DateTimeTool, CalculatorTool, TextProcessTool, DataFormatTool, EncodingTool |
| **网络访问** | WebFetchTool, WebSearchTool |
| **知识库** | KnowledgeBaseTool |
| **通知** | NotificationTool |

### 1.4 现有 REST API (26 个 Controller)

完整覆盖：Auth, Host, Agent, Task, Script, Cluster, Tag, AuditLog, SystemConfig, FileProxy, Health, AiChat (SSE streaming), AiConfig, AiRisk, AiInspect, AiScheduledTask, AgentDefinition, Sop, Memory, HostIntelligence, ScriptAi, CopilotAuth 等。

---

## 2. 封装形式分析：Skill vs MCP vs 混合

### 2.1 方案对比

| 维度 | Skill (SKILL.md) | MCP Server | 混合方案 (Skill + MCP) |
|------|-------------------|------------|----------------------|
| **本质** | 静态知识注入 | 动态工具协议 (JSON-RPC 2.0) | 知识 + 工具 |
| **能力** | 教 AI 如何使用 EasyShell | 让 AI 直接调用 EasyShell API | 两者兼得 |
| **复杂度** | 低 | 中-高 | 中-高 |
| **交互模式** | AI 学后通过 HTTP 调 API | AI 通过 MCP 协议调用工具 | Skill 提供上下文，MCP 提供工具 |
| **发布渠道** | mdskills.ai, GitHub, 各 Agent 内置 | npm, MCP Registry, Smithery | 两套渠道全覆盖 |
| **维护成本** | 低 | 中 | 中 |

### 2.2 推荐：**Skill + MCP 混合** ✅

- **Skill** = 大脑（教 AI 何时用、怎么用、最佳实践）
- **MCP** = 双手（让 AI 直接操作 EasyShell，无需拼 HTTP）
- 两个产物**独立发布、独立安装**，也可组合使用

---

## 3. MCP Server 工程规范

### 3.1 项目结构

```
easyshell-mcp/
├── package.json              # npm 包定义（含 bin、mcpName、keywords）
├── tsconfig.json             # TypeScript 编译配置
├── tsup.config.ts            # 打包配置（单文件 bundle）
├── smithery.yaml             # Smithery.ai 平台元数据
├── server.json               # MCP Registry 官方元数据
├── LICENSE                   # MIT
├── README.md                 # 标准 MCP Server README
├── CHANGELOG.md              # 版本变更记录
├── .github/
│   └── workflows/
│       ├── ci.yml            # CI: lint + build + test
│       └── publish.yml       # CD: npm publish + Registry publish
├── src/
│   ├── index.ts              # 入口：#!/usr/bin/env node + Server 初始化
│   ├── config.ts             # 环境变量 → 配置对象
│   ├── client.ts             # EasyShell REST API Client（JWT 自动刷新）
│   ├── errors.ts             # 统一错误处理（EasyShell 错误码 → MCP 错误）
│   └── tools/
│       ├── host.ts           # list_hosts, get_host_detail
│       ├── script.ts         # list_scripts, create_script, execute_script
│       ├── task.ts           # get_task_detail, list_recent_tasks
│       ├── cluster.ts        # list_clusters, get_cluster_detail
│       ├── monitoring.ts     # get_dashboard_stats, get_host_metrics
│       ├── inspection.ts     # list_scheduled_tasks, trigger_inspection, get_inspect_reports
│       ├── ai-chat.ts        # ai_chat (核心：自然语言 → 任务编排)
│       ├── audit.ts          # query_audit_logs, approve_task
│       └── notification.ts   # send_notification
└── test/
    ├── client.test.ts        # API Client 单元测试
    └── tools/
        └── host.test.ts      # Tool 集成测试
```

### 3.2 package.json 规范

```json
{
  "name": "@easyshell/mcp-server",
  "version": "1.0.0",
  "description": "MCP server for EasyShell — AI-Native Server Operations Platform. Manage hosts, execute scripts, orchestrate multi-host tasks, and schedule inspections from any AI assistant.",
  "license": "MIT",
  "type": "module",
  "mcpName": "io.github.easyshell-ai/mcp-server",
  "homepage": "https://github.com/easyshell-ai/easyshell",
  "repository": {
    "type": "git",
    "url": "git+https://github.com/easyshell-ai/easyshell.git",
    "directory": "easyshell-mcp"
  },
  "bugs": {
    "url": "https://github.com/easyshell-ai/easyshell/issues"
  },
  "author": "EasyShell AI <contact@easyshell.ai>",
  "bin": {
    "easyshell-mcp": "./dist/index.js"
  },
  "main": "./dist/index.js",
  "files": [
    "dist",
    "README.md",
    "LICENSE",
    "server.json",
    "smithery.yaml"
  ],
  "scripts": {
    "build": "tsup",
    "dev": "tsup --watch",
    "lint": "eslint src/",
    "test": "vitest",
    "inspector": "npx @modelcontextprotocol/inspector dist/index.js",
    "prepare": "npm run build",
    "prepublishOnly": "npm run lint && npm run test && npm run build"
  },
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.26.0",
    "zod": "^3.25.0"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "eslint": "^9.0.0",
    "tsup": "^8.0.0",
    "typescript": "^5.6.0",
    "vitest": "^3.0.0"
  },
  "keywords": [
    "mcp",
    "mcp-server",
    "model-context-protocol",
    "easyshell",
    "devops",
    "server-management",
    "shell",
    "ssh",
    "ai-ops",
    "infrastructure",
    "automation",
    "claude",
    "cursor",
    "opencode"
  ],
  "engines": {
    "node": ">=18"
  }
}
```

**关键字段说明：**

| 字段 | 作用 |
|------|------|
| `mcpName` | MCP 官方 Registry 注册名（反向域名格式），用于验证身份 |
| `bin` | 使 `npx @easyshell/mcp-server` 可直接运行 |
| `type: "module"` | ESM 模块，MCP SDK 要求 |
| `files` | npm 发布时只包含 dist + 元数据，不泄露源码 |
| `keywords` | npm 搜索和 Registry 分类的关键 |

### 3.3 tsup.config.ts（单文件打包）

```typescript
import { defineConfig } from "tsup";

export default defineConfig({
  entry: ["src/index.ts"],
  format: ["esm"],
  target: "node18",
  clean: true,
  minify: true,
  shims: true,
  banner: {
    js: "#!/usr/bin/env node",
  },
  // 单文件 bundle，npx 下载后即可运行，无需 node_modules
  noExternal: [/.*/],
});
```

> **为什么用 tsup 而非 tsc？**
> - `npx` 每次执行都会下载包，单文件 bundle 启动速度快 10x+
> - 避免用户环境 `node_modules` 冲突
> - `banner` 自动注入 shebang，无需手动 `chmod`

### 3.4 server.json（MCP 官方 Registry 元数据）

```json
{
  "$schema": "https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json",
  "name": "io.github.easyshell-ai/mcp-server",
  "display_name": "EasyShell",
  "description": "AI-Native Server Operations — manage hosts, execute scripts, orchestrate multi-host tasks, schedule inspections with AI-powered analysis.",
  "homepage": "https://easyshell.ai",
  "repository": "https://github.com/easyshell-ai/easyshell",
  "icon": "https://easyshell.ai/logo.png",
  "categories": ["devops", "cloud", "automation"],
  "artifacts": {
    "npm": {
      "package": "@easyshell/mcp-server"
    }
  },
  "configuration": {
    "properties": {
      "EASYSHELL_URL": {
        "type": "string",
        "description": "EasyShell Server URL (e.g. http://localhost:18080)",
        "required": true
      },
      "EASYSHELL_USER": {
        "type": "string",
        "description": "EasyShell username",
        "required": true
      },
      "EASYSHELL_PASS": {
        "type": "string",
        "description": "EasyShell password",
        "sensitive": true,
        "required": true
      }
    }
  }
}
```

### 3.5 smithery.yaml（Smithery.ai 平台元数据）

```yaml
name: easyshell
displayName: EasyShell
description: >
  AI-Native Server Operations Platform — manage hosts, execute scripts, 
  orchestrate multi-host tasks, and schedule AI-powered inspections.
icon: https://easyshell.ai/logo.png
category: devops
license: MIT
startCommand:
  type: stdio
  configSchema:
    type: object
    properties:
      easyshellUrl:
        type: string
        title: EasyShell Server URL
        description: "The URL of your EasyShell server (e.g. http://localhost:18080)"
        default: "http://localhost:18080"
      easyshellUser:
        type: string
        title: Username
        default: "easyshell"
      easyshellPass:
        type: string
        title: Password
        format: password
    required: [easyshellUrl, easyshellUser, easyshellPass]
  commandFunction:
    # Smithery 会调用此函数生成启动命令
    |-
    (config) => ({
      command: "npx",
      args: ["-y", "@easyshell/mcp-server"],
      env: {
        EASYSHELL_URL: config.easyshellUrl,
        EASYSHELL_USER: config.easyshellUser,
        EASYSHELL_PASS: config.easyshellPass
      }
    })
```

### 3.6 src/index.ts 入口

```typescript
#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { EasyShellClient } from "./client.js";
import { loadConfig } from "./config.js";
import { registerHostTools } from "./tools/host.js";
import { registerScriptTools } from "./tools/script.js";
import { registerTaskTools } from "./tools/task.js";
import { registerClusterTools } from "./tools/cluster.js";
import { registerMonitoringTools } from "./tools/monitoring.js";
import { registerInspectionTools } from "./tools/inspection.js";
import { registerAiChatTools } from "./tools/ai-chat.js";
import { registerAuditTools } from "./tools/audit.js";
import { registerNotificationTools } from "./tools/notification.js";

const config = loadConfig();

const server = new McpServer({
  name: "easyshell",
  version: process.env.npm_package_version ?? "1.0.0",
  description:
    "AI-Native Server Operations Platform — manage hosts, execute scripts, orchestrate tasks",
});

const client = new EasyShellClient(config);

// 注册全部工具
registerHostTools(server, client);
registerScriptTools(server, client);
registerTaskTools(server, client);
registerClusterTools(server, client);
registerMonitoringTools(server, client);
registerInspectionTools(server, client);
registerAiChatTools(server, client);
registerAuditTools(server, client);
registerNotificationTools(server, client);

// 连接 stdio transport
const transport = new StdioServerTransport();
await server.connect(transport);

// 优雅退出
process.on("SIGINT", async () => {
  await server.close();
  process.exit(0);
});
```

### 3.7 MCP Tool 映射表（完整 19 个工具）

**Tier 1 — 核心（MVP，必须）**

| MCP Tool | EasyShell API | 说明 |
|----------|---------------|------|
| `list_hosts` | `GET /api/v1/hosts` | 列主机（支持状态/标签筛选） |
| `get_host_detail` | `GET /api/v1/hosts/{id}` | 主机详情 + 指标 |
| `execute_script` | `POST /api/v1/tasks` | 在主机上执行脚本 |
| `get_task_detail` | `GET /api/v1/tasks/{id}` | 任务执行结果 |
| `list_scripts` | `GET /api/v1/scripts` | 脚本库列表 |
| `ai_chat` | `POST /api/v1/ai/chat` | 自然语言 → 任务编排（核心） |

**Tier 2 — 管理**

| MCP Tool | EasyShell API | 说明 |
|----------|---------------|------|
| `list_recent_tasks` | `GET /api/v1/tasks` | 最近任务列表 |
| `create_script` | `POST /api/v1/scripts` | 创建脚本到库 |
| `list_clusters` | `GET /api/v1/clusters` | 集群列表 |
| `get_dashboard_stats` | `GET /api/v1/monitoring/dashboard` | 平台总览统计 |
| `get_host_metrics` | `GET /api/v1/monitoring/hosts/{id}/metrics` | 主机历史指标 |
| `list_scheduled_tasks` | `GET /api/v1/ai/scheduled-tasks` | 定时巡检列表 |
| `trigger_inspection` | `POST /api/v1/ai/scheduled-tasks/{id}/trigger` | 手动触发巡检 |
| `get_inspect_reports` | `GET /api/v1/ai/inspect/reports` | 巡检报告 |

**Tier 3 — 高级**

| MCP Tool | EasyShell API | 说明 |
|----------|---------------|------|
| `manage_tags` | `POST/DELETE /api/v1/tags` | 主机标签管理 |
| `query_audit_logs` | `GET /api/v1/audit-logs` | 审计日志 |
| `approve_task` | `POST /api/v1/ai/risk/approve` | 审批高风险操作 |
| `send_notification` | 通过 AI Chat | 推送通知到 Bot 渠道 |
| `search_knowledge` | 通过 AI Chat | 知识库搜索 |

### 3.8 GitHub Actions CI/CD

#### ci.yml

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

defaults:
  run:
    working-directory: easyshell-mcp

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: npm
          cache-dependency-path: easyshell-mcp/package-lock.json
      - run: npm ci
      - run: npm run lint
      - run: npm test
      - run: npm run build
      # 验证 bin 可执行
      - run: node dist/index.js --help || true
```

#### publish.yml

```yaml
name: Publish to npm + MCP Registry
on:
  release:
    types: [published]

defaults:
  run:
    working-directory: easyshell-mcp

jobs:
  publish-npm:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          registry-url: https://registry.npmjs.org
      - run: npm ci
      - run: npm run build
      - run: npm publish --provenance --access public
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}

  publish-registry:
    needs: publish-npm
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - run: npx mcp-publisher publish
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## 4. Skill 工程规范

### 4.1 目录结构

```
easyshell-skill/
├── SKILL.md                    # 主入口
├── resources/
│   ├── tool-catalog.md         # MCP 工具目录 + 使用范例
│   ├── deployment-guide.md     # 部署与配置
│   └── troubleshooting.md      # 常见问题
├── README.md                   # 发布说明（面向 mdskills.ai 等平台）
└── LICENSE                     # MIT
```

### 4.2 SKILL.md 完整内容

```markdown
---
name: easyshell
description: >
  AI-Native Server Operations Platform — manage hosts, execute scripts,
  orchestrate multi-host tasks, schedule AI-powered inspections, and
  access Web SSH. Use this skill for server management, DevOps automation,
  or infrastructure operations through EasyShell.
version: 1.0.0
license: MIT
author: EasyShell AI
homepage: https://easyshell.ai
repository: https://github.com/easyshell-ai/easyshell
compatibility:
  - opencode
  - claude-code
  - cursor
  - vscode-copilot
glue:
  mcp: "@easyshell/mcp-server"
tags:
  - devops
  - server-management
  - shell
  - ssh
  - automation
  - ai-ops
---

# EasyShell Skill

## What is EasyShell

EasyShell is an AI-native server operations platform that lets you manage
servers, execute scripts, orchestrate multi-host tasks, and SSH into machines
through a web interface with built-in AI assistance.

The platform has three components:
- **Server** (Java/Spring Boot) — central management, REST API, AI engine
- **Agent** (Go) — lightweight binary deployed on each managed host
- **Web** (React) — browser-based UI at port 18880

## When to Use This Skill

Activate when the user:
- Wants to manage remote servers, check host status, or run commands
- Mentions "EasyShell" or needs server operations tooling
- Needs to deploy, monitor, or inspect infrastructure
- Wants to create or manage shell scripts across multiple hosts
- Asks about scheduled inspections or automated alerting
- Needs Web SSH access to a server

## MCP Server (Recommended)

This skill works best with the `@easyshell/mcp-server` MCP server installed.
It provides direct tool access to EasyShell. See `resources/tool-catalog.md`
for the full tool list with examples.

### Quick Install

Add to your AI assistant's MCP config:

```json
{
  "mcpServers": {
    "easyshell": {
      "command": "npx",
      "args": ["-y", "@easyshell/mcp-server"],
      "env": {
        "EASYSHELL_URL": "http://your-server:18080",
        "EASYSHELL_USER": "easyshell",
        "EASYSHELL_PASS": "your-password"
      }
    }
  }
}
```

## Core Workflows

### 1. Execute a Script on Hosts
1. `list_hosts` → find target hosts by status or tag
2. `execute_script` → send script content + host IDs
3. `get_task_detail` → check execution results per host

### 2. AI Task Orchestration (Most Powerful)
1. `ai_chat` → send a natural language goal  
   Example: "Check disk usage on all production hosts, flag anything over 80%"
2. EasyShell AI decomposes into DAG execution plan
3. Scripts dispatched to hosts in parallel
4. Results returned as structured analysis with recommendations

### 3. Schedule an Inspection
1. `list_scheduled_tasks` → see existing inspections
2. `trigger_inspection` → run one immediately
3. `get_inspect_reports` → review AI analysis results

### 4. Monitor Infrastructure
- `get_dashboard_stats` → platform overview (host count, online rate, load)
- `get_host_metrics` → historical CPU/memory/disk for a specific host
- `list_hosts` with status filter → find offline or degraded hosts

## Security Model

- Low-risk commands (ls, cat, df, free, ps) → auto-execute
- Medium-risk commands → require user confirmation
- High-risk commands (rm -rf, mkfs, dd) → require admin approval
- Banned commands → auto-create approval request
- All operations are audit-logged

**Never attempt to bypass risk controls.**

## Prerequisites

EasyShell must be deployed and accessible:
- Quick start: `git clone && docker compose up -d`
- Default Web UI: http://localhost:18880
- Default API: http://localhost:18080
- Default credentials: `easyshell` / `easyshell@changeme`
- Docs: https://docs.easyshell.ai
```

### 4.3 跨 Agent 安装路径

发布时需同时提供以下路径的安装说明：

| Agent | 安装路径 |
|-------|---------|
| **OpenCode** | `.opencode/skills/easyshell/SKILL.md` |
| **Claude Code** | `.claude/skills/easyshell/SKILL.md` |
| **Cursor** | `.cursor/skills/easyshell/SKILL.md` |
| **VS Code Copilot** | `.github/skills/easyshell/SKILL.md` |
| **通用 (Vendor-Neutral)** | `.agents/skills/easyshell/SKILL.md` |
| **全局安装** | `~/.config/opencode/skills/easyshell/SKILL.md` 或对应 Agent 全局路径 |

---

## 5. AI 自动安装方案

**核心目标**: 用户对 AI 说 "帮我安装 EasyShell MCP"，AI 自动完成全部配置，零手动步骤。

### 5.1 MCP Server 自动安装

AI 助手执行以下步骤（可编入 Skill 指令或 README）：

#### Step 1: 检测 AI 客户端类型

```
检查以下文件是否存在（按优先级）：
1. opencode.json                              → OpenCode
2. .cursor/mcp.json                           → Cursor
3. ~/Library/Application Support/Claude/claude_desktop_config.json  → Claude Desktop (macOS)
4. %APPDATA%/Claude/claude_desktop_config.json → Claude Desktop (Windows)
```

#### Step 2: 写入 MCP 配置

**OpenCode** (`opencode.json`):
```json
{
  "mcp": {
    "easyshell": {
      "type": "local",
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

**Cursor** (`.cursor/mcp.json`):
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

**Claude Desktop** (`claude_desktop_config.json`):
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

#### Step 3: 提示用户

```
✅ EasyShell MCP server 已配置。
请重启你的 AI 助手（或重新加载 MCP 配置）以激活。

⚠️ 请修改环境变量中的 EASYSHELL_URL、EASYSHELL_USER、EASYSHELL_PASS 
   为你实际的 EasyShell 服务器地址和凭证。
```

### 5.2 Skill 自动安装

```bash
# 方式 1: npx CLI 安装（推荐，如果 mdskills CLI 可用）
npx mdskills install easyshell

# 方式 2: AI 自动复制
# AI 检测当前 Agent 类型，将 SKILL.md + resources/ 写入对应路径
```

### 5.3 一体化安装脚本（放入 README）

```bash
# 一行安装 MCP Server + Skill（适用于 OpenCode）
npx @easyshell/mcp-server --install \
  --url http://localhost:18080 \
  --user easyshell \
  --pass easyshell@changeme
```

> 实现思路：在 `src/index.ts` 中检测 `--install` flag，自动：
> 1. 检测 AI 客户端
> 2. 写入 MCP 配置
> 3. 下载 Skill 文件到对应路径
> 4. 输出成功提示

---

## 6. 发布与上架指南

### 6.1 发布渠道总览

```
                    ┌─────────────────────────────────────────┐
                    │         EasyShell MCP + Skill            │
                    └───────────────┬─────────────────────────┘
                                    │
           ┌────────────────────────┼────────────────────────┐
           │                        │                        │
    ┌──────▼──────┐          ┌──────▼──────┐          ┌──────▼──────┐
    │   Package    │          │  Registries  │          │  Community   │
    │  Managers    │          │  & Markets   │          │   Channels   │
    ├─────────────┤          ├─────────────┤          ├─────────────┤
    │ npm          │          │ MCP Registry │          │ awesome-mcp  │
    │ (required)   │          │ (official)   │          │   -servers   │
    └─────────────┘          │ Smithery.ai  │          │ GitHub topic │
                             │ Glama.ai     │          │ Discord/社区 │
                             │ Pulse MCP    │          │ mdskills.ai  │
                             │ mcp.run      │          │              │
                             │ LobeHub      │          │              │
                             └─────────────┘          └─────────────┘
```

### 6.2 逐平台发布指南

#### ① npm（必须，基础设施）

```bash
# 首次发布
cd easyshell-mcp
npm login --scope=@easyshell-ai
npm publish --access public

# 后续自动化：通过 GitHub Actions publish.yml（见 3.8 节）
```

**前置条件**:
- npmjs.com 注册 `@easyshell-ai` scope（或使用 unscoped name `easyshell-mcp`）
- 生成 npm access token → 存入 GitHub Secrets `NPM_TOKEN`

**验证**: `npx @easyshell/mcp-server --help` 可正常运行

---

#### ② MCP 官方 Registry（最重要，客户端发现源）

**地址**: https://registry.modelcontextprotocol.io

**步骤**:

```bash
# 1. 确保 package.json 中有 mcpName
#    "mcpName": "io.github.easyshell-ai/mcp-server"

# 2. 确保 server.json 存在（见 3.4 节）

# 3. 安装发布工具
npx mcp-publisher login github

# 4. 初始化（首次）
npx mcp-publisher init

# 5. 发布
npx mcp-publisher publish
```

**效果**: Claude Desktop / Cursor 等客户端可从 Registry 发现并一键安装 EasyShell

---

#### ③ Smithery.ai（一键安装生态）

**地址**: https://smithery.ai

**步骤**:
1. 登录 Smithery.ai（GitHub OAuth）
2. 点击 "Add Server" → 连接 GitHub repo
3. 上传或检查 `smithery.yaml`（见 3.5 节）
4. Smithery 自动扫描 repo，提取 Tool 定义
5. 发布后用户可通过 `npx @smithery/cli install @easyshell/mcp-server` 安装

**额外好处**: Smithery 数据同步到 **Glama.ai**，一次发布两处上架。

---

#### ④ Glama.ai（MCP 目录）

**地址**: https://glama.ai/mcp

**步骤**:
- 如果已在 Smithery 发布 → 自动索引，无需额外操作
- 如果未用 Smithery → 通过 Glama Gateway 提交（需公共 HTTPS URL）

---

#### ⑤ Pulse MCP（社区目录）

**地址**: https://pulsemcp.com

**步骤**:
1. Fork https://github.com/pulsemcp/mcp-servers
2. 添加 EasyShell 条目（Markdown + 元数据）
3. 提交 PR

---

#### ⑥ awesome-mcp-servers（GitHub SEO）

**地址**: https://github.com/punkpeye/awesome-mcp-servers

**步骤**:
1. Fork repo
2. 在合适分类（DevOps & Cloud / System Tools）下添加条目：
   ```markdown
   - [EasyShell](https://github.com/easyshell-ai/easyshell) - AI-Native Server Operations Platform — manage hosts, execute scripts, orchestrate tasks, schedule inspections.
   ```
3. 提交 PR

---

#### ⑦ mdskills.ai（Skill 发布）

**地址**: https://www.mdskills.ai

**步骤**:
1. 登录 mdskills.ai（GitHub/Google OAuth）
2. 点击 "Submit" → 填写 Skill 信息
3. 关联 GitHub repo 中的 `easyshell-skill/` 目录
4. 或使用 CLI: `npx mdskills publish`

---

#### ⑧ LobeHub Skills Marketplace

**地址**: https://lobehub.com/en/skills

**步骤**:
1. 登录 LobeHub Dashboard
2. 提交 Skill 或 MCP Server 到 marketplace
3. 或提交 PR 到 LobeHub registry repo

---

#### ⑨ GitHub 仓库自身优化

确保仓库自身作为发现入口：

```bash
# 添加 GitHub Topics（仓库 Settings → Topics）
mcp-server
model-context-protocol
devops
ai-ops
server-management
shell
automation
```

README 中添加标准徽章：

```markdown
[![npm version](https://img.shields.io/npm/v/@easyshell/mcp-server)](https://www.npmjs.com/package/@easyshell/mcp-server)
[![MCP Registry](https://img.shields.io/badge/MCP-Registry-blue)](https://registry.modelcontextprotocol.io/servers/io.github.easyshell-ai/mcp-server)
[![Smithery](https://smithery.ai/badge/@easyshell/mcp-server)](https://smithery.ai/server/@easyshell/mcp-server)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
```

### 6.3 发布优先级与节奏

| 优先级 | 平台 | 时机 | 原因 |
|--------|------|------|------|
| **P0** | npm | Day 1 | 一切的基础，其他平台都依赖 npm 包 |
| **P0** | MCP 官方 Registry | Day 1 | Claude Desktop / Cursor 等客户端的发现源 |
| **P0** | Smithery.ai | Day 2 | 一键安装生态 + 自动同步到 Glama |
| **P1** | awesome-mcp-servers | Day 3 | GitHub SEO，开发者搜索入口 |
| **P1** | mdskills.ai | Day 3 | Skill 发现平台 |
| **P1** | GitHub Topics + Badges | Day 1 | 仓库元数据优化 |
| **P2** | Pulse MCP | Week 2 | 社区目录补充 |
| **P2** | LobeHub | Week 2 | LobeChat 生态用户 |
| **P2** | Glama.ai | 自动 | Smithery 发布后自动索引 |

---

## 7. 可行性评估

### 7.1 技术可行性 ✅

| 维度 | 评估 | 说明 |
|------|------|------|
| **API 成熟度** | ✅ 高 | 26 Controller, RESTful, JWT Auth, SSE Streaming |
| **工具映射** | ✅ 自然 | 22 内部 AI Tool → 19 MCP Tool，一一对应 |
| **认证集成** | ✅ 已有 | JWT 体系完善，MCP 侧一次登录换 Token |
| **协议兼容** | ✅ 标准 | MCP JSON-RPC 2.0 + stdio，SDK 成熟 |
| **流式支持** | ✅ 已有 | SSE endpoint 已实现 |
| **安全模型** | ✅ 天然 | 服务端风险管控，MCP 层继承 |

### 7.2 发布可行性 ✅

| 维度 | 评估 | 说明 |
|------|------|------|
| **npm 发布** | ✅ 标准 | 纯 TypeScript，CI/CD 一键发布 |
| **Registry 注册** | ✅ 有工具 | `mcp-publisher` CLI 自动化 |
| **Smithery 上架** | ✅ 有规范 | `smithery.yaml` 配置即可 |
| **Skill 分发** | ✅ 多渠道 | mdskills.ai + GitHub + 各 Agent 目录 |
| **AI 自动安装** | ✅ 可实现 | `--install` flag + 客户端检测 |

### 7.3 工程量评估

| 工作项 | 工时 | 优先级 |
|--------|------|--------|
| MCP Server 骨架 + API Client + Auth | 2-3 天 | P0 |
| Tier 1 核心工具 (6 个) | 2-3 天 | P0 |
| Tier 2 管理工具 (8 个) | 2-3 天 | P1 |
| Tier 3 高级工具 (5 个) | 1-2 天 | P2 |
| `--install` 自动安装逻辑 | 1 天 | P0 |
| Skill 编写 (SKILL.md + resources) | 1-2 天 | P0 |
| CI/CD + npm 首次发布 | 1 天 | P0 |
| server.json + smithery.yaml + 平台上架 | 1 天 | P0 |
| 测试 + MCP Inspector 验证 | 1-2 天 | P0 |
| awesome-mcp-servers PR + 社区推广 | 1 天 | P1 |
| **总计** | **~2.5-3 周** | |

### 7.4 风险与缓解

| 风险 | 缓解 |
|------|------|
| EasyShell API 变更导致 MCP Tool 失效 | MCP Server 版本与 EasyShell 版本绑定发布 |
| JWT Token 过期 | Client 内建自动刷新 |
| 长时运行任务 AI 超时 | 异步模式：execute → task_id → poll get_task_detail |
| npm scope 被占用 | 备选：`easyshell-mcp-server`（unscoped） |
| Smithery 平台变更 | smithery.yaml 遵循其官方 schema，持续跟进 |

---

## 8. 实现路线图

### Phase 1: MVP (Week 1-2) — "能用 + 能装"

- [ ] 创建 `easyshell-mcp/` 项目骨架（package.json, tsconfig, tsup）
- [ ] 实现 EasyShellClient（JWT 认证 + 自动刷新 + 错误映射）
- [ ] 实现 Tier 1 核心工具 (6 个)
- [ ] 实现 `--install` 自动安装逻辑
- [ ] 编写 SKILL.md + resources/
- [ ] MCP Inspector 本地验证
- [ ] npm 首次发布
- [ ] server.json + MCP 官方 Registry 上架
- [ ] smithery.yaml + Smithery.ai 上架
- [ ] README 徽章 + GitHub Topics

### Phase 2: Complete (Week 3) — "好用 + 全覆盖"

- [ ] 实现 Tier 2 + Tier 3 工具 (13 个)
- [ ] 错误处理标准化
- [ ] mdskills.ai 上架
- [ ] awesome-mcp-servers PR
- [ ] Pulse MCP 提交
- [ ] LobeHub 提交
- [ ] E2E 测试套件

### Phase 3: Advanced (Week 4+) — "强大"

- [ ] MCP Resources（暴露主机列表、脚本库为可浏览资源）
- [ ] MCP Prompts（预置运维场景模板）
- [ ] SSE streaming → MCP progressive tool
- [ ] 多 EasyShell 实例管理
- [ ] Java 原生嵌入 MCP endpoint（消除代理层）

---

## 9. 关键决策点

| # | 决策 | 推荐 | 备选 |
|---|------|------|------|
| 1 | npm 包名 | `@easyshell/mcp-server` | `easyshell-mcp-server` |
| 2 | 打包工具 | tsup（单文件 bundle） | tsc（官方模式） |
| 3 | 项目位置 | monorepo 内 `easyshell-mcp/` | 独立 repo |
| 4 | Transport | stdio（AI 助手标准） | 后续加 SSE |
| 5 | 认证方式 | 用户名/密码（现有体系） | 后续加 API Key |
| 6 | Skill 安装方式 | `npx mdskills install` + AI 自动复制 | 纯手动复制 |
| 7 | 首发平台 | npm + MCP Registry + Smithery | 逐步扩展 |
