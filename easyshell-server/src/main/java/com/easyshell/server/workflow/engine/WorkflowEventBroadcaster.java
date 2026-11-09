package com.easyshell.server.workflow.engine;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.websocket.WorkflowRunWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 工作流事件出口的 WS 实现（§6.7.1 出口② / §6.4）。
 *
 * <p>引擎的事件出口是构造参数 {@code Optional<Consumer<WorkflowEvent>>}；本 bean 存在时
 * 引擎自动拿到它（拿不到就用 {@link WorkflowEventPublisher} 的纯日志兜底，行为不变）。
 * 因此<b>引擎一行未改</b>——WS 推送是挂在出口上的第四层，四层注入防线与门禁不受影响。</p>
 *
 * <p>两条纪律：① 日志出口原样复用 {@link WorkflowEventPublisher}（脱敏掩码 {@code ***}、
 * payload 不落日志），不写第二套；② WS 消息只放 type/runId/nodeKey/message/at——
 * 不转发 payload（节点输出可能含敏感数据，出站通道只放人读摘要）。</p>
 */
@Slf4j
@Component
@Primary
public class WorkflowEventBroadcaster implements Consumer<WorkflowEvent> {

    private final WorkflowEventPublisher logPublisher;
    private final WorkflowRunWebSocketHandler runWebSocketHandler;

    public WorkflowEventBroadcaster(WorkflowRunWebSocketHandler runWebSocketHandler,
                                    SensitiveDataFilter sensitiveDataFilter) {
        this.runWebSocketHandler = runWebSocketHandler;
        this.logPublisher = new WorkflowEventPublisher(sensitiveDataFilter);
    }

    @Override
    public void accept(WorkflowEvent event) {
        logPublisher.accept(event);
        try {
            runWebSocketHandler.broadcast(event);
        } catch (Exception e) {
            // WS 推送失败绝不能反噬执行内核
            log.warn("工作流事件 WS 推送失败: run={} type={} err={}", event.runId(), event.type(), e.getMessage());
        }
    }
}
