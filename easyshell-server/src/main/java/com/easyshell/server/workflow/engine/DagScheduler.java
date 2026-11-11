package com.easyshell.server.workflow.engine;

import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.model.NodeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 调度波计算（算法复制自 {@code ai/orchestrator/DagExecutor.findReadySteps}：
 * 遍历全部节点，入边全部"已出结果"的 pending 节点构成本波 ready 集合）。
 *
 * <p>与旧实现的差异：① 状态来自持久化的 {@code workflow_node_run}（attempt 链取最新一行），
 * 不是内存 map；② 增加"失败但还有重试额度"的再就绪判定（由调用方通过
 * {@code retryDue} 谓词控制退避到期）；③ 上游为 skipped 也算"已出结果"——
 * 下游按失败策略决定自己是 skipped 还是继续（§2.8）。</p>
 */
public final class DagScheduler {

    /** 已出结果的上游状态：本波不再等待它们 */
    private static final Set<String> SETTLED = Set.of(
            "succeeded", "failed", "skipped", "cancelled", "timeout");

    private DagScheduler() {
    }

    /** 每个 nodeKey 的最新 attempt 行 */
    public static Map<String, com.easyshell.server.model.entity.WorkflowNodeRun> latestByKey(
            List<com.easyshell.server.model.entity.WorkflowNodeRun> rows) {
        Map<String, com.easyshell.server.model.entity.WorkflowNodeRun> latest = new LinkedHashMap<>();
        for (com.easyshell.server.model.entity.WorkflowNodeRun row : rows) {
            com.easyshell.server.model.entity.WorkflowNodeRun current = latest.get(row.getNodeKey());
            if (current == null || row.getAttempt() >= current.getAttempt()) {
                latest.put(row.getNodeKey(), row);
            }
        }
        return latest;
    }

    /**
     * 本波可执行的节点（保持 definition 声明顺序，便于测试与审计）。
     *
     * @param retryDue 谓词：返回 true 表示该节点的退避已到期（可以重试）。
     *                 传 {@code n -> true} 时等价于"失败即可立刻重试"
     */
    public static List<WorkflowNode> findReady(WorkflowDefinition def,
                                        Map<String, com.easyshell.server.model.entity.WorkflowNodeRun> latest,
                                        Predicate<WorkflowNode> retryDue) {
        List<WorkflowNode> ready = new ArrayList<>();
        for (WorkflowNode node : def.getNodes()) {
            com.easyshell.server.model.entity.WorkflowNodeRun row = latest.get(node.getKey());
            if (row == null) {
                // 从未开始：只要入边全部已出结果就可就绪
                if (allPredecessorsSettled(def, node, latest)) {
                    ready.add(node);
                }
                continue;
            }
            String status = row.getStatus();
            if ("pending".equals(status) || "running".equals(status) || "waiting".equals(status)) {
                continue;   // 已在进行中（waiting 由 resume 唤醒，不进 ready）
            }
            if ("failed".equals(status) || "timeout".equals(status)) {
                // 重试额度未尽且退避已到期 → 再就绪
                if (hasRetryRemaining(node, row) && retryDue.test(node)) {
                    ready.add(node);
                }
                continue;
            }
            // succeeded / skipped / cancelled：终态，不再就绪
        }
        return ready;
    }

    private static boolean allPredecessorsSettled(WorkflowDefinition def, WorkflowNode node,
                                           Map<String, com.easyshell.server.model.entity.WorkflowNodeRun> latest) {
        for (var edge : def.inEdges(node.getKey())) {
            com.easyshell.server.model.entity.WorkflowNodeRun upstream = latest.get(edge.getFrom());
            if (upstream == null) {
                return false;   // 上游还没开始
            }
            if (!SETTLED.contains(upstream.getStatus())) {
                return false;   // 上游还在 pending/running/waiting
            }
        }
        return true;
    }

    /** 是否还有"失败但重试额度未尽"的节点（决定 drive() 是该退出还是等退避） */
    public static boolean hasPendingRetry(WorkflowDefinition def,
                                          Map<String, com.easyshell.server.model.entity.WorkflowNodeRun> latest) {
        for (WorkflowNode node : def.getNodes()) {
            com.easyshell.server.model.entity.WorkflowNodeRun row = latest.get(node.getKey());
            if (row == null) {
                continue;
            }
            String status = row.getStatus();
            if ("failed".equals(status) || "timeout".equals(status)) {
                if (hasRetryRemaining(node, row)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A timed-out external call may already be accepted; no blind automatic replay. */
    public static boolean automaticRetryAllowed(WorkflowNode node) {
        return node.nodeType() != NodeType.SCRIPT_EXEC && node.nodeType() != NodeType.NOTIFY
                && node.nodeType() != NodeType.CODE;
    }

    /** The persisted error message starts with the error code followed by a colon. */
    public static boolean hasRetryRemaining(WorkflowNode node,
            com.easyshell.server.model.entity.WorkflowNodeRun row) {
        if (!("failed".equals(row.getStatus()) || "timeout".equals(row.getStatus()))) {
            return false;
        }
        String error = row.getErrorMessage();
        int attempt = row.getAttempt() == null ? 1 : row.getAttempt();
        return automaticRetryAllowed(node)
                && (error == null || !error.startsWith("WAITING_UNSUPPORTED:"))
                && attempt < node.retryMaxOrDefault(0) + 1;
    }
}
