<p align="center">
  <img src="docs/images/logo.png" alt="EasyShell Logo" width="200" />
</p>

# EasyShell

**轻量级服务器管理与智能运维平台**

Server-Agent 架构 | 批量脚本执行 | 实时日志 | AI 驱动运维 | 机器人通知

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)
[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/WqFD9VQe)

**语言**: [English](./README.md) | 简体中文 | [繁體中文](./README.zh-TW.md) | [한국어](./README.ko.md) | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## 亮点功能：AI 驱动的定时巡检

> **定时任务 → 脚本执行 → AI 智能分析 → 机器人推送** —— 全自动服务器巡检流水线。

EasyShell 可通过 cron 表达式在服务器上运行**定时巡检任务**，自动收集脚本输出（磁盘使用率、服务健康状态、日志等），将结果发送给 **AI 模型进行智能分析**，并通过**机器人渠道**将分析报告推送给您的团队 —— 全程无需人工干预。

**支持的机器人渠道**（[配置指南](https://docs.easyshell.ai/configuration/bot-channels/)）：

| 机器人 | 状态 |
|--------|------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [钉钉 (DingTalk)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [飞书 (Feishu)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [企业微信 (WeCom)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |

**工作流程：**
1. **配置** 定时任务：cron 表达式 + Shell 脚本 + AI 分析提示词
2. **执行** —— EasyShell 按计划将脚本分发到目标 Agent 执行
3. **分析** —— 脚本输出发送到您配置的 AI 模型（OpenAI / Gemini / GitHub Copilot / 自定义）
4. **通知** —— AI 分析报告推送到您的机器人渠道（Telegram、Discord、Slack 等）

## 快速开始

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 按需修改 .env
docker compose up -d
```

无需本地构建 — 预构建镜像将自动从 [Docker Hub](https://hub.docker.com/u/laolupaojiao) 拉取。

打开 `http://localhost:18880` → 使用 `easyshell` / `easyshell@changeme` 登录。

> **想使用 GHCR？** 在 `.env` 中设置：
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **开发者？从源码构建：**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

## 架构

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

## 技术栈

| 组件 | 技术 |
|------|------|
| Server | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, 单一二进制文件, 零运行时依赖 |
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 |

## 功能特性

| 类别 | 功能 |
|------|------|
| **基础设施** | 主机管理、监控、集群分组 |
| **运维操作** | 脚本库、批量执行、实时日志、Web 终端 |
| **AI 智能** | AI 对话、**定时巡检 + AI 分析 + 机器人推送**、巡检报告、操作审批 |
| **系统管理** | 用户管理、系统配置、AI 模型设置、风险控制、Agent 编排 |
| **平台特性** | 国际化（EN / ZH）、深色/浅色主题、响应式设计、审计日志 |

## 截图

### 主机管理
![主机管理](https://easyshell.ai/images/features/host-management.png)

### 脚本执行
![脚本执行](https://easyshell.ai/images/features/script-execution.png)

### 实时日志
![实时日志](https://easyshell.ai/images/features/realtime-logs.png)

### Web 终端
![Web 终端](https://easyshell.ai/images/features/web-terminal.png)

### AI 运维
![AI 运维](https://easyshell.ai/images/features/ai-operations.png)

### 安全管控
![安全管控](https://easyshell.ai/images/features/security-controls.png)

### Bot 集成

支持 [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) · [Discord](https://docs.easyshell.ai/configuration/bot-channels/) · [Slack](https://docs.easyshell.ai/configuration/bot-channels/) · [钉钉](https://docs.easyshell.ai/configuration/bot-channels/) · [飞书](https://docs.easyshell.ai/configuration/bot-channels/) · [企业微信](https://docs.easyshell.ai/configuration/bot-channels/) —— 交互式对话 & 定时巡检通知推送。

![Bot 集成](docs/images/bot-integration.png)

## 项目结构

```
easyshell/
├── easyshell-server/           # 中央管理服务器（Java / Spring Boot）
├── easyshell-agent/            # Agent 客户端（Go, 单一二进制文件）
├── easyshell-web/              # Web 前端（React + Ant Design）
├── docker-compose.yml          # 生产部署（拉取预构建镜像）
├── docker-compose.build.yml    # 开发环境（从源码本地构建）
├── Dockerfile.server           # Server + Agent 多阶段构建
├── Dockerfile.web              # Web 前端多阶段构建
├── .github/workflows/          # CI/CD：构建与发布 Docker 镜像
└── .env.example                # 环境变量配置模板
```

## 文档

前往 **[docs.easyshell.ai](https://docs.easyshell.ai)** 查看：

- 安装与部署指南
- 快速入门指引
- 配置参考手册
- 开发指南

## 社区

[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/WqFD9VQe)

加入我们的 Discord 社区，获取支持、参与讨论和了解最新动态：
**[https://discord.gg/WqFD9VQe](https://discord.gg/WqFD9VQe)**

## 许可证

本项目采用 [MIT 许可证](./LICENSE)。
