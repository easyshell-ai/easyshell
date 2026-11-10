# AGENTS.md — EasyShell 仓库协作硬规则

本文件收纳**设计文档明确要求进 AGENTS.md 的硬规则**。违反任意一条都视为漏洞，
不论测试是否全绿。

## 1. 工作流 REST 端点：权限注解与数据级校验一一对应（设计 §6.1 硬规则）

- **每个带路径变量的端点都必须经过一个数据级校验函数**，并且**各配一条"非所有者访问
  返回 403"的参数化测试**。M1 基期：`/api/v1/workflow` 下 **17 个端点 / 13 个带路径
  变量**，对应 13 条参数化 403 用例（`WorkflowControllerTest.pathVariableEndpoints()`）。
- 三个数据级校验函数只有一处实现：`workflow/controller/WorkflowAccess.java`
  （`requireWorkflowOwner` / `requireRunAccess` / `requireApprovalAdmin`）。
  **禁止在任何 Controller 里内联重复实现所有权判断**——反面教材是
  `TaskController.java:203-211`：`requireAccess` 全仓只有一个调用点，
  `agentJobs` 端点自己又写了一遍过滤。
- 新增端点时必须同步更新 `WorkflowControllerTest` 的端点清单哨兵与参数化用例，
  否则视为"缺一即漏洞"。
- 方法级 `@PreAuthorize` 管角色，数据级校验管归属，**两者缺一不可**：
  注解回答"这个角色能不能碰这类操作"，校验函数回答"这个 id 是不是他的"。

## 2. 风险门禁与出站内容纪律

- `449` 响应体与 `workflow_approval.risk_matches` / `estimated_risk` **只允许存风险等级
  与模式串，禁止存命令原文**（`CommandRisk` 携带命令原文，外泄即泄敏感数据）。
- 门禁唯一位置是 `TaskServiceImpl.createAndDispatch`；不得新建第二道门禁、第二套命令
  白名单/黑名单。
- `DispatchAuthority` 的调用方集合由 `DispatchAuthorityArchTest` 锁定，新增调用方必须
  显式修改该测试。

## 3. WebSocket 资源级授权

- 每个按资源 id 建连的 WS 端点（`/ws/task/log/{taskId}`、`/ws/workflow/run/{runId}`、
  `/ws/terminal/*`）必须在 `afterConnectionEstablished` 内做资源归属校验：
  属主或 admin 放行，**run/task 不存在或查询异常一律拒绝（fail-closed）**，
  越权关闭为 `CloseStatus.POLICY_VIOLATION` 并记 WARN 审计。
  回归测试：`TaskLogWebSocketHandlerTest`、`WorkflowRunWebSocketHandlerTest`。

## 4. 测试与验证

- `cd easyshell-server && ./gradlew clean compileJava compileTestJava test` 必须全绿。
- 全量 Spring 上下文测试（`@SpringBootTest`）必须能启动：内核的 bean 依赖
  （如 `ExpressionEvaluator`）漏登记会**启动即失败**，而单测全绿盖不住——
  见 `WorkflowApiWiringTest`。

### 4.1 四条测试命令（本地起栈与回归）

| 组件 | 命令 |
| --- | --- |
| server | `cd easyshell-server && ./gradlew clean compileJava compileTestJava test` |
| web 单元 | `cd easyshell-web && npm run lint && npx tsc --noEmit && npm test` |
| web 构建 | `cd easyshell-web && npm run build` |
| web 视觉 | `cd easyshell-web && npm run test:visual`（需要先 `npm run build`） |
| agent | `cd easyshell-agent && go build ./... && go vet ./... && go test ./...` |
| mcp | `cd easyshell-mcp && npm run lint && npm test` |

**本地起栈跑工作流**（默认 MySQL + Redis 绑回环）：

```bash
docker compose up -d                       # 起 MySQL / Redis / server / web
docker logs easyshell-server | grep 'initial admin password'   # 首次启动的初始密码
# 打开 http://localhost:18880 → /workflow；script-exec 需要至少一个在线 agent
```

工作流的 H2 集成测试不依赖 agent：`WorkflowApiContractTest` 用 `wait` 节点搭三节点
定义，因此没有执行环境也能跑通 17 个端点的契约。**契约测试改 `easyshell.workflow.*`
默认值前先看它的 `@TestPropertySource`**——它把 `min-trigger-interval-sec` 调到 0、
把 `wakeup-interval-sec` 调到 1，否则用例之间会互相排队。

### 4.2 PR 检查单（设计准入条件中属于工程纪律的部分）

- **新增端点必须同步 403 参数化测试**：带路径变量的端点各配一条"非所有者返回 403"
  用例，并更新 `WorkflowControllerTest` 的端点清单哨兵（§1 的硬规则）。
- **视觉基线必须与改样式同一个 commit**：`test:visual:update` 不与样式改动分开提交，
  否则基线追不上代码，回归测试失去意义。
- **CI 必须装 `fonts-noto-cjk` 且字体栈显式声明 CJK 家族**：`'Noto Sans CJK SC'` 要写在
  平台专属名字之前。没有它，中文会回落到 fontconfig 默认字体，同一份截图在不同机器上
  差出几千像素，视觉回归全部假红。
- **画布性能预算以比率判定**：拖拽 P95 不得超过空闲 P95 的 2 倍（40ms 下限），不用固定
  加性阈值——那是按一台机器标定的。
- **工作流契约变更必须先看 `WorkflowApiContractTest`**：VO 字段名、嵌套层级、内部列名
  是否外泄都由它锁死，改 VO 先跑它。
- **新字符串必须同时进 `zh-CN.json` 与 `en-US.json`**（设计 SF-11 / G-19）。
- **禁止第二道门禁、第二套命令白名单、第二套掩码实现**：门禁唯一位置是
  `TaskServiceImpl.createAndDispatch`，掩码唯一实现是 `SensitiveDataFilter`。

## 5. 审计

- 复用既有 `audit_log` 与 `AuditLogService`，**不另造一套审计表**。
- 五类高危事件（`WORKFLOW_PUBLISH` / `WORKFLOW_APPROVE` / `WORKFLOW_REJECT` /
  `WORKFLOW_RUN_TRIGGER` / `WORKFLOW_RUN_CANCEL`）必须 `logSync`：写失败即使业务失败。
- `workflow_run` / `workflow_node_run` / `workflow_approval` / `audit_log` **永久保留，
  禁止未来加自动清理**。

## 6. 工作流 v2 节点与边契约（2026-11 重构）

- **condition 的 IF/ELSE 是真实门控**：边上的 `sourceHandle`（`true` / `false`）决定走哪条；
  引擎读取上游 condition 节点的 `output.result`，不匹配的边不可通行。`edge.condition`
  只作为旧定义的兼容门控，新 UI 不再要求用户把表达式写两遍。
- **start / end 是真实图节点**：最多各一个；start 不得有入边，end 不得有出边。
  新工作流自动插入；旧工作流可以没有，编辑器用 WARNING/提示引导补全。
- **节点类型权威列表**：`start` / `script-exec` / `transform` / `condition` /
  `ai-analyze` / `approval` / `notify` / `wait` / `end`。新增类型必须同时更新：
  `NodeType`、执行器、`WorkflowTestRunService` switch、`DagValidator` 的表达式/结构校验、
  前端 `types.ts` / `graph.ts` / 节点注册表 / 双语文案 / 契约测试。
- **变量引用只允许拓扑祖先**：模板用 `${path}`，表达式用裸路径；发布期由
  `DagValidator` 校验引用集合 ⊆ 祖先集合，运行期 fail-closed。禁止在前端发明第三套
  引用语法或第二套变量存储。
- **`ai-analyze` 只产出结论文本，不产出命令**：执行器不得注入 `TaskService` /
  `DispatchAuthority`，输出必须过 `SensitiveDataFilter`；需要执行时必须由人写脚本并经过
  `approval` 节点，AI 不是授权来源。
- **画布选中态只属于 React Flow**：不得把 `selectedKeys`/`selectedEdgeIds` 提升到页面
  state 再回写节点数组；React Flow 回调必须稳定。React #185 的根因就是这条被违反。
