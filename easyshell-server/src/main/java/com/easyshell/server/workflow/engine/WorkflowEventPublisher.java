package com.easyshell.server.workflow.engine;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * 工作流事件出口（§6.7.1 出口②：{@code /ws/workflow/run} 事件推送前的统一过滤点）。
 *
 * <p>M1 只有日志出口——WS 端点（§6.4）上线时在这里挂推送即可，四层注入防线与
 * 门禁都不受影响。纪律：message 过 {@link SensitiveDataFilter}（掩码 {@code ***}），
 * <b>payload 不落日志</b>（节点输出可能含敏感数据，出站通道只放人读摘要）。</p>
 */
@Slf4j
public class WorkflowEventPublisher implements Consumer<WorkflowEvent> {

    private final SensitiveDataFilter sensitiveDataFilter;

    public WorkflowEventPublisher() {
        this(new SensitiveDataFilter());
    }

    public WorkflowEventPublisher(SensitiveDataFilter sensitiveDataFilter) {
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    @Override
    public void accept(WorkflowEvent event) {
        log.info("[workflow] run={} node={} type={} msg={}", event.runId(), event.nodeKey(), event.type(),
                event.message() == null ? "-" : sensitiveDataFilter.filter(event.message()));
    }
}
