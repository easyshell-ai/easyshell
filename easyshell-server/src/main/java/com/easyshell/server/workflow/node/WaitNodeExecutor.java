package com.easyshell.server.workflow.node;

import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * wait 节点执行器（{@code config.durationSec} 到点继续）。
 *
 * <p>设计文档把 wait 列在 M2（§1.2 N5）；本实现按任务要求纳入 M1，形态是
 * <b>带取消检查点的 sleep-loop</b>（每 500ms 检查一次 {@code ctx.isCancelled()}），
 * 与 §3.13 的 task 轮询同一模式——不是无脑 {@code Thread.sleep}，取消能立刻传播。
 * 上限 3600s（发布期校验），避免一个节点占死引擎线程。</p>
 */
@Slf4j
@Component
public class WaitNodeExecutor implements NodeExecutor {

    private static final long CHECK_INTERVAL_MS = 500L;

    @Override
    public NodeType supports() {
        return NodeType.WAIT;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        int duration = node.configInt("durationSec", 0);
        if (duration < 1 || duration > 3600) {
            issues.error("NODE_CONFIG_INVALID",
                    "wait 节点 durationSec 必须在 1..3600 之间，实际 " + duration, node.getKey());
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        int durationSec = node.configInt("durationSec", 0);
        if (durationSec < 1 || durationSec > 3600) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "wait 节点 durationSec 必须在 1..3600 之间，实际 " + durationSec);
        }
        long deadline = System.currentTimeMillis() + durationSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (ctx.isCancelled()) {
                throw new NodeCancelledException("运行已取消，停止等待");
            }
            try {
                Thread.sleep(Math.min(CHECK_INTERVAL_MS, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeCancelledException("等待被中断");
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("waitedMs", durationSec * 1000L);
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }
}
