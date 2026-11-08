package com.easyshell.server.workflow.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.model.ValueType;
import com.easyshell.server.workflow.model.VariableSpec;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作流触发与取消入口（纯逻辑层，Controller 后续只做参数绑定与权限注解）。
 *
 * <p>四件事：① 入参按 {@code definition.variables} 校验（类型系统 + 必填 + 默认值）；
 * ② {@code uk_wfr_idem(started_by, idempotency_key)} 幂等（重复触发返回 200 + 首个 runId）；
 * ③ 全局并发上限（超出的 run 置 queued，由扫描器泵回）；
 * ④ 条件 UPDATE pending/queued -&gt; running 后 {@code submitDrive}——<b>立即返回，
 * drive 永远不跑在 HTTP 线程上</b>（§3.2.1）。</p>
 */
@Slf4j
@Service
public class WorkflowRunService {

    private static final List<String> TERMINAL_RUN = List.of("succeeded", "failed", "cancelled", "timeout");

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowApprovalRepository approvalRepository;
    private final WorkflowEngine engine;
    private final AuditLogService auditLogService;
    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper;

    /** 显式事务边界（REQUIRED）：触发链路要么整段落库要么整段回滚，不留半份 run */
    private final TransactionTemplate transactionTemplate;

    public WorkflowRunService(WorkflowRepository workflowRepository,
                              WorkflowVersionRepository versionRepository,
                              WorkflowRunRepository runRepository,
                              WorkflowNodeRunRepository nodeRunRepository,
                              WorkflowApprovalRepository approvalRepository,
                              WorkflowEngine engine,
                              AuditLogService auditLogService,
                              WorkflowProperties properties,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.approvalRepository = approvalRepository;
        this.engine = engine;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 触发一个运行（幂等）。
     *
     * @param idempotencyKey 同 userId 同 key 重复触发返回首个 run（§3.5 幂等①）
     */
    public WorkflowRun trigger(Long workflowId, Long versionId, Map<String, Object> input,
                               Long userId, String idempotencyKey, String triggerType) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND"));
        WorkflowVersion version = versionRepository.findByIdAndWorkflowId(versionId, workflowId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND"));
        if (!Boolean.TRUE.equals(version.getIsValid())) {
            throw new BusinessException(409, "NOT_PUBLISHED: 版本未发布或校验未通过");
        }

        WorkflowDefinition def = parseDefinition(version.getDefinitionJson());
        Map<String, Object> normalizedInput = validateInput(def, input, userId);

        // 同工作流触发最小间隔（§3.9 trigger.min-interval-sec）
        int minInterval = properties.getEngine().getMinTriggerIntervalSec();
        if (minInterval > 0) {
            List<WorkflowRun> recent = runRepository
                    .findByWorkflowIdOrderByCreatedAtDesc(workflowId, PageRequest.of(0, 1)).getContent();
            if (!recent.isEmpty()) {
                LocalDateTime last = recent.get(0).getStartedAt();
                if (last != null && last.plusSeconds(minInterval).isAfter(LocalDateTime.now())) {
                    throw new BusinessException(429, "WORKFLOW_CONCURRENCY_LIMIT: 同一工作流触发过于频繁");
                }
            }
        }

        // 幂等①：同 userId 同 key 已触发过 → 返回首个 run
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<WorkflowRun> existing = runRepository.findByStartedByAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        run.setWorkflowId(workflowId);
        run.setVersionId(versionId);
        run.setVersionNo(version.getVersion());
        run.setTriggerType(triggerType == null || triggerType.isBlank() ? "manual" : triggerType);
        run.setStatus("pending");
        run.setStartedBy(userId);
        run.setIdempotencyKey(idempotencyKey);
        run.setInputJson(toJson(normalizedInput));
        run.setRiskSnapshotJson(version.getEstimatedRisk());
        try {
            run = runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            // 并发同 key：唯一键兜底，重查后返回首个 run（§3.5）
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                return runRepository.findByStartedByAndIdempotencyKey(userId, idempotencyKey)
                        .orElseThrow(() -> new BusinessException(409, "IDEMPOTENCY_CONFLICT"));
            }
            throw e;
        }

        // 触发审计（五类高危事件之一，同步写）
        auditLogService.logSync(userId, "SYSTEM", "WORKFLOW_RUN_TRIGGER", "workflow_run", run.getId(),
                "workflow=" + workflowId + ", version=" + versionId + ", trigger=" + run.getTriggerType(),
                null, "success");
        return run;
    }

    /**
     * 触发并启动执行：校验入参 → 建 run → 灌变量 → 条件 UPDATE 启动 → 提交 drive。
     *
     * <p>{@code submitDrive} 注册在事务 afterCommit：drive 跑在引擎池线程上，
     * 必须能看到已提交的 run 行（§3.2.1：drive 永远不跑在 HTTP 线程上）。</p>
     *
     * @return 已启动的 run（status=running 或 queued）
     */
    public WorkflowRun startRun(Long workflowId, Long versionId, Map<String, Object> input,
                                Long userId, String idempotencyKey, String triggerType) {
        return transactionTemplate.execute(status ->
                startRunInTransaction(workflowId, versionId, input, userId, idempotencyKey, triggerType));
    }

    private WorkflowRun startRunInTransaction(Long workflowId, Long versionId, Map<String, Object> input,
                                              Long userId, String idempotencyKey, String triggerType) {
        WorkflowRun run = trigger(workflowId, versionId, input, userId, idempotencyKey, triggerType);
        WorkflowDefinition def = parseDefinition(
                versionRepository.findDefinition(run.getVersionId()).orElse("{}"));

        long active = runRepository.countByStatusIn(List.of("pending", "queued", "running", "waiting"));
        boolean hasCapacity = active <= properties.getEngine().getMaxConcurrentRuns();
        if (hasCapacity) {
            int started = runRepository.startAtomically(run.getId(), LocalDateTime.now(), ownerInstance());
            if (started == 0) {
                // 已被取消/终态（并发取消竞态）：不再驱动
                return runRepository.findById(run.getId()).orElse(run);
            }
            engine.initVariables(run, def, parseInput(run.getInputJson()));
            afterCommit(() -> engine.submitDrive(run));
        } else {
            // 背压①：置 queued，由 WorkflowPendingWakeupScanner 泵回（§3.9）
            runRepository.queueIfPending(run.getId());
            log.info("run {} 进入排队（当前活跃 run 数 {}）", run.getId(), active);
        }
        return runRepository.findById(run.getId()).orElse(run);
    }

    /** 事务提交后执行；没有活动事务时直接执行（测试/内部调用） */
    private void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    /**
     * 取消运行（B10 强披露语义由 UI 承担；这里负责状态迁移、job 逻辑取消与审计）。
     */
    public void requestCancel(String runId, Long userId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            throw new BusinessException(404, "RUN_NOT_FOUND");
        }
        if (TERMINAL_RUN.contains(run.getStatus())) {
            return;   // 幂等
        }
        engine.requestCancel(runId, userId);
    }

    // ------------------------------------------------------------------
    // 入参校验（§2.7 类型系统 + 四条报错硬规则）
    // ------------------------------------------------------------------

    private Map<String, Object> validateInput(WorkflowDefinition def, Map<String, Object> input, Long userId) {
        Map<String, Object> provided = input == null ? Map.of() : input;
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, VariableSpec> specs = def.getVariables() == null ? Map.of() : def.getVariables();

        for (String key : provided.keySet()) {
            if (!specs.containsKey(key)) {
                throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: 未声明的入参 '" + key
                        + "'（服务端 schema 权威，未知字段一律拒绝）");
            }
        }
        for (Map.Entry<String, VariableSpec> entry : specs.entrySet()) {
            String name = entry.getKey();
            VariableSpec spec = entry.getValue();
            ValueType type = ValueType.fromSpec(spec.getType());
            if (type == null) {
                throw new BusinessException(400, "WORKFLOW_TYPE_MISMATCH: 变量 '" + name + "' 类型未知: "
                        + spec.getType());
            }
            Object value = provided.get(name);
            if (value == null) {
                if (spec.getDefaultValue() != null) {
                    value = spec.getDefaultValue();
                } else if (Boolean.TRUE.equals(spec.getRequired())) {
                    throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: 缺少必填入参 '" + name + "'");
                } else {
                    continue;
                }
            }
            String mismatch = type.validate(value);
            if (mismatch != null) {
                throw new BusinessException(400, "WORKFLOW_TYPE_MISMATCH\n"
                        + "  runId: -  nodeKey: -  attempt: -  field: run.input." + name + "\n"
                        + "  expected: " + type.code() + "    actual: " + ValueType.describe(value) + "\n"
                        + "  hint: 触发入参类型与声明不符（" + mismatch + "）");
            }
            normalized.put(name, type.normalize(value));
        }
        return normalized;
    }

    // ------------------------------------------------------------------

    private Map<String, Object> parseInput(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private WorkflowDefinition parseDefinition(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new BusinessException(500, "WORKFLOW_INTERNAL: 定义解析失败");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 单执行者标识（M1 不支持多实例，G-20：ownerInstance 用于启动自检） */
    private String ownerInstance() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
