# 从 v1.1.2 升级：当前候选与数据保护

**不得在未备份、未停旧写入者的情况下直接连接需要保留的数据库。** 用户已授权重建测试环境。正式标签 v1.1.2 原程序生成的数据库已完成升级及备份恢复回滚演练，下面记录证据和边界；这不是任意客户旧库均可直接升级的保证。

## 正式基线与迁移合并

正式发布基线为标签 `v1.1.2`（提交 `db20540`）。该版本的服务端没有当前 Flyway 迁移目录，其存量表由 Hibernate 管理。当前候选中的迁移均位于该标签之后；文件已被 Git 跟踪不代表该版本已正式发布。

针对从纯 v1.1.2 直接升级、且没有旧版本 server 并行写入的路径：

- 将 `acl_version BIGINT NOT NULL DEFAULT 0` 放入首次创建 `host_identity` 的 `V1_2_1`；原未发布 `V1_2_18` 不再单独执行。
- 将非唯一索引 `idx_agent_enrollment_active(agent_id, consumed_at)` 放入 `V1_2_14`；原未发布 `V1_2_16` 不再单独执行。
- 其余迁移及版本空缺保留。尤其不能删去 `host_acl_state` 的初始记录、身份墓碑、ACL、任务目标快照、来源、审批绑定、Agent 凭证或用户代数表，也不能为历史记录凭空补造来源。
- MySQL 实测发现显式 `DEFAULT CHARSET=utf8mb4` 会使用服务器默认排序规则，而 Hibernate 存量表使用数据库默认排序规则，二者不同时跨表权限查询会 500。未发布迁移现在继承数据库字符集与排序规则；已在数据库默认 `utf8mb4_unicode_ci` 的全新专用 MySQL 上验证关键跨表联接。实际旧库必须确认相关存量列的字符集及排序规则一致，不能单靠数据库默认值推断。
- 旧 Agent 和用户的 ACL 回填仍由 `HostAclMigrationRunner` 调用 Java 事务完成，而非仅靠建表。首次建表默认 ACL 版本为零；回填后每条新增授权会推进版本，因此不能要求启动后的版本仍为零。

被移除的两个未跟踪开发脚本在本机 `/tmp/opencode/easyshell-112-migration-originals/` 留有原件。原位修改的 `V1_2_1` 和 `V1_2_14` 没有逐字节原件备份；测试中的旧脚本是明确标注的手写模拟，不代表任何实际环境的 checksum。

## 开发中间态：单独处理，不能自动修复

已执行旧 `1.2.x` 的数据库不属于上述纯正式基线。修改后的早期脚本可能与已有 checksum 不一致，被移除的 `1.2.16` / `1.2.18` 也可能已写入 history；某些库可能只有部分表或缺少 `acl_version`。手写模拟 history 的 H2 测试覆盖了代表性的拒绝场景，但不能推断任何现存数据库的实际状态。

**特别注意：**非空数据库若没有 Flyway history，但已手工存在部分新表，当前 `baseline-on-migrate` 可能先写 history 和部分 MySQL DDL，然后才因表冲突失败。仓库尚无覆盖所有配置与数据库状态的启动前 fail-closed 守卫。不能把“Flyway 校验通常会拒绝”当作“失败前绝不写入”的保证。

必须先获得授权，在停止旧写入者的前提下，对受保护副本单独核对实际 history、表、列和数据；制定并验证其专用兼容方案。禁止擅自执行 Flyway `repair`、重置 history、删表、删卷或覆盖身份/ACL。无 history、部分 history、失败 DDL 和混跑都必须单独处理。工作流运行、节点、审批及审计记录不得删除。

## 已验证与未验证

- H2 真正运行当前 Flyway：覆盖空库、少量合成旧表和数据的非空库、baseline `1`、最终列与索引、重复迁移，以及两种手写开发 history 的校验拒绝。保留原有迁移 SQL 零 ALTER/DROP 检查；它不约束 Hibernate `ddl-auto=update` 对存量表可能产生的 DDL。
- 独立 H2 模拟旧库执行真实 Spring JPA/JDBC 事务和真实 ACL 服务，覆盖多 Agent、角色、禁用用户、授权版本、重复调用和回填失败回滚；runner 的初始化和调度阶段受控模拟，不能代表完整生产启动或实际重启。
- 使用新建、无宿主挂载与端口、仅 loopback 的临时 MySQL 8 容器，对四张合成旧表运行真实 Flyway，并验证 baseline、版本、数据、关键列/索引和重复迁移；另在该专用库运行主机身份并发重建测试。该实例不是 v1.1.2 实际旧库，也没有运行生产 ACL 回填或真实 Agent。
- 包含精确审批、持久恢复和远程 code 的独立网络命名空间 clean 全量为 1560 项、0 失败、5 条件跳过；四项真实 Java mTLS 测试另行实际执行通过。`WorkflowApiWiringTest` 实际启动，基础设施通用 `contextLoads` 仍禁用。专用 MySQL 另实际验证排序规则、恢复 SQL 1093 回归以及 RR 旧快照不能错误释放恢复槽。
- 单一独立测试栈实际完成双 VM LOW 只读脚本、真实 OpenAI 协议 LLM 文本/严格 JSON/分类、脚本输出传模型，以及 HIGH `chmod --help` 的精确审批批准后双 VM 成功、拒绝后不派发；Agent 在测试 server 更新后可重连。此为测试实例证据，不能替代真实旧库或多实例崩溃证明。
- GitHub Actions、完整崩溃恢复和生产 code 部署仍未完成。临时测试库升级前备份已导入独立 proof schema，恢复42张表、8条运行和两台 Agent，全部 `CHECK TABLE` 为 OK。
- code 在专用运行时完成 JS/Python、mTLS 跨身份授权、取消、监督器 SIGKILL 独立回收、memcg OOM 后恢复、保留 journal 重启和固定空环境 native launcher 验收。产品浏览器样例与工作流详情已验证。此证据不覆盖固定基础镜像供应链、完整主机重启或 systemd 模板，`easyshell-code-worker/deploy` 仍保持发布阻断。

后端、前端、Agent 身份底座和 worker 已分批建立本地候选提交；尚未推送、打标签或声明正式发布。独立体验配置见 `docs/isolated-trial.md`；文档中的 Compose 方案与实际专用运行时试用栈必须区分，不能未经验证混用。

## 正式 v1.1.2 程序生成旧库的实测演练

2026-11-11 从正式标签 `v1.1.2` 导出未修改源码并构建原版 bootJar，在独立 MySQL schema
启动，生成22张旧版表且没有 Flyway history。通过原版 API 登录并创建私有脚本，然后：

1. 停止旧 server，确认不再写入，执行 `mysqldump --single-transaction --routines --triggers`。
2. 当前候选启动并迁移原库，健康检查通过；Flyway baseline `1` 及至 `1.2.20` 的全部迁移成功。
3. 使用原版口令登录当前候选，核对脚本 ID、名称、内容、创建人和公开标志与升级前一致。
4. 将升级前备份导入另一独立 schema，用原版 v1.1.2 bootJar 启动；原口令登录和同一脚本
   的逐字段核对均通过，恢复库仍无 Flyway history。完成后停止 proof 写入者并保留备份。

这是真实旧版程序生成 schema 的演练，不是手写四表模拟；数据为专门创建的合成测试数据，
不覆盖每个客户的历史脏数据、外部 Agent、AI 记录及所有业务表组合。客户库仍需先做副本
预检。支持的回滚是停止新写入者后恢复升级前完整备份，再启动匹配旧版程序；**不支持
在已升级 schema 上直接运行旧版，不支持自动逆向 Flyway 或以 repair 绕过校验**。
