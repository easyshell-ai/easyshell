package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.channel.ChannelMessageRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.List;

@Slf4j
@Component
public class NotificationTool {

    private final ChannelMessageRouter channelMessageRouter;

    public NotificationTool(@Lazy ChannelMessageRouter channelMessageRouter) {
        this.channelMessageRouter = channelMessageRouter;
    }

    @Tool(description = "发送通知消息到配置的 Bot 渠道（Telegram、Discord、Slack、钉钉、飞书、企业微信）。")
    public String sendNotification(
            @ToolParam(description = "通知消息内容") String message,
            @ToolParam(description = "渠道名称：telegram/discord/dingtalk/slack/feishu/wecom。为空则发送到所有已启用的渠道") String channelName,
            @ToolParam(description = "消息类型：info/warning/error/success，影响消息前缀样式，默认 info") String messageType) {
        try {
            if (message == null || message.isBlank()) {
                return "通知消息内容不能为空";
            }

            // Format message with type prefix
            String effectiveType = (messageType != null && !messageType.isBlank()) ? messageType.toLowerCase() : "info";
            String prefix = switch (effectiveType) {
                case "warning" -> "⚠️ 警告：";
                case "error" -> "❌ 错误：";
                case "success" -> "✅ 成功：";
                default -> "ℹ️ 通知：";
            };
            String formattedMessage = prefix + message;

            if (channelName != null && !channelName.isBlank()) {
                // Send to specific channel
                String channel = channelName.trim().toLowerCase();
                channelMessageRouter.pushMessage(channel, formattedMessage);
                return String.format("已发送通知到 %s 渠道", channel);
            } else {
                // Send to all enabled channels
                List<String> channels = List.of("telegram", "discord", "dingtalk", "slack", "feishu", "wecom");
                channelMessageRouter.pushToChannelsAsync(channels, formattedMessage);
                return "已向所有已启用的渠道发送通知";
            }
        } catch (Exception e) {
            log.warn("Send notification failed: {}", e.getMessage());
            return "发送通知失败：" + e.getMessage();
        }
    }

    @Tool(description = "列出所有可用的通知渠道及其状态。")
    public String listChannels() {
        try {
            // Check known channels
            List<String> knownChannels = List.of("telegram", "discord", "dingtalk", "slack", "feishu", "wecom");
            StringBuilder sb = new StringBuilder("可用通知渠道：\n\n");

            for (String channel : knownChannels) {
                String enabled = channelMessageRouter.getConfigValue("ai.channel." + channel + ".enabled");
                boolean isEnabled = "true".equals(enabled);
                sb.append("- **").append(channel).append("**: ")
                        .append(isEnabled ? "✅ 已启用" : "❌ 未启用")
                        .append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "查询渠道列表失败：" + e.getMessage();
        }
    }
}
