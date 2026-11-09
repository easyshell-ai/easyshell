package com.easyshell.server.workflow.service;

import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.exception.WorkflowValidationFailedException;
import com.easyshell.server.common.exception.WorkflowVersionConflictException;
import com.easyshell.server.model.dto.WorkflowCreateRequest;
import com.easyshell.server.model.dto.WorkflowDraftSaveRequest;
import com.easyshell.server.model.dto.WorkflowUpdateRequest;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.model.vo.WorkflowDetailVO;
import com.easyshell.server.model.vo.WorkflowValidateResultVO;
import com.easyshell.server.model.vo.WorkflowVersionConflictVO;
import com.easyshell.server.model.vo.WorkflowVersionDetailVO;
import com.easyshell.server.model.vo.WorkflowVersionVO;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.NodeExecutorRegistry;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工作流定义与版本服务（§6.1 的定义侧端点：新建 / 元信息 / 软删 / 版本 / 草稿 / 校验 / 发布）。
 *
 * <p>所有权校验不在这里——全部由 {@code WorkflowAccess} 在 Controller 边界做完，
 * 本服务只负责"已授权之后"的逻辑（与 {@code TaskServiceImpl} 的职责划分一致）。</p>
 *
 * <p><b>版本模型</b>：每个工作流有一条"草稿行"（{@code is_valid=false}，version 最大），
 * 发布即把该行翻成不可变（{@code is_valid=true + published_at/by}），下一次编辑产生
 * 新的草稿行。因此 {@code definition_sha256} 一旦发布就永不改变——它是执行指纹的
 * 唯一书面载体（§7.2.1 要素①）。</p>
 */
@Slf4j
@Service
public class WorkflowDefinitionService {

    /** estimated_risk 的 riskMatches 上限（只存模式串，防无限增长） */
    private static final int MAX_RISK_MATCHES = 20;

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final DagValidator dagValidator;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final CommandRiskEngine riskEngine;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /** 显式事务边界（REQUIRED）：发布要么整段落库要么整段回滚 */
    private final TransactionTemplate transactionTemplate;

    public WorkflowDefinitionService(WorkflowRepository workflowRepository,
                                     WorkflowVersionRepository versionRepository,
                                     DagValidator dagValidator,
                                     NodeExecutorRegistry nodeExecutorRegistry,
                                     CommandRiskEngine riskEngine,
                                     AuditLogService auditLogService,
                                     ObjectMapper objectMapper,
                                     PlatformTransactionManager transactionManager) {
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.dagValidator = dagValidator;
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.riskEngine = riskEngine;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // 工作流元信息（§6.1 POST /、PUT /{id}、DELETE /{id}）
    // ------------------------------------------------------------------

    /**
     * 管理员视角的工作流分页（§6.1 GET /page）。
     *
     * @param status   为空表示不过滤
     * @param keyword  名称模糊匹配（列表页搜索框），为空不过滤
     * @param category category 精确匹配，为空不过滤
     */
    public Page<Workflow> pageForAdmin(String status, String keyword, String category, Pageable pageable) {
        Page<Workflow> page = workflowRepository.pageForAdmin(blankToNull(status), blankToNull(keyword),
                blankToNull(category), pageable);
        fillCurrentVersions(page.getContent());
        return page;
    }

    /** 非管理员视角：created_by=本人 或 visibility=public */
    public Page<Workflow> pageForUser(String status, String keyword, String category, Long userId,
                                      Pageable pageable) {
        Page<Workflow> page = workflowRepository.pageForUser(blankToNull(status), userId, blankToNull(keyword),
                blankToNull(category), pageable);
        fillCurrentVersions(page.getContent());
        return page;
    }

    /**
     * 批量回填 {@code currentVersion}（列表页"版本"列要显示 {@code v3} 而不是版本行 id，
     * 而调用方手里只有 {@code current_version_id}）。
     *
     * <p>字段是 {@code @Transient} 不落库；一次 IN 投影查询拿齐（只取 id/version 两列，
     * 不拉 MEDIUMTEXT 的定义 JSON），避免 N+1。</p>
     */
    private void fillCurrentVersions(List<Workflow> workflows) {
        List<Long> versionIds = workflows.stream()
                .map(Workflow::getCurrentVersionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (versionIds.isEmpty()) {
            return;
        }
        Map<Long, Integer> versionById = new java.util.HashMap<>();
        for (Object[] row : versionRepository.findIdAndVersionByIdIn(versionIds)) {
            versionById.put((Long) row[0], (Integer) row[1]);
        }
        for (Workflow workflow : workflows) {
            Integer version = versionById.get(workflow.getCurrentVersionId());
            if (version != null) {
                workflow.setCurrentVersion(version);
            }
        }
    }

    public Workflow create(WorkflowCreateRequest request, Long userId, Authentication auth,
                           String ip) {
        Workflow workflow = new Workflow();
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setCategory(request.getCategory());
        workflow.setVisibility(normalizeVisibility(request.getVisibility()));
        workflow.setStatus("draft");
        workflow.setCreatedBy(userId);
        workflow = workflowRepository.save(workflow);

        auditLogService.log(userId, AuditUsers.name(auth), "WORKFLOW_CREATE", "workflow",
                String.valueOf(workflow.getId()), "name=" + workflow.getName(), ip, "success");
        fillCurrentVersions(List.of(workflow));
        return workflow;
    }

    public Workflow updateMeta(Long workflowId, WorkflowUpdateRequest request, Long userId,
                               Authentication auth, String ip) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND: 工作流不存在"));
        if (request.getName() != null && !request.getName().isBlank()) {
            workflow.setName(request.getName());
        }
        if (request.getDescription() != null) {
            workflow.setDescription(request.getDescription());
        }
        if (request.getCategory() != null) {
            workflow.setCategory(request.getCategory());
        }
        if (request.getVisibility() != null && !request.getVisibility().isBlank()) {
            workflow.setVisibility(normalizeVisibility(request.getVisibility()));
        }
        workflow = workflowRepository.save(workflow);

        auditLogService.log(userId, AuditUsers.name(auth), "WORKFLOW_UPDATE", "workflow",
                String.valueOf(workflowId), "meta updated", ip, "success");
        fillCurrentVersions(List.of(workflow));
        return workflow;
    }

    /** 软删 → archived（G-18：绝不物理删除，运行历史必须可回溯） */
    public Workflow archive(Long workflowId, Long userId, Authentication auth, String ip) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND: 工作流不存在"));
        if (!"archived".equals(workflow.getStatus())) {
            workflow.setStatus("archived");
            workflow = workflowRepository.save(workflow);
            auditLogService.log(userId, AuditUsers.name(auth), "WORKFLOW_ARCHIVE", "workflow",
                    String.valueOf(workflowId), "soft delete -> archived", ip, "success");
        }
        fillCurrentVersions(List.of(workflow));
        return workflow;
    }

    // ------------------------------------------------------------------
    // 版本读取（§6.1 GET /{id}/versions、GET /{id}/versions/{versionId}）
    // ------------------------------------------------------------------

    public List<WorkflowVersionVO> listVersions(Long workflowId) {
        return versionRepository.findByWorkflowIdOrderByVersionDesc(workflowId).stream()
                .map(WorkflowVersionVO::from)
                .toList();
    }

    /** 版本定义（平铺的版本摘要 + definitionJson 解析后的对象） */
    public WorkflowVersionDetailVO versionDefinition(Long workflowId, Long versionId) {
        WorkflowVersion version = versionRepository.findByIdAndWorkflowId(versionId, workflowId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND: 版本不存在"));
        return detailOf(version);
    }

    /**
     * 版本行 → 出站 VO：{@code definition_json} 解析后原样透出（未知字段已在入口被
     * 服务端 schema 拒绝，这里不二次建模）。保存草稿、版本详情、详情里的草稿共用它，
     * 保证三个端点的版本形状永远一致。
     */
    public WorkflowVersionDetailVO detailOf(WorkflowVersion version) {
        return WorkflowVersionDetailVO.from(version, readTree(version.getDefinitionJson()));
    }

    /**
     * 工作流详情（§6.1 GET /{id}）：元信息 + 版本列表 + 当前草稿行，编辑器首屏一次拿全。
     *
     * <p>草稿行为 null = 当前没有未发布版本（发布后、下次编辑前，G-14）。</p>
     */
    public WorkflowDetailVO detail(Long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND: 工作流不存在"));
        fillCurrentVersions(List.of(workflow));
        WorkflowVersionDetailVO draft = versionRepository
                .findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflowId)
                .map(this::detailOf)
                .orElse(null);
        return new WorkflowDetailVO(workflow, listVersions(workflowId), draft);
    }

    // ------------------------------------------------------------------
    // 草稿保存（§6.1 PUT /{id}/versions/draft，乐观锁 + 409 三选一）
    // ------------------------------------------------------------------

    /**
     * 保存草稿。
     *
     * @return 保存后的草稿行（baseVersion 已推进，客户端必须带回新值）
     * @throws WorkflowVersionConflictException 409（onConflict=reject 且 baseVersion 过期）
     */
    public WorkflowVersion saveDraft(Long workflowId, WorkflowDraftSaveRequest request, Long userId,
                                     Authentication auth, String ip) {
        return transactionTemplate.execute(status ->
                saveDraftInTransaction(workflowId, request, userId, auth, ip));
    }

    private WorkflowVersion saveDraftInTransaction(Long workflowId, WorkflowDraftSaveRequest request,
                                                   Long userId, Authentication auth, String ip) {
        WorkflowDefinition definition = parseDefinition(request.getDefinition());
        String definitionJson = writeDefinition(definition);
        String sha256 = sha256Hex(definitionJson);
        String changelog = request.getChangelog();
        int expectedBaseVersion = request.getBaseVersion() == null ? 0 : request.getBaseVersion();
        String onConflict = request.getOnConflict() == null || request.getOnConflict().isBlank()
                ? "reject" : request.getOnConflict().trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("reject", "overwrite", "copy").contains(onConflict)) {
            throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: onConflict 必须是 reject / overwrite / copy");
        }

        Optional<WorkflowVersion> currentDraft =
                versionRepository.findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflowId);

        // ① 没有草稿行：新建一条（version = max+1，baseVersion = 客户端令牌）
        if (currentDraft.isEmpty()) {
            WorkflowVersion draft = new WorkflowVersion();
            draft.setWorkflowId(workflowId);
            draft.setVersion(nextVersion(workflowId));
            draft.setBaseVersion(expectedBaseVersion);
            draft.setDefinitionJson(definitionJson);
            draft.setDefinitionSha256(sha256);
            draft.setChangelog(changelog);
            draft.setIsValid(false);
            draft = versionRepository.save(draft);
            auditLogService.log(userId, AuditUsers.name(auth), "WORKFLOW_UPDATE", "workflow",
                    String.valueOf(workflowId), "draft created version=" + draft.getVersion(), ip, "success");
            return draft;
        }

        WorkflowVersion draft = currentDraft.get();

        // ② 覆盖保存（409 三选一之 overwrite）：无条件写 + WORKFLOW_OVERWRITE 审计
        if ("overwrite".equals(onConflict)) {
            int updated = versionRepository.overwriteDraft(draft.getId(), definitionJson, sha256, changelog);
            if (updated == 0) {
                throw new BusinessException(409, "VERSION_CONFLICT: 草稿行已发布，无法覆盖");
            }
            auditLogService.logSync(userId, AuditUsers.name(auth), "WORKFLOW_OVERWRITE", "workflow",
                    String.valueOf(workflowId),
                    "draft version=" + draft.getVersion() + " overwritten, baseVersion=" + draft.getBaseVersion(),
                    ip, "success");
            return applyWritten(draft, definitionJson, sha256, changelog);
        }

        // ③ 另存副本（409 三选一之 copy）：不动当前草稿，新建一条
        if ("copy".equals(onConflict)) {
            WorkflowVersion copy = new WorkflowVersion();
            copy.setWorkflowId(workflowId);
            copy.setVersion(nextVersion(workflowId));
            copy.setBaseVersion(expectedBaseVersion);
            copy.setDefinitionJson(definitionJson);
            copy.setDefinitionSha256(sha256);
            copy.setChangelog(changelog);
            copy.setIsValid(false);
            copy = versionRepository.save(copy);
            auditLogService.log(userId, AuditUsers.name(auth), "WORKFLOW_UPDATE", "workflow",
                    String.valueOf(workflowId), "draft copied as version=" + copy.getVersion(), ip, "success");
            return copy;
        }

        // ④ 默认 reject：base_version 条件 UPDATE，影响 0 行 → 409 + 三选一契约
        int updated = versionRepository.saveDraftIfBaseVersionMatches(draft.getId(), definitionJson, sha256,
                changelog, expectedBaseVersion);
        if (updated == 0) {
            throw new WorkflowVersionConflictException(
                    WorkflowVersionConflictVO.of(draft.getBaseVersion(), draft.getId()));
        }
        auditLogService.log(userId, AuditUsers.name(auth), "WORKFLOW_UPDATE", "workflow",
                String.valueOf(workflowId), "draft saved version=" + draft.getVersion(), ip, "success");
        return applyWritten(draft, definitionJson, sha256, changelog);
    }

    /**
     * 条件 UPDATE 成功后回填内存对象。
     *
     * <p><b>为什么不 reload</b>：@Modifying 的批量 UPDATE 不进持久化上下文，同一事务里
     * {@code findById} 会返回 L1 缓存里的旧行（baseVersion 还是旧值）——客户端拿着旧令牌
     * 下一次保存就会收到假 409。这里按"刚刚写进去的值"回填，语义等价且无缓存歧义。</p>
     */
    private WorkflowVersion applyWritten(WorkflowVersion draft, String definitionJson, String sha256,
                                         String changelog) {
        draft.setDefinitionJson(definitionJson);
        draft.setDefinitionSha256(sha256);
        draft.setChangelog(changelog);
        draft.setBaseVersion(draft.getBaseVersion() + 1);
        return draft;
    }

    // ------------------------------------------------------------------
    // 校验与发布（§6.1 POST /{id}/validate、POST /{id}/publish）
    // ------------------------------------------------------------------

    /** 全量校验：DagValidator 结构规则 + 各节点执行器的配置规则 */
    public WorkflowValidateResultVO validate(JsonNode definitionNode) {
        WorkflowDefinition definition = parseDefinition(definitionNode);
        ValidationIssue.Sink sink = new ValidationIssue.Sink();
        dagValidator.validate(definition, sink);
        for (WorkflowNode node : definition.getNodes()) {
            if (node.nodeType() == null) {
                continue;   // 类型未知已由 DagValidator 报错
            }
            var executor = nodeExecutorRegistry.executorFor(node.nodeType());
            if (executor != null) {
                executor.validate(node, definition, sink);
            }
        }
        RiskSummary risk = assessEstimatedRisk(definition);
        return WorkflowValidateResultVO.builder()
                .valid(!sink.hasError())
                .issues(sink.issues().stream().map(WorkflowValidateResultVO.ValidationIssue::from).toList())
                .estimatedRisk(risk.level().name())
                .riskMatches(risk.matches())
                .build();
    }

    /**
     * 发布：校验 ERROR → 422（带 issues[]）；通过则把草稿行翻成不可变版本并回写
     * {@code workflow.current_version_id}。
     *
     * <p>审计用 {@code logSync}：WORKFLOW_PUBLISH 是五类高危事件之一（C-13），
     * 写失败即使发布失败。</p>
     */
    public WorkflowVersion publish(Long workflowId, String changelog, Long userId, Authentication auth,
                                   String ip) {
        WorkflowVersion draft = versionRepository
                .findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflowId)
                .orElseThrow(() -> new BusinessException(400,
                        "WORKFLOW_PARAM_INVALID: 没有可发布的草稿，请先保存草稿"));

        WorkflowDefinition definition = parseDefinitionNode(draft.getDefinitionJson());
        ValidationIssue.Sink sink = new ValidationIssue.Sink();
        dagValidator.validate(definition, sink);
        for (WorkflowNode node : definition.getNodes()) {
            if (node.nodeType() == null) {
                continue;
            }
            var executor = nodeExecutorRegistry.executorFor(node.nodeType());
            if (executor != null) {
                executor.validate(node, definition, sink);
            }
        }
        RiskSummary risk = assessEstimatedRisk(definition);
        if (sink.hasError()) {
            throw new WorkflowValidationFailedException(WorkflowValidateResultVO.builder()
                    .valid(false)
                    .issues(sink.issues().stream()
                            .map(WorkflowValidateResultVO.ValidationIssue::from).toList())
                    .estimatedRisk(risk.level().name())
                    .riskMatches(risk.matches())
                    .build());
        }
        if (changelog != null && !changelog.isBlank()) {
            draft.setChangelog(changelog);
            versionRepository.save(draft);
        }

        LocalDateTime publishedAt = LocalDateTime.now();
        return transactionTemplate.execute(status -> {
            int published = versionRepository.publishDraftAtomically(draft.getId(), publishedAt,
                    userId, risk.level().name());
            if (published == 0) {
                throw new BusinessException(409, "VERSION_CONFLICT: 草稿行已被发布");
            }
            workflowRepository.updateCurrentVersionAtomically(workflowId, draft.getId());
            Workflow workflow = workflowRepository.findById(workflowId).orElseThrow();
            if (!"published".equals(workflow.getStatus())) {
                workflow.setStatus("published");
                workflowRepository.save(workflow);
            }
            auditLogService.logSync(userId, AuditUsers.name(auth), "WORKFLOW_PUBLISH", "workflow",
                    String.valueOf(workflowId),
                    "version=" + draft.getVersion() + ", sha256=" + draft.getDefinitionSha256()
                            + ", estimatedRisk=" + risk.level().name()
                            + ", matches=" + String.join(",", risk.matches()),
                    ip, "success");
            // 与 saveDraft 同理：不回读，直接按刚写进去的值回填（避免 L1 缓存旧行）
            draft.setIsValid(true);
            draft.setPublishedAt(publishedAt);
            draft.setPublishedBy(userId);
            draft.setEstimatedRisk(risk.level().name());
            return draft;
        });
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 插值前风险估算（G-16：estimated_risk = 所有 script-exec 评估值的最大者） */
    private RiskSummary assessEstimatedRisk(WorkflowDefinition definition) {
        RiskLevel max = RiskLevel.LOW;
        Set<String> matches = new LinkedHashSet<>();
        for (WorkflowNode node : definition.getNodes()) {
            if (node.nodeType() != NodeType.SCRIPT_EXEC) {
                continue;
            }
            String script = node.configString("script");
            if (script == null || script.isBlank()) {
                continue;   // 缺脚本由校验器报错，不在这里升级风险
            }
            var assessment = riskEngine.assessScript(script);
            if (assessment.getOverallRisk().getLevel() > max.getLevel()) {
                max = assessment.getOverallRisk();
            }
            if (assessment.getBannedMatches() != null) {
                matches.addAll(assessment.getBannedMatches());
            }
        }
        List<String> capped = matches.size() > MAX_RISK_MATCHES
                ? new ArrayList<>(matches).subList(0, MAX_RISK_MATCHES) : new ArrayList<>(matches);
        return new RiskSummary(max, capped);
    }

    private record RiskSummary(RiskLevel level, List<String> matches) {
    }

    private int nextVersion(Long workflowId) {
        return versionRepository.findMaxVersion(workflowId) + 1;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeVisibility(String visibility) {
        return "public".equalsIgnoreCase(visibility) ? "public" : "private";
    }

    private WorkflowDefinition parseDefinition(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: definition 不能为空");
        }
        try {
            return objectMapper.treeToValue(node, WorkflowDefinition.class);
        } catch (Exception e) {
            // 服务端 schema 权威（ignoreUnknown=false）：未知字段/类型不符一律 400，不进 500
            throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: definition 不符合 schema: "
                    + e.getClass().getSimpleName());
        }
    }

    private WorkflowDefinition parseDefinitionNode(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new BusinessException(500, "WORKFLOW_INTERNAL: 版本定义解析失败");
        }
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(500, "WORKFLOW_INTERNAL: 版本定义解析失败");
        }
    }

    /** 规范化输出：保证同一定义的 sha256 稳定（字段顺序由 Jackson 按声明序） */
    private String writeDefinition(WorkflowDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (Exception e) {
            throw new BusinessException(500, "WORKFLOW_INTERNAL: 定义序列化失败");
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
