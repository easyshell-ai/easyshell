# 工作流 recovery claim 有界协议（未接线）

本协议及实现只新增持久互斥与状态证据，不启用恢复。Repository 不注册 Spring Bean，默认构造关闭；测试或未来经审查的调用者必须显式开启。迁移建表不代表功能开启。没有引擎、扫描器、Task、审批或旧 worker 接线，没有自动重派，不宣称 exactly-once、多实例安全或完整恢复。

## 身份与持久边界

- `workflow_recovery_run` 是永久 run 槽和已提交 round 高水位。先显式 provision，再锁该行；一 run 只有一个 active round。并发首次 provision 的唯一键冲突由外层回滚后重试，不吞 SQL 错误。
- batch 主键 `(run_id, claim_round)`；round 每个 run 单调递增，已提交 round 永不复用，溢出失败。事务回滚的分配不可对外暴露；随机 fencing token 进一步区分事务内身份。调用者只能在 commit 成功后使用 claim；commit 结果未知不得派发。
- member 固定精确的 `(node_run_id, attempt)`，另存 node key；这对身份全表唯一且永久保留，因此连已取消的同一 attempt 也不能再次 claim。需要以后业务分配全新 attempt 才可能建新 batch，本底座不分配 attempt。不查旧表，调用者未来必须在同事务验证 run/node/attempt 归属及状态。
- batch/member/round 行兼作持久墓碑，不提供删除、重置、租约续期、owner 迁移、reclaim API。没有新审计表。未来需要业务审计时复用 `AuditLogService` / `audit_log`，本底座不虚构已完成业务审计。

## 状态与 CAS

`CLAIMED -> ENTERED -> SUCCEEDED | FAILED | UNCERTAIN`；`CLAIMED -> CANCELLED`。

`ENTERED` 是保守的交付屏障：**先在独立已提交事务中写 ENTERED，再向 executor 提交**。它包含 accepted、可能 accepted、已经执行；不能据此断言已经开始。即使交付明确拒绝，也无回到 CLAIMED 的路径。外部副作用前后没有原子事务，因此无法承诺一次执行。

每次写先锁 run 槽，再匹配 active round、owner、随机 fencing token、数据库时间租约，再以完整 member 身份和预期状态更新。owner/token 是数据库 CAS 凭据，不是外部 executor 的真实 fencing；未改造旧 worker 不能受本协议保护。时间使用数据库 `CURRENT_TIMESTAMP(6)`，无应用传入的 now。事务应短小，数据库事务时间语义和真实 MySQL 锁/隔离行为尚需验证。

正常写在租约到期后失败。显式 `expire` 仅在匹配相同 batch 身份且数据库确认过期后，将 CLAIMED 变 CANCELLED、ENTERED 变 UNCERTAIN；它不是接管，不换 owner/token、不派发。UNCERTAIN 永久不可重派，不开放凭猜测解决未知的 API。`finishBatch` 仅当所有 member 均为 SUCCEEDED/FAILED/CANCELLED 才释放 active 槽；UNKNOWN/ENTERED/CLAIMED 均阻塞。

取消只逐个 CAS 请求中的精确成员，且只接受 CLAIMED；返回实际变更数。已 ENTERED 或未列出的成员不变。此处 CANCELLED 只是 claim 墓碑，**不写旧 node/run 状态，不冒充 `resume_aborted` 的业务收尾**。超时/取消不覆盖 UNCERTAIN；未来 watchdog 无权自动 reclaim。

## 事务与故障窗口

所有 API 要求调用者持有 `autoCommit=false` 的 JDBC Connection，不自行 commit、rollback 或开连接；锁顺序为 run 槽 -> batch -> member。任何 SQL/校验异常都要求调用者回滚整个事务；不得捕获后提交部分批次。使用相同 Connection 的未来终态写可以与结果/变量/Task 绑定原子提交，但本次未实现这些业务写入或 JPA 事务适配。

| 窗口 | 持久证据和保守处理 |
|---|---|
| 创建 batch 事务回滚 | batch/member/高水位全部回滚，无可发布身份 |
| CLAIMED 提交后崩溃 | 过期仅允许 CANCELLED 墓碑，同 attempt 不再 claim |
| ENTERED 提交前崩溃 | 不允许对外派发；按 CLAIMED 处理 |
| ENTERED 提交后、提交 executor 前崩溃 | 按未知执行处理，过期转 UNCERTAIN，可能损失进度 |
| executor accepted 后崩溃/返回丢失 | 同上，绝不自动重派 |
| 终态与结果事务回滚 | ENTERED 保留，未来需要结果证据；不凭失败重派 |
| 旧 owner/token 回写 | 身份/active round/租约拒绝；这不阻止旧 worker 的外部动作 |

## 验证及集成责任

新增测试使用每测试唯一 H2 内存库、keeper Connection、真实 Flyway classpath 迁移、独立 JDBC 连接与事务；覆盖并发 claim/进入竞争、旧 token/owner/过期拒绝、回滚、高水位、未知不 reclaim、精确取消及永久身份唯一性。测试不启动 Spring、网络或容器。本轮按授权不启动构建或测试，尚无执行通过证据，也未验证真实 MySQL。

加入 `V1_2_20` 会改变现有 migration exact version / 表清单 / 最新版本断言（包括 `Release112MigrationH2Test` 等）；由协调者同步这些既有测试及相关 schema filter 清单，本子任务不修改已有文件。迁移只有 CREATE，无旧表外键，无 ALTER/DROP，继承数据库字符集/排序规则。正式接线前还须评审真实 MySQL、跨实例、旧 worker fencing、业务权限/审计及结果原子提交；不得仅凭本底座启用自动恢复。
