# EasyShell Installation Guide

## Table of Contents

- [Prerequisites](#prerequisites)
- [Architecture Overview](#architecture-overview)
- [Option A: Docker Compose (Recommended)](#option-a-docker-compose-recommended)
- [Option B: Manual Installation](#option-b-manual-installation)
- [Option C: Bare Metal Production Deployment](#option-c-bare-metal-production-deployment)
- [Agent Deployment](#agent-deployment)
- [Nginx Reverse Proxy](#nginx-reverse-proxy)
- [AI Configuration (Optional)](#ai-configuration-optional)
- [Verification](#verification)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

| Component | Required Version | Check Command |
|-----------|-----------------|---------------|
| Java | 17+ | `java -version` |
| Node.js | 18+ | `node -v` |
| npm | 8+ | `npm -v` |
| Go | 1.23+ | `go version` |
| Docker & Docker Compose | 20+ / v2+ | `docker --version && docker compose version` |
| Git | 2.30+ | `git --version` |

> **Note**: Go is only required if you need to build the Agent binary from source. Pre-built binaries for `linux/amd64` and `linux/arm64` are included in the repository.

---

## Architecture Overview

```
┌──────────────┐    HTTPS    ┌──────────────────┐    SSH/WS    ┌───────────────┐
│   Browser    │ ──────────► │   EasyShell Web   │              │  Target Host  │
│   (React)    │             │   (Nginx/Vite)    │              │               │
└──────────────┘             └────────┬─────────┘              │  ┌─────────┐  │
                                      │ /api proxy              │  │  Agent  │  │
                                      ▼                         │  │  (Go)   │  │
                             ┌──────────────────┐    HTTP/WS    │  └────┬────┘  │
                             │  EasyShell Server │ ◄────────────┤       │       │
                             │  (Spring Boot)    │              │   heartbeat   │
                             │    :18080         │              │   metrics     │
                             └──┬────┬────┬─────┘              │   exec logs   │
                                │    │    │                     └───────────────┘
                         ┌──────┘    │    └──────┐
                         ▼           ▼           ▼
                    ┌─────────┐ ┌─────────┐ ┌────────────┐
                    │ MySQL   │ │  Redis  │ │ VectorStore│
                    │  :3306  │ │  :6379  │ │ (file-based)│
                    └─────────┘ └─────────┘ └────────────┘
```

| Component | Purpose |
|-----------|---------|
| **easyshell-web** | React + TypeScript + Ant Design frontend. Communicates with server via `/api` proxy. |
| **easyshell-server** | Java 17 + Spring Boot 3.5. REST API, WebSocket, AI orchestration, SSH provisioning. |
| **easyshell-agent** | Go single-binary agent installed on managed hosts. Heartbeat, metrics, script execution. |
| **MySQL 8.0** | Primary database. Schema managed by Hibernate auto-DDL (`ddl-auto: update`). |
| **Redis 7** | Session/cache store required by Spring Boot Data Redis starter. |
| **VectorStore** | In-memory `SimpleVectorStore` persisted to `data/memory-vectors.json`. No external vector DB needed. |

---

## Option A: Docker Compose (Recommended)

The fastest way to get infrastructure running. Server and Web are built/run separately.

### 1. Clone the repository

```bash
git clone https://github.com/easyshell-org/easyshell.git
cd easyshell
```

### 2. Start infrastructure services

```bash
docker compose up -d
```

This starts **MySQL 8.0** and **Redis 7**. Wait for health checks to pass:

```bash
docker compose ps
# Both services should show "healthy"
```

### 3. Build and start the Server

```bash
cd easyshell-server
./gradlew bootJar
java -jar build/libs/easyshell-server-0.1.0-SNAPSHOT.jar --server.port=18080
```

The server will:
- Auto-create all database tables on first start (Hibernate DDL auto)
- Create a default admin user: `admin` / `admin123` (change immediately)
- Listen on port `18080`

### 4. Build and serve the Web frontend

```bash
cd easyshell-web
npm install
npm run build
```

For development:
```bash
npm run dev
# Vite dev server at http://localhost:5173, proxies /api → localhost:18080
```

For production, serve the `dist/` directory with Nginx (see [Nginx Reverse Proxy](#nginx-reverse-proxy)).

### 5. Build the Agent (optional — pre-built binaries available)

```bash
cd easyshell-agent

# Build for current platform
go build -o easyshell-agent ./cmd/agent

# Cross-compile for Linux targets
GOOS=linux GOARCH=amd64 go build -o easyshell-agent-linux-amd64 ./cmd/agent
GOOS=linux GOARCH=arm64 go build -o easyshell-agent-linux-arm64 ./cmd/agent
```

Pre-built binaries are available in `easyshell-agent/`:
- `easyshell-agent-linux-amd64`
- `easyshell-agent-linux-arm64`

---

## Option B: Manual Installation

Install MySQL and Redis directly on the host without Docker.

### MySQL 8.0

**Ubuntu/Debian:**
```bash
sudo apt update && sudo apt install -y mysql-server-8.0
sudo systemctl enable --now mysql

sudo mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'your-password';"
sudo mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS easyshell CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

**CentOS/RHEL:**
```bash
sudo yum install -y mysql-server
sudo systemctl enable --now mysqld

# Get temporary password
sudo grep 'temporary password' /var/log/mysqld.log
mysql -u root -p  # Use temp password, then change it
```

### Redis 7

**Ubuntu/Debian:**
```bash
sudo apt update && sudo apt install -y redis-server
sudo systemctl enable --now redis-server
redis-cli ping  # Should return PONG
```

**CentOS/RHEL:**
```bash
sudo yum install -y redis
sudo systemctl enable --now redis
```

### Update Server Configuration

Edit `easyshell-server/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://YOUR_MYSQL_HOST:3306/easyshell?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: YOUR_MYSQL_PASSWORD

  data:
    redis:
      host: YOUR_REDIS_HOST
      port: 6379
      # password: YOUR_REDIS_PASSWORD  # Uncomment if Redis requires auth
```

Then build and start the server as shown in [Option A, Step 3](#3-build-and-start-the-server).

---

## Option C: Bare Metal Production Deployment

For production deployments without Docker. This section covers the full stack on a single server.

### 1. Install all dependencies

```bash
# Java 17
sudo apt install -y openjdk-17-jdk

# Node.js 22 (via NodeSource)
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs

# MySQL 8.0
sudo apt install -y mysql-server-8.0

# Redis
sudo apt install -y redis-server

# Go 1.23+ (only for building Agent from source)
wget https://go.dev/dl/go1.23.6.linux-amd64.tar.gz
sudo tar -C /usr/local -xzf go1.23.6.linux-amd64.tar.gz
echo 'export PATH=$PATH:/usr/local/go/bin' >> ~/.bashrc
source ~/.bashrc

# Nginx
sudo apt install -y nginx
```

### 2. Configure MySQL

```bash
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS easyshell CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'your-secure-password';
FLUSH PRIVILEGES;
SQL
```

### 3. Configure application.yml

Update `easyshell-server/src/main/resources/application.yml` with:

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/easyshell?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: your-secure-password

easyshell:
  auth:
    # CHANGE THIS in production — must be at least 64 characters
    jwt-secret: your-production-jwt-secret-key-that-is-at-least-64-characters-long-here
  security:
    # CHANGE THIS in production — must be exactly 32 characters for AES-256
    encryption-key: your-32-char-aes256-encrypt-key!
  provision:
    server-url: http://YOUR_SERVER_PUBLIC_IP:18080
    agent-binary-dir: /path/to/easyshell/easyshell-agent
```

### 4. Build and run as a systemd service

```bash
cd easyshell-server
./gradlew bootJar
sudo cp build/libs/easyshell-server-0.1.0-SNAPSHOT.jar /opt/easyshell/easyshell-server.jar
```

Create `/etc/systemd/system/easyshell-server.service`:

```ini
[Unit]
Description=EasyShell Server
After=network.target mysql.service redis.service

[Service]
Type=simple
User=easyshell
ExecStart=/usr/bin/java -jar /opt/easyshell/easyshell-server.jar --server.port=18080
Restart=always
RestartSec=10
Environment=JAVA_OPTS=-Xms256m -Xmx512m

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now easyshell-server
sudo systemctl status easyshell-server
```

### 5. Build and deploy frontend

```bash
cd easyshell-web
npm install
npm run build
sudo mkdir -p /www/easyshell
sudo cp -r dist/* /www/easyshell/
```

---

## Agent Deployment

The Agent runs on each managed host and communicates back to the EasyShell Server.

### Automatic Deployment (from Web UI)

1. Go to **Host Management** → click **Install Agent** on a host entry
2. Provide the target host's SSH credentials (IP, port, username, password/key)
3. The server will SSH into the target, upload the correct binary (amd64/arm64), and start the agent

### Manual Deployment

1. Copy the correct binary to the target host:

```bash
scp easyshell-agent/easyshell-agent-linux-amd64 user@target-host:/usr/local/bin/easyshell-agent
```

2. Create the config file at `/etc/easyshell/agent.yaml`:

```yaml
server:
  url: http://YOUR_EASYSHELL_SERVER:18080

agent:
  id: ""  # Leave empty to auto-generate from hostname

heartbeat:
  interval: 30  # seconds

metrics:
  interval: 60  # seconds

log:
  level: info  # debug, info, warn, error
```

3. Run the agent:

```bash
chmod +x /usr/local/bin/easyshell-agent
/usr/local/bin/easyshell-agent --config /etc/easyshell/agent.yaml
```

4. (Optional) Create a systemd service at `/etc/systemd/system/easyshell-agent.service`:

```ini
[Unit]
Description=EasyShell Agent
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/easyshell-agent --config /etc/easyshell/agent.yaml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now easyshell-agent
```

---

## Nginx Reverse Proxy

Serve the frontend and proxy API/WebSocket requests to the backend.

Create `/etc/nginx/sites-available/easyshell`:

```nginx
server {
    listen 80;
    server_name your-domain.com;

    root /www/easyshell;
    index index.html;

    # Frontend — SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API proxy
    location /api/ {
        proxy_pass http://127.0.0.1:18080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }

    # WebSocket proxy
    location /ws/ {
        proxy_pass http://127.0.0.1:18080/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400s;
    }

    # Swagger API docs (optional, disable in production)
    location /swagger-ui/ {
        proxy_pass http://127.0.0.1:18080/swagger-ui/;
    }
    location /v3/api-docs {
        proxy_pass http://127.0.0.1:18080/v3/api-docs;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/easyshell /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

---

## AI Configuration (Optional)

EasyShell includes AI-powered operations features. These require an LLM API key.

### Supported AI Providers

| Provider | Models | Configuration |
|----------|--------|---------------|
| OpenAI | GPT-4o, GPT-4o-mini, etc. | API key + base URL |
| Anthropic | Claude 3.5 Sonnet, Claude 4, etc. | API key |
| Ollama | Llama, Mistral, Qwen, etc. | Local base URL (default: `http://localhost:11434`) |
| OpenAI-compatible | Gemini, DeepSeek, Groq, etc. | API key + custom base URL |

### Setup via Web UI

1. Log in to EasyShell
2. Navigate to **System** → **AI Configuration**
3. Add a model provider with your API key
4. Set the active model in **Agent Management**
5. (Optional) Configure an Embedding model for memory/SOP features

### Embedding Model (for Memory & SOP)

The memory and SOP (Standard Operating Procedure) auto-learning features require an embedding model:

1. Configure an embedding model in **AI Configuration** → **Embedding** section
2. Ensure `ai.memory.sop-enabled` is set to `true` in **System Configuration**
3. The VectorStore persists to `data/memory-vectors.json` (file-based, no external DB required)

---

## Verification

After installation, verify all components are running:

### 1. Infrastructure

```bash
# MySQL
mysql -u root -p -e "SELECT 1;"

# Redis
redis-cli ping
# Expected: PONG
```

### 2. Server

```bash
curl -s http://localhost:18080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | head -c 200

# Expected: JSON response with token
```

### 3. Web Frontend

Open `http://YOUR_SERVER_IP` in a browser. You should see the EasyShell login page.

### 4. Agent Connectivity

After deploying an agent, check the **Host Management** page. The host should appear with status **Online** within 30 seconds (one heartbeat interval).

---

## Troubleshooting

### Server won't start — "Cannot connect to Redis"

Redis must be running before the server starts. Verify with `redis-cli ping`.

If you don't need Redis features and want to start without it, add to `application.yml`:

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
      - org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
```

### Server won't start — "Cannot connect to MySQL"

Check that MySQL is running and the `easyshell` database exists:

```bash
mysql -u root -p -e "SHOW DATABASES LIKE 'easyshell';"
```

### Agent shows "Offline" in Web UI

1. Verify the agent is running: `ps aux | grep easyshell-agent`
2. Verify server URL in `agent.yaml` is reachable from the agent host
3. Check agent logs for connection errors
4. Ensure firewall allows outbound HTTP to server port `18080`

### AI Chat returns errors

1. Verify AI model provider is configured in **System** → **AI Configuration**
2. Check that the API key is valid and has sufficient credits
3. Review server logs: `journalctl -u easyshell-server -f`

### Frontend shows blank page

1. Verify the `dist/` files are in the Nginx root directory
2. Check Nginx config has the SPA fallback: `try_files $uri $uri/ /index.html`
3. Check browser console for errors

---

## Port Reference

| Service | Port | Protocol | Description |
|---------|------|----------|-------------|
| EasyShell Web | 80/443 | HTTP/HTTPS | Frontend (via Nginx) |
| EasyShell Server | 18080 | HTTP/WS | Backend API & WebSocket |
| MySQL | 3306 | TCP | Primary database |
| Redis | 6379 | TCP | Cache/session store |
| Vite Dev Server | 5173 | HTTP | Development only |
| Swagger UI | 18080/swagger-ui | HTTP | API documentation (dev) |
