package com.easyshell.server.workflow;

import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作流数据层仓储行为（真实 H2，MODE=MySQL）。
 *
 * <p>覆盖设计 §3 执行内核要用的关键查询与并发安全写法：
 * uk_node_run 抢占冲突、findMaxAttempt/nextAttempt 分配、markFailedIfPresent 留痕、
 * run 终态条件 UPDATE、waiting 挂起/唤醒的条件 UPDATE、取消置位、checkpoint、
 * uk_wfr_idem 幂等重查、审批的乐观锁决策与悬死清理。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=warn"})
@DisplayName("工作流仓储 — 关键查询与条件 UPDATE（真实 H2）")
class WorkflowRepositoryTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowNodeRunRepository workflowNodeRunRepository;

    @Autowired
    private WorkflowApprovalRepository workflowApprovalRepository;

    @Autowired
    private EntityManager entityManager;

    private Long workflowId;
    private Long versionId;
    private String runId;

    @BeforeEach
    void setUp() {
        workflowApprovalRepository.deleteAll();
        workflowNodeRunRepository.deleteAll();
        workflowRunRepository.deleteAll();
        workflowVersionRepository.deleteAll();
        workflowRepository.deleteAll();

        Workflow workflow = new Workflow();
        workflow.setName("data-layer");
        workflow.setCreatedBy(42L);
        workflowId = workflowRepository.saveAndFlush(workflow).getId();

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowId(workflowId);
        version.setVersion(1);
        version.setDefinitionJson("{\"nodes\":[],\"edges\":[]}");
        version.setDefinitionSha256("b".repeat(64));
        versionId = workflowVersionRepository.saveAndFlush(version).getId();

        WorkflowRun run = new WorkflowRun();
        run.setId("wfr-repo-1");
        run.setWorkflowId(workflowId);
        run.setVersionId(versionId);
        run.setVersionNo(1);
        run.setStartedBy(42L);
        run.setStartedAt(LocalDateTime.now());
        run.setOwnerInstance("instance-a");
        runId = workflowRunRepository.saveAndFlush(run).getId();
    }

    private WorkflowNodeRun nodeRun(String nodeKey, int attempt, String status) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr-" + nodeKey + "-" + attempt);
        nodeRun.setRunId(runId);
        nodeRun.setNodeKey(nodeKey);
        nodeRun.setNodeType("script-exec");
        nodeRun.setAttempt(attempt);
        nodeRun.setStatus(status);
        return nodeRun;
    }

    @Test
    @DisplayName("workflow：归属过滤分页 + 三个原子计数器")
    void workflow_ownershipPagingAndCounters() {
        Page<Workflow> ownerPage = workflowRepository
                .findByVisibilityOrCreatedByOrderByCreatedAtDesc("public", 42L, PageRequest.of(0, 10));
        assertThat(ownerPage.getContent()).extracting(Workflow::getId).containsExactly(workflowId);

        Page<Workflow> strangerPage = workflowRepository
                .findByVisibilityOrCreatedByOrderByCreatedAtDesc("public", 7L, PageRequest.of(0, 10));
        assertThat(strangerPage.getContent()).isEmpty();

        assertThat(workflowRepository.incrementRunCountAtomically(workflowId, LocalDateTime.now())).isEqualTo(1);
        assertThat(workflowRepository.incrementRunCountAtomically(workflowId, LocalDateTime.now())).isEqualTo(1);
        assertThat(workflowRepository.incrementSuccessCountAtomically(workflowId)).isEqualTo(1);
        assertThat(workflowRepository.updateCurrentVersionAtomically(workflowId, versionId)).isEqualTo(1);
        // 不存在的工作流：三个计数器都返回 0 行（调用方放弃后续处理）
        assertThat(workflowRepository.incrementRunCountAtomically(9999L, LocalDateTime.now())).isZero();
        assertThat(workflowRepository.incrementSuccessCountAtomically(9999L)).isZero();

        entityManager.clear();
        Workflow reloaded = workflowRepository.findById(workflowId).orElseThrow();
        assertThat(reloaded.getRunCount()).isEqualTo(2);
        assertThat(reloaded.getSuccessCount()).isEqualTo(1);
        assertThat(reloaded.getCurrentVersionId()).isEqualTo(versionId);
        assertThat(reloaded.getLastRunAt()).isNotNull();
    }

    @Test
    @DisplayName("workflow_version：最大版本号 / 草稿冲突预检 / 定义只读投影")
    void workflowVersion_maxVersionConflictCheckAndDefinitionProjection() {
        assertThat(workflowVersionRepository.findMaxVersion(workflowId)).isEqualTo(1);
        assertThat(workflowVersionRepository.existsByWorkflowIdAndVersion(workflowId, 1)).isTrue();
        assertThat(workflowVersionRepository.existsByWorkflowIdAndVersion(workflowId, 2)).isFalse();
        assertThat(workflowVersionRepository.findDefinition(versionId)).contains("{\"nodes\":[],\"edges\":[]}");
        assertThat(workflowVersionRepository.findByIdAndWorkflowId(versionId, workflowId)).isPresent();
        assertThat(workflowVersionRepository.findByIdAndWorkflowId(versionId, 7L)).isEmpty();
        assertThat(workflowVersionRepository.findByWorkflowIdOrderByVersionDesc(workflowId)).hasSize(1);
    }

    @Test
    @DisplayName("uk_node_run：同一 (runId, nodeKey, attempt) 只能有一行（抢占竞态兜底）")
    void nodeRun_uniqueKeyBlocksDuplicateAttempt() {
        workflowNodeRunRepository.saveAndFlush(nodeRun("cleanup", 1, "running"));

        // 同一 attempt 号被占用，但不同 attempt 允许并存（重试链）
        workflowNodeRunRepository.saveAndFlush(nodeRun("cleanup", 2, "pending"));
        assertThat(workflowNodeRunRepository.findByRunId(runId)).hasSize(2);

        // 不同主键、相同 (runId, nodeKey, attempt)：uk_node_run 必须挡住（MF-10 的兜底）。
        // 约束冲突后事务已不可再用，故本断言放在最后
        WorkflowNodeRun duplicate = nodeRun("cleanup", 1, "pending");
        duplicate.setId("wnr-cleanup-1-dup");
        assertThatThrownBy(() -> workflowNodeRunRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("nextAttempt 分配：findMaxAttempt 从空到 1，随 attempt 行增长")
    void nodeRun_maxAttemptDrivesNextAttemptAllocation() {
        assertThat(workflowNodeRunRepository.findMaxAttempt(runId, "cleanup")).isEmpty();
        workflowNodeRunRepository.saveAndFlush(nodeRun("cleanup", 1, "succeeded"));
        assertThat(workflowNodeRunRepository.findMaxAttempt(runId, "cleanup")).contains(1);
        workflowNodeRunRepository.saveAndFlush(nodeRun("cleanup", 2, "running"));
        assertThat(workflowNodeRunRepository.findMaxAttempt(runId, "cleanup")).contains(2);
        // 别的 nodeKey 的 attempt 不影响本节点的分配
        assertThat(workflowNodeRunRepository.findMaxAttempt(runId, "check")).isEmpty();
    }

    @Test
    @DisplayName("markFailedIfPresent：既有行置 failed 留痕，行不存在返回 0 行")
    void nodeRun_markFailedIfPresentLeavesTrace() {
        workflowNodeRunRepository.saveAndFlush(nodeRun("cleanup", 1, "running"));
        LocalDateTime now = LocalDateTime.now();

        int updated = workflowNodeRunRepository.markFailedIfPresent(
                runId, "cleanup", 1, "attempt_conflict", now);
        assertThat(updated).isEqualTo(1);

        int missing = workflowNodeRunRepository.markFailedIfPresent(
                runId, "cleanup", 99, "attempt_conflict", now);
        assertThat(missing).isZero();

        entityManager.clear();
        WorkflowNodeRun reloaded = workflowNodeRunRepository
                .findByRunIdAndNodeKeyAndAttempt(runId, "cleanup", 1).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("failed");
        assertThat(reloaded.getErrorMessage()).isEqualTo("attempt_conflict");
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("node_run 条件 UPDATE：waiting 唤醒与未终态节点批量取消")
    void nodeRun_conditionalStatusTransitions() {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(runId);
        approval.setNodeKey("gate");
        approval.setNodeType("script-exec");
        approval.setTitle("gate");
        approval.setStatus("approved");
        approval = workflowApprovalRepository.saveAndFlush(approval);
        WorkflowNodeRun gate = nodeRun("gate", 1, "waiting");
        gate.setApprovalId(approval.getId());
        workflowNodeRunRepository.saveAndFlush(gate);
        workflowNodeRunRepository.saveAndFlush(nodeRun("check", 1, "pending"));
        workflowNodeRunRepository.saveAndFlush(nodeRun("done", 1, "succeeded"));

        assertThat(workflowNodeRunRepository.resumeWaitingAttempt(gate.getId(), runId, approval.getId())).isEqualTo(1);
        assertThat(workflowNodeRunRepository.cancelUnfinishedNodes(runId, "user_cancelled")).isEqualTo(1);
        // 幂等：第二次取消已没有 pending/waiting 行
        assertThat(workflowNodeRunRepository.cancelUnfinishedNodes(runId, "user_cancelled")).isZero();

        entityManager.clear();
        assertThat(workflowNodeRunRepository.findByRunIdAndStatus(runId, "running")).hasSize(1);
        assertThat(workflowNodeRunRepository.findByRunIdAndStatus(runId, "cancelled")).hasSize(1);
        assertThat(workflowNodeRunRepository.findByRunIdAndStatus(runId, "succeeded")).hasSize(1);
    }

    @Test
    @DisplayName("run 终态条件 UPDATE：只有非终态能写终态，重复写返回 0 行")
    void run_finishAtomicallyOnlyFromNonTerminalStatus() {
        LocalDateTime now = LocalDateTime.now();

        int first = workflowRunRepository.finishRunAtomically(
                runId, "succeeded", now, 1200L, "{\"ok\":true}", null,
                List.of("succeeded", "failed", "cancelled", "timeout"));
        assertThat(first).isEqualTo(1);

        int second = workflowRunRepository.finishRunAtomically(
                runId, "failed", now.plusSeconds(1), 1300L, null, "late failure",
                List.of("succeeded", "failed", "cancelled", "timeout"));
        assertThat(second).isZero();

        entityManager.clear();
        WorkflowRun reloaded = workflowRunRepository.findById(runId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("succeeded");
        assertThat(reloaded.getDurationMs()).isEqualTo(1200L);
        assertThat(reloaded.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("run 挂起/唤醒条件 UPDATE：状态不匹配时影响 0 行（幂等）")
    void run_suspendAndResumeAreConditional() {
        assertThat(workflowRunRepository.suspendIfRunning(runId)).isZero();

        // 先把 run 拉到 running（终态集合用一个不存在的状态占位，等价于"任何状态都可写"）
        assertThat(workflowRunRepository.finishRunAtomically(
                runId, "running", LocalDateTime.now(), null, null, null, List.of("__none__"))).isEqualTo(1);
        assertThat(workflowRunRepository.resumeIfWaiting(runId)).isZero();

        assertThat(workflowRunRepository.suspendIfRunning(runId)).isEqualTo(1);
        assertThat(workflowRunRepository.suspendIfRunning(runId)).isZero();
        assertThat(workflowRunRepository.resumeIfWaiting(runId)).isEqualTo(1);
        assertThat(workflowRunRepository.resumeIfWaiting(runId)).isZero();

        entityManager.clear();
        assertThat(workflowRunRepository.findById(runId).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    @DisplayName("run 取消置位与 checkpoint：重复置位返回 0 行")
    void run_requestCancelAndCheckpoint() {
        assertThat(workflowRunRepository.requestCancelAtomically(runId)).isEqualTo(1);
        assertThat(workflowRunRepository.requestCancelAtomically(runId)).isZero();

        LocalDateTime heartbeat = LocalDateTime.now();
        assertThat(workflowRunRepository.checkpointAtomically(
                runId, heartbeat, "[\"cleanup\"]", "{\"threshold\":90}", false)).isEqualTo(1);

        entityManager.clear();
        WorkflowRun reloaded = workflowRunRepository.findById(runId).orElseThrow();
        assertThat(reloaded.getCancelRequested()).isTrue();
        assertThat(reloaded.getHeartbeatAt()).isNotNull();
        assertThat(reloaded.getCurrentNodes()).isEqualTo("[\"cleanup\"]");
        assertThat(reloaded.getVariablesJson()).isEqualTo("{\"threshold\":90}");
        assertThat(reloaded.getVariablesTruncated()).isFalse();
    }

    @Test
    @DisplayName("uk_wfr_idem：幂等重查命中；同用户同 key 重复触发被唯一键拦截")
    void run_idempotencyUniqueKeyAndLookup() {
        Optional<WorkflowRun> found =
                workflowRunRepository.findByStartedByAndIdempotencyKey(42L, null);
        assertThat(found).isPresent();

        WorkflowRun first = workflowRunRepository.findById(runId).orElseThrow();
        first.setIdempotencyKey("idem-key-1");
        workflowRunRepository.saveAndFlush(first);
        assertThat(workflowRunRepository.findByStartedByAndIdempotencyKey(42L, "idem-key-1"))
                .isPresent();
        assertThat(workflowRunRepository.findByStartedByAndIdempotencyKey(7L, "idem-key-1"))
                .isEmpty();

        WorkflowRun conflicting = new WorkflowRun();
        conflicting.setId("wfr-repo-2");
        conflicting.setWorkflowId(workflowId);
        conflicting.setVersionId(versionId);
        conflicting.setVersionNo(1);
        conflicting.setStartedBy(42L);
        conflicting.setIdempotencyKey("idem-key-1");
        assertThatThrownBy(() -> workflowRunRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("看门狗与多实例自检查询：按 started_at / heartbeat_at 过滤")
    void run_watchdogAndMultiInstanceQueries() {
        LocalDateTime now = LocalDateTime.now();
        workflowRunRepository.checkpointAtomically(runId, now, null, null, false);

        assertThat(workflowRunRepository.findByStatusInAndStartedAtBefore(List.of("pending"), now.plusSeconds(1)))
                .extracting(WorkflowRun::getId)
                .containsExactly(runId);
        assertThat(workflowRunRepository.findByStatusInAndStartedAtBefore(List.of("pending"), now.minusSeconds(1)))
                .isEmpty();

        assertThat(workflowRunRepository.findByOwnerInstanceNotAndHeartbeatAtAfterAndStatusIn(
                "self", now.minusSeconds(120), List.of("pending", "running", "waiting"))).hasSize(1);
        assertThat(workflowRunRepository.findByOwnerInstanceNotAndHeartbeatAtAfterAndStatusIn(
                "self", now.plusSeconds(1), List.of("pending", "running", "waiting"))).isEmpty();
    }

    @Test
    @DisplayName("approval：待审批过滤、乐观锁决策、悬死清理")
    void approval_pendingQueryOptimisticDecideAndCleanup() {
        LocalDateTime now = LocalDateTime.now();
        WorkflowApproval pending = new WorkflowApproval();
        pending.setRunId(runId);
        pending.setNodeKey("gate");
        pending.setNodeType("approval");
        pending.setTitle("审批标题");
        pending.setDetailJson("{\"scriptPreview\":\"...\"}");
        pending.setExpiresAt(now.plusHours(2));
        Long approvalId = workflowApprovalRepository.saveAndFlush(pending).getId();

        WorkflowApproval expired = new WorkflowApproval();
        expired.setRunId(runId);
        expired.setNodeKey("gate2");
        expired.setNodeType("approval");
        expired.setTitle("已过期");
        expired.setExpiresAt(now.minusHours(1));
        workflowApprovalRepository.saveAndFlush(expired);

        // 待我审批只返回 pending 且未过期
        assertThat(workflowApprovalRepository.findByStatusAndExpiresAtAfter("pending", now))
                .extracting(WorkflowApproval::getId)
                .containsExactly(approvalId);
        assertThat(workflowApprovalRepository.findByStatusAndExpiresAtBefore("pending", now)).hasSize(1);
        assertThat(workflowApprovalRepository.findByRunId(runId)).hasSize(2);
        assertThat(workflowApprovalRepository.findByRunIdAndNodeKey(runId, "gate")).isPresent();

        // 乐观锁决策：版本正确才写得进；重复决策影响 0 行（409 APPROVAL_DECIDED）
        assertThat(workflowApprovalRepository.decideIfPending(
                approvalId, "approved", 9L, now, "同意", 0)).isEqualTo(1);
        assertThat(workflowApprovalRepository.decideIfPending(
                approvalId, "rejected", 9L, now, "改主意", 0)).isZero();

        entityManager.clear();
        WorkflowApproval decided = workflowApprovalRepository.findById(approvalId).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo("approved");
        assertThat(decided.getDecidedBy()).isEqualTo(9L);
        assertThat(decided.getDecisionComment()).isEqualTo("同意");
        assertThat(decided.getVersion()).isEqualTo(1);
        // detail_json 是不可变快照：决策语句不碰它
        assertThat(decided.getDetailJson()).isEqualTo("{\"scriptPreview\":\"...\"}");

        // 悬死审批清理：run 终态时把仍 pending 的批量置 cancelled
        assertThat(workflowApprovalRepository.cancelPendingByRunId(runId)).isEqualTo(1);
        entityManager.clear();
        assertThat(workflowApprovalRepository.findByStatusAndExpiresAtAfter("pending", now)).isEmpty();
    }
}
