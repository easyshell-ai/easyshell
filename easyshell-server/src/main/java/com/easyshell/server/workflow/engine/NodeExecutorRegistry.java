package com.easyshell.server.workflow.engine;

import com.easyshell.server.workflow.model.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行器注册表（§3.3 NodeExecutorRegistry）：按类型分派，一个类型多个实现时
 * 后者覆盖前者并告警（防止有人复制粘贴出第二套 script-exec 实现）。
 */
@Slf4j
@Component
public class NodeExecutorRegistry {

    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);

    public NodeExecutorRegistry(List<NodeExecutor> executorList) {
        for (NodeExecutor executor : executorList) {
            NodeType type = executor.supports();
            NodeExecutor previous = executors.put(type, executor);
            if (previous != null) {
                log.warn("节点类型 {} 有多个执行器：{} 覆盖 {}", type, executor.getClass().getSimpleName(),
                        previous.getClass().getSimpleName());
            }
        }
        log.info("工作流节点执行器注册完成: {}", executors.keySet());
    }

    public NodeExecutor executorFor(NodeType type) {
        return type == null ? null : executors.get(type);
    }

    public boolean supports(NodeType type) {
        return type != null && executors.containsKey(type);
    }
}
