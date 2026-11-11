# 精确派发审批底座（尚未接入）

本底座不改变风险门禁或现有审批验证器；不能用于宣称高风险派发已可用。
`V1_2_10` 为避开目前的 1.2.0–1.2.2 而预留，合并各 lane 前仍须检查版本冲突和 Flyway 已部署版本；不得修改已经发布的迁移。

## 密钥

在数据库外的密钥管理系统生成、保存至少 32 字节随机密钥，Base64 编码后以受保护的环境变量 `EASYSHELL_DISPATCH_HMAC_KEY` 注入所有 server 实例。例如安全环境中使用 `openssl rand -base64 32`；不要把输出放进仓库、数据库、日志、命令行参数或工单。限制环境变量读取权限。所有实例必须使用相同密钥；丢失或轮换密钥会使所有未消费审批失效，轮换前应取消并重新创建审批，不得尝试降级到无签名校验。不要使用示例密钥。

调用方须在启用精确审批前显式调用 `DispatchIntentHmac.fromEnvironment()`；缺失、编码错误或少于 32 字节会抛异常，必须拒绝审批派发，不能回退。此类目前不是 Spring bean，不会主动校验进程启动配置。构造函数不验证随机性，部署方须保证密钥由安全随机源生成。

## 集成契约与未完成的安全工作

`scriptHmac(script)` 对实际派发的 UTF-8 字符串逐字节签名；`intentHmac(runId, nodeRunId, attempt, userId, scriptHmac, targets, timeoutSec)` 以版本化、长度前缀编码签名排序后的 `(agentId,generation)` 集合。字段区分大小写；不做 Unicode 或换行规范化。目标集合不得重复或为空。`matches` 只接受小写 64 位十六进制并常量时间比较。不要记录脚本、密钥、编码前内容或异常上下文中的敏感数据。

绑定证据独立存于 `workflow_dispatch_approval_binding`，`approval_id` 是指向既有 `workflow_approval.id` 的唯一软关联（主键），不是第二套审批。旧审批没有绑定行，必须拒绝授权；不得回填猜测的证据。绑定表不保存命令原文，必需字段非空，但数据库非空约束不能代替合法性检查。调用方需要定义并一致使用 `dispatch_targets` 的可解析格式，只包含目标 ID 和 generation，不含命令；从可信的活动 host identity 快照重建目标而非相信用户输入。run_id 取关联审批，并绑定 nodeRun ID、attempt、实际用户、脚本签名、完整 intent 签名、目标和超时；所有绑定必须完整且合法。消费时必须从实际派发数据重新计算两个签名，比较审批及绑定的所有字段、状态、有效期、节点与 run 归属，并确认未消费且 task ID 为空。单独验证签名不能代替这些检查。

`WorkflowSchemaFilterProvider` 已按 `workflow` 前缀将新表交给 Flyway。`WorkflowSchemaConsistencyChecker.WORKFLOW_ENTITIES` 当前仍只枚举原有五个实体，虽可接受此迁移，却不会检查新表漂移；集成方须将 `WorkflowDispatchApprovalBinding` 加入该清单，并同步更新 `WorkflowMigrationH2Test.WORKFLOW_TABLES`、期望列及主键相关验证、schema 一致性测试的表清单和说明。不要放宽现有零 ALTER / 零 DROP 契约。

**事务和 ACL 尚未解决：** 集成方须设计并测试审批决策、审批绑定不可变性、并发原子一次性消费、任务持久化与派发的事务/崩溃恢复和幂等机制；避免先消费后丢任务或先发任务后消费。须在同一可信边界重新检查用户对每个目标的 ACL、目标 active/generation 以及并发删除重建的竞态，并确定数据库锁顺序；审批不能覆盖 ACL。须明确过期和取消竞态，以及多实例配置一致性。还需要 MySQL 8 和 H2 真实迁移、历史数据、并发及端到端安全测试。现有验证器继续 fail-closed，直到上述工作完成。
