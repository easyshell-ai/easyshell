package com.easyshell.server.service.impl;

import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TaskServiceImpl 并发场景回归测试：agent 并发回传结果、并发写日志。
 * <p>
 * 用真实 H2 库（application-test.yml，MODE=MySQL）驱动 repository，覆盖
 * updateTaskStatus 的读-改-写竞态与 appendJobLog 的并发丢日志 / output 无上限，
 * 以及 createAndDispatch 派发失败（agent 在派发瞬间掉线）时 post-commit 回调的落库。
 * 测试方法不加事务（NOT_SUPPORTED），让每次服务调用独立提交，和生产里
 * WebSocket 逐条消息处理的行为一致，竞态才能真正暴露出来。
 * </p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=warn"})
@Import({TaskServiceImpl.class, TaskCounterService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("TaskServiceImpl — 并发回传结果 / 并发写日志的原子性")
class TaskServiceImplConcurrencyTest {

    private static final int CONCURRENCY = 8;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private AgentRepository agentRepository;

    @MockitoBean
    private TaskLogWebSocketHandler logWebSocketHandler;

    @MockitoBean
    private AgentWebSocketHandler agentWebSocketHandler;

    @MockitoBean
    private ClusterService clusterService;

    @MockitoBean
    private TagService tagService;

    @Autowired
    private TaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        taskRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @Test
    @DisplayName("并发回传成功结果：计数一个不丢，任务收敛到 success 终态")
    void concurrentReports_allSuccess_taskEndsSuccessWithExactCounts() throws Exception {
        Task task = newTask("concurrent-all-success", CONCURRENCY);
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            jobs.add(newJob(task.getId(), "agent-" + i));
        }
        jobRepository.saveAll(jobs);

        runConcurrently(CONCURRENCY, i -> taskService.reportJobResult(
                JobResultRequest.builder()
                        .jobId(jobs.get(i).getId())
                        .status(2)
                        .exitCode(0)
                        .output("ok-" + i)
                        .build(),
                "agent-" + i));

        Task reloaded = taskRepository.findById(task.getId()).orElseThrow();
        // 旧实现是读-改-写竞态：并发回传互相覆盖，successCount 少于 8、任务卡在 running
        assertThat(reloaded.getSuccessCount()).isEqualTo(CONCURRENCY);
        assertThat(reloaded.getFailedCount()).isZero();
        assertThat(reloaded.getStatus()).isEqualTo(2);
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("并发回传混合结果：成功/失败/超时计数精确，任务落到 partial_success")
    void concurrentReports_mixedStatuses_taskEndsPartialSuccess() throws Exception {
        int successCount = 3;
        int failedCount = 2;
        int timeoutCount = 1;
        int total = successCount + failedCount + timeoutCount;

        Task task = newTask("concurrent-mixed", total);
        List<Job> jobs = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            jobs.add(newJob(task.getId(), "agent-" + i));
            statuses.add(i < successCount ? 2 : (i < successCount + failedCount ? 3 : 4));
        }
        jobRepository.saveAll(jobs);

        runConcurrently(total, i -> taskService.reportJobResult(
                JobResultRequest.builder()
                        .jobId(jobs.get(i).getId())
                        .status(statuses.get(i))
                        .exitCode(statuses.get(i) == 2 ? 0 : 1)
                        .output("out-" + i)
                        .build(),
                "agent-" + i));

        Task reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getSuccessCount()).isEqualTo(successCount);
        assertThat(reloaded.getFailedCount()).isEqualTo(failedCount + timeoutCount);
        assertThat(reloaded.getStatus()).isEqualTo(3);
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("同一 job 被并发回传两次：只计数一次，任务仍能正确收敛")
    void concurrentDuplicateReports_sameJobCountedOnce() throws Exception {
        int total = 3;
        Task task = newTask("concurrent-duplicate-report", total);
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            jobs.add(newJob(task.getId(), "agent-" + i));
        }
        jobRepository.saveAll(jobs);

        // 4 个线程回传 3 个 job：job 0 被并发回传两次，job 1/2 各一次
        List<Integer> jobIndexes = List.of(0, 0, 1, 2);
        runConcurrently(jobIndexes.size(), i -> {
            int jobIndex = jobIndexes.get(i);
            taskService.reportJobResult(
                    JobResultRequest.builder()
                            .jobId(jobs.get(jobIndex).getId())
                            .status(2)
                            .exitCode(0)
                            .output("dup-" + i)
                            .build(),
                    "agent-" + jobIndex);
        });

        Task reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getSuccessCount()).isEqualTo(total);
        assertThat(reloaded.getStatus()).isEqualTo(2);
    }

    @Test
    @DisplayName("并发写同一 job 的日志：一行都不丢，首行不带换行")
    void concurrentLogAppends_noLostLines() throws Exception {
        Task task = newTask("concurrent-log-append", 1);
        Job job = newJob(task.getId(), "agent-log");
        jobRepository.save(job);

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            lines.add("log-line-" + i);
        }

        runConcurrently(CONCURRENCY, i -> taskService.appendJobLog(job.getId(), lines.get(i), "agent-log"));

        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        // 旧实现是读-改-写且无事务：并发日志行互相覆盖，最终只剩零星几行
        assertThat(reloaded.getOutput()).isNotNull();
        assertThat(reloaded.getOutput().split("\n"))
                .containsExactlyInAnyOrderElementsOf(lines);
        assertThat(reloaded.getOutput()).doesNotStartWith("\n");
    }

    @Test
    @DisplayName("output 超过上限时只保留尾部，绝不写溢出的列")
    void appendJobLog_truncatesOutputToTail() {
        int maxLength = 256 * 1024;
        Task task = newTask("log-output-cap", 1);
        Job job = newJob(task.getId(), "agent-cap");
        // 预置一份远超上限的历史输出
        job.setOutput("h".repeat(maxLength * 2));
        jobRepository.save(job);

        taskService.appendJobLog(job.getId(), "tail-line", "agent-cap");

        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getOutput()).endsWith("\ntail-line");
        assertThat(reloaded.getOutput()).hasSize(maxLength);
        assertThat(reloaded.getOutput()).startsWith("h");
    }

    @Test
    @DisplayName("日志顺序追加：首行无换行前缀，后续按序换行拼接")
    void appendJobLog_appendsInOrderWithNewlineSeparator() {
        Task task = newTask("log-order", 1);
        Job job = newJob(task.getId(), "agent-order");
        jobRepository.save(job);

        taskService.appendJobLog(job.getId(), "first", "agent-order");
        taskService.appendJobLog(job.getId(), "second", "agent-order");

        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getOutput()).isEqualTo("first\nsecond");
    }

    @Test
    @DisplayName("createAndDispatch 时 agent 全部不可达：失败 job 直接把任务推到 failed 终态")
    void createAndDispatch_allAgentsOffline_taskEndsFailed() {
        Agent offline = new Agent();
        offline.setId("agent-offline");
        offline.setHostname("offline-host");
        offline.setIp("127.0.0.1");
        offline.setStatus(0);
        agentRepository.save(offline);
        when(agentWebSocketHandler.isAgentConnected("agent-offline")).thenReturn(false);

        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("dispatch-offline");
        request.setScriptContent("echo hi");
        request.setAgentIds(List.of("agent-offline"));

        Task created = taskService.createAndDispatch(request, 1L);

        Task reloaded = taskRepository.findById(created.getId()).orElseThrow();
        // 失败 job 的计数必须落到刚 INSERT 的 task 行上，否则任务永远卡在 running
        assertThat(reloaded.getFailedCount()).isEqualTo(1);
        assertThat(reloaded.getSuccessCount()).isZero();
        assertThat(reloaded.getStatus()).isEqualTo(4);
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("派发时 agent 掉线：post-commit 在无活动事务下也要把 job/任务推到 failed 终态")
    void createAndDispatch_agentDropsAtDispatch_taskEndsFailedAfterCommit() {
        Agent online = new Agent();
        online.setId("agent-dispatch-drop");
        online.setHostname("online-host");
        online.setIp("127.0.0.1");
        online.setStatus(1);
        agentRepository.save(online);
        // 循环里看起来在线（放行），真正派发时 agent 已经掉线
        when(agentWebSocketHandler.isAgentConnected("agent-dispatch-drop")).thenReturn(true);
        when(agentWebSocketHandler.dispatchJob(any(), any(), any(), anyInt())).thenReturn(false);

        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("dispatch-agent-drops");
        request.setScriptContent("echo hi");
        request.setAgentIds(List.of("agent-dispatch-drop"));

        // 不抛异常：post-commit 里即便没有活动事务，状态也必须正确落库
        Task created = taskService.createAndDispatch(request, 1L);

        Task reloaded = taskRepository.findById(created.getId()).orElseThrow();
        List<Job> reloadedJobs = jobRepository.findByTaskId(created.getId());
        assertThat(reloadedJobs).hasSize(1);
        assertThat(reloadedJobs.get(0).getStatus()).isEqualTo(3);
        assertThat(reloaded.getFailedCount()).isEqualTo(1);
        assertThat(reloaded.getSuccessCount()).isZero();
        assertThat(reloaded.getStatus()).isEqualTo(4);
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("派发一半掉线：掉线的 job 记 failed，任务保持 running 等其余 job 回传后收敛")
    void createAndDispatch_partialDispatchFailure_taskStaysRunningThenConverges() {
        Agent agentOk = newAgent("agent-dispatch-ok");
        Agent agentDrop = newAgent("agent-dispatch-drop2");
        agentRepository.saveAll(List.of(agentOk, agentDrop));
        when(agentWebSocketHandler.isAgentConnected(anyString())).thenReturn(true);
        when(agentWebSocketHandler.dispatchJob(eq("agent-dispatch-ok"), any(), any(), anyInt()))
                .thenReturn(true);
        // 只有这一个 agent 在派发瞬间掉线
        when(agentWebSocketHandler.dispatchJob(eq("agent-dispatch-drop2"), any(), any(), anyInt()))
                .thenReturn(false);

        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("dispatch-partial-failure");
        request.setScriptContent("echo hi");
        request.setAgentIds(List.of("agent-dispatch-ok", "agent-dispatch-drop2"));

        Task created = taskService.createAndDispatch(request, 1L);

        // post-commit 之后：掉线的 job 已终态，任务只累加了一次失败，仍在 running
        List<Job> jobsAfterDispatch = jobRepository.findByTaskId(created.getId());
        assertThat(jobsAfterDispatch).hasSize(2);
        Job failedJob = jobsAfterDispatch.stream()
                .filter(j -> "agent-dispatch-drop2".equals(j.getAgentId()))
                .findFirst().orElseThrow();
        Job pendingJob = jobsAfterDispatch.stream()
                .filter(j -> "agent-dispatch-ok".equals(j.getAgentId()))
                .findFirst().orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(3);
        assertThat(pendingJob.getStatus()).isZero();

        Task afterDispatch = taskRepository.findById(created.getId()).orElseThrow();
        assertThat(afterDispatch.getFailedCount()).isEqualTo(1);
        assertThat(afterDispatch.getSuccessCount()).isZero();
        assertThat(afterDispatch.getStatus()).isEqualTo(1);
        assertThat(afterDispatch.getFinishedAt()).isNull();

        // 剩下的 job 正常回传成功后，任务必须能收敛（post-commit 的累加没有把任务写坏）
        taskService.reportJobResult(
                JobResultRequest.builder()
                        .jobId(pendingJob.getId())
                        .status(2)
                        .exitCode(0)
                        .output("ok")
                        .build(),
                "agent-dispatch-ok");

        Task reloaded = taskRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSuccessCount()).isEqualTo(1);
        assertThat(reloaded.getFailedCount()).isEqualTo(1);
        assertThat(reloaded.getStatus()).isEqualTo(3);
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    private Agent newAgent(String id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setHostname(id + "-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        return agent;
    }

    private Task newTask(String name, int totalCount) {
        Task task = new Task();
        task.setId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        task.setName(name);
        task.setScriptType("shell");
        task.setScriptContent("echo hi");
        task.setTimeoutSeconds(3600);
        task.setStatus(1);
        task.setTotalCount(totalCount);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setCreatedBy(1L);
        task.setStartedAt(LocalDateTime.now());
        return taskRepository.save(task);
    }

    private Job newJob(String taskId, String agentId) {
        Job job = new Job();
        job.setId("job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        job.setTaskId(taskId);
        job.setAgentId(agentId);
        job.setStatus(0);
        return job;
    }

    /**
     * 用同一条起跑线并发执行 action，任意一个 action 抛异常都会让测试失败。
     */
    private void runConcurrently(int threads, IndexedAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            Callable<Void> callable = () -> {
                startLine.await();
                action.accept(index);
                return null;
            };
            futures.add(pool.submit(callable));
        }
        startLine.countDown();
        try {
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
    }

    @FunctionalInterface
    private interface IndexedAction {
        void accept(int index) throws Exception;
    }
}
