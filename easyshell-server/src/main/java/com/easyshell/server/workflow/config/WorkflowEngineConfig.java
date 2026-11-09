package com.easyshell.server.workflow.config;

import com.easyshell.server.workflow.properties.WorkflowProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流引擎的线程池配置（§3.2.1 四类线程之二）。
 *
 * <p><b>SF-5</b>：{@code workflow-scheduler-} 必须是<b>独立</b>池——现有
 * {@code ai-scheduler-} 只有 4 线程且已被 5 个 {@code @Scheduled} 占用
 * （{@code SchedulerConfig.java:14}），混用会让看门狗/扫描器和 AI 定时任务互相饿死。
 * 本配置只新增 bean，不改 {@code SchedulerConfig} 一行。</p>
 *
 * <p>两个 bean 都用显式名字 + {@code @Qualifier} 注入：{@code @Scheduled} 的基础设施
 * 仍按名字 {@code taskScheduler} 找既有那颗 AI 调度器，工作流的三个定时任务因为
 * 配了独立 scheduler 而在 {@code workflow-scheduler-} 线程上跑。</p>
 */
@Configuration
public class WorkflowEngineConfig {

    /**
     * 表达式求值器（§2.5）：{@code ExpressionEvaluator} 本身不是 bean（纯函数工具类），
     * 而 {@code DagValidator} / 五个节点执行器 / {@code WorkflowEngine} 的构造函数都要它。
     * 在这里显式声明，否则整个 Spring 上下文起不来（内核落地时漏登记，全量上下文测试
     * 又是 @Disabled，所以 559 个单测全绿也盖不住这个启动即失败）。
     */
    @Bean
    public com.easyshell.server.workflow.expression.ExpressionEvaluator expressionEvaluator(
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new com.easyshell.server.workflow.expression.ExpressionEvaluator(objectMapper);
    }

    /**
     * {@code workflow-engine} 池：跑 drive() 主循环与 runNode()。
     * 守护线程工厂照搬 {@code DagExecutor.java:48-52} 的形状，前缀改为 workflow-engine-。
     */
    @Bean(name = "workflowEnginePool")
    public ExecutorService workflowEnginePool(WorkflowProperties properties) {
        WorkflowProperties.Engine engine = properties.getEngine();
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, engine.getPoolCore()));
        executor.setMaxPoolSize(Math.max(engine.getPoolCore(), engine.getPoolMax()));
        executor.setQueueCapacity(Math.max(1, engine.getPoolQueue()));
        executor.setThreadFactory(daemonFactory("workflow-engine-"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    /** 退避重试与看门狗的调度池（独立于 ai-scheduler-，SF-5） */
    @Bean(name = "workflowScheduler")
    public TaskScheduler workflowScheduler(WorkflowProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, properties.getEngine().getSchedulerPoolSize()));
        scheduler.setThreadNamePrefix("workflow-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    /** 守护线程工厂（与 DagExecutor 的 dag-executor 线程同形状） */
    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 供测试与运维：池是否已耗尽（背压观测点） */
    static boolean isSaturated(ThreadPoolExecutor executor) {
        return executor.getActiveCount() >= executor.getMaximumPoolSize();
    }
}
