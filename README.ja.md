<p align="center">
  <img src="docs/images/logo.png" alt="EasyShell Logo" width="200" />
</p>

# EasyShell

**AI ネイティブ サーバー運用プラットフォーム**

AI にスクリプトを書かせ、複数ホストのタスクをオーケストレーションし、インフラを分析させましょう。あなたは重要な意思決定に集中できます。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)
[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

**言語**: [English](./README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-TW.md) | [한국어](./README.ko.md) | [Русский](./README.ru.md) | 日本語

---

## なぜ EasyShell なのか？

従来のサーバー管理ツールでは、すべてのスクリプトを自分で書き、すべてのサーバーに SSH でログインし、すべての出力を自分で解釈する必要がありました。EasyShell はそのモデルを逆転させます。**AI がオペレーターとなり、あなたは意思決定者となります。**

- **自然な言葉で必要なものを説明** → AI が差分レビュー付きで本番環境レベルのシェルスクリプトを作成
- **複数ホストにわたるゴールを設定** → AI が実行ステップを計画し、実行し、結果を要約
- **スケジュール点検タスクを設定** → AI が出力を分析し、Bot チャネル経由でチームに通知すべきかを自律的に判断
- **Web SSH で接続** → ファイルマネージャー、マルチタブ、検索機能を備えたフル機能のターミナル。ローカルクライアントは不要

---

## コア機能

### 1. AI スクリプトアシスタント

> やりたいことを説明するだけ。AI がスクリプトを作成。差分を確認して、ワンクリックで適用。

AI スクリプトワークベンチは、要件を自然言語で説明すると、AI が選択した OS 向けのシェルスクリプトを生成（または修正）する分割パネルエディタです。スクリプトが作成される様子をリアルタイムで確認できます。組み込みの差分ビューにより、変更箇所が正確に強調表示されます。サマリータブでは、修正内容が日本語で解説されます。

<p align="center">
  <img src="docs/images/AI%20Script%20helper.png" alt="AI スクリプトアシスタント — 差分レビュー付きのライブコード生成" width="90%" />
</p>

**動作の仕組み:**
1. **記述** — 自然言語で要件を記述し、ターゲット OS を選択
2. **生成** — AI が本番環境レベルのスクリプトをリアルタイムでストリーミング生成
3. **レビュー** — 組み込みの差分ビューがすべての変更を強調表示、サマリータブが修正内容を解説
4. **適用** — ワンクリックでスクリプトライブラリに保存、または即座に配布

### 2. AI タスクオーケストレーション

> 「すべてのホストのディスクとメモリを確認し、80% を超えているものがあればフラグを立てて、修正案を提示して。」 — これだけで完了です。

AI チャットインターフェースでは、高レベルの運用目標を指示できます。AI はそれらをマルチステップの実行プラン（調査 → 分析 → 報告）に分解し、ターゲットホストにスクリプトを配布して結果を収集します。そして、リスク評価と実行可能な推奨事項を含む構造化された分析結果を、一度のやり取りで提供します。

<p align="center">
  <img src="docs/images/AI%20task%20orchestration.png" alt="AI タスクオーケストレーション — 分析を含むマルチステップ実行プラン" width="90%" />
</p>

**動作の仕組み:**
1. **指示** — AI チャットで高レベルの目標を記述（例：「すべてのホストのディスクを確認」）
2. **計画** — AI が目標をマルチステップ実行プランに分解（調査 → 分析 → 報告）
3. **実行** — スクリプトがターゲットホストに並列配布され、結果が自動収集
4. **報告** — AI がリスク評価と実行可能な推奨事項を含む構造化された分析を提供

### 3. AI スケジュール点検

> **Cron → スクリプト → AI 分析 → インテリジェントアラート** — AI が出力を分析し、チームへの通知が必要かどうかを自律的に判断します。

cron 式で点検タスクをスケジュールし、組み込みスクリプトライブラリからスクリプトを選択できます。EasyShell はスケジュールに従ってエージェントにスクリプトを配布し、出力（ディスク、メモリ、サービス、ログ）を収集して AI モデルに送信しインテリジェントな分析を行い、**AI が結果の重要度を判断して**、対応が必要な場合にのみ通知をプッシュします。

<p align="center">
  <img src="docs/images/schedule_task.png" alt="AI スケジュール点検 — Cron ベースのタスクで AI が出力を分析しインテリジェントなアラートを判断" width="90%" />
</p>

**動作の仕組み:**
1. **設定** — cron 式 + スクリプト（ライブラリから選択またはカスタム）+ AI 分析プロンプト + 通知ルール
2. **実行** — EasyShell がスケジュールに従ってターゲットエージェントに配布
3. **分析** — 出力が AI モデル（OpenAI / Gemini / GitHub Copilot / カスタム）に送信されインテリジェント分析を実行
4. **通知** — AI が重大度を評価し、対応が必要な場合に Bot チャネル経由でアラートをプッシュ

**通知モード:** 常にプッシュ、失敗時にプッシュ、警告時にプッシュ、または **AI が自律判断** — AI モデルが出力を評価し、アラートの必要性を自律的に判断します。

**対応 Bot チャネル** ([設定ガイド](https://docs.easyshell.ai/configuration/bot-channels/)):

| Bot | ステータス |
|-----|--------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 対応済み |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 対応済み |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 対応済み |
| [DingTalk (钉钉)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 対応済み |
| [Feishu (飞书)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 対応済み |
| [WeCom (企业微信)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 対応済み |

### 4. フル機能の Web SSH

> 本物のターミナル。本物のファイルマネージャー。SSH クライアントは不要。

マルチタブセッション、統合ファイルマネージャー（アップロード、ダウンロード、作成、削除、移動）、ターミナルバッファ内の全文検索、WebSocket による永続接続を備えた本番環境グレードの Web ターミナルです。ファイルの管理とコマンドの実行を並行して行えます。

<p align="center">
  <img src="docs/images/Fully%20functional%20web%20SSH.png" alt="Web SSH — ファイルマネージャーとマルチタブを備えたターミナル" width="90%" />
</p>

### 5. ホスト管理とモニタリング

> すべてのサーバーのリアルタイムステータスを統合ビューで表示し、一括操作とエージェントのライフサイクル管理が可能です。

ホストを個別または一括で管理できます — フォームや CSV インポートで追加、クラスタで整理、接続状態の監視、ワンクリックでエージェントのデプロイ/アップグレード。統合ダッシュボードでヘルスメトリクスを一目で確認できます。

<p align="center">
  <img src="docs/images/host-management.png" alt="ホスト管理 — 一括操作対応の統合サーバーダッシュボード" width="90%" />
</p>

### 6. リアルタイムストリーミングログ

> すべてのターゲットホストでスクリプトの実行をライブで確認できます。

スクリプトを配布すると、EasyShell はすべてのエージェントからの出力をリアルタイムでストリーミングします。カラーコード付きログ、タイムスタンプ、ホストごとのフィルタリングで問題を即座に発見できます — バッチジョブの完了を待つ必要はありません。

<p align="center">
  <img src="docs/images/realtime-logs.png" alt="リアルタイムログ — 複数ホストからのライブストリーミング出力" width="90%" />
</p>

### 7. セキュリティとリスク管理

> 組み込みのセーフガード：承認ワークフロー、監査証跡、操作制限。

どの操作が実行前に承認を必要とするかを設定できます。すべてのアクションはコンプライアンスのためにログに記録されます。ロールベースのアクセス制御で「誰が何をできるか」を制限し、機密性の高いコマンドにはフラグを付けたり完全にブロックしたりできます。

<p align="center">
  <img src="docs/images/security-controls.png" alt="セキュリティ管理 — 承認ワークフローと監査ログ" width="90%" />
</p>

---

## クイックスタート

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 必要に応じて .env を編集
docker compose up -d
```

ローカルでのビルドは不要です。ビルド済みイメージが [Docker Hub](https://hub.docker.com/u/laolupaojiao) から自動的にプルされます。

`http://localhost:18880` を開く → `easyshell` でログイン。初回起動時にサーバーログへランダム生成された初期パスワードが出力されます（`EASYSHELL_ADMIN_PASSWORD` で指定することも可能です）。ログイン後すぐに変更してください。

> **GHCR を使用する場合**は、`.env` で以下を設定してください。
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **開発者向け：ソースからビルドする場合：**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

---

## 全機能リスト

| カテゴリ | 機能 |
|----------|----------|
| **AI インテリジェンス** | AI スクリプトアシスタント（生成 / 修正 / 差分 / サマリー）、AI タスクオーケストレーション（マルチステッププラン、並列実行、分析）、AI スケジュール点検（Cron ベース、AI 出力分析、インテリジェントアラート判断、マルチチャネル Bot プッシュ）、AI チャット、点検レポート、操作承認 |
| **運用** | スクリプトライブラリ、一括実行、リアルタイムストリーミングログ、ファイルマネージャー付き Web SSH ターミナル |
| **インフラ** | ホスト管理、リアルタイムモニタリング、クラスターグルーピング、エージェント自動デプロイ |
| **管理** | ユーザー管理、システム構成、AI モデル設定（OpenAI / Gemini / Copilot / カスタム）、リスク制御 |
| **プラットフォーム** | 多言語対応（EN / ZH）、ダーク/ライトテーマ、レスポンシブデザイン、監査ログ |

---

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
|-----------|-----------|
| サーバー | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| エージェント | Go 1.24, シングルバイナリ, ランタイム依存ゼロ |
| Web | React 19, TypeScript, Vite 7, Ant Design 6 |
| データベース | MySQL 8.0 |
| キャッシュ | Redis 7 |

## プロジェクト構造

```
easyshell/
├── easyshell-server/           # 中央管理サーバー (Java / Spring Boot)
├── easyshell-agent/            # エージェントクライアント (Go, シングルバイナリ)
├── easyshell-web/              # Web フロントエンド (React + Ant Design)
├── docker-compose.yml          # 本番デプロイ (ビルド済みイメージをプル)
├── docker-compose.build.yml    # 開発用 (ソースからローカルビルド)
├── Dockerfile.server           # Server + Agent マルチステージビルド
├── Dockerfile.web              # Web フロントエンド マルチステージビルド
├── .github/workflows/          # CI/CD: Docker イメージのビルドと公開
└── .env.example                # 環境設定テンプレート
```

## ドキュメント

**[docs.easyshell.ai](https://docs.easyshell.ai)** で以下の情報を確認できます。

- インストール＆デプロイガイド
- はじめに（ウォークスルー）
- 設定リファレンス
- 開発ガイド

## コミュニティ

[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

Discord コミュニティに参加して、サポート、ディスカッション、最新情報をご確認ください。
**[https://discord.gg/akQqRgNB6t](https://discord.gg/akQqRgNB6t)**

## ライセンス

このプロジェクトは [MIT ライセンス](./LICENSE) の下でライセンスされています。
