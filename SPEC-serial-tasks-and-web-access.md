# Spec: 串行任务编排 + AI 网络访问能力

## 问题一：任务编排缺少串行支持（Planner 不感知 DAG）

### 现状分析

后端 **已经完整实现了** DAG 执行引擎（`DagExecutor.java`），支持：
- `dependsOn`: 步骤依赖关系（有向无环图）
- `condition`: 条件表达式（如 `step[1].status == 'completed'`）
- `checkpoint`: 人工审批卡点
- `outputVar` / `inputVars`: 跨步骤变量传递（`${varName}` 语法）
- `onFailure`: 失败策略（`abort` / `skip` / `goto:{stepIndex}`）
- `timeoutSec`: 步骤级别超时

但 **Planner Agent 的 prompt 完全没有提及这些字段**，导致 LLM 永远不会生成 DAG 计划。

`PlanExecutor.executePlan()` 的路由逻辑：
```java
if (isDagPlan(plan)) {
    dagExecutor.executeDag(plan, request, sink, cancelled);  // DAG 模式
} else {
    executeSimplePlan(plan, request, sink, cancelled);        // 简单模式
}
```

`isDagPlan()` 判断条件：步骤中存在 `dependsOn`、`condition` 或 `checkpoint` 任一字段。由于 Planner 从不生成这些字段，所以 **永远走简单模式**。

### 解决方案

#### 变更 1: 更新 PLANNER_AGENT Prompt

**文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/chat/SystemPrompts.java`

将 `PLANNER_AGENT` 常量的输出格式部分从当前的简单格式扩展为包含 DAG 字段的完整格式。具体改动：

**当前输出格式（第 108-125 行）：**
```json
{
  "summary": "计划概要说明",
  "steps": [
    {
      "index": 0,
      "description": "步骤描述",
      "agent": "execute",
      "tools": ["executeScript"],
      "hosts": ["host1-id"],
      "parallel_group": 0,
      "rollback_hint": "回滚提示（可选）"
    }
  ],
  "requires_confirmation": true,
  "estimated_risk": "LOW"
}
```

**改为：**
```json
{
  "summary": "计划概要说明",
  "steps": [
    {
      "index": 0,
      "description": "步骤描述",
      "agent": "explore",
      "tools": ["listHosts"],
      "hosts": ["host1-id"],
      "depends_on": [],
      "output_var": "host_list",
      "input_vars": {},
      "on_failure": "abort",
      "timeout_sec": 60,
      "rollback_hint": "无需回滚（只读操作）"
    },
    {
      "index": 1,
      "description": "在目标主机上执行脚本",
      "agent": "execute",
      "tools": ["executeScript"],
      "hosts": ["host1-id"],
      "depends_on": [0],
      "input_vars": {"target": "${host_list}"},
      "on_failure": "abort",
      "timeout_sec": 300,
      "rollback_hint": "需要手动清理"
    }
  ],
  "requires_confirmation": true,
  "estimated_risk": "MEDIUM"
}
```

同时新增 prompt 规则说明：

```
## 步骤依赖与编排
- depends_on: 数组，包含当前步骤依赖的步骤 index。依赖的步骤必须先完成。为空或省略表示无依赖
- output_var: 将步骤结果存储到变量中，供后续步骤通过 input_vars 引用
- input_vars: 从前序步骤获取数据，格式 {"参数名": "${变量名}"}
- on_failure: 步骤失败时的策略 — "abort"（终止计划）/ "skip"（跳过继续）/ "goto:N"（跳转到步骤N）
- timeout_sec: 步骤超时秒数，默认 300

## 串行 vs 并行判断原则
- 步骤 B 需要步骤 A 的结果 → B.depends_on = [A.index]（串行）
- 多个步骤相互独立、操作不同主机 → 不设 depends_on（并行）
- 先查询再执行 → 查询步骤的 output_var 被执行步骤的 input_vars 引用
- 删除旧 parallel_group 字段，统一使用 depends_on 表达依赖关系
```

#### 变更 2: DagExecutor 补充 Host Context 注入

**文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/orchestrator/DagExecutor.java`

**问题**: `DagExecutor.executeDagStep()` 第 177 行直接将 `taskDescription` 传给 `executeAsSubAgent()`，没有像 `PlanExecutor.buildSubAgentPrompt()` 那样注入 host context 和 tool hints。

**方案**: 复用 `PlanExecutor.buildSubAgentPrompt()` 的逻辑。有两种实现路径：

- **方案 A（推荐）**: 将 `buildSubAgentPrompt()` 提取为共享工具方法（如 `SubAgentPromptBuilder` 工具类或 `PlanExecutor` 上的 `public static` 方法），让 `DagExecutor` 也调用
- **方案 B**: 在 `DagExecutor` 中内联同样的逻辑

改动点（以方案 A 为例）：

1. 新建 `SubAgentPromptBuilder.java`（或将方法移到已有的工具类中）:
```java
public static String buildSubAgentPrompt(ExecutionPlan.PlanStep step, OrchestratorRequest request) {
    // 现有 PlanExecutor.buildSubAgentPrompt() 的逻辑，原封不动
}
```

2. `PlanExecutor.executeSingleStep()` 改为调用共享方法
3. `DagExecutor.executeDagStep()` 第 153-156 行，将：
```java
String taskDescription = substituteVariables(step.getDescription(), step.getInputVars(), variables);
```
改为：
```java
String taskDescription = substituteVariables(step.getDescription(), step.getInputVars(), variables);
// 在变量替换后，注入 host context
step.setDescription(taskDescription);  // 更新为替换后的描述
taskDescription = SubAgentPromptBuilder.buildSubAgentPrompt(step, request);
```

#### 变更 3: 移除 parallel_group，统一用 depends_on

**文件**: `ExecutionPlan.java`

`parallelGroup` 字段可以保留向后兼容，但 Planner prompt 不再推荐使用。`DagExecutor` 天然支持无 `dependsOn` 的步骤并行执行（`findReadySteps()` 会把所有无依赖的 pending 步骤同时放入 ready 列表）。

**无需改动代码** — DAG 引擎已经隐式支持并行：没有 `dependsOn` 的步骤自动并行执行。只需确保 Planner prompt 不再输出 `parallel_group`。

#### 变更 4: 简单模式作为降级兜底

`executeSimplePlan()` 保留不动，作为 LLM 输出不含 DAG 字段时的降级路径。无需修改。

### 涉及文件

| 文件 | 改动 |
|------|------|
| `SystemPrompts.java` | 更新 `PLANNER_AGENT` prompt，增加 DAG 字段文档和示例 |
| `DagExecutor.java` | 调用 `buildSubAgentPrompt()` 注入 host context |
| `PlanExecutor.java` | 将 `buildSubAgentPrompt()` 提取为共享方法 |
| 新建 `SubAgentPromptBuilder.java`（可选）| 共享 prompt 构建逻辑 |

### 风险评估

- **低风险**: Prompt 更新不影响任何代码逻辑，只影响 LLM 生成的计划结构
- **低风险**: DagExecutor 的 host 注入是纯增量改动
- **需验证**: LLM 是否能稳定生成符合 DAG schema 的 JSON（建议在 prompt 中给出 2-3 个示例覆盖串行、并行、混合场景）

---

## 问题二：AI 无法访问网络（URL 获取 / 网络搜索）

### 现状分析

当前 AI Agent 拥有 15 个工具，全部面向内部运维操作（主机、脚本、任务、监控等）。**没有任何网络访问能力**。当用户给 AI 一个 URL 让其分析时，AI 无法获取该 URL 的内容。

### 解决方案：新增两个工具 — WebFetchTool + WebSearchTool

#### 工具 1: WebFetchTool — URL 内容获取

**目的**: 获取指定 URL 的网页内容，提取纯文本，返回给 AI 分析。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/WebFetchTool.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class WebFetchTool {

    private final AgenticConfigService configService;

    @Tool(description = "获取指定 URL 的网页内容并提取纯文本。适用于用户提供了一个网址需要 AI 分析其内容的场景。支持 HTTP/HTTPS 网页。注意：返回的是提取后的纯文本，不包含 HTML 标签、脚本、样式等。")
    public String fetchUrl(
            @ToolParam(description = "要获取的完整 URL 地址，必须以 http:// 或 https:// 开头") String url) {
        // 1. URL 校验（白名单/黑名单）
        // 2. Jsoup 获取 + 提取纯文本
        // 3. 截断到配置的最大字符数
        // 4. 返回结构化结果（标题 + 正文摘要）
    }
}
```

**核心实现逻辑：**

```java
// 安全校验
if (!url.startsWith("http://") && !url.startsWith("https://")) {
    return "URL 格式无效，必须以 http:// 或 https:// 开头";
}

// 域名黑名单检查（可配置）
List<String> blockedDomains = configService.getList("ai.web.blocked-domains",
    List.of("localhost", "127.0.0.1", "10.*", "172.16.*", "192.168.*"));
// ... 校验逻辑

// 获取页面
int timeout = configService.getInt("ai.web.fetch-timeout-ms", 10000);
int maxSize = configService.getInt("ai.web.max-body-size-bytes", 1048576); // 1MB

Document doc = Jsoup.connect(url)
    .userAgent("EasyShell-AI/1.0")
    .timeout(timeout)
    .maxBodySize(maxSize)
    .followRedirects(true)
    .get();

// 移除非内容元素
doc.select("script, style, nav, footer, header, noscript, aside, iframe, form").remove();

// 提取标题和正文
String title = doc.title();
String bodyText = doc.body().text();

// 截断
int maxChars = configService.getInt("ai.web.max-content-chars", 8000);
if (bodyText.length() > maxChars) {
    bodyText = bodyText.substring(0, maxChars) + "\n... [内容已截断，共 " + bodyText.length() + " 字符]";
}

return String.format("【网页标题】%s\n【来源 URL】%s\n【正文内容】\n%s", title, url, bodyText);
```

**依赖新增**: `build.gradle.kts` 添加 Jsoup

```kotlin
implementation("org.jsoup:jsoup:1.18.3")
```

#### 工具 2: WebSearchTool — 网络搜索

**目的**: 用关键词搜索互联网，返回搜索结果摘要，帮助 AI 获取实时信息。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/WebSearchTool.java`

**搜索引擎选型**:

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **Tavily API** | 专为 AI 设计，返回干净文本；业界标准（OpenAI/LangChain 都用） | 需要 API Key，免费额度 1000次/月 | ⭐⭐⭐⭐⭐ |
| **SerpAPI** | 包装 Google 搜索，结果质量高 | 收费，$50/月起 | ⭐⭐⭐ |
| **Google Custom Search** | 官方 API | 每天 100 次免费，之后 $5/1000 次 | ⭐⭐⭐ |
| **DuckDuckGo HTML** | 免费，无需 API Key | 不稳定，可能被反爬 | ⭐⭐ |
| **SearXNG 自建** | 完全免费，隐私友好 | 需自行部署和维护 | ⭐⭐⭐ |

**推荐方案：Tavily（主） + 可配置切换（备）**

选择 Tavily 的理由：
1. 专为 AI Agent 设计，返回的是 LLM-ready 的干净文本（不是 HTML）
2. 单次 API 调用即可获得搜索结果 + 页面正文提取
3. 免费额度 1000 次/月，对运维场景绰绰有余
4. 被 OpenAI Function Calling、LangChain、LlamaIndex 等主流框架广泛使用

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    private final AgenticConfigService configService;
    private final RestClient.Builder restClientBuilder;

    @Tool(description = "使用搜索引擎搜索互联网信息。适用于需要获取实时信息、查找技术文档、搜索错误解决方案等场景。返回搜索结果的标题、URL 和内容摘要。")
    public String webSearch(
            @ToolParam(description = "搜索关键词") String query,
            @ToolParam(description = "返回结果数量，默认 5，最大 10") int maxResults) {

        String provider = configService.get("ai.web.search-provider", "tavily");

        return switch (provider) {
            case "tavily" -> searchViaTavily(query, Math.min(maxResults, 10));
            case "searxng" -> searchViaSearXNG(query, Math.min(maxResults, 10));
            default -> "未配置搜索引擎，请在系统设置中配置 ai.web.search-provider";
        };
    }

    private String searchViaTavily(String query, int maxResults) {
        String apiKey = configService.get("ai.web.tavily-api-key", "");
        if (apiKey.isBlank()) {
            return "Tavily API Key 未配置，请在系统设置中配置 ai.web.tavily-api-key";
        }

        // POST https://api.tavily.com/search
        // Body: { "query": "...", "max_results": 5, "search_depth": "basic" }
        // Response: { "results": [{ "title": "...", "url": "...", "content": "..." }] }

        // ... RestClient 调用并格式化结果
    }

    private String searchViaSearXNG(String query, int maxResults) {
        String baseUrl = configService.get("ai.web.searxng-url", "http://localhost:8888");
        // GET {baseUrl}/search?q={query}&format=json&engines=google,bing&limit={maxResults}
        // ... RestClient 调用并格式化结果
    }
}
```

#### 变更 3: 注册工具到 OrchestratorEngine

**文件**: `OrchestratorEngine.java`

1. 注入 `WebFetchTool` 和 `WebSearchTool`:
```java
private final WebFetchTool webFetchTool;
private final WebSearchTool webSearchTool;
```

2. 在 `getAllTools()` 中注册:
```java
private Object[] getAllTools() {
    return new Object[]{
        hostListTool, hostTagTool, scriptExecuteTool, softwareDetectTool,
        taskManageTool, scriptManageTool, clusterManageTool,
        monitoringTool, auditQueryTool, scheduledTaskTool, approvalTool,
        subAgentTool, delegateTaskTool, getTaskResultTool,
        webFetchTool, webSearchTool  // 新增
    };
}
```

#### 变更 4: 更新 ToolSetSelector 白名单

**文件**: `ToolSetSelector.java`

将 `fetchUrl` 和 `webSearch` 加入相关任务类型的白名单：

```java
TaskType.QUERY → 加入 "fetchUrl", "webSearch"
TaskType.GENERAL → 加入 "fetchUrl", "webSearch"
// EXECUTE/TROUBLESHOOT/DEPLOY 本就允许所有工具，无需改动
```

#### 变更 5: 更新系统 Prompt

**文件**: `SystemPrompts.java`

在 `OPS_ASSISTANT` 的能力清单中新增：

```
### 网络访问
- 获取指定 URL 的网页内容并提取纯文本用于分析
- 使用搜索引擎搜索互联网信息（技术文档、错误解决方案等）
```

#### 变更 6: 安全配置项

**通过 AgenticConfigService（数据库配置）新增以下配置项：**

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `ai.web.enabled` | `true` | 总开关：是否启用 AI 网络访问能力 |
| `ai.web.fetch-timeout-ms` | `10000` | URL 获取超时（毫秒） |
| `ai.web.max-body-size-bytes` | `1048576` | 最大下载体积（1MB） |
| `ai.web.max-content-chars` | `8000` | 返回给 AI 的最大文本字符数 |
| `ai.web.blocked-domains` | `localhost,127.0.0.1,10.*,172.16.*,192.168.*` | 禁止访问的域名/IP 模式 |
| `ai.web.allowed-schemes` | `http,https` | 允许的 URL 协议 |
| `ai.web.search-provider` | `tavily` | 搜索引擎提供商 |
| `ai.web.tavily-api-key` | (空) | Tavily API Key |
| `ai.web.searxng-url` | `http://localhost:8888` | SearXNG 实例地址 |

**安全考量：**
- 默认禁止访问内网地址（SSRF 防护）
- 配置 `ai.web.enabled = false` 可完全关闭此能力
- URL 获取限制体积（1MB）和超时（10s），防止 DoS
- 域名黑名单支持通配符匹配

#### 变更 7: 新增依赖

**文件**: `build.gradle.kts`

```kotlin
// HTML 解析与文本提取
implementation("org.jsoup:jsoup:1.18.3")
```

> 注：Tavily API 调用使用 Spring 自带的 `RestClient`，不需要额外依赖。

### 涉及文件汇总

| 文件 | 改动类型 |
|------|----------|
| `build.gradle.kts` | 新增 Jsoup 依赖 |
| 新建 `WebFetchTool.java` | URL 获取 + 文本提取 |
| 新建 `WebSearchTool.java` | 网络搜索（Tavily / SearXNG） |
| `OrchestratorEngine.java` | 注入并注册两个新工具 |
| `ToolSetSelector.java` | 白名单加入 `fetchUrl`、`webSearch` |
| `SystemPrompts.java` | OPS_ASSISTANT 能力清单新增网络访问 |
| `DataInitializer.java`（可选） | 初始化默认配置值 |

### 风险评估

- **低风险**: 新增工具是纯增量改动，不修改现有逻辑
- **安全敏感**: SSRF 防护必须到位（内网地址阻断），建议上线前做安全审查
- **需配置**: Tavily 需要 API Key，首次部署需在系统设置中配置
- **降级方案**: 若未配置 API Key 或 `ai.web.enabled=false`，工具返回友好提示而非报错

---

## 问题三：AI 缺少通用工具能力

### 现状分析

当前 AI 的 15 个工具全部面向运维场景（主机管理、脚本执行、任务监控等）。缺少通用的实用工具，导致 AI 在处理一些基础任务时能力受限：

- 无法获取当前时间或进行时间计算
- 无法进行数学运算
- 无法处理文本（正则提取、diff 对比等）
- 无法转换数据格式（JSON ↔ YAML 等）
- 无法发送通知
- 无法查询知识库（如果有的话）
- 无法进行编码/解码操作

### 解决方案：新增 7 个通用工具

#### 工具 1: DateTimeTool — 时间日期处理（P0）

**目的**: 获取当前时间、解析时间字符串、计算时间差。运维场景中经常需要处理时间（日志时间、任务调度、超时计算等）。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/DateTimeTool.java`

```java
@Slf4j
@Component
public class DateTimeTool {

    @Tool(description = "获取当前时间。可指定时区和输出格式。")
    public String getCurrentTime(
            @ToolParam(description = "时区，如 'Asia/Shanghai'、'UTC'、'America/New_York'，默认 UTC") String timezone,
            @ToolParam(description = "输出格式，如 'yyyy-MM-dd HH:mm:ss'、'ISO8601'，默认 ISO8601") String format) {
        // 返回格式化的当前时间
    }

    @Tool(description = "解析时间字符串为标准格式。支持多种常见格式的自动识别。")
    public String parseTime(
            @ToolParam(description = "要解析的时间字符串") String timeStr,
            @ToolParam(description = "输入时间的格式（可选，不提供则自动识别）") String inputFormat,
            @ToolParam(description = "目标输出格式") String outputFormat) {
        // 解析并转换时间格式
    }

    @Tool(description = "计算两个时间点之间的差值。")
    public String timeDiff(
            @ToolParam(description = "开始时间") String startTime,
            @ToolParam(description = "结束时间") String endTime,
            @ToolParam(description = "返回单位：'seconds'、'minutes'、'hours'、'days'、'human'（人类可读）") String unit) {
        // 计算时间差并按指定单位返回
    }
}
```

**使用场景**:
- "现在几点了？"
- "这个日志时间戳 1709123456 是什么时候？"
- "任务从 10:30 到 15:45 执行了多久？"

---

#### 工具 2: CalculatorTool — 数学计算（P1）

**目的**: 进行数学表达式计算和单位换算。运维场景中经常需要计算（磁盘容量、内存百分比、带宽换算等）。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/CalculatorTool.java`

```java
@Slf4j
@Component
public class CalculatorTool {

    @Tool(description = "计算数学表达式。支持 +、-、*、/、^（幂）、%（取模）、括号、常用数学函数（sqrt、sin、cos、log、abs 等）。")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '(100 - 75) / 100 * 100' 或 'sqrt(144) + 2^3'") String expression) {
        // 使用 exp4j 库解析和计算表达式
    }

    @Tool(description = "存储单位换算。在 B、KB、MB、GB、TB、PB 之间转换。")
    public String convertStorageUnit(
            @ToolParam(description = "数值") double value,
            @ToolParam(description = "源单位：B/KB/MB/GB/TB/PB") String fromUnit,
            @ToolParam(description = "目标单位：B/KB/MB/GB/TB/PB") String toUnit) {
        // 单位换算
    }
}
```

**依赖**: `net.objecthunter:exp4j:0.4.8` — 轻量级数学表达式解析器

**使用场景**:
- "磁盘使用了 800GB，总共 2TB，使用率是多少？"
- "1.5TB 等于多少 GB？"
- "计算 (1024 * 1024 * 500) / (1024 * 1024 * 1024)"

---

#### 工具 3: TextProcessTool — 文本处理（P1）

**目的**: 正则提取、文本对比、统计等。运维场景中经常需要从日志/输出中提取信息。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/TextProcessTool.java`

```java
@Slf4j
@Component
public class TextProcessTool {

    @Tool(description = "使用正则表达式从文本中提取内容。返回所有匹配项。")
    public String extractByRegex(
            @ToolParam(description = "要搜索的文本") String text,
            @ToolParam(description = "正则表达式") String regex,
            @ToolParam(description = "要提取的捕获组编号，0 表示整个匹配，默认 0") int group) {
        // 正则匹配并提取
    }

    @Tool(description = "对比两段文本的差异，返回 diff 结果。")
    public String diffText(
            @ToolParam(description = "原始文本") String originalText,
            @ToolParam(description = "修改后的文本") String modifiedText,
            @ToolParam(description = "输出格式：'unified'（统一 diff）、'inline'（行内标记）") String format) {
        // 使用 java-diff-utils 生成 diff
    }

    @Tool(description = "统计文本信息：字符数、单词数、行数等。")
    public String textStats(
            @ToolParam(description = "要统计的文本") String text) {
        // 返回统计信息
    }
}
```

**依赖**: `io.github.java-diff-utils:java-diff-utils:4.12` — Diff 算法库

**使用场景**:
- "从这段日志中提取所有 IP 地址"
- "对比这两个配置文件的差异"
- "这段文本有多少行？"

---

#### 工具 4: DataFormatTool — 数据格式转换（P2）

**目的**: JSON、YAML、XML、CSV 等格式之间的转换，以及 JSONPath 查询。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/DataFormatTool.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DataFormatTool {

    private final ObjectMapper objectMapper;
    private final YAMLMapper yamlMapper;

    @Tool(description = "在不同数据格式之间转换。支持 JSON、YAML、Properties 格式。")
    public String convertFormat(
            @ToolParam(description = "要转换的数据") String data,
            @ToolParam(description = "源格式：json/yaml/properties") String fromFormat,
            @ToolParam(description = "目标格式：json/yaml/properties") String toFormat) {
        // 格式转换
    }

    @Tool(description = "格式化 JSON 字符串，使其更易读。")
    public String prettyPrintJson(
            @ToolParam(description = "要格式化的 JSON 字符串") String json) {
        // Pretty print
    }

    @Tool(description = "使用 JSONPath 表达式从 JSON 中提取数据。")
    public String extractJsonPath(
            @ToolParam(description = "JSON 数据") String json,
            @ToolParam(description = "JSONPath 表达式，如 '$.store.book[0].title' 或 '$..author'") String jsonPath) {
        // 使用 JsonPath 库提取
    }
}
```

**依赖**:
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` — YAML 支持（Spring Boot 已有）
- `com.jayway.jsonpath:json-path:2.9.0` — JSONPath 查询

**使用场景**:
- "把这个 JSON 转换成 YAML"
- "从这个 API 响应中提取所有用户的 email"
- "格式化这个压缩的 JSON"

---

#### 工具 5: NotificationTool — 发送通知（P1）

**目的**: 复用现有的 BotChannelService，让 AI 能够主动发送通知到配置的渠道。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/NotificationTool.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationTool {

    private final BotChannelRepository botChannelRepository;
    private final BotChannelService botChannelService;

    @Tool(description = "发送通知消息到配置的 Bot 渠道（Telegram、Discord、Slack、钉钉、飞书、企业微信）。")
    public String sendNotification(
            @ToolParam(description = "通知消息内容") String message,
            @ToolParam(description = "渠道名称（可选，不提供则发送到所有已启用的渠道）") String channelName,
            @ToolParam(description = "消息类型：'info'、'warning'、'error'、'success'，影响消息样式") String messageType) {
        // 调用 BotChannelService 发送
    }

    @Tool(description = "列出所有可用的通知渠道。")
    public String listChannels() {
        // 返回已配置的渠道列表
    }
}
```

**使用场景**:
- "发现磁盘使用率超过 90%，通知运维群"
- "任务执行完成，发送结果到 Telegram"
- "发送告警到钉钉"

**注意**: 复用现有 `BotChannelService`，无需新增外部依赖。

---

#### 工具 6: KnowledgeBaseTool — 知识库查询（P2）

**目的**: 利用 Spring AI 的 VectorStore 实现 RAG（检索增强生成），让 AI 能够查询内部知识库。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/KnowledgeBaseTool.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(VectorStore.class)  // 仅在配置了 VectorStore 时启用
public class KnowledgeBaseTool {

    private final VectorStore vectorStore;

    @Tool(description = "从知识库中搜索相关文档。适用于查找内部文档、操作手册、FAQ 等。")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询") String query,
            @ToolParam(description = "返回结果数量，默认 5") int topK) {
        // 向量检索
        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.query(query).withTopK(Math.min(topK, 10)));
        // 格式化返回结果
    }
}
```

**依赖**: Spring AI VectorStore（可选配置，支持 Chroma、Pinecone、PGVector 等）

**使用场景**:
- "查一下我们的运维手册中关于 MySQL 备份的说明"
- "搜索内部文档：如何申请服务器权限"

**注意**: 这是可选功能，需要额外配置 VectorStore。使用 `@ConditionalOnBean` 确保未配置时不会报错。

---

#### 工具 7: EncodingTool — 编码解码（P2）

**目的**: Base64、URL 编码/解码、哈希计算等。

**新文件**: `easyshell-server/src/main/java/com/easyshell/server/ai/tool/EncodingTool.java`

```java
@Slf4j
@Component
public class EncodingTool {

    @Tool(description = "Base64 编码或解码。")
    public String base64(
            @ToolParam(description = "要处理的字符串") String input,
            @ToolParam(description = "操作：'encode'（编码）或 'decode'（解码）") String operation) {
        // Base64 编解码
    }

    @Tool(description = "URL 编码或解码。")
    public String urlEncode(
            @ToolParam(description = "要处理的字符串") String input,
            @ToolParam(description = "操作：'encode'（编码）或 'decode'（解码）") String operation) {
        // URL 编解码
    }

    @Tool(description = "计算字符串的哈希值。")
    public String hash(
            @ToolParam(description = "要计算哈希的字符串") String input,
            @ToolParam(description = "哈希算法：'MD5'、'SHA-1'、'SHA-256'、'SHA-512'") String algorithm) {
        // 计算哈希
    }
}
```

**使用场景**:
- "把这个字符串 Base64 编码一下"
- "解码这个 URL 编码的参数"
- "计算这个文件内容的 SHA-256"

---

### 涉及文件汇总

| 文件 | 改动类型 | 优先级 |
|------|----------|--------|
| 新建 `DateTimeTool.java` | 时间日期处理 | P0 |
| 新建 `CalculatorTool.java` | 数学计算 | P1 |
| 新建 `TextProcessTool.java` | 文本处理 | P1 |
| 新建 `DataFormatTool.java` | 数据格式转换 | P2 |
| 新建 `NotificationTool.java` | 发送通知 | P1 |
| 新建 `KnowledgeBaseTool.java` | 知识库查询（可选） | P2 |
| 新建 `EncodingTool.java` | 编码解码 | P2 |
| `OrchestratorEngine.java` | 注入并注册所有新工具 | - |
| `ToolSetSelector.java` | 白名单加入所有新工具 | - |
| `SystemPrompts.java` | OPS_ASSISTANT 能力清单新增通用工具说明 | - |

### 新增依赖汇总

```kotlin
// build.gradle.kts 新增

// 数学表达式解析
implementation("net.objecthunter:exp4j:0.4.8")

// 文本 Diff
implementation("io.github.java-diff-utils:java-diff-utils:4.12")

// JSONPath 查询
implementation("com.jayway.jsonpath:json-path:2.9.0")

// YAML 支持（Spring Boot 已自带 jackson-dataformat-yaml，无需额外添加）
// VectorStore（可选，按需配置）
```

---

## 实施建议

### 优先级总览

| 优先级 | 任务 | 工作量 | 价值 |
|--------|------|--------|------|
| **P0** | Planner Prompt 更新（问题一变更 1） | 低 | 高 — 解锁已有 DAG 引擎 |
| **P0** | DagExecutor Host 注入（问题一变更 2） | 低 | 高 — 修复 DAG 模式 host 丢失 |
| **P0** | DateTimeTool | 低 | 高 — 基础能力 |
| **P1** | WebFetchTool（问题二工具 1） | 中 | 高 — 核心网络能力 |
| **P1** | CalculatorTool | 低 | 中 |
| **P1** | TextProcessTool | 中 | 中 |
| **P1** | NotificationTool | 低 | 中 — 复用现有服务 |
| **P2** | WebSearchTool（问题二工具 2） | 中 | 中 — 需要外部 API |
| **P2** | DataFormatTool | 中 | 中 |
| **P2** | EncodingTool | 低 | 低 |
| **P2** | KnowledgeBaseTool | 高 | 中 — 需要 VectorStore 配置 |

### 分阶段实施

**阶段一（核心能力）— 预计 1 天**:
1. Planner Prompt 更新 + DagExecutor Host 注入（问题一）
2. WebFetchTool（问题二核心）
3. DateTimeTool

**阶段二（增强能力）— 预计 1 天**:
1. CalculatorTool
2. TextProcessTool
3. NotificationTool
4. WebSearchTool（需要 Tavily API Key）

**阶段三（扩展能力）— 预计 1-2 天**:
1. DataFormatTool
2. EncodingTool
3. KnowledgeBaseTool（可选，取决于是否需要 RAG）

### ToolSetSelector 白名单更新

```java
// ToolSetSelector.java 更新

// 通用工具 — 所有任务类型都可用
private static final Set<String> UNIVERSAL_TOOLS = Set.of(
    "getCurrentTime", "parseTime", "timeDiff",  // DateTime
    "calculate", "convertStorageUnit",           // Calculator
    "extractByRegex", "diffText", "textStats",  // TextProcess
    "convertFormat", "prettyPrintJson", "extractJsonPath",  // DataFormat
    "base64", "urlEncode", "hash"               // Encoding
);

// 需要特定权限的工具
TaskType.QUERY → 加入 "fetchUrl", "webSearch", "searchKnowledge"
TaskType.GENERAL → 加入 "fetchUrl", "webSearch", "searchKnowledge"
TaskType.EXECUTE → 加入 "sendNotification", "listChannels"  // 执行后可能需要通知
```

### 系统 Prompt 更新

**文件**: `SystemPrompts.java` — `OPS_ASSISTANT`

在能力清单中新增：

```
### 网络访问
- 获取指定 URL 的网页内容并提取纯文本用于分析
- 使用搜索引擎搜索互联网信息（技术文档、错误解决方案等）

### 通用工具
- 时间日期：获取当前时间、解析时间、计算时间差
- 数学计算：表达式计算、存储单位换算
- 文本处理：正则提取、文本对比 diff、统计
- 数据格式：JSON/YAML/Properties 互转、JSONPath 查询、格式化
- 编码解码：Base64、URL 编码、哈希计算
- 发送通知：将消息推送到配置的 Bot 渠道
- 知识库搜索：查询内部文档（如已配置）
```

---

## 测试验证

### 问题一（串行任务）测试用例

1. **串行依赖测试**: "检查所有在线主机的磁盘使用率，对超过 80% 的主机执行清理脚本"
   - 预期：计划包含 `depends_on`（步骤 2 依赖步骤 1）
   - 验证：DAG 模式被触发（`isDagPlan()` 返回 true）

2. **变量传递测试**: "先获取负载最高的主机，然后在该主机上执行诊断脚本"
   - 预期：步骤 1 有 `output_var`，步骤 2 的 `input_vars` 引用该变量

3. **Host Context 测试**: 验证 DAG 模式下子 Agent 收到的 prompt 包含主机信息

### 问题二（网络访问）测试用例

1. **URL 获取测试**: 给 AI 发送一个公开 URL，验证能获取并分析内容
2. **SSRF 防护测试**: 尝试获取 `http://192.168.1.1` — 应被阻断
3. **大页面截断测试**: 获取一个大于 1MB 的页面 — 应正确截断
4. **搜索测试**: "搜索 Linux 磁盘清理最佳实践" — 返回搜索结果

### 问题三（通用工具）测试用例

1. **DateTime**: "现在北京时间几点？"
2. **Calculator**: "500GB 等于多少 TB？"
3. **TextProcess**: "从这段日志中提取所有 ERROR 开头的行"
4. **DataFormat**: "把这个 JSON 转换成 YAML"
5. **Notification**: "发送测试消息到 Telegram"
6. **Encoding**: "Base64 编码 'hello world'"

---

## 风险评估

| 风险点 | 级别 | 缓解措施 |
|--------|------|----------|
| SSRF（WebFetchTool 访问内网） | 高 | 域名黑名单 + IP 范围检查 |
| LLM 无法稳定生成 DAG JSON | 中 | Prompt 中提供 2-3 个完整示例 |
| Tavily API Key 未配置 | 低 | 返回友好提示而非报错 |
| 表达式注入（CalculatorTool） | 低 | exp4j 只支持数学表达式，无代码执行风险 |
| 正则 ReDoS（TextProcessTool） | 中 | 设置匹配超时 |
| VectorStore 未配置 | 低 | 使用 @ConditionalOnBean 优雅降级 |
