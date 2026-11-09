package com.easyshell.server.ai.channel;

/**
 * 主动推送的<b>如实</b>结果（真机 E2E 缺陷 P-3）。
 *
 * <p>{@link ChannelMessageRouter#pushMessage} 在渠道未注册 / 未启用 / 解析不出推送目标时
 * 只打 WARN 然后返回，<b>不抛异常</b>。旧签名是 {@code void}，调用方只能把"没抛异常"
 * 当成"已交付"——notify 节点因此把 {@code ai.channel.dingtalk.enabled=false} 的渠道
 * 报成 {@code {"delivered":["dingtalk"]}}，运维收到的是假信号。</p>
 *
 * <p>现在把结果交回去，由调用方决定怎么记；{@code DELIVERED} 是唯一能进
 * {@code delivered} 列表的状态，其余一律按未送达分流。</p>
 */
public enum PushOutcome {

    /** 至少一个推送目标确认接收（部分目标失败仍算 DELIVERED，逐目标明细在服务端日志） */
    DELIVERED,

    /** 渠道标识不在已注册的 {@link BotChannelService} 里（拼错 / M1 未实现） */
    CHANNEL_UNKNOWN,

    /** 渠道已注册但未启用或未启动（{@code ai.channel.*.enabled=false}） */
    CHANNEL_NOT_RUNNING,

    /** 渠道在运行，但配置里解析不出任何推送目标（如 telegram 未配 allowed-chat-ids） */
    NO_TARGETS,

    /** 推送已尝试，但所有目标都返回 false 或抛错 */
    PUSH_FAILED;

    /** output_json 里对外的稳定原因码（小写蛇形） */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
