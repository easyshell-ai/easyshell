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

### 8. MCP 服务器与 AI 技能

> 让 AI 客户端直接运维你的基础设施 —— 与 Web 控制台共用同一套风险引擎、审批流程与审计日志。

EasyShell 提供两个配套组件，把平台能力装进你的 AI 助手：

- **`easyshell-mcp`** —— 一个 [Model Context Protocol](https://modelcontextprotocol.io) 服务器，把 EasyShell 的主机、脚本、任务与巡检能力以 MCP **工具**形式暴露出来，让 Claude Desktop、Claude Code、Cursor、Cherry Studio 等 MCP 客户端可以直接驱动 EasyShell。
- **`easyshell-skill`** —— 一份 `SKILL.md` 定义，告诉 AI 助手**何时**以及**如何**使用 EasyShell（核心工作流、完整工具目录、安全模型），适用于支持技能（skills）的助手。

#### `easyshell-mcp` —— 把 EasyShell 变成 MCP 工具

发布在 npm 上：[`@easyshell/mcp-server`](https://www.npmjs.com/package/@easyshell/mcp-server)，并已收录至 [MCP Registry](https://registry.modelcontextprotocol.io/servers/io.github.easyshell/mcp-server) 与 [Smithery](https://smithery.ai/server/@easyshell/mcp-server)。它通过 EasyShell REST API 通信，只需三个环境变量：

| 变量 | 必填 | 说明 |
|---|---|---|
| `EASYSHELL_URL` | 是 | EasyShell 服务器 API 地址（例如 `http://localhost:18080`）。 |
| `EASYSHELL_USER` | 是 | EasyShell 用户名。 |
| `EASYSHELL_PASS` | 是 | EasyShell 密码。 |

**工作流程：**
1. **运行** —— `npx -y @easyshell/mcp-server`，Node.js `>=18` 即可，无需构建
2. **连接** —— 用上面三个变量指向你的 EasyShell 服务器
3. **认证** —— 它会登录 EasyShell API，并在后续所有工具调用中复用该会话
4. **操作** —— AI 客户端调用 EasyShell 工具；风险分级、审批流程与审计日志依然全部生效

**stdio（默认）—— Claude Desktop / Claude Code / Cursor / Cherry Studio**

```json
{
  "mcpServers": {
    "easyshell": {
      "command": "npx",
      "args": ["-y", "@easyshell/mcp-server"],
      "env": {
        "EASYSHELL_URL": "http://localhost:18080",
        "EASYSHELL_USER": "easyshell",
        "EASYSHELL_PASS": "easyshell@changeme"
      }
    }
  }
}
```

> 请替换为你自己的账号。采用零配置 Docker 部署时，初始管理员密码为随机生成，并只在服务端日志中打印一次（`docker logs easyshell-server | grep 'initial admin password'`）。

不想手动改配置文件？自动安装器会识别 OpenCode、Cursor 或 Claude Desktop 并代为写入配置块 —— 由于文件包含密码，生成时会设置为 `0600` 权限：

```bash
MCP_PASSWORD='your-password' npx @easyshell/mcp-server --install
# 可选：--url http://your-server:18080 --user easyshell
```

> 推荐使用 `MCP_PASSWORD` 而非 `--pass`：命令行参数会出现在 `ps` 输出中。

**HTTP（SSE + Streamable HTTP）—— 远程或共享部署**

```bash
# 仅监听本机回环，无需令牌
npx -y @easyshell/mcp-server --port 3000

# 需要被其他机器访问时，必须提供 bearer 令牌
MCP_AUTH_TOKEN='choose-a-strong-token' \
  npx -y @easyshell/mcp-server --port 3000 --host 0.0.0.0 --allow-remote
```

| 端点 | 方法 | 协议 |
|---|---|---|
| `/mcp` | POST / GET / DELETE | Streamable HTTP (2025-03-26) |
| `/sse` | GET | 旧版 SSE (2024-11-05) |
| `/messages?sessionId=<id>` | POST | 旧版 SSE 消息处理 |
| `/health` | GET | 健康检查 |

支持 HTTP transport 的客户端指向 `http://<host>:3000/mcp`，并在请求头携带 `Authorization: Bearer <MCP_AUTH_TOKEN>`（具体配置字段名因客户端而异）：

```json
{
  "mcpServers": {
    "easyshell": {
      "type": "http",
      "url": "http://your-server:3000/mcp",
      "headers": { "Authorization": "Bearer <MCP_AUTH_TOKEN>" }
    }
  }
}
```

HTTP 模式会校验 `Host` 头以防御 DNS 重绑定攻击，CORS 仅放行 `--allowed-origin` 指定的来源，并对会话数与请求体大小做了上限限制。

**工具能力 —— 3 个层级共 20 个工具**

| 层级 | 工具 | 用途 |
|---|---|---|
| 核心层（6） | `list_hosts`、`get_host_detail`、`execute_script`、`get_task_detail`、`list_scripts`、`ai_chat` | 查找主机、执行脚本、读取结果、驱动 AI 编排 |
| 管理层（9） | `list_recent_tasks`、`create_script`、`list_clusters`、`get_cluster_detail`、`get_dashboard_stats`、`get_host_metrics`、`list_scheduled_tasks`、`trigger_inspection`、`get_inspect_reports` | 日常运维、监控与定时巡检 |
| 高级层（5） | `manage_tags`、`query_audit_logs`、`approve_task`、`send_notification`、`search_knowledge` | 治理能力：标签、审计、审批、通知、知识库 |

> [`easyshell-mcp/README.md`](./easyshell-mcp/README.md) 的工具表标注为 19 个，而源码实际注册了 20 个 —— `get_cluster_detail` 遗漏在该表中。上表与 `easyshell-mcp/src/tools/` 保持一致。

#### `easyshell-skill` —— 教会助手 EasyShell 的工作流

`easyshell-skill/SKILL.md` 是一份与助手无关的技能定义（含 name、description、compatibility、tags 等元信息），告诉 AI 助手何时启用 EasyShell 以及应遵循哪些工作流 —— 在多机执行脚本、AI 任务编排、安排巡检、监控基础设施 —— 以及绝不可绕过的安全模型。

手动安装或使用 `mdskills` CLI：

```bash
npx mdskills install easyshell
```

| 助手 | 技能路径 |
|---|---|
| OpenCode | `.opencode/skills/easyshell/SKILL.md` |
| Claude Code | `.claude/skills/easyshell/SKILL.md` |
| Cursor | `.cursor/skills/easyshell/SKILL.md` |
| VS Code Copilot | `.github/skills/easyshell/SKILL.md` |

该技能附带 `resources/tool-catalog.md`（每个工具的参数与示例）、`resources/deployment-guide.md` 与 `resources/troubleshooting.md`。技能可单独使用，但**技能 + MCP 服务器**才是预期组合：技能决定*做什么*，MCP 服务器提供*做事的工具*。

#### 当前边界 —— 规划方案前请先读这段

`easyshell-mcp` 是一个**出口**：它让 EasyShell 能被 AI 客户端*调用*。而在 EasyShell 平台**内部消费外部 MCP 服务器**（Prometheus、Elasticsearch 或你自建的 MCP）**仍在路线图上，1.1.0 尚未实现**。目前没有内置 MCP 客户端、没有外部工具注册表、也没有外部工具审批流，本节内容不应被理解为对此的任何承诺。

如果这正是你的场景，欢迎到 [issue #6](https://github.com/easyshell-ai/easyshell/issues/6)（「mcp 和skill模块是怎么玩的呢？」）补充讨论，或提交功能建议。

### 9. 工作流引擎

> 把运维手册拖到画布上。让节点之间传值。在任何有破坏性的步骤前放一道人工闸门。

工作流引擎把一串运维步骤变成**可视化、带版本、可审计**的工作流：画布上的一张有向无环图，每个节点做一件事，并把产出交给下游节点。运行时可提供带类型的入参，由与脚本下发同一套派发咽喉执行，并逐节点留痕。

<p align="center">
  <img src="docs/images/workflow-canvas.png" alt="工作流画布 —— 节点、连线与一次运行中的状态" width="90%" />
</p>

<!-- TODO(1.2.0 发布)：补充 docs/images/workflow-canvas.png。建议截图：编辑器中的四节点 DAG
     （一个 script-exec、一个 condition 且两个把手都连了线、一个 approval、一个 notify），
     左侧节点调色板，右侧配置表单展开，且至少一个节点显示运行状态色。深色主题，1440x900。
     注意：*.png 已被 .gitignore 忽略，需要用 `git add -f` 入库。 -->

**工作流程：**
1. **编排** —— 把节点拖到画布上，连成 DAG，在右侧表单里配置每个节点
2. **发布** —— 服务端校验；ERROR 阻止发布，WARNING 只作提示
3. **触发** —— 提供带类型的运行入参，可带上幂等键，重试会返回同一个 run
4. **观察** —— 实时节点状态、每个节点的输入/输出、耗时，以及 `script-exec` 产生的真实 Task

#### 五类节点

| 节点 | 作用 |
|------|------|
| `script-exec` | 在目标主机（指定 agent / 集群 / 标签）上执行脚本 —— 它落到真实的 **Task**，因此审计、实时日志、超时看门狗与 agent 掉线处理都是**继承**而来，而非重写一套 |
| `condition` | 求值一个表达式（如 `nodes.check.output.usage > 90`），把布尔结果写入 `output.result`；真正决定走哪条分支的是**边上的条件**，不是节点本身 |
| `notify` | 把渲染后的消息推送到你已配置的机器人渠道（Telegram、Discord、Slack、钉钉、飞书、企业微信） |
| `approval` | 挂起本次运行，等待管理员批准或拒绝；支持超时、可要求必须填写意见，并留存不可变的详情快照 |
| `wait` | 按 `durationSec`（1–3600）等待后继续，每 500ms 检查一次取消，取消绝不会被 sleep 卡住 |

`approval` 还有一个 `mode: "human-confirm"` 配置变体 —— 更轻的「仅确认」闸门，复用同一张审批行、同一套挂起/恢复状态机，而不是另造第二套机制。

#### 节点之间如何传参

值沿边通过**三条通道**传递：

- **标量变量** —— 运行入参（`run.input.<name>`）、节点输出（`nodes.<key>.output.<path>`）与 `outputVar` 别名。值只对该节点的拓扑后继可见，并在每个调度波结束时快照进 `workflow_run.variables_json`。单值上限 8192 UTF-8 字节；超出则存头尾 preview 并带 `truncated: true` 与 warning。
- **artifact 引用** —— **规划中，尚未实现**。1.2.0 保留截断 preview，没有独立的 artifact 表。
- **secret** —— **明确不做**。没有 `secret` 变量类型，也没有把凭据注入脚本的路径。agent 协议只承载脚本正文，在此注入的 secret 会写进 task 的脚本列，并会被任何 `env` 类命令原样打印出来。在 agent 具备旁路通道之前，secret 注入是装饰而非功能。结构化数据请走 JSON 输出；真正需要保密的请留在主机自己的凭据管理里。

脚本插值是安全模型最吃紧的地方，因为沿边传来的值来自远程主机，天然不可信。四层防线，各自独立可达、各自有测试：

1. **值策略** —— 白名单字符集（字母、数字、组合记号加一组经过审计的 ASCII 标点），上限 256 字符；空值、换行、控制字符一律拒绝。
2. **引用渲染** —— `{{NAME}}` 渲染为**单引号包裹**的值，`$`、反引号、`${}`、反斜杠因此全部失去含义；`{{NAME:raw}}` 渲染裸值，且必须在 `rawParams` 中声明。
3. **区间级替换防线** —— 渲染后的脚本若在被替换值贡献的字符区间内出现 `$(`、反引号或 `${`，一律拒绝；raw 值还额外禁止命令结构字符、禁止以 `-` 开头。
4. **命令风险门禁** —— 渲染后的脚本与其他脚本一样，在派发咽喉处过风险引擎。

任何**看起来像槽位但不符合契约**的写法（`{{msg}}`、`{{MSG-X}}`）一律直接拒绝 —— 否则旧的宽松替换会在三层防线之后又跑一次，把前三层全部抹掉。

**诚实说明**：含 shell 结构字符（`; & | < > ( ) $ { }`）的值在 1.2.0 **没有合法传递路径**，请改写脚本结构；传 glob 模式正是 `raw` 的正当用途。

#### 失败策略

每条边（以及整个 definition）可选择上游失败时下游节点怎么办：

| 策略 | 效果 |
|------|------|
| `abort` | 本节点及全部未开始节点被取消，run 终态 `failed` |
| `skip`（默认） | 本节点标记 `skipped`，其下游再按各自策略判断 |
| `partial` | 上游失败也执行本节点，失败的上游 output 视为不存在 |

多条入边策略不一致时**取最严格**（`abort > skip > partial`）。无法识别的策略一律按 `abort` 处理 —— 失败方向是闭合的，永远不会是放开的。

#### 审批与人机协同

`approval` 节点挂起 run 时**不占线程**：数据库里的审批行是唯一事实源，决策后引擎唤醒 run（同 JVM 事件 + 10 秒扫描兜底），且不依赖打开 run 的那个浏览器保持连接。正因如此，一次运行可以跨重启保持 `waiting`，也可以在数天后由另一台机器上的管理员批准。

两个值得知道的后果：

- **高风险脚本需要一个已批准的 `approval` 祖先。** 风险门禁就是所有脚本共同经过的那个咽喉；绕过非 LOW 评级的唯一合法路径，是这个 `script-exec` 拓扑祖先上的人工审批。没有 `approval` 节点的工作流，在任何 `riskPolicy` 下都无法派发 HIGH 脚本。
- **取消的含义是「我不等了」，不是「它停了」。** agent 没有取消消息，已下发的作业会在主机侧继续跑完，其结果被丢弃。UI 原样说明这一点，而不是把它藏进 tooltip。

#### 完整画布交互

编辑器是一张完整可交互的画布，不是只读示意图：

- **框选**（拖空白处）、**对齐线与吸附**、**网格对齐**（20px）
- **撤销 / 重做**（`Ctrl+Z` / `Ctrl+Shift+Z` / `Ctrl+Y`），覆盖结构变更而非仅文本
- **小地图**、缩放控件、**自动布局**（按分层深度，复用 AI 计划视图的深度算法 —— 不引入布局库）
- **复制 / 粘贴 / 再制**，并重新生成 key，粘贴一棵子树也能得到合法图
- **快捷键**：`Delete`（带确认，列明将删除的节点与连线）、`Ctrl+A`、`Ctrl+S`、方向键微移 20px（`Shift` 4px）、`Enter` 打开选中节点配置、`Esc` 关闭、`空格`+拖动平移

一条规则把编辑器粘在一起：**画布是结构的唯一编辑器，表单是被选中节点配置的唯一编辑器**。同一份 definition 有两条写入路径时，冲突处理与 dirty 追踪都会失效 —— 所以节点调色板只能添加，不能删除。

连线在客户端即做校验（不自连、不重复、不成环、condition 的 false 分支边不能带条件），服务端仍是权威。condition 节点有 `true` / `false` 两个出口把手。

#### 运行监控与审计

运行详情页是一张按实际发生情况着色的只读画布，旁边是节点表格：状态、attempt、耗时、输出、错误与警告信息、关联 Task。变量面板展示波末快照。脚本输出仍按节点走任务日志通道实时推送。

每次运行都记录到足以还原「发生了什么、为什么」：不可变的定义版本及其 SHA-256、完整入参快照、每节点的输入与输出及指纹、attempt 序号、Task id、派发时的 agent 版本、以及 run 启动时生效的风险规则快照。（其中一部分 —— 例如 agent 版本快照 —— 目前只落库，未在 UI 展示。）五类高危事件（发布、批准、拒绝、触发、取消）同步写审计，审计写失败即使业务失败。

#### 当前边界 —— 规划方案前请先读这段

1.2.0 交付的是引擎与编辑器。以下功能**仍在路线图上，尚未实现**：cron 与 webhook 触发器、`parallel` / `ai-analyze` / `http-request` 节点、dry-run、replay、模板市场、artifact 存储、secret 注入、running 状态运行的重启续跑、fan-in 变量合并。单节点试运行端点在服务端已存在，但 UI 尚未接入。请勿把多个 server 实例指向同一个数据库：引擎按单执行者编写（启动 run 的实例会记录在 run 上），跨实例锁尚未实现。

完整上手流程见[《Workflow 从 0 到 1》](https://docs.easyshell.ai/guides/workflow/)，缺失的功能欢迎提交建议。

---

## 快速开始

```bash
git clone https://github.com/easyshell-ai/easyshell.git
cd easyshell
docker compose up -d
```

以上就是全部步骤 —— **不需要 `.env`、不需要生成密钥、不需要任何手动配置**。首次启动时一个一次性容器会
生成数据库密码、JWT 密钥、加密密钥和 agent 注册密钥，存入 `easyshell_secrets` 数据卷，之后每次启动都复用同一份。

`scripts/deploy.sh` 是可选封装，额外等待健康检查通过并打印登录信息：

```bash
./scripts/deploy.sh            # docker compose up -d + 等待就绪 + 显示登录方式
./scripts/deploy.sh --build    # 用当前源码构建镜像后启动（而非拉取预构建镜像）
./scripts/deploy.sh --down     # 停止全部组件（保留数据卷）
```

默认路径无需本地构建 —— 预构建镜像将自动从 [Docker Hub](https://hub.docker.com/u/laolupaojiao) 拉取；
如需运行当前源码（包含尚未发布的安全修复），请使用 `--build`。

访问 `http://localhost:18880` → 使用 `easyshell` 登录。初始密码在首次启动时随机生成，并在服务端日志中打印一次
（`docker logs easyshell-server | grep 'initial admin password'`），登录后请立即修改；也可在 `.env` 中设置
`EASYSHELL_ADMIN_PASSWORD` 自行指定。

部署到其他机器上的 agent 需要一个可达的服务器地址：在 `.env` 中设置 `PROVISION_SERVER_URL`，
或在 UI 的「系统管理 → 系统配置」中设置 `server.external-url`。

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
| **运维操作** | 脚本库、批量执行、实时流式日志、集成文件管理器的 Web SSH 终端、**工作流引擎**（可视化 DAG 画布、节点传参、失败策略、人工审批、运行监控） |
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
├── easyshell-mcp/              # MCP 服务器 —— 把 EasyShell 暴露为 AI 客户端工具 (npm: @easyshell/mcp-server)
├── easyshell-skill/            # 面向 AI 助手的 SKILL.md 定义
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
