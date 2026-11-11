package com.easyshell.server.workflow.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流引擎配置（设计 §11.3，前缀 {@code easyshell.workflow}）。
 *
 * <p>默认值与设计文档逐项对齐；M1 不支持的项（多实例、artifact、secret）不出现在这里。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "easyshell.workflow")
public class WorkflowProperties {

    /** 总开关：false 时路由与 bean 不注册（回退点，§5.6） */
    private boolean enabled = true;

    private Engine engine = new Engine();

    private Approval approval = new Approval();

    private Value value = new Value();

    @Data
    public static class Engine {
        /** 全局并发 run 数（§3.9：默认 3，超出的 run 置 queued） */
        private int maxConcurrentRuns = 3;

        /** 单 run 并发节点数（与 ai.dag.max-concurrent-steps 默认一致） */
        private int maxConcurrentNodes = 5;

        /** 单 agent 并发 job 数（必须 < agent 侧 8，§3.9 SF-4） */
        private int maxJobsPerAgent = 4;

        /** workflow-engine 池 */
        private int poolCore = 5;
        private int poolMax = 10;
        private int poolQueue = 50;

        /** task 轮询间隔（±20% jitter，§3.13） */
        private int pollIntervalMs = 1000;

        /** 独立 workflow-scheduler 池（SF-5：不许与 ai-scheduler- 混用） */
        private int schedulerPoolSize = 6;

        /** run 级超时兜底（秒，§3.10 ③） */
        private int defaultRunTimeoutSec = 3600;

        /** 节点级超时默认（秒，§3.10 ①） */
        private int defaultNodeTimeoutSec = 300;

        /** 僵尸判定：心跳超时（秒） */
        private int heartbeatTimeoutSec = 120;

        /** 自动接管未知执行状态尚无持久 fencing；默认关闭。 */
        private boolean automaticRecoveryEnabled = false;

        /** Persistent approval-resume delivery; does not authorize replay of unknown tasks. */
        private boolean durableResumeEnabled = false;

        /** 超龄 run 放弃（小时） */
        private int maxRecoveryAgeHours = 6;

        /** 重试默认次数与基础间隔（§3.12） */
        private int retryMax = 0;
        private int retryIntervalSec = 5;

        /** WorkflowWatchdog 扫描周期（秒） */
        private int watchdogIntervalSec = 30;

        /** WorkflowPendingWakeupScanner 扫描周期（秒，§3.8 兜底） */
        private int wakeupIntervalSec = 10;

        /** 同工作流两次触发最小间隔（秒） */
        private int minTriggerIntervalSec = 30;
    }

    @Data
    public static class Approval {
        /** Enable only after the exact dispatch binding and runtime key are configured. */
        private boolean dispatchEnabled = false;
        /** 审批超时默认（秒，§3.8 边界②） */
        private int defaultTimeoutSec = 86400;

        /** 防永久挂起的上限（秒） */
        private int maxWaitSeconds = 86400;

        /** 自审批约束（S1）：decided_by == started_by 时记 WARNING 审计 */
        private boolean requireDifferentApprover = true;
    }

    @Data
    public static class Value {
        /** 单值硬上限（字符，§2.10） */
        private int maxLength = 262144;

        /** 标量内联阈值（UTF-8 字节，§2.10） */
        private int inlineThreshold = 8192;

        /** variables_json 全量快照上限（字节，§3.7） */
        private int snapshotLimit = 524288;

        /** 值策略字符上限（§2.4.4，与通道阈值是两个概念，N7-j） */
        private int policyMaxChars = 256;
    }
}
