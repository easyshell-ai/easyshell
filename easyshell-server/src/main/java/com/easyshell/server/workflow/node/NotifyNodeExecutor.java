package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.ai.channel.PushOutcome;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * notify 节点执行器（复用 {@link ChannelMessageRouter#pushMessage}，§3.1 类级迁移表）。
 *
 * <p>出站纪律（§6.7.3 出口 17）：消息内容离开系统边界前必须过
 * {@link SensitiveDataFilter}（掩码 {@code ***}）——比落库更严重，故这里是硬闸。</p>
 *
 * <p><b>如实上报（P-3）</b>：output 除 {@code delivered}/{@code failed} 外还有
 * {@code skipped:[{channel,reason}]}。渠道未注册/未启用/无推送目标时
 * {@link ChannelMessageRouter#pushMessage} 只告警不抛异常，旧代码据此把没发出去的
 * 消息记进 {@code delivered}；现在按 {@link PushOutcome} 分流，只有
 * {@code DELIVERED} 才进 {@code delivered}，节点本身仍然 succeeded
 * （通知不到不反转工作流结论，但运维看到的必须是真的）。</p>
 */
@Slf4j
@Component
public class NotifyNodeExecutor implements NodeExecutor {

    private final ChannelMessageRouter channelMessageRouter;
    private final ExpressionEvaluator expressionEvaluator;
    private final SensitiveDataFilter sensitiveDataFilter;

    public NotifyNodeExecutor(ChannelMessageRouter channelMessageRouter,
                              ExpressionEvaluator expressionEvaluator,
                              SensitiveDataFilter sensitiveDataFilter) {
        this.channelMessageRouter = channelMessageRouter;
        this.expressionEvaluator = expressionEvaluator;
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    @Override
    public NodeType supports() {
        return NodeType.NOTIFY;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        List<Object> channels = node.configList("channels");
        if (channels == null || channels.isEmpty()) {
            issues.error("NODE_CONFIG_INVALID", "notify 节点缺少 channels", node.getKey());
        } else if (channels.size() > 6) {
            issues.error("NODE_CONFIG_INVALID", "notify 节点 channels 最多 6 个", node.getKey());
        }
        if (node.configString("messageTemplate") == null || node.configString("messageTemplate").isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "notify 节点缺少 messageTemplate", node.getKey());
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        List<Object> channels = node.configList("channels");
        if (channels == null || channels.isEmpty()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "notify 节点缺少 channels");
        }
        String template = node.configString("messageTemplate");
        if (template == null || template.isBlank()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "notify 节点缺少 messageTemplate");
        }
        // 模板渲染：${path} 表达式 + {{NAME}} 槽位（值来自可选 parameters，表达式或字面量）
        String rendered = expressionEvaluator.renderTemplate(template,
                stringMap(node.getConfig() == null ? null : node.getConfig().get("parameters")),
                ctx.resolver(), ctx.variables()::addWarning);
        // 出站脱敏（出口 17）
        String content = sensitiveDataFilter.filter(rendered);
        String level = node.configString("level", "info");
        if (!"info".equals(level)) {
            content = "[" + level + "] " + content;
        }

        List<String> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        // skipped：渠道未注册/未启用/无推送目标——消息根本没离开系统，绝不能进 delivered（P-3）
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Object channel : channels) {
            String channelKey = String.valueOf(channel);
            PushOutcome outcome;
            try {
                outcome = channelMessageRouter.pushMessage(channelKey, content);
            } catch (Exception e) {
                // 抛错路径：确实尝试过但失败了
                log.warn("notify 推送失败: channel={} err={}", channelKey, e.getMessage());
                failed.add(channelKey);
                continue;
            }
            // 结果缺失（null）按未送达处理：fail-closed，绝不把"不知道"报成"已交付"
            switch (outcome == null ? PushOutcome.PUSH_FAILED : outcome) {
                case DELIVERED -> delivered.add(channelKey);
                case PUSH_FAILED -> {
                    log.warn("notify 推送失败: channel={}（全部目标均未接收）", channelKey);
                    failed.add(channelKey);
                }
                default -> {
                    log.warn("notify 渠道不可用，未发送: channel={} reason={}", channelKey, outcome.code());
                    skipped.add(Map.of("channel", channelKey, "reason", outcome.code()));
                }
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("delivered", delivered);
        output.put("failed", failed);
        output.put("skipped", skipped);
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item == null ? null : String.valueOf(item)));
        return result;
    }
}
