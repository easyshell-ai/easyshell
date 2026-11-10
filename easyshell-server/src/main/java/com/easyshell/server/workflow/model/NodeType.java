package com.easyshell.server.workflow.model;

import java.util.Arrays;

/**
 * 工作流节点类型（工作流 2.0 九类：start / script-exec / transform / condition /
 * ai-analyze / approval / notify / wait / end）。
 *
 * <p>设计 §3.3 包结构把节点类型列为枚举，definition_json 里存的是 {@link #code()} 字符串；
 * {@code human-confirm} 不是独立类型，而是 approval 节点的 {@code config.mode} 变体
 * （复用同一张审批行与同一套挂起/恢复状态机，不新建表、不新增状态）。</p>
 *
 * <p>旧 5 类节点（script-exec / condition / notify / approval / wait）完全兼容，不升
 * {@code schemaVersion}；start / end 是真实图节点，旧工作流可以没有，校验只给 WARNING。</p>
 */
public enum NodeType {

    /** 开始：把 {@code definition.variables} 声明的入参从 {@code run.input.*} 读成节点输出 */
    START("start"),

    /** 脚本执行：落到真实 Task，复用 createAndDispatch 的审计/日志/看门狗 */
    SCRIPT_EXEC("script-exec"),

    /** 数据映射：按 {@code config.mappings} 产出 {@code {name: value}} */
    TRANSFORM("transform"),

    /** 条件分支：expression 求值结果写入 output.result；边按 sourceHandle=true/false 门控（§2.11） */
    CONDITION("condition"),

    /** AI 分析：只读上游输出做运维语义分析，不执行任何命令（无 Task/dispatch 依赖） */
    AI_ANALYZE("ai-analyze"),

    /** 通知：复用 ChannelMessageRouter.pushMessage */
    NOTIFY("notify"),

    /** 人工审批：挂起 run，DB 行唯一事实源 + 同 JVM 事件 + 10s 扫描兜底 */
    APPROVAL("approval"),

    /** 等待：durationSec 到点继续（带取消检查点的 sleep-loop，不占线程空转） */
    WAIT("wait"),

    /** 结束：按 {@code config.outputs} 产出工作流最终输出 */
    END("end");

    private final String code;

    NodeType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** definition_json 里的字符串 → 枚举；未知类型返回 null（调用方按节点类型未知处理） */
    public static NodeType fromCode(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst().orElse(null);
    }
}
