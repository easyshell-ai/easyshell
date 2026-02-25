# EasyShell

**Lightweight Server Management & Intelligent Operations Platform**

Server-Agent Architecture | Batch Script Execution | Real-time Logs | AI-Powered Ops

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)

---

## Quick Start

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # Edit .env if needed
docker compose up -d
```

No local build required — pre-built images are pulled automatically from [GHCR](https://github.com/orgs/easyshell-ai/packages).

Open `http://localhost:18880` → login with `easyshell` / `easyshell@changeme`.

> **Want to use Docker Hub instead?** Set in `.env`:
> ```
> EASYSHELL_SERVER_IMAGE=laolupaojiao/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=laolupaojiao/easyshell-web:latest
> ```

> **Developer? Build from source:**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

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
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| Database | MySQL 8.0 |
| Cache | Redis 7 |

## Features

| Category | Features |
|----------|----------|
| **Infrastructure** | Host management, monitoring, cluster grouping |
| **Operations** | Script library, batch execution, real-time logs, web terminal |
| **AI Intelligence** | AI chat, scheduled inspections, reports, operation approvals |
| **Administration** | User management, system config, AI model settings, risk control, agent orchestration |
| **Platform** | i18n (EN / ZH), dark/light theme, responsive design, audit logging |

## Screenshots

### Host Management
![Host Management](https://easyshell.ai/images/features/host-management.png)

### Script Execution
![Script Execution](https://easyshell.ai/images/features/script-execution.png)

### Real-time Logs
![Real-time Logs](https://easyshell.ai/images/features/realtime-logs.png)

### Web Terminal
![Web Terminal](https://easyshell.ai/images/features/web-terminal.png)

### AI Operations
![AI Operations](https://easyshell.ai/images/features/ai-operations.png)

### Security Controls
![Security Controls](https://easyshell.ai/images/features/security-controls.png)

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

## License

This project is licensed under the [MIT License](./LICENSE).
