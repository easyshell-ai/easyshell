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

Open `http://localhost:18880` → login with `easyshell` / `easyshell@changeme`.

📖 For detailed installation, configuration, and usage guides, visit **[docs.easyshell.ai](https://docs.easyshell.ai)**.

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

## Project Structure

```
easyshell/
├── easyshell-server/     # Central management server
├── easyshell-agent/      # Agent client
├── easyshell-web/        # Web frontend
├── docker-compose.yml    # Full-stack deployment
├── Dockerfile.server     # Server + Agent multi-stage build
├── Dockerfile.web        # Web frontend multi-stage build
└── .env.example          # Environment configuration template
```

## Documentation

Visit **[docs.easyshell.ai](https://docs.easyshell.ai)** for:

- Installation & deployment guide
- Getting started walkthrough
- Configuration reference
- Development guide

## License

This project is licensed under the [MIT License](./LICENSE).
