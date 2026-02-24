# EasyShell Getting Started Guide

Welcome to EasyShell — a lightweight server management and intelligent operations platform. This guide walks you through your first session: logging in, adding hosts, running scripts, and exploring AI features.

> **Prerequisite**: EasyShell must be installed and running. See [INSTALL.md](./INSTALL.md) for setup instructions.

## Table of Contents

- [1. Login](#1-login)
- [2. Dashboard Overview](#2-dashboard-overview)
- [3. Host Management](#3-host-management)
- [4. Script Management](#4-script-management)
- [5. Task Execution](#5-task-execution)
- [6. Web Terminal](#6-web-terminal)
- [7. Cluster Management](#7-cluster-management)
- [8. AI Operations](#8-ai-operations)
- [9. System Administration](#9-system-administration)
- [10. Language & Theme](#10-language--theme)
- [Feature Reference](#feature-reference)

---

## 1. Login

Open EasyShell in your browser (e.g. `http://your-server-ip`).

**Default credentials:**
- Username: `easyshell`
- Password: `easyshell@changeme`

> Change the default password immediately after first login via **System** → **Users**.

---

## 2. Dashboard Overview

The dashboard is your landing page after login. It provides:

- **Host statistics** — Total hosts, online/offline counts, host status distribution
- **Task statistics** — Recent task execution results, success/failure rates
- **System health** — Quick overview of the platform's operational state
- **Quick navigation** — Direct links to common actions

---

## 3. Host Management

Navigate to **Hosts** in the sidebar.

### Viewing Hosts

The host list shows all registered hosts with:
- Hostname, IP address, OS, architecture
- Connection status (Online / Offline / Unknown)
- CPU, memory, and disk usage (updated every 60 seconds)
- Agent version

### Adding a Host via Agent

There are two ways to register a host:

#### Option A: Automatic Deployment (Recommended)

1. Click **Install Agent** on a host entry (or add a new host)
2. Enter the target host's SSH credentials:
   - IP address and SSH port (default: 22)
   - Username and password (or SSH key)
3. EasyShell will:
   - SSH into the target host
   - Detect the architecture (amd64/arm64)
   - Upload the correct Agent binary
   - Start the Agent process

#### Option B: Manual Agent Installation

1. Copy the Agent binary to the target host
2. Create a config file pointing to your EasyShell server
3. Start the Agent

See the [Agent Deployment section in INSTALL.md](./INSTALL.md#agent-deployment) for detailed steps.

### Host Detail Page

Click any host row to open its detail page:
- **System Information** — OS, kernel, CPU model, total memory
- **Resource Usage** — Real-time CPU, memory, and disk utilization with charts
- **Installed Software** — Detected services (Nginx, MySQL, Docker, etc.)
- **Quick Actions** — Open terminal, run script, view history

---

## 4. Script Management

Navigate to **Scripts** in the sidebar.

### Creating a Script

1. Click **Create Script**
2. Fill in:
   - **Name** — descriptive name (e.g. "Check Disk Usage")
   - **Content** — the shell script to execute
   - **Description** — (optional) what this script does
3. Click **Save**

### Script Examples

**Check disk usage:**
```bash
df -h | head -20
```

**Check memory usage:**
```bash
free -h
```

**List running services:**
```bash
systemctl list-units --type=service --state=running
```

Scripts are stored in the database and can be reused across multiple task executions.

---

## 5. Task Execution

Navigate to **Tasks** in the sidebar.

### Running a Task

1. Click **Create Task**
2. Select:
   - **Script** — choose from your saved scripts, or enter ad-hoc commands
   - **Target Hosts** — select one or more hosts to execute on
   - **Execution parameters** — timeout, concurrency, etc.
3. Click **Execute**

### Monitoring Execution

The task list shows all executions with:
- Task name, status (Running / Completed / Failed / Cancelled)
- Success/failed/total host counts
- Creation time

Click a task row to see the **Task Detail**:
- Per-host execution status (each host is a "Job")
- Real-time execution logs for each job
- Execution output and exit codes

### Execution Model

- Tasks are dispatched to all selected hosts **simultaneously**
- Each host's Agent receives the command via WebSocket
- Execution is **real-time** — agents execute immediately upon receiving the command
- Logs stream back in real-time via WebSocket
- Execution speed varies by host (depends on command complexity and host performance)

---

## 6. Web Terminal

Access a remote terminal session directly in the browser.

### Opening a Terminal

1. Go to **Hosts** → click on a host
2. Click the **Terminal** button in the host detail page
3. A full-featured terminal opens via WebSocket → SSH

Or navigate directly to **Terminal** from a host's action menu.

The terminal supports:
- Full interactive shell (bash/zsh)
- Copy/paste
- Terminal resize
- Persistent connection during the session

---

## 7. Cluster Management

Navigate to **Clusters** in the sidebar.

Clusters let you group hosts for organized management:

1. Click **Create Cluster**
2. Give it a name and description
3. Assign hosts to the cluster

When creating tasks, you can select a cluster to target all its hosts at once.

---

## 8. AI Operations

EasyShell includes AI-powered intelligent operations. These features require an AI provider configuration (see [Section 9](#ai-settings)).

### AI Chat

Navigate to **AI Ops** → **AI Chat**.

The AI assistant can:
- **Diagnose server issues** — "Check why the disk is full on host-01"
- **Execute operations** — "Restart nginx on all web servers"
- **Generate scripts** — "Write a script to clean Docker images older than 30 days"
- **Analyze results** — The AI reviews command output and provides insights

The AI operates through a multi-step orchestration pipeline:
1. **Planning** — AI creates an execution plan
2. **Approval** (if configured) — High-risk operations require human approval
3. **Execution** — Commands are dispatched to target hosts
4. **Review** — AI analyzes results and suggests next steps

Each conversation is stored and can be resumed from the sidebar.

### Scheduled Tasks

Navigate to **AI Ops** → **Scheduled Tasks**.

Create AI-powered scheduled operations:
- Periodic health checks
- Automated inspection routines
- Recurring maintenance tasks

### Inspect Reports

Navigate to **AI Ops** → **Inspect Reports**.

View AI-generated inspection reports from scheduled tasks, including:
- System health analysis
- Performance metrics
- Anomaly detection findings
- Recommended actions

### Approvals

Navigate to **AI Ops** → **Approvals**.

When the AI proposes high-risk operations, they appear here for review:
- View the proposed command and its risk assessment
- Approve or reject the operation
- View execution results after approval

---

## 9. System Administration

Navigate to **System** in the sidebar.

### Users

Manage platform users and roles:
- Create new users with username/password
- Assign roles (admin, operator, viewer)
- Reset passwords

### Configuration

System-wide configuration parameters stored in the database:
- Agent heartbeat interval, metrics interval
- AI model settings
- Feature toggles

> System configuration parameters cannot be deleted — they are essential for platform operation.

### AI Settings

Configure AI model providers:
1. **Add a provider** — Choose OpenAI, Anthropic, Ollama, or OpenAI-compatible
2. **Enter API credentials** — API key and (optional) custom base URL
3. **Configure models** — Select which models to use for chat and embedding
4. **Test connection** — Verify the configuration works

**OpenAI-compatible providers** (Gemini, DeepSeek, Groq, etc.):
- Use the OpenAI provider type
- Set the custom base URL (e.g. `https://generativelanguage.googleapis.com/v1beta/openai` for Gemini)
- Enter the provider's API key

### Risk Control

Configure safety rules for AI-executed commands:
- Define dangerous command patterns (e.g. `rm -rf /`, `dd if=`)
- Set risk levels (block, require approval, warn)
- Commands matching risk rules require human approval before execution

### Agent Manager

Configure the AI orchestration agents:
- Set the main agent's model provider and model
- Configure maximum iteration count
- Manage sub-agent definitions
- Configure available tools and permissions

### Memory Store

View and manage the AI's conversation memory:
- Browse stored memory embeddings
- Delete irrelevant memories
- Memory is stored in a file-based VectorStore (`data/memory-vectors.json`)

### SOP Manager

View auto-extracted Standard Operating Procedures:
- SOPs are **automatically** generated when the AI successfully completes multi-step operations
- On similar future requests, the AI retrieves matching SOPs to guide execution
- Browse, view, and delete extracted SOPs

**Prerequisites for SOP generation:**
1. An embedding model must be configured in AI Settings
2. `ai.memory.sop-enabled` must be `true` in System Configuration
3. Multiple successful multi-step AI conversations must have occurred

---

## 10. Language & Theme

### Language Switching

EasyShell supports **English** and **Simplified Chinese** (简体中文).

- Click the **globe icon** (🌐) in the top-right header bar
- The interface switches instantly
- Your preference is saved in the browser

Default language is **English**. All UI elements, status labels, and messages are fully localized.

### Theme Switching

- Click the **sun/moon icon** in the top-right header bar
- Toggle between **light mode** and **dark mode**
- Your preference is saved in the browser

---

## Feature Reference

| Feature | Path | Description |
|---------|------|-------------|
| Dashboard | `/` | System overview and statistics |
| Hosts | `/host` | Host management and monitoring |
| Host Detail | `/host/:agentId` | Individual host details and resources |
| Web Terminal | `/terminal/:agentId` | Browser-based SSH terminal |
| Scripts | `/script` | Script library management |
| Tasks | `/task` | Task execution and monitoring |
| Clusters | `/cluster` | Host grouping |
| AI Chat | `/ai/chat` | AI-powered operations assistant |
| Scheduled Tasks | `/ai/scheduled` | AI scheduled inspections |
| Inspect Reports | `/ai/reports` | AI-generated reports |
| Approvals | `/ai/approval` | Human review for AI operations |
| Audit Log | `/audit` | Platform activity log |
| Users | `/system/users` | User management |
| Configuration | `/system/config` | System parameters |
| AI Settings | `/system/ai` | AI model provider configuration |
| Risk Control | `/system/risk` | Dangerous command rules |
| Agent Manager | `/system/agents` | AI agent orchestration config |
| Memory Store | `/system/memory` | AI memory management |
| SOP Manager | `/system/sop` | Auto-extracted operation procedures |

---

## Next Steps

- **Add your first host** — Install an Agent on a server you manage
- **Create a script** — Write a simple health-check script
- **Run your first task** — Execute the script across your hosts
- **Try AI Chat** — Configure an AI provider and ask the AI to inspect a host
- **Explore the API** — Visit `/swagger-ui.html` on the server for the full API documentation
