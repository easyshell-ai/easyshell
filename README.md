<p align="center">
  <img src="docs/images/logo.png" alt="EasyShell Logo" width="200" />
</p>

# EasyShell

**AI-Native Server Operations Platform**

Let AI write your scripts, orchestrate multi-host tasks, and analyze your infrastructure — while you focus on what matters.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)
[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

**Language**: English | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-TW.md) | [한국어](./README.ko.md) | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## Why EasyShell?

Traditional server management tools expect you to write every script, SSH into every box, and interpret every output yourself. EasyShell flips that model: **AI is the operator, you are the decision-maker.**

- **Describe what you need in plain language** → AI writes production-ready shell scripts with diff review
- **Set a goal across multiple hosts** → AI plans the execution steps, runs them, and synthesizes the results
- **Schedule automated inspections** → AI analyzes output and decides whether to alert your team via bot channels
- **Connect via Web SSH** → Full terminal with file manager, multi-tab, search — no local client needed

---

## Core Features

### 1. AI Script Assistant

> Describe what you want. AI writes the script. Review the diff. One click to apply.

The AI Script Workbench is a split-panel editor where you describe requirements in natural language, and AI generates (or modifies) shell scripts targeting your chosen OS. Real-time streaming shows the script being written. A built-in diff view highlights exactly what changed. A summary tab explains the modifications in your language.

<p align="center">
  <img src="docs/images/AI%20Script%20helper.png" alt="AI Script Assistant — live code generation with diff review" width="90%" />
</p>

**How it works:**
1. **Describe** — write your requirements in natural language, choose the target OS
2. **Generate** — AI streams a production-ready script in real time
3. **Review** — built-in diff view highlights every change; summary tab explains modifications
4. **Apply** — one click to save the script to your library or dispatch it immediately

### 2. AI Task Orchestration

> "Check disk and memory on all hosts, flag anything over 80%, and suggest fixes." — Done.

The AI Chat interface lets you issue high-level operational goals. AI decomposes them into a multi-step execution plan (explore → analyze → report), dispatches scripts to target hosts, collects results, and delivers a structured analysis with risk assessment and actionable recommendations — all in a single conversation turn.

<p align="center">
  <img src="docs/images/AI%20task%20orchestration.png" alt="AI Task Orchestration — multi-step execution plan with analysis" width="90%" />
</p>

**How it works:**
1. **Instruct** — describe a high-level goal in the AI Chat (e.g. "check disk on all hosts")
2. **Plan** — AI decomposes the goal into a multi-step execution plan (explore → analyze → report)
3. **Execute** — scripts are dispatched to target hosts in parallel; results collected automatically
4. **Report** — AI delivers a structured analysis with risk assessment and actionable recommendations

### 3. AI Scheduled Inspections

> **Cron → Script → AI Analysis → Intelligent Alert** — AI analyzes output and decides whether to alert your team.

Schedule inspection tasks with cron expressions and select scripts from the built-in script library. EasyShell dispatches them to agents on schedule, collects output (disk, memory, services, logs), sends it to your AI model for intelligent analysis, and **lets AI decide whether the results warrant an alert** — pushing notifications only when they matter.

<p align="center">
  <img src="docs/images/schedule_task.png" alt="AI Scheduled Inspections — cron-based tasks with AI-powered output analysis and intelligent alerting" width="90%" />
</p>

**How it works:**
1. **Configure** — cron expression + script (from library or custom) + AI analysis prompt + notification rules
2. **Execute** — EasyShell dispatches to target agents on schedule
3. **Analyze** — Output sent to AI model (OpenAI / Gemini / GitHub Copilot / Custom) for intelligent analysis
4. **Notify** — AI determines severity and pushes alerts via bot channels when action is needed

**Notification modes:** Always push, push on failure, push on warning, or **AI decides** — the AI model evaluates the output and autonomously determines if an alert is necessary.

**Supported Bot Channels** ([Configuration Guide](https://docs.easyshell.ai/configuration/bot-channels/)):

| Bot | Status |
|-----|--------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Supported |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Supported |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Supported |
| [DingTalk (钉钉)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Supported |
| [Feishu (飞书)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Supported |
| [WeCom (企业微信)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ Supported |

### 4. Fully Functional Web SSH

> Real terminal. Real file manager. No SSH client required.

A production-grade web terminal with multi-tab sessions, integrated file manager (upload, download, create, delete, navigate), full-text search within terminal buffer, and persistent connections via WebSocket. Manage files and run commands side by side.

<p align="center">
  <img src="docs/images/Fully%20functional%20web%20SSH.png" alt="Web SSH — terminal with file manager and multi-tab" width="90%" />
</p>

### 5. Host Management & Monitoring

> Unified view of all servers with real-time status, batch operations, and agent lifecycle management.

Manage hosts individually or in bulk — add via form or CSV import, organize into clusters, monitor connection status, and deploy/upgrade agents with one click. The unified dashboard shows health metrics at a glance.

<p align="center">
  <img src="docs/images/host-management.png" alt="Host Management — unified server dashboard with batch operations" width="90%" />
</p>

### 6. Real-Time Streaming Logs

> Watch script execution unfold live across all target hosts.

When you dispatch a script, EasyShell streams output from every agent in real time. Color-coded logs, timestamps, and per-host filtering help you spot issues instantly — no more waiting for batch jobs to complete.

<p align="center">
  <img src="docs/images/realtime-logs.png" alt="Real-Time Logs — live streaming output from multiple hosts" width="90%" />
</p>

### 7. Security & Risk Controls

> Built-in safeguards: approval workflows, audit trails, and operation restrictions.

Configure which operations require approval before execution. All actions are logged for compliance. Role-based access control limits who can do what, and sensitive commands can be flagged or blocked entirely.

<p align="center">
  <img src="docs/images/security-controls.png" alt="Security Controls — approval workflows and audit logging" width="90%" />
</p>

### 8. MCP Server & AI Skills

> Let your AI client run your infrastructure — with the same risk engine, approvals and audit trail as the web console.

EasyShell ships two companion components that put the platform *inside* your AI assistant:

- **`easyshell-mcp`** — a [Model Context Protocol](https://modelcontextprotocol.io) server that exposes EasyShell hosts, scripts, tasks and inspections as MCP **tools**, so Claude Desktop, Claude Code, Cursor, Cherry Studio and other MCP clients can drive EasyShell directly.
- **`easyshell-skill`** — a `SKILL.md` definition that teaches an assistant *when* and *how* to use EasyShell (core workflows, full tool catalog, security model), for assistants that support skills.

#### `easyshell-mcp` — EasyShell as MCP tools

Published on npm as [`@easyshell/mcp-server`](https://www.npmjs.com/package/@easyshell/mcp-server), listed in the [MCP Registry](https://registry.modelcontextprotocol.io/servers/io.github.easyshell/mcp-server) and on [Smithery](https://smithery.ai/server/@easyshell/mcp-server). It talks to the EasyShell REST API and needs three variables:

| Variable | Required | Description |
|---|---|---|
| `EASYSHELL_URL` | Yes | EasyShell server API endpoint (for example `http://localhost:18080`). |
| `EASYSHELL_USER` | Yes | EasyShell username. |
| `EASYSHELL_PASS` | Yes | EasyShell password. |

**How it works:**
1. **Run** — `npx -y @easyshell/mcp-server` on Node.js `>=18`; nothing to build
2. **Connect** — point it at your EasyShell server with the three variables above
3. **Authenticate** — it logs in against the EasyShell API and reuses that session for every tool call
4. **Operate** — your AI client calls EasyShell tools; risk classification, approval workflows and audit logging still apply

**stdio (default) — Claude Desktop / Claude Code / Cursor / Cherry Studio**

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

> Replace the credentials with your own. With zero-configuration Docker deployment the initial admin password is generated randomly and printed once in the server logs (`docker logs easyshell-server | grep 'initial admin password'`).

Prefer not to hand-edit config files? The auto-installer detects OpenCode, Cursor or Claude Desktop and writes the block for you — the generated file is created with `0600` permissions because it holds your password:

```bash
MCP_PASSWORD='your-password' npx @easyshell/mcp-server --install
# optional: --url http://your-server:18080 --user easyshell
```

> `MCP_PASSWORD` is preferred over `--pass`: values passed on the command line are visible in `ps`.

**HTTP (SSE + Streamable HTTP) — remote or shared deployments**

```bash
# loopback only, no token required
npx -y @easyshell/mcp-server --port 3000

# reachable from other machines: a bearer token is mandatory
MCP_AUTH_TOKEN='choose-a-strong-token' \
  npx -y @easyshell/mcp-server --port 3000 --host 0.0.0.0 --allow-remote
```

| Endpoint | Method | Protocol |
|---|---|---|
| `/mcp` | POST / GET / DELETE | Streamable HTTP (2025-03-26) |
| `/sse` | GET | Legacy SSE (2024-11-05) |
| `/messages?sessionId=<id>` | POST | Legacy SSE message handler |
| `/health` | GET | Health check |

Clients with HTTP transport point at `http://<host>:3000/mcp` and send `Authorization: Bearer <MCP_AUTH_TOKEN>` (exact config keys vary by client):

```json
{
  "mcpServers": {
    "easyshell": {
      "type": "http",
      "url": "http://your-server:3000/mcp",
      "headers": { "Authorization": "Bearer <MCP_AUTH_TOKEN>" }
    }
  }
}
```

The HTTP transport validates the `Host` header against DNS-rebinding attacks, restricts CORS to `--allowed-origin` values, and caps sessions and request size.

**Tool surface — 20 tools in 3 tiers**

| Tier | Tools | Purpose |
|---|---|---|
| Core (6) | `list_hosts`, `get_host_detail`, `execute_script`, `get_task_detail`, `list_scripts`, `ai_chat` | Find hosts, run scripts, read results, drive AI orchestration |
| Management (9) | `list_recent_tasks`, `create_script`, `list_clusters`, `get_cluster_detail`, `get_dashboard_stats`, `get_host_metrics`, `list_scheduled_tasks`, `trigger_inspection`, `get_inspect_reports` | Day-to-day operations, monitoring and scheduled inspections |
| Advanced (5) | `manage_tags`, `query_audit_logs`, `approve_task`, `send_notification`, `search_knowledge` | Governance: tags, audit, approvals, notifications, knowledge base |

> The [`easyshell-mcp/README.md`](./easyshell-mcp/README.md) tool table advertises 19 tools; the source registers 20 — `get_cluster_detail` is missing from that table. The list above matches `easyshell-mcp/src/tools/`.

#### `easyshell-skill` — teach your assistant the EasyShell workflows

`easyshell-skill/SKILL.md` is an assistant-agnostic skill definition (name, description, compatibility, tags) that tells an AI assistant when to activate EasyShell and which workflows to follow — execute a script on hosts, AI task orchestration, schedule an inspection, monitor infrastructure — plus the security model it must never bypass.

Install it manually or with the `mdskills` CLI:

```bash
npx mdskills install easyshell
```

| Assistant | Skill path |
|---|---|
| OpenCode | `.opencode/skills/easyshell/SKILL.md` |
| Claude Code | `.claude/skills/easyshell/SKILL.md` |
| Cursor | `.cursor/skills/easyshell/SKILL.md` |
| VS Code Copilot | `.github/skills/easyshell/SKILL.md` |

The skill bundles `resources/tool-catalog.md` (every tool with parameters and examples), `resources/deployment-guide.md` and `resources/troubleshooting.md`. It works standalone, but **skill + MCP server** is the intended combination: the skill decides *what* to do, the MCP server provides the tools to do it.

#### Current boundary — read this before you plan a setup

`easyshell-mcp` is an **outbound** bridge: it makes EasyShell callable *from* your AI client. Consuming **external** MCP servers — Prometheus, Elasticsearch, or your own in-house MCP — *inside* the EasyShell platform is **on the roadmap, not implemented** in 1.1.0. There is no built-in MCP client, no external tool registry and no external-tool approval flow yet, and nothing in this section should be read as promising one.

If that is your use case, add it to the discussion in [issue #6](https://github.com/easyshell-ai/easyshell/issues/6) ("how do the mcp and skill modules work?"), or open a feature request.

### 9. Workflow Engine

> Drag the runbook onto a canvas. Pass values between steps. Put a human gate in front of anything destructive.

The Workflow Engine turns a sequence of operational steps into a **visual, versioned, auditable workflow**.
Every node does one thing; inputs come from upstream, outputs go downstream. The Start node declares what
a run needs; the End node declares what a run produces. Execution uses the same dispatch, risk gate, audit
and log path as ordinary script tasks.

<p align="center">
  <img src="docs/images/workflow-canvas.png" alt="Workflow canvas with Start, Script, Condition, Approval, Notify and End nodes" width="90%" />
</p>

#### What a workflow looks like

```
Start ──> Collect disk usage ──> Normalize ──> Over threshold? ──IF──> Notify ──> End
                                                    └──────────ELSE──────────────┘
```

- **Start** declares inputs (name, type, required, default, description). The trigger dialog is generated from it.
- **Script Exec** runs a script on agents / clusters / tags and becomes a real Task.
- **Transform** reshapes upstream output into named variables so downstream nodes do not repeat long paths.
- **Condition** evaluates one expression and has two real exits: `IF` and `ELSE`.
- **AI Analyze** asks the platform-configured model for a conclusion (**analysis only, never executes commands**).
- **Approval** suspends the run until an administrator decides; it is the only legitimate path for high-risk scripts.
- **Notify** pushes to configured bot channels and reports delivery honestly.
- **Wait** sleeps for a bounded duration; cancellation is never trapped in the sleep.
- **End** declares the run's final output fields, shown directly in the run detail.

#### Twelve node types

| Node | Purpose | Output available downstream |
|---|---|---|
| `start` | Declare run inputs; the only entry | each input as `nodes.start.output.<name>` |
| `script-exec` | Execute a script on target hosts, as a real Task | stdout (text or JSON), referenced as a whole |
| `transform` | Map / rename / compose values | `nodes.<key>.output.<name>` |
| `condition` | Evaluate an expression, IF / ELSE branches | `nodes.<key>.output.result` (boolean) |
| `ai-analyze` | Analyze upstream output with the configured model | `.text` / `.json` / `.structured` / `.truncated` |
| `llm` | Generic LLM: prompt to text or structured JSON | `.text` / `.json` / `.structured` / `.model` |
| `classifier` | Question classifier: route by model-selected class | `.class` / `.reason` / `.raw` |
| `code` | JavaScript / Python data transformation (isolated sandbox) | JSON value returned by code |
| `approval` | Suspend for human approval | `.decision` / `.comment` / `.decidedBy` |
| `notify` | Push a rendered message to bot channels | `.delivered` / `.failed` / `.skipped` |
| `wait` | Wait a bounded number of seconds | `.waitedMs` |
| `end` | Declare workflow outputs | the run detail `output` |

`approval` also has a lighter `mode: "human-confirm"` variant, reusing the same approval row and
suspend/resume state machine.

#### Passing values between nodes

You do not memorize paths. Every template, parameter, mapping and condition field has a **variable picker**
grouped by "run inputs / upstream nodes / output aliases". It only lists ancestors that the current node can
actually read. Selecting an item inserts it:

<p align="center">
  <img src="docs/images/workflow-inspector.png" alt="Node inspector Data tab: upstream variables grouped by node, click to insert" width="90%" />
</p>

- **Template fields** (script parameters, notify templates, approval details, transform values, AI input,
  End outputs) use `${path}`:
  ```text
  Disk ${nodes.probe.output.mount} is at ${nodes.probe.output.usage}%
  ```
- **Expression fields** (condition / edge conditions) use a bare path:
  ```text
  nodes.metrics.output.usage > run.input.threshold
  ```
- **Script bodies** keep the script-library `{{NAME}}` slots. Values come from `parameters`, and a value
  may itself be `${path}`. Example: script `systemctl restart {{SERVICE}}`, parameter
  `SERVICE = ${run.input.service_name}`.

Path rules:

| Source | Syntax |
|---|---|
| Run input | `run.input.<name>` |
| Start node output | `nodes.<startKey>.output.<name>` |
| Upstream node output | `nodes.<key>.output` / `nodes.<key>.output.<field>` |
| Output alias | `<outputVar>` / `<outputVar>.<field>` (equivalent to `nodes.<key>.output`) |

Values are visible only to topological descendants. Referencing a non-ancestor fails publishing with
`EXPRESSION_REFERENCE_NOT_ANCESTOR` and is unreadable at runtime. A single value is inlined up to 8192 UTF-8
bytes; larger values become a head/tail preview with a warning. There is no secret-injection channel: keep
credentials in the host's own credential store.


Start is not raw JSON: its field editor declares `label`, `name`, `type`
(string / number / boolean / json), required, default and description. String fields can also declare
`options`; the trigger dialog renders them as a Select instead of asking for free text. Condition nodes
also provide a structured "variable + operator + value" builder with an advanced raw-expression escape hatch.

#### Conditions: IF / ELSE are real gates

A Condition node evaluates one expression to `true` / `false`. Its two exits connect to `IF` and `ELSE`.
The engine reads the upstream node's `result` and only traverses the matching edge; the other edge is not
traversable. If the upstream output is missing, neither edge is traversed (fail-closed). The `IF` / `ELSE`
pills are readable in both light and dark themes; the expression itself is edited in the node inspector.

#### AI nodes: text, structured JSON and classification

- `ai-analyze`: reads upstream operations output and produces a conclusion; supports `text` and structured
  `json` modes.
- `llm`: generic LLM node with provider/model selection, system/user prompts and temperature; supports
  `text` and structured `json`, with optional JSON Schema guidance and one repair retry on invalid JSON.
- `classifier`: Dify-style question classifier with 1–10 classes (name + description). The model returns
  `{"class":"...","reason":"..."}`; each class gets a real source handle on the canvas plus a `default`
  fallback. An unknown class fails the node closed; it never routes silently.

All three reuse the platform's AI configuration under System Settings → AI. Input and output are masked by
the sensitive-data filter. They **only produce text/JSON/classification results and cannot execute
anything**: even if the output says `rm -rf`, it gains no dispatch authority. Turning AI output into an
action requires a human-written script and an `approval` node/risk gate; AI/LLM output is also forbidden
as a script `raw` parameter.

<p align="center">
  <img src="docs/images/workflow-llm-structured.png" alt="LLM node structured JSON output: mode, JSON Schema and temperature" width="90%" />
</p>

Explicitly not supported: autonomous AI agents with tools, and AI-generated scripts executed directly.
`CommandRiskEngine` is pattern-based; semantic prompt injection cannot be safely backstopped by it alone.

#### Code node: data sandbox, not a host executor

The `code` node supports JavaScript and Python for field extraction, formatting and composition:

```javascript
function main(input) {
  return { total: Number(input.a) + Number(input.b) };
}
```

```python
def main(input):
    return {"total": float(input["a"]) + float(input["b"])}
```

- Inputs are declared as an `inputs` map and may reference upstream variables. Code runs in a
  **GraalVM Polyglot sandbox**: no host objects, no file/network/environment/process/thread access, and a
  hard timeout that closes the context.
- The return value must be JSON-serializable and is written to `nodes.<key>.output`.
- It does not operate on servers. Host operations still use `script-exec` + agents.

#### Failure policy

| Policy | Effect |
|---|---|
| `abort` | Cancel this node and every unstarted node; run ends `failed` |
| `skip` (default) | Mark this node `skipped`; its descendants re-evaluate their own policy |
| `partial` | Run this node even if upstream failed; the failed output is treated as absent |

When incoming edges disagree, the strictest policy wins (`abort > skip > partial`). Unknown policies are
treated as `abort`: failure always closes, never opens.

#### Editor

- The palette groups nodes into Entry / Execute / Flow / Human, with one-line docs and search.
- The canvas fills the viewport; node cards show type, input/output summary, output variable and run status.
- The right inspector has **Config / Data / Docs** tabs: fields, readable upstream variables, purpose and example.
- The top bar **Help** drawer has a four-step tour, variable syntax, node reference and a full example.
  **Templates** offers three editable starting points: disk inspection, service-restart approval, batch health check.
- The canvas supports box selection, alignment guides, undo/redo, minimap, auto-layout, copy/paste and
  shortcuts. Connections are validated client-side (no self-loop, duplicate, cycle; Start has no input;
  End has no output), with the server authoritative.

#### Run monitoring and audit

The run detail is a read-only status-colored canvas plus a node table. Click a node to inspect input, output,
error, attempt and the linked Task, and **test-run that single node** without rerunning the whole workflow.
Outputs declared by the End node appear directly in Run Output.

Every run records enough to explain what happened and why: immutable definition version and SHA-256, full
input snapshot, per-node input/output and fingerprints, attempt number, Task id, agent version at dispatch,
and the risk-rule snapshot active when the run started. Five high-risk events (publish, approve, reject,
trigger, cancel) are audited synchronously; an audit write failure fails the business action.

#### Current boundaries

The 2.1 release covers Start/End contracts, twelve node types, the variable picker, real IF/ELSE gating,
LLM/structured output, a question classifier, a JS/Python data sandbox and per-node test runs. Still not
implemented: cron / webhook triggers, HTTP nodes, parallel fan-out/fan-in, sub-workflows, loops, artifact
storage, secret injection, dry-run / replay, and cross-instance scheduling. Per-node test-run is available
in run detail and only applies to an existing run.

See the [Workflow guide](https://docs.easyshell.ai/guides/workflow/) for a from-zero walkthrough, or open a
feature request for what is missing.

---

## Quick Start

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
docker compose up -d
```

That is the whole setup — **no `.env`, no secret generation, no manual steps**. On first
boot a one-shot container creates the database password, JWT secret, encryption key and
agent registration secret, stores them in the `easyshell_secrets` Docker volume and every
later start reuses them.

`scripts/deploy.sh` is a convenience wrapper that also waits for the health check and prints
the sign-in details:

```bash
./scripts/deploy.sh            # docker compose up -d + wait + show credentials
./scripts/deploy.sh --build    # build the images from this source tree instead of pulling
./scripts/deploy.sh --down     # stop everything (data volumes are kept)
```

No local build is required for the default path — pre-built images are pulled automatically
from [Docker Hub](https://hub.docker.com/u/laolupaojiao). Use `--build` to run exactly this
source tree (that is also how you pick up changes that have not been released yet).

Open `http://localhost:18880` → log in as `easyshell`. The initial password is generated
randomly on first start and printed once in the server logs
(`docker logs easyshell-server | grep 'initial admin password'`) — change it right after
signing in. To choose it yourself, set `EASYSHELL_ADMIN_PASSWORD` in `.env`.

Agents installed on other machines need a reachable server URL: set `PROVISION_SERVER_URL`
in `.env`, or **System config → server.external-url** in the UI.

> **Want to use GHCR instead?** Set in `.env`:
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **Developer? Build from source:**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

Local web development and dependency checks require Node.js `^20.19.0 || >=22.12.0`, matching the Vite 8 toolchain. `Dockerfile.web` uses the Node 22 image line for web builds.

---

## Full Feature Set

| Category | Features |
|----------|----------|
| **AI Intelligence** | AI Script Assistant (generate / modify / diff / summary), AI Task Orchestration (multi-step plans, parallel execution, analysis), AI Scheduled Inspections (cron-based, AI output analysis, intelligent alert decisions, multi-channel bot push), AI Chat, Inspection Reports, Operation Approvals |
| **Operations** | Script library, batch execution, real-time streaming logs, Web SSH terminal with file manager, **Workflow Engine** (visual DAG canvas, node parameter passing, failure policies, human approvals, run monitoring) |
| **Infrastructure** | Host management, real-time monitoring, cluster grouping, agent auto-deployment |
| **Administration** | User management, system config, AI model settings (OpenAI / Gemini / Copilot / Custom), risk control |
| **Platform** | i18n (EN / ZH), dark/light theme, responsive design, audit logging |

---

## Architecture

```
┌──────────────┐       HTTP/WS        ┌──────────────────┐
│  EasyShell   │◄─────────────────────►│   EasyShell      │
│    Agent     │  register / heartbeat │     Server       │
│  (Go 1.24)  │  script exec / logs   │ (Spring Boot 3.5)│
└──────────────┘                       └────────┬─────────┘
                                                │
                                       ┌────────┴─────────┐
                                       │   EasyShell Web   │
                                       │ (React + Ant Design)│
                                       └──────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Server | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, single binary, zero runtime dependencies |
| Web | React 19, TypeScript, Vite 8, Ant Design 6 |
| Database | MySQL 8.0 |
| Cache | Redis 7 |

## Project Structure

```
easyshell/
├── easyshell-server/           # Central management server (Java / Spring Boot)
├── easyshell-agent/            # Agent client (Go, single binary)
├── easyshell-web/              # Web frontend (React + Ant Design)
├── easyshell-mcp/              # MCP server — EasyShell as tools for AI clients (npm: @easyshell/mcp-server)
├── easyshell-skill/            # SKILL.md definition for AI assistants
├── docker-compose.yml          # Production deployment (pulls pre-built images)
├── docker-compose.build.yml    # Development (local build from source)
├── Dockerfile.server           # Server + Agent multi-stage build
├── Dockerfile.web              # Web frontend multi-stage build
├── .github/workflows/          # CI/CD: build & publish Docker images
└── .env.example                # Environment configuration template
```

## Documentation

Visit **[docs.easyshell.ai](https://docs.easyshell.ai)** for:

- Installation & deployment guide
- Getting started walkthrough
- Configuration reference
- Development guide

## Community

[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

Join our Discord community for support, discussions, and updates:
**[https://discord.gg/akQqRgNB6t](https://discord.gg/akQqRgNB6t)**

## License

This project is licensed under the [MIT License](./LICENSE).
