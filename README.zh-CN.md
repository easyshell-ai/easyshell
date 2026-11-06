<p align="center">
  <img src="docs/images/logo.png" alt="EasyShell Logo" width="200" />
</p>

# EasyShell

**AI 原生服务器运维平台**

让 AI 为你编写脚本、编排多机任务并分析基础设施 —— 而你只需关注核心决策。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)
[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

**语言**: [English](./README.md) | 简体中文 | [繁體中文](./README.zh-TW.md) | [한국어](./README.ko.md) | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## 为什么选择 EasyShell？

传统的服务器管理工具需要你亲手编写每个脚本、登录每台机器并自行解读输出结果。EasyShell 颠覆了这一模式：**AI 是执行者，你是决策者。**

- **用自然语言描述需求** → AI 编写生产级 Shell 脚本，并支持差异比对预览
- **设定跨多机目标** → AI 规划执行步骤、运行任务并汇总分析结果
- **配置定时巡检任务** → AI 分析输出结果并自主决策是否通过机器人频道告警通知团队
- **通过 Web SSH 连接** → 全功能终端，集成文件管理器、多标签页及搜索 —— 无需本地客户端

---

## 核心功能

### 1. AI 脚本助手

> 描述你的需求。AI 编写脚本。查看差异比对。一键应用执行。

AI 脚本工作台是一个双栏编辑器，你只需用自然语言描述需求，AI 即可针对目标操作系统生成（或修改）Shell 脚本。支持实时流式输出，内置差异比对视图（Diff View）精准定位改动，并提供中文摘要解释修改内容。

<p align="center">
  <img src="docs/images/AI%20Script%20helper.png" alt="AI 脚本助手 —— 实时代码生成与差异比对" width="90%" />
</p>

**工作流程：**
1. **描述** —— 用自然语言描述需求，选择目标操作系统
2. **生成** —— AI 实时流式生成生产级脚本
3. **审查** —— 内置差异比对视图高亮显示所有变更；摘要标签解释修改内容
4. **应用** —— 一键保存至脚本库或立即下发执行

### 2. AI 任务编排

> “检查所有主机的磁盘和内存，标记占用超过 80% 的项，并给出修复建议。” —— 已完成。

AI 对话界面允许你下达高层级的运维目标。AI 会将其拆解为多步执行计划（探索 → 分析 → 报告），将脚本分发至目标主机，收集执行结果，并在单次对话中交付包含风险评估和操作建议的结构化分析报告。

<p align="center">
  <img src="docs/images/AI%20task%20orchestration.png" alt="AI 任务编排 —— 多步执行计划与分析" width="90%" />
</p>

**工作流程：**
1. **指令** —— 在 AI 对话中描述高层级运维目标（如「检查所有主机磁盘使用率」）
2. **规划** —— AI 将目标拆解为多步执行计划（探索 → 分析 → 报告）
3. **执行** —— 脚本并行分发至目标主机，结果自动收集
4. **报告** —— AI 生成包含风险评估和操作建议的结构化分析报告

### 3. AI 定时巡检

> **定时任务 → 脚本执行 → AI 智能分析 → 智能告警** —— AI 分析输出结果并自主决策是否告警。

通过 Cron 表达式安排巡检任务，并从内置脚本库中选择脚本。EasyShell 按计划将脚本分发至 Agent，收集输出内容（磁盘、内存、服务、日志），交由 AI 模型进行智能分析，**由 AI 判断是否需要告警** —— 只在真正需要关注时才推送通知。

<p align="center">
  <img src="docs/images/schedule_task.png" alt="AI 定时巡检 —— 基于 Cron 的定时任务，AI 智能分析输出并自主决策告警" width="90%" />
</p>

**工作流程：**
1. **配置** —— Cron 表达式 + 脚本（从脚本库选择或自定义）+ AI 分析提示词 + 通知规则
2. **执行** —— EasyShell 按计划将脚本分发到目标 Agent
3. **分析** —— 输出发送到 AI 模型（OpenAI / Gemini / GitHub Copilot / 自定义）进行智能分析
4. **通知** —— AI 评估严重程度，在需要时通过机器人频道推送告警

**通知模式：** 始终推送、失败时推送、警告时推送、或 **AI 自主决策** —— AI 模型评估输出内容并自主判断是否需要告警。

**支持的机器人频道** ([配置指南](https://docs.easyshell.ai/configuration/bot-channels/)):

| 机器人 | 状态 |
|-----|--------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [钉钉 (DingTalk)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [飞书 (Feishu)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |
| [企业微信 (WeCom)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支持 |

### 4. 全功能 Web SSH

> 真正的终端。真正的文件管理器。无需安装 SSH 客户端。

生产级的 Web 终端，支持多标签会话、集成式文件管理器（上传、下载、创建、删除、导航）、终端回滚缓冲区全文本搜索，并通过 WebSocket 保持长连接。让你在运行命令的同时高效管理文件。

<p align="center">
  <img src="docs/images/Fully%20functional%20web%20SSH.png" alt="Web SSH —— 终端集成文件管理器与多标签页" width="90%" />
</p>

### 5. 主机管理与监控

> 统一视图展示所有服务器的实时状态，支持批量操作和 Agent 生命周期管理。

单独或批量管理主机 —— 通过表单或 CSV 导入添加、按集群组织、监控连接状态，一键部署/升级 Agent。统一仪表板让健康指标一目了然。

<p align="center">
  <img src="docs/images/host-management.png" alt="主机管理 —— 支持批量操作的统一服务器仪表板" width="90%" />
</p>

### 6. 实时流式日志

> 实时观察脚本在所有目标主机上的执行过程。

当你下发脚本时，EasyShell 会从每个 Agent 实时流式传输输出内容。彩色日志、时间戳和按主机筛选功能，让你能即时发现问题 —— 无需再等待批处理任务完成。

<p align="center">
  <img src="docs/images/realtime-logs.png" alt="实时日志 —— 多主机实时流式输出" width="90%" />
</p>

### 7. 安全与风控

> 内置安全机制：审批流程、审计追踪和操作限制。

配置哪些操作在执行前需要审批。所有操作都会被记录以满足合规要求。基于角色的访问控制限制「谁可以做什么」，敏感命令可以被标记或完全禁止。

<p align="center">
  <img src="docs/images/security-controls.png" alt="安全控制 —— 审批流程与审计日志" width="90%" />
</p>

---

## 快速开始

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
./scripts/deploy.sh       # 一条命令：首次运行自动生成 .env（随机密钥），随后启动全部组件并等待健康检查通过
```

`scripts/deploy.sh` 会在首次运行时生成含随机密钥的 `.env`（数据库密码、JWT 密钥、加密密钥、
agent 注册密钥），然后启动整个栈。常用参数：

```bash
./scripts/deploy.sh --build    # 用当前源码构建镜像后启动（而非拉取预构建镜像）
./scripts/deploy.sh --down     # 停止全部组件（保留数据卷）
```

默认路径无需本地构建 —— 预构建镜像将自动从 [Docker Hub](https://hub.docker.com/u/laolupaojiao) 拉取；
如需运行当前源码（包含尚未发布的安全修复），请使用 `--build`。

访问 `http://localhost:18880` → 使用 `easyshell` 登录。首次启动时服务端会在日志中打印随机生成的初始密码（也可通过设置 `EASYSHELL_ADMIN_PASSWORD` 自行指定），登录后请立即修改。

> **想使用 GHCR？** 在 `.env` 中设置：
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **开发者？从源码构建：**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

---

## 完整功能集

| 类别 | 功能特性 |
|----------|----------|
| **AI 智能** | AI 脚本助手（生成 / 修改 / 差异比对 / 摘要）、AI 任务编排（多步计划、并行执行、深度分析）、AI 定时巡检（Cron 定时、AI 输出分析、智能告警决策、多频道机器人推送）、AI 对话、巡检报告、操作审批 |
| **运维操作** | 脚本库、批量执行、实时流式日志、集成文件管理器的 Web SSH 终端 |
| **基础设施** | 主机管理、实时监控、集群分组、Agent 自动部署 |
| **系统管理** | 用户管理、系统配置、AI 模型设置（OpenAI / Gemini / Copilot / 自定义）、风险控制 |
| **平台特性** | 国际化（中/英）、深色/浅色主题、响应式设计、审计日志 |

---

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
|-----------|-----------|
| Server | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, 单一二进制文件, 零运行时依赖 |
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| Database | MySQL 8.0 |
| Cache | Redis 7 |

## 项目结构

```
easyshell/
├── easyshell-server/           # 中央管理服务器 (Java / Spring Boot)
├── easyshell-agent/            # Agent 客户端 (Go, 单一二进制文件)
├── easyshell-web/              # Web 前端 (React + Ant Design)
├── docker-compose.yml          # 生产环境部署 (拉取预构建镜像)
├── docker-compose.build.yml    # 开发环境部署 (从源码本地构建)
├── Dockerfile.server           # Server + Agent 多阶段构建
├── Dockerfile.web              # Web 前端多阶段构建
├── .github/workflows/          # CI/CD: 构建与发布 Docker 镜像
└── .env.example                # 环境变量配置模板
```

## 文档

访问 **[docs.easyshell.ai](https://docs.easyshell.ai)** 获取：

- 安装与部署指南
- 快速入门指引
- 配置参考
- 开发指南

## 社区

[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

加入我们的 Discord 社区获取支持、参与讨论并获取更新动态：
**[https://discord.gg/akQqRgNB6t](https://discord.gg/akQqRgNB6t)**

## 许可证

本项目采用 [MIT 许可证](./LICENSE) 开源。
