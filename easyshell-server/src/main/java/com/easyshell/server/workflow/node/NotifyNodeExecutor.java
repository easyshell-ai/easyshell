package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.channel.ChannelMessageRouter;
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
        for (Object channel : channels) {
            String channelKey = String.valueOf(channel);
            try {
                channelMessageRouter.pushMessage(channelKey, content);
                delivered.add(channelKey);
            } catch (Exception e) {
                log.warn("notify 推送失败: channel={} err={}", channelKey, e.getMessage());
                failed.add(channelKey);
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("delivered", delivered);
        output.put("failed", failed);
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
