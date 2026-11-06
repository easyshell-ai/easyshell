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
| **Operations** | Script library, batch execution, real-time streaming logs, Web SSH terminal with file manager |
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
