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

### 8. MCP サーバーと AI スキル

> AI クライアントからインフラを直接操作 — Web コンソールと同じリスクエンジン、承認フロー、監査証跡で。

EasyShell は、プラットフォームを AI アシスタントの内側に置くための 2 つのコンポーネントを同梱しています。

- **`easyshell-mcp`** — [Model Context Protocol](https://modelcontextprotocol.io) サーバー。ホスト、スクリプト、タスク、点検を MCP **ツール**として公開するため、Claude Desktop、Claude Code、Cursor、Cherry Studio などの MCP クライアントから EasyShell を直接操作できます。npm の [`@easyshell/mcp-server`](https://www.npmjs.com/package/@easyshell/mcp-server) として公開されており、`EASYSHELL_URL` / `EASYSHELL_USER` / `EASYSHELL_PASS` の 3 つの環境変数で接続先を指定します。ローカルクライアントでは `npx -y @easyshell/mcp-server`（stdio）、リモート共有では `npx -y @easyshell/mcp-server --port 3000`（SSE + Streamable HTTP、非ループバック bind 時は `--token` が必須）を使います。ツールは 3 階層 20 個（コア 6 / 管理 9 /  advanced 5）です。
- **`easyshell-skill`** — `SKILL.md` 定義。AI アシスタントに EasyShell を**いつ****どう**使うか（ワークフロー、ツールカタログ、セキュリティモデル）を教えます。`npx mdskills install easyshell` でインストールでき、OpenCode は `.opencode/skills/easyshell/SKILL.md`、Claude Code は `.claude/skills/easyshell/SKILL.md` に配置されます。

**現在の境界:** `easyshell-mcp` は**外向き**のブリッジであり、EasyShell を AI クライアントから*呼べる*ようにするものです。Prometheus、Elasticsearch、自社 MCP など**外部** MCP サーバーを EasyShell プラットフォーム*内部で*利用する機能はロードマップ上にあり、1.1.0 では未実装です。詳細は [issue #6](https://github.com/easyshell-ai/easyshell/issues/6) で議論しています。完全なツール一覧と設定例は [easyshell-mcp/README.md](./easyshell-mcp/README.md) を参照してください。

### 9. ワークフローエンジン

> **Workflow 2.0 の説明は [README.md](README.md#9-workflow-engine) / [README.zh-CN.md](README.zh-CN.md#9-工作流引擎) を参照してください。** この節の以下の記述は 1.2.0 時点のものです。


> 運用手順書をキャンバスに並べる。ノード間で値を渡す。破壊的な操作の前に人のゲートを置く。

ワークフローエンジンは、一連の運用手順を**視覚的・バージョン管理付き・監査可能**なワークフローに変えます。インタラクティブなキャンバス上の有向非巡回グラフ（DAG）で、各ノードが 1 つのことを行い、出力を下流ノードへ渡します。実行は型付き入力で起動でき、スクリプト実行と同じ派筋を通り、ノード単位で記録されます。

**ノード種別（5 種類）:**

| ノード | 役割 |
|--------|------|
| `script-exec` | 対象ホスト（agent 指定 / クラスタ / タグ）でスクリプトを実行。実際の **Task** として落ちるため、監査・リアルタイムログ・タイムアウト監視・agent 切断処理は作り直さず継承します |
| `condition` | 式（`nodes.check.output.usage > 90` など）を評価し `output.result` に布尔値を書き込みます。分岐を決めるのは**辺の条件**であって、ノード本身ではありません |
| `notify` | 設定済みのボットチャンネル（Telegram / Discord / Slack / DingTalk / Feishu / WeCom）へレンダリング済みメッセージを送信 |
| `approval` | 実行を一時停止し、管理者の承認または却下を待ちます。タイムアウト、コメント必須、不変の詳細スナップショットに対応 |
| `wait` | `durationSec`（1〜3600）待ってから続行。500ms ごとにキャンセルを確認するため、sleep に取り残されません |

**ノード間の通信:** 値は辺に沿って 3 つのチャネルを流れます。**スカラー変数**（`run.input.<name>` / `nodes.<key>.output.<path>` / `outputVar` エイリアス。後続ノードにのみ見え、波末に `workflow_run.variables_json` へスナップショット。単値上限 8192 UTF-8 バイト、超過時は head+tail プレビューと `truncated: true`）、**artifact 参照**（*計画中・未実装*）、**secret**（*意図的に未実装* —— agent プロトコルが運べるのはスクリプト本文だけで、注入した secret は task のスクリプト列に書かれ `env` 系コマンドで出力されてしまうため）。スクリプト插值は 4 層の防御（値ポリシーのホワイトリスト → `{{NAME}}` の単一引用符包み → 置換区間の `$(`/`` ` ``/`${` 検査 → 派发咽喉のリスクゲート）を通ります。

**失敗ポリシー:** `abort`（以降を全てキャンセルし run は failed）/ `skip`（既定。当該ノードのみ skipped）/ `partial`（失敗上游の出力を欠落扱いで実行）。入辺が不一致の場合は**最も厳しい**方が勝ち、認識できないポリシーは `abort` 扱いです。

**审批:** `approval` ノードは**スレッドを占有せず**に実行を停止します。DB の审批行が唯一の事实源で、决策后在 JVM イベント＋10 秒スキャナで実行が再开します。高风险スクリプトを执行できる唯一の正当な経路は、拓扑上の祖先にある承認済み `approval` ノードです。なお「キャンセル」は「待たない」という意味であり、配信済みジョブはホスト側で走り切り、その結果は破棄されます。

**キャンバス:** ボックス選択、整列ガイドとスナップ、20px のグッドスナップ、取り消し/やり直し（Ctrl+Z / Ctrl+Shift+Z）、ミニマップ、自动レイアウト（AI 計画ビューの深度算法を再利用、レイアウトライブラリは不要）、キー再生成付きコピー/ペースト/複製、キーボード操作（Delete / Ctrl+A / Ctrl+S / 方向キー 20px・Shift で 4px / Enter / Esc / Space+拖动）。**キャンバスが構造の唯一のエディタ、フォームは選択中ノードの設定の唯一のエディタ**です。

**未実装（ロードマップ）:** cron / webhook トリガー、`parallel` / `ai-analyze` / `http-request` ノード、dry-run、replay、テンプレートマーケット、artifact 保存、secret 注入、実行中 run の再起動後継続、fan-in 変数マージ。单ノード試行端點はサーバーに存在しますが UI には未接続です。复数 server インスタンスで同一 DB を指す運用は自己責任で（单一実行者前提、跨インスタンスロックなし）。詳細は[《Workflow 从 0 到 1》](https://docs.easyshell.ai/guides/workflow/)（英語・簡体字）を参照してください。

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
| **運用** | スクリプトライブラリ、一括実行、リアルタイムストリーミングログ、ファイルマネージャー付き Web SSH ターミナル、**ワークフローエンジン**（視覚的 DAG キャンバス、ノード間パラメータ受け渡し、失敗ポリシー、人の承認、実行監視） |
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
