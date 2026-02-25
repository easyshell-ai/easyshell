# EasyShell

**輕量級伺服器管理與智慧運維平台**

Server-Agent 架構 | 批次腳本執行 | 即時日誌 | AI 驅動運維

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)

**語言**: [English](./README.md) | [简体中文](./README.zh-CN.md) | 繁體中文 | [한국어](./README.ko.md) | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## 快速開始

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 按需修改 .env
docker compose up -d
```

無需本地建構 — 預建構映像檔將自動從 [GHCR](https://github.com/orgs/easyshell-ai/packages) 拉取。

開啟 `http://localhost:18880` → 使用 `easyshell` / `easyshell@changeme` 登入。

> **想使用 Docker Hub？** 在 `.env` 中設定：
> ```
> EASYSHELL_SERVER_IMAGE=laolupaojiao/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=laolupaojiao/easyshell-web:latest
> ```

> **開發者？從原始碼建構：**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

## 架構

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

## 技術棧

| 組件 | 技術 |
|------|------|
| Server | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, 單一二進位檔案, 零執行時依賴 |
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| 資料庫 | MySQL 8.0 |
| 快取 | Redis 7 |

## 功能特性

| 類別 | 功能 |
|------|------|
| **基礎設施** | 主機管理、監控、叢集分組 |
| **運維操作** | 腳本庫、批次執行、即時日誌、Web 終端 |
| **AI 智慧** | AI 對話、定時巡檢、自動報告、操作審批 |
| **系統管理** | 使用者管理、系統設定、AI 模型配置、風險控制、Agent 編排 |
| **平台特性** | 國際化（EN / ZH）、深色/淺色主題、響應式設計、審計日誌 |

## 截圖

### 主機管理
![主機管理](https://easyshell.ai/images/features/host-management.png)

### 腳本執行
![腳本執行](https://easyshell.ai/images/features/script-execution.png)

### 即時日誌
![即時日誌](https://easyshell.ai/images/features/realtime-logs.png)

### Web 終端
![Web 終端](https://easyshell.ai/images/features/web-terminal.png)

### AI 運維
![AI 運維](https://easyshell.ai/images/features/ai-operations.png)

### 安全管控
![安全管控](https://easyshell.ai/images/features/security-controls.png)

### Bot 整合（Telegram / Discord / DingTalk / Feishu / Slack / WeCom）
![Bot 整合](docs/images/bot-integration.png)

## 專案結構

```
easyshell/
├── easyshell-server/           # 中央管理伺服器（Java / Spring Boot）
├── easyshell-agent/            # Agent 用戶端（Go, 單一二進位檔案）
├── easyshell-web/              # Web 前端（React + Ant Design）
├── docker-compose.yml          # 生產部署（拉取預建構映像檔）
├── docker-compose.build.yml    # 開發環境（從原始碼本地建構）
├── Dockerfile.server           # Server + Agent 多階段建構
├── Dockerfile.web              # Web 前端多階段建構
├── .github/workflows/          # CI/CD：建構與發佈 Docker 映像檔
└── .env.example                # 環境變數設定範本
```

## 文件

前往 **[docs.easyshell.ai](https://docs.easyshell.ai)** 查看：

- 安裝與部署指南
- 快速入門導覽
- 設定參考手冊
- 開發指南

## 授權條款

本專案採用 [MIT 授權條款](./LICENSE)。
