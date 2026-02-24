# EasyShell

**Lightweight Server Management & Intelligent Operations Platform**

Server-Agent Architecture | Batch Script Execution | Real-time Logs | AI-Powered Ops

---

## Documentation

| Document | Description |
|----------|-------------|
| **[Installation Guide](./INSTALL.md)** | Prerequisites, Docker Compose setup, manual installation, production deployment, Nginx config |
| **[Getting Started](./GETTING_STARTED.md)** | First-time walkthrough: login, add hosts, run scripts, use AI features |

## Project Structure

```
easyshell/
├── easyshell-server/     # Central management server (Java 17 + Spring Boot 3.5)
├── easyshell-agent/      # Agent client (Go 1.24, single binary)
├── easyshell-web/        # Web frontend (React + TypeScript + Ant Design)
├── docker-compose.yml    # Full-stack deployment (MySQL, Redis, Server, Web)
├── Dockerfile.server     # Server + Agent binaries multi-stage build
├── Dockerfile.web        # Web frontend multi-stage build
├── .env.example          # Environment configuration template
├── INSTALL.md            # Detailed installation guide
├── GETTING_STARTED.md    # Getting started handbook
└── README.md
```

## Tech Stack

| Component | Technology | Details |
|-----------|-----------|---------|
| Server | Java 17 + Spring Boot 3.5.10 | Gradle, JPA/Hibernate, QueryDSL, Spring AI, Spring Security |
| Agent | Go 1.24 | Single binary, zero runtime dependencies |
| Web | React 19 + TypeScript + Vite 7 | Ant Design 6 UI components |
| Database | MySQL 8.0 | Primary store, Hibernate auto-DDL |
| Cache | Redis 7 | Session/cache store |
| Vector Store | SimpleVectorStore (file-based) | AI memory & SOP features |

## Quick Start
### Docker (Recommended)

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # Edit .env if needed
docker compose up -d
```

Open `http://localhost:18880` → login with `easyshell` / `easyshell@changeme`.

### Manual Development Setup

See **[Installation Guide](./INSTALL.md)** for manual build instructions.

## Communication Protocol

- **HTTP**: Agent registration, heartbeat (30s), metrics reporting (60s), config polling
- **WebSocket** (on-demand): Real-time log streaming during script execution, interactive terminal

## Features

| Category | Features |
|----------|----------|
| **Infrastructure** | Host management, host detail & monitoring, cluster grouping |
| **Operations** | Script library, batch task execution, real-time logs, web terminal |
| **AI Intelligence** | AI chat assistant, scheduled inspections, inspection reports, operation approvals |
| **Administration** | User management, system configuration, AI model settings, risk control, agent orchestration, memory store, SOP manager |
| **Platform** | i18n (English / Chinese), dark/light theme, responsive design, audit logging |

## License

MIT
