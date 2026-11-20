package com.easyshell.server.workflow.engine;

import com.easyshell.server.workflow.expression.VariableResolver;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 节点执行上下文（§3.3）。阻塞式执行体拿到的全部世界。
 *
 * @param run            当前 run（只读元信息；状态迁移只走引擎）
 * @param nodeRun        本次 attempt 的 node_run 行（执行体回填 task_id/approval_id 等）
 * @param node           节点定义
 * @param definition     不可变版本定义
 * @param variables      变量存储（已按拓扑祖先裁剪的快照，§2.2 ③）
 * @param pool           引擎线程池（禁止在里面 join 自己）
 * @param cancelled      run 级取消标志（用户取消时翻转）
 * @param nodeCancelled  本次 attempt 的取消标志（节点超时由引擎翻转）
 * @param dryRun         M2 预演标志，M1 恒 false
 * @param eventSink      事件出口（WS 上线前只做日志 + 脱敏）
 * @param resolver       变量解析入口（= variables 本身，供表达式求值）
 */
public record NodeContext(WorkflowRun run, WorkflowNodeRun nodeRun, WorkflowNode node,
                          WorkflowDefinition definition, VariableStore variables, ExecutorService pool,
                          AtomicBoolean cancelled, AtomicBoolean nodeCancelled, boolean dryRun,
                          Consumer<WorkflowEvent> eventSink, VariableResolver resolver) {

    /** 执行体在长操作中每轮检查一次（取消传播的唯一检查点，§3.10） */
    public boolean isCancelled() {
        return cancelled.get() || nodeCancelled.get();
    }

    public void emit(String type, String message) {
        if (eventSink != null) {
            eventSink.accept(new WorkflowEvent(type, run.getId(), node.getKey(), message, null, java.time.Instant.now()));
        }
    }
}
