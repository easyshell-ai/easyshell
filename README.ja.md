# EasyShell

**軽量サーバー管理＆インテリジェント運用プラットフォーム**

Server-Agent アーキテクチャ | バッチスクリプト実行 | リアルタイムログ | AI 駆動オペレーション

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)

**言語**: [English](./README.md) | [繁體中文](./README.zh-TW.md) | [한국어](./README.ko.md) | [Русский](./README.ru.md) | 日本語

---

## クイックスタート

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 必要に応じて .env を編集
docker compose up -d
```

ローカルビルド不要 — ビルド済みイメージが [GHCR](https://github.com/orgs/easyshell-ai/packages) から自動的にプルされます。

`http://localhost:18880` を開く → `easyshell` / `easyshell@changeme` でログイン。

> **Docker Hub を使用する場合は？** `.env` で設定：
> ```
> EASYSHELL_SERVER_IMAGE=laolupaojiao/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=laolupaojiao/easyshell-web:latest
> ```

> **開発者向け：ソースからビルド：**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

## アーキテクチャ

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

## 技術スタック

| コンポーネント | テクノロジー |
|---------------|-------------|
| Server | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, シングルバイナリ, ランタイム依存ゼロ |
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| データベース | MySQL 8.0 |
| キャッシュ | Redis 7 |

## 機能

| カテゴリ | 機能 |
|---------|------|
| **インフラ** | ホスト管理、モニタリング、クラスターグルーピング |
| **運用** | スクリプトライブラリ、バッチ実行、リアルタイムログ、Web ターミナル |
| **AI インテリジェンス** | AI チャット、定期点検、レポート、操作承認 |
| **管理** | ユーザー管理、システム設定、AI モデル構成、リスク制御、Agent オーケストレーション |
| **プラットフォーム** | 国際化（EN / ZH）、ダーク/ライトテーマ、レスポンシブデザイン、監査ログ |

## スクリーンショット

### ホスト管理
![ホスト管理](https://easyshell.ai/images/features/host-management.png)

### スクリプト実行
![スクリプト実行](https://easyshell.ai/images/features/script-execution.png)

### リアルタイムログ
![リアルタイムログ](https://easyshell.ai/images/features/realtime-logs.png)

### Web ターミナル
![Web ターミナル](https://easyshell.ai/images/features/web-terminal.png)

### AI オペレーション
![AI オペレーション](https://easyshell.ai/images/features/ai-operations.png)

### セキュリティ制御
![セキュリティ制御](https://easyshell.ai/images/features/security-controls.png)

### Bot 連携（Telegram / Discord / DingTalk / Feishu / Slack / WeCom）
![Bot 連携](docs/images/bot-integration.png)

## プロジェクト構造

```
easyshell/
├── easyshell-server/           # 中央管理サーバー（Java / Spring Boot）
├── easyshell-agent/            # Agent クライアント（Go, シングルバイナリ）
├── easyshell-web/              # Web フロントエンド（React + Ant Design）
├── docker-compose.yml          # 本番デプロイ（ビルド済みイメージをプル）
├── docker-compose.build.yml    # 開発環境（ソースからローカルビルド）
├── Dockerfile.server           # Server + Agent マルチステージビルド
├── Dockerfile.web              # Web フロントエンド マルチステージビルド
├── .github/workflows/          # CI/CD: Docker イメージのビルドと公開
└── .env.example                # 環境変数設定テンプレート
```

## ドキュメント

**[docs.easyshell.ai](https://docs.easyshell.ai)** をご覧ください：

- インストール＆デプロイガイド
- はじめに
- 設定リファレンス
- 開発ガイド

## ライセンス

このプロジェクトは [MIT ライセンス](./LICENSE) の下で公開されています。
