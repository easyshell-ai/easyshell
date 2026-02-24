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
}
