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

## 5. 审计

- 复用既有 `audit_log` 与 `AuditLogService`，**不另造一套审计表**。
- 五类高危事件（`WORKFLOW_PUBLISH` / `WORKFLOW_APPROVE` / `WORKFLOW_REJECT` /
  `WORKFLOW_RUN_TRIGGER` / `WORKFLOW_RUN_CANCEL`）必须 `logSync`：写失败即使业务失败。
- `workflow_run` / `workflow_node_run` / `workflow_approval` / `audit_log` **永久保留，
  禁止未来加自动清理**。
