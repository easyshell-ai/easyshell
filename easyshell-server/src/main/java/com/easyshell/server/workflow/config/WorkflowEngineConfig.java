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
     *
     * <p><b>为什么池大小要从并发配置推导，而不是只用 {@code poolCore}</b>：
     * {@code WorkflowEngine} 在同一个池里做<b>三级嵌套</b>，每一级都阻塞在下一级的
     * future 上：</p>
     * <ol>
     *   <li>{@code drive()}（{@code submitDrive} 提交）整个波期间阻塞在
     *       {@code runWave} 的 {@code future.join()}；</li>
     *   <li>{@code runNode()}（同一池）阻塞在 {@code executeWithTimeout} 的
     *       {@code future.get(timeoutSec)}——节点级超时必须在另一个线程上等；</li>
     *   <li>{@code executor.execute(ctx)}（同一池）是唯一真正干活的阶段。</li>
     * </ol>
     * <p>于是<b>每个在飞节点占 3 个池线程</b>（1 个 drive + 1 个 runNode + 1 个 executor），
     * 同时可存在的线程数上限 = {@code maxConcurrentRuns × (1 + 2 × maxConcurrentNodes)}。
     * 池线程一旦低于这本账，全部线程都会停在 join/get 上，而它们等的任务还排在
     * {@code poolQueue} 里——<b>同池嵌套死锁</b>：没有线程能去取队列里的任务，
     * 也没有任务能完成来释放线程。表现是 run 卡在 running 不前、审批行永不出现，
     * awaitility 超时（契约测试两条审批用例 40s 超时即此，见
     * {@code WorkflowApiContractTest} 的审批三例；生产上 {@code maxConcurrentRuns=3}
     * 配 {@code poolCore=5} 同样在射程内）。</p>
     *
     * <p>线程是按需创建的（不预启动），把账做够不会常驻占用；{@code poolCore} /
     * {@code poolMax} 保留为<b>下限</b>，运维显式调大仍然生效。</p>
     */
    @Bean(name = "workflowEnginePool")
    public ExecutorService workflowEnginePool(WorkflowProperties properties) {
        WorkflowProperties.Engine engine = properties.getEngine();
        int runs = Math.max(1, engine.getMaxConcurrentRuns());
        int nodesPerWave = Math.max(1, engine.getMaxConcurrentNodes());
        // 唤醒路径（审批 afterCommit 的 resume、扫描器泵回、退避重试）也会往池里提交
        // drive 级任务，与触发路径并发，留一份余量
        int nestedThreads = runs * (1 + 2 * nodesPerWave) + runs;
        int core = Math.max(Math.max(1, engine.getPoolCore()), nestedThreads);
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(Math.max(core, engine.getPoolMax()));
        executor.setQueueCapacity(Math.max(1, engine.getPoolQueue()));
        executor.setThreadFactory(daemonFactory("workflow-engine-"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    /**
     * {@code workflowAiPool}：ai-analyze 节点的专用有界池。
     *
     * <p>不复用 {@code workflow-engine} 池：LLM 调用可能阻塞数十秒，混进引擎池会把
     * drive/节点执行线程一起拖死。2 个核心线程、4 个上限、8 个排队位，队列满直接拒绝
     * （执行器按 AI_UNAVAILABLE 失败），绝不无界堆积。</p>
     */
    @Bean(name = "workflowAiPool")
    public ExecutorService workflowAiPool() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadFactory(daemonFactory("workflow-ai-"));
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
