<p align="center">
  <img src="docs/images/logo.png" alt="EasyShell Logo" width="200" />
</p>

# EasyShell

**AI 原生伺服器運維平台**

讓 AI 為您撰寫腳本、編排多機任務並分析基礎設施 —— 您只需專注於核心決策。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Docs](https://img.shields.io/badge/Docs-docs.easyshell.ai-green.svg)](https://docs.easyshell.ai)
[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

**語言**: [English](./README.md) | [简体中文](./README.zh-CN.md) | 繁體中文 | [한국어](./README.ko.md) | [Русский](./README.ru.md) | [日本語](./README.ja.md)

---

## 為什麼選擇 EasyShell？

傳統的伺服器管理工具需要您親自撰寫每個腳本、手動 SSH 進入每台機器，並自行解讀所有輸出結果。EasyShell 翻轉了這個模式：**AI 是操作員，您是決策者。**

- **用自然語言描述您的需求** → AI 撰寫生產環境等級的 Shell 腳本，並提供差異比對（diff）審核
- **設定多機協作目標** → AI 規劃執行步驟、自動執行並彙整產出結果
- **設定定時巡檢任務** → AI 分析輸出結果並自主決策是否透過機器人頻道告警通知團隊
- **透過 Web SSH 連線** → 完整終端機整合檔案管理員、多標籤頁與搜尋功能 —— 無需安裝在地端用戶端

---

## 核心功能

### 1. AI 腳本助手

> 描述您的需求。AI 撰寫腳本。審核差異。一鍵套用。

AI 腳本工作台是一個雙面板編輯器，您可以用自然語言描述需求，AI 會針對您選擇的作業系統生成（或修改）Shell 腳本。即時串流顯示腳本撰寫過程，內建的差異檢視器會標示出變動之處，摘要分頁則會以您的語言解釋修改內容。

<p align="center">
  <img src="docs/images/AI%20Script%20helper.png" alt="AI 腳本助手 —— 即時程式碼生成與差異審核" width="90%" />
</p>

**運作方式：**
1. **描述** —— 用自然語言描述需求，選擇目標作業系統
2. **生成** —— AI 即時串流生成生產環境等級的腳本
3. **審核** —— 內建差異檢視器標示所有變動；摘要分頁解釋修改內容
4. **套用** —— 一鍵儲存至腳本庫或立即下發執行

### 2. AI 任務編排

> 「檢查所有主機的磁碟與記憶體，標記任何超過 80% 的項目，並建議修復方案。」—— 完成。

AI 對話介面讓您能下達高層級的運維目標。AI 會將目標分解為多步驟執行計畫（探索 → 分析 → 報告），將腳本分發至目標主機，收集結果，並在單次對話中提供包含風險評估與行動建議的結構化分析。

<p align="center">
  <img src="docs/images/AI%20task%20orchestration.png" alt="AI 任務編排 —— 多步驟執行計畫與分析" width="90%" />
</p>

**運作方式：**
1. **指令** —— 在 AI 對話中描述高層級運維目標（如「檢查所有主機磁碟使用率」）
2. **規劃** —— AI 將目標拆解為多步驟執行計畫（探索 → 分析 → 報告）
3. **執行** —— 腳本並行分發至目標主機，結果自動收集
4. **報告** —— AI 生成包含風險評估與行動建議的結構化分析報告

### 3. AI 定時巡檢

> **定時任務 → 腳本執行 → AI 智慧分析 → 智能告警** —— AI 分析輸出結果並自主決策是否告警。

透過 Cron 運算式排程巡檢任務，並從內建腳本庫中選擇腳本。EasyShell 按排程將腳本分發至 Agent，收集輸出結果（磁碟、記憶體、服務、日誌），發送至 AI 模型進行智慧分析，**由 AI 判斷是否需要告警** —— 只在真正需要關注時才推送通知。

<p align="center">
  <img src="docs/images/schedule_task.png" alt="AI 定時巡檢 —— 基於 Cron 的定時任務，AI 智慧分析輸出並自主決策告警" width="90%" />
</p>

**運作方式：**
1. **設定** —— Cron 運算式 + 腳本（從腳本庫選擇或自訂）+ AI 分析提示詞 + 通知規則
2. **執行** —— EasyShell 按排程將任務分發至目標 Agent
3. **分析** —— 輸出結果發送至 AI 模型（OpenAI / Gemini / GitHub Copilot / 自訂）進行智慧分析
4. **通知** —— AI 評估嚴重程度，在需要時透過機器人頻道推送告警

**通知模式：** 始終推送、失敗時推送、警告時推送、或 **AI 自主決策** —— AI 模型評估輸出內容並自主判斷是否需要告警。

**支援的機器人頻道** ([設定指南](https://docs.easyshell.ai/configuration/bot-channels/)):

| 機器人 | 狀態 |
|-----|--------|
| [Telegram](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支援 |
| [Discord](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支援 |
| [Slack](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支援 |
| [釘釘 (DingTalk)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支援 |
| [飛書 (Feishu)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支援 |
| [企業微信 (WeCom)](https://docs.easyshell.ai/configuration/bot-channels/) | ✅ 已支援 |

### 4. 全功能 Web SSH

> 真實終端。整合檔案管理。無需 SSH 用戶端。

生產等級的網頁終端機，支援多標籤頁工作階段、整合式檔案管理員（上傳、下載、建立、刪除、瀏覽）、終端機緩衝區全文搜尋，並透過 WebSocket 維持穩定連線。讓您能併排管理檔案與執行指令。

<p align="center">
  <img src="docs/images/Fully%20functional%20web%20SSH.png" alt="Web SSH —— 整合檔案管理與多標籤頁的終端機" width="90%" />
</p>

### 5. 主機管理與監控

> 統一檢視所有伺服器的即時狀態，支援批次操作和 Agent 生命週期管理。

單獨或批次管理主機 —— 透過表單或 CSV 匯入新增、依叢集組織、監控連線狀態，一鍵部署/升級 Agent。統一儀表板讓健康指標一目瞭然。

<p align="center">
  <img src="docs/images/host-management.png" alt="主機管理 —— 支援批次操作的統一伺服器儀表板" width="90%" />
</p>

### 6. 即時串流日誌

> 即時觀察腳本在所有目標主機上的執行過程。

當您下發腳本時，EasyShell 會從每個 Agent 即時串流輸出內容。彩色日誌、時間戳記和依主機篩選功能，讓您能立即發現問題 —— 無需再等待批次任務完成。

<p align="center">
  <img src="docs/images/realtime-logs.png" alt="即時日誌 —— 多主機即時串流輸出" width="90%" />
</p>

### 7. 安全與風控

> 內建安全機制：審批流程、稽核追蹤和操作限制。

設定哪些操作在執行前需要審批。所有操作都會被記錄以符合合規要求。基於角色的存取控制限制「誰可以做什麼」，敏感命令可以被標記或完全禁止。

<p align="center">
  <img src="docs/images/security-controls.png" alt="安全控制 —— 審批流程與稽核日誌" width="90%" />
</p>

### 8. MCP 伺服器與 AI 技能

> 讓 AI 客戶端直接維運你的基礎設施 —— 與 Web 控制台共用同一套風險引擎、審批流程與稽核日誌。

EasyShell 提供兩個配套元件，把平台能力放進你的 AI 助手：

- **`easyshell-mcp`** —— 一個 [Model Context Protocol](https://modelcontextprotocol.io) 伺服器，把 EasyShell 的主機、腳本、任務與巡檢能力以 MCP **工具**形式開放，讓 Claude Desktop、Claude Code、Cursor、Cherry Studio 等 MCP 客戶端可以直接驅動 EasyShell。發布於 npm 的 [`@easyshell/mcp-server`](https://www.npmjs.com/package/@easyshell/mcp-server)，透過 `EASYSHELL_URL` / `EASYSHELL_USER` / `EASYSHELL_PASS` 三個環境變數指定目標。本地客戶端使用 `npx -y @easyshell/mcp-server`（stdio），遠端共用則使用 `npx -y @easyshell/mcp-server --port 3000`（SSE + Streamable HTTP，綁定非迴環位址時必須提供 `--token`）。工具共 3 層 20 個（核心 6／管理 9／進階 5）。
- **`easyshell-skill`** —— 一份 `SKILL.md` 定義，告訴 AI 助手**何時**以及**如何**使用 EasyShell（工作流、工具目錄、安全模型）。使用 `npx mdskills install easyshell` 安裝，OpenCode 路徑為 `.opencode/skills/easyshell/SKILL.md`，Claude Code 為 `.claude/skills/easyshell/SKILL.md`。

**目前邊界：** `easyshell-mcp` 是**出口**：它讓 EasyShell 能被 AI 客戶端*呼叫*。而在 EasyShell 平台*內部消費外部* MCP 伺服器（Prometheus、Elasticsearch 或你自建的 MCP）仍在路線圖上，1.1.0 尚未實作。相關討論見 [issue #6](https://github.com/easyshell-ai/easyshell/issues/6)。完整工具清單與設定範例請見 [easyshell-mcp/README.md](./easyshell-mcp/README.md)。

### 9. 工作流引擎

> **2.0 工作流（開始/結束、變數選擇器、IF/ELSE）請見 [README.zh-CN.md](README.zh-CN.md#9-工作流引擎) 與 [README.md](README.md#9-workflow-engine)。** 以下為 1.2.0 內容。


> 把運維手冊拖到畫布上。讓節點之間傳值。在任何有破壞性的步驟前放一道人工閘門。

工作流引擎把一連串運維步驟變成**視覺化、帶版本、可審計**的工作流：畫布上的一張有向無環圖（DAG），每個節點做一件事，並把產出交給下游節點。執行時提供帶類型的入參，由與腳本下發同一套派發咽喉執行，並逐節點留痕。

**五類節點：**

| 節點 | 作用 |
|------|------|
| `script-exec` | 在目標主機（指定 agent／叢集／標籤）上執行腳本 —— 它落到真實的 **Task**，因此稽核、即時日誌、逾時看門狗與 agent 掉線處理都是**繼承**而來，而非重寫一套 |
| `condition` | 求值一個表達式（如 `nodes.check.output.usage > 90`），把布林結果寫入 `output.result`；真正決定走哪條分支的是**邊上的條件**，不是節點本身 |
| `notify` | 把渲染後的訊息推送到你已設定的機器人渠道（Telegram、Discord、Slack、釘釘、飛書、企業微信） |
| `approval` | 掛起本次執行，等待管理員核准或駁回；支援逾時、可要求必須填寫意見，並留存不可變的詳情快照 |
| `wait` | 依 `durationSec`（1–3600）等待後繼續，每 500ms 檢查一次取消，取消絕不會被 sleep 卡住 |

**節點之間如何傳參：** 值沿邊通過**三條通道**傳遞。**純量變數**（`run.input.<name>`／`nodes.<key>.output.<path>`／`outputVar` 別名）只對該節點的拓撲後繼可見，並在每個排程波結束時快照進 `workflow_run.variables_json`；單值上限 8192 UTF-8 位元組，超出則存頭尾 preview 並帶 `truncated: true`。**artifact 引用** —— *規劃中，尚未實作*。**secret** —— *明確不做*：agent 協定只承載腳本正文，在此注入的 secret 會寫進 task 的腳本欄，並會被任何 `env` 類命令原樣印出。腳本插值經過四層獨立防線（值策略白名單 → `{{NAME}}` 的單引號包裹 → 替換區間的 `$(`／反引號／`${` 檢查 → 派發咽喉的風險閘門）。

**失敗策略：** `abort`（本節點及全部未開始節點被取消，run 終態 `failed`）／`skip`（預設，僅本節點標記 `skipped`）／`partial`（上游失敗也執行本節點，失敗的上游 output 視為不存在）。多條入邊策略不一致時**取最嚴格**；無法識別的策略一律按 `abort` 處理。

**核准與人機協同：** `approval` 節點掛起 run 時**不佔執行緒**：資料庫裡的核准行是唯一事實來源，決策後引擎喚醒 run（同 JVM 事件 + 10 秒掃描兜底），且不依賴開啟 run 的那個瀏覽器保持連線。高風險腳本可被執行的唯一正當路徑，是這個 `script-exec` 拓撲祖先上的人工核准。另外，「取消」的含義是「我不等了」，不是「它停了」—— 已下發的作業會在主機側繼續跑完，其結果被丟棄。

**完整畫布互動：** 框選、對齊線與吸附、20px 網格對齊、復原／重做（Ctrl+Z／Ctrl+Shift+Z）、小地圖、自動佈局（復用 AI 計畫檢視的深度演算法，不引入佈局庫）、帶 key 重新產生的複製／貼上／再制、鍵盤操作（Delete／Ctrl+A／Ctrl+S／方向鍵 20px、Shift 4px／Enter／Esc／空白+拖曳平移）。**畫布是結構的唯一編輯器，表單是被選中節點設定的唯一編輯器。**

**尚未實作（路線圖）：** cron 與 webhook 觸發器、`parallel`／`ai-analyze`／`http-request` 節點、dry-run、replay、範本市集、artifact 儲存、secret 注入、running 狀態執行的重啟續跑、fan-in 變數合併。單節點試運行端點在服務端已存在，但 UI 尚未接入。請勿把多個 server 執行個體指向同一個資料庫（引擎按單一執行者編寫，跨執行個鎖尚未實作）。完整上手流程見[《Workflow 從 0 到 1》](https://docs.easyshell.ai/guides/workflow/)。

---

## 快速開始

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
cp .env.example .env      # 按需編輯 .env
docker compose up -d
```

無需本地端建構 —— 系統會自動從 [Docker Hub](https://hub.docker.com/u/laolupaojiao) 拉取預建構的映像檔。

開啟 `http://localhost:18880` → 使用 `easyshell` 登入。首次啟動時服務端會在日誌中印出隨機產生的初始密碼（也可透過設定 `EASYSHELL_ADMIN_PASSWORD` 自行指定），登入後請立即修改。

> **想改用 GHCR？** 在 `.env` 中設定：
> ```
> EASYSHELL_SERVER_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-server:latest
> EASYSHELL_WEB_IMAGE=ghcr.io/easyshell-ai/easyshell/easyshell-web:latest
> ```

> **開發者？從原始碼建構：**
> ```bash
> docker compose -f docker-compose.build.yml up -d
> ```

---

## 完整功能集

| 類別 | 功能 |
|----------|----------|
| **AI 智慧** | AI 腳本助手（生成 / 修改 / 差異比對 / 摘要）、AI 任務編排（多步驟計畫、並行執行、分析）、AI 定時巡檢（Cron 定時、AI 輸出分析、智能告警決策、多頻道機器人推送）、AI 對話、巡檢報告、操作審批 |
| **運維操作** | 腳本庫、批次執行、即時串流日誌、整合檔案管理員的 Web SSH 終端機、**工作流引擎**（視覺化 DAG 畫布、節點傳參、失敗策略、人工核准、執行監控） |
| **基礎設施** | 主機管理、即時監控、叢集分組、Agent 自動部署 |
| **系統管理** | 使用者管理、系統設定、AI 模型設定（OpenAI / Gemini / Copilot / 自訂）、風險控制 |
| **平台特性** | 多語系（英 / 中）、深色/淺色主題、響應式設計、審計日誌 |

---

## 系統架構

```
┌──────────────┐       HTTP/WS        ┌──────────────────┐
│  EasyShell   │◄─────────────────────►│   EasyShell      │
│    Agent     │  註冊 / 心跳跳動      │      伺服器      │
│  (Go 1.24)  │  腳本執行 / 日誌     │ (Spring Boot 3.5)│
└──────────────┘                       └────────┬─────────┘
                                                │
                                       ┌────────┴─────────┐
                                       │   EasyShell Web   │
                                       │ (React + Ant Design)│
                                       └──────────────────┘
```

## 技術棧

| 組件 | 技術 |
|-----------|-----------|
| 伺服器 | Java 17, Spring Boot 3.5, Gradle, JPA/Hibernate, Spring AI, Spring Security |
| Agent | Go 1.24, 單一二進位檔案, 零執行時依賴 |
| 網頁前端 | React 19, TypeScript, Vite 7, Ant Design 6 |
| 資料庫 | MySQL 8.0 |
| 快取 | Redis 7 |

## 專案結構

```
easyshell/
├── easyshell-server/           # 中央管理伺服器 (Java / Spring Boot)
├── easyshell-agent/            # Agent 用戶端 (Go, 單一二進位檔案)
├── easyshell-web/              # Web 前端 (React + Ant Design)
├── docker-compose.yml          # 生產部署 (拉取預建構映像檔)
├── docker-compose.build.yml    # 開發環境 (從原始碼本地建構)
├── Dockerfile.server           # Server + Agent 多階段建構
├── Dockerfile.web              # Web 前端多階段建構
├── .github/workflows/          # CI/CD: 建構與發佈 Docker 映像檔
└── .env.example                # 環境變數設定範本
```

## 說明文件

請瀏覽 **[docs.easyshell.ai](https://docs.easyshell.ai)** 取得：

- 安裝與部署指南
- 快速入門導覽
- 設定參考手冊
- 開發指南

## 社群

[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289da?logo=discord&logoColor=white)](https://discord.gg/akQqRgNB6t)

加入我們的 Discord 社群以獲取支援、參與討論與追蹤更新：
**[https://discord.gg/akQqRgNB6t](https://discord.gg/akQqRgNB6t)**

## 授權條款

本專案採用 [MIT 授權條款](./LICENSE)。
