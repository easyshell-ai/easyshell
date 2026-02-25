# EasyShell

**轻量级服务器管理与智能运维平台**

Server-Agent 架构 | 批量脚本执行 | 实时日志 | AI 驱动运维

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)

**语言**: [English](./README.md) | 简体中文 | [繁體中文](./README.zh-TW.md) | [한국어](./README.ko.md) | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## 快速开始

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 按需修改 .env
docker compose up -d
```

无需本地构建 — 预构建镜像将自动从 [GHCR](https://github.com/orgs/easyshell-ai/packages) 拉取。

打开 `http://localhost:18880` → 使用 `easyshell` / `easyshell@changeme` 登录。

> **想使用 Docker Hub？** 在 `.env` 中设置：
> ```
> EASYSHELL_SERVER_IMAGE=laolupaojiao/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=laolupaojiao/easyshell-web:latest
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
| **AI 智能** | AI 对话、定时巡检、自动报告、操作审批 |
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

### Bot 集成（Telegram / Discord / DingTalk / Feishu / Slack / WeCom）
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

## 许可证

本项目采用 [MIT 许可证](./LICENSE)。
