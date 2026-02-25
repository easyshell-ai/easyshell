package com.easyshell.server.ai.channel;

/**
 * 机器人渠道服务接口。
 * 每个渠道（Telegram、Discord、钉钉等）实现此接口。
 */
public interface BotChannelService {

    /**
     * 渠道标识，如 "telegram"、"discord"、"dingtalk"
     */
    String getChannelKey();

    /**
     * 启动机器人（连接 / 注册 webhook 等）
     */
    void start();

    /**
     * 停止机器人
     */
    void stop();

    /**
     * 当前是否运行中
     */
    boolean isRunning();

    /**
     * 主动推送消息到指定目标（如 chatId / channelId / webhook）。
     * 用于定时任务通知等非交互式场景。
     * @param targetId 目标标识（Telegram chatId, Discord channelId, DingTalk 固定为 "webhook"）
     * @param message 消息内容
     * @return 是否发送成功
     */
    default boolean pushMessage(String targetId, String message) {
        return false;
    }
}
