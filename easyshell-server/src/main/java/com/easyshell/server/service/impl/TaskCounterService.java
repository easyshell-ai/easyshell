package com.easyshell.server.service.impl;

import com.easyshell.server.model.entity.Job;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务计数 / 终态的写入入口。
 * <p>
 * 为什么必须单独拆一个 bean：createAndDispatch 的派发动作跑在
 * {@code TransactionSynchronization.afterCommit()} 里，那时外层事务已经提交、
 * 线程上还绑着一个已经用完的 EntityManager。在这种"没有可用事务"的上下文里直接调用
 * Spring Data JPA 的 {@code @Modifying} 仓库会被 Hibernate 拒绝
 * （{@code TransactionRequiredException: no transaction is in progress}，
 * 被 Spring 翻译成 {@code InvalidDataAccessApiUsageException}），
 * 而 {@code save()} 虽然不报错，却因为无处 flush 而把变更静默丢掉。
 * 所以这里提供 REQUIRES_NEW 的入口，让 post-commit 路径换一个全新的事务再落库。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskCounterService {

    private final TaskRepository taskRepository;
    private final JobRepository jobRepository;

    /**
     * 派发失败后的收尾：把 job 落成终态，再原子累加任务计数。
     * <p>
     * REQUIRES_NEW 会挂起外层那个"已提交但资源还没清理"的同步状态，
     * 新建一个独立的 EntityManager 和事务，job 的 UPDATE 与任务计数都在这个新事务里
     * 真正提交。调用方（afterCommit 回调）无论有没有活动事务都能安全使用。
     * </p>
     *
     * @param job       已经填好终态字段（status/output/finishedAt）的 job
     * @param jobStatus 终态语义：2=success，3=failed，4=timeout，其余不计数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyJobResultInNewTransaction(Job job, int jobStatus) {
        jobRepository.save(job);
        incrementCounters(job.getTaskId(), jobStatus);
    }

    /**
     * 把一条 job 的终态结果反映到任务上，参与调用方已有的事务。
     * <p>
     * 给有事务的调用方用（reportJobResult / timeoutStaleJobs /
     * failRunningJobsForAgent / createAndDispatch 的同步失败分支）：
     * REQUIRED 会并入调用方事务，与 job 的终态化保持同一个提交边界，
     * 不会把"job 已终态但计数没累加"的窗口暴露出来。
     * </p>
     *
     * @param taskId    任务 ID
     * @param jobStatus 刚终态化的 job 状态：2=success，3=failed，4=timeout，其余不计数
     */
    @Transactional
    public void applyJobResult(String taskId, int jobStatus) {
        incrementCounters(taskId, jobStatus);
    }

    /**
     * 把一条 job 的终态结果原子地反映到任务上。
     * <p>
     * 旧实现是"查出全部 job 统计 success/failed → 改 task → save"的读-改-写：
     * 多个 agent 几乎同时回传时，两个线程基于同一份旧统计各自 save，后提交的覆盖先提交的，
     * 计数少计、completed == total 误判，任务可能永远卡在 running。
     * </p>
     * <p>
     * 新实现分两步，全部由数据库行锁串行化：
     * 1) {@link TaskRepository#incrementCountersAtomically} 单条 UPDATE 累加计数（返回值判定 task 是否存在）；
     * 2) 用标量投影读回最新计数，全部 job 终态时再走
     * {@link TaskRepository#finishTaskAtomically}，以 status = 1 为竞争条件写终态
     * （返回值 1 = 本线程写入，0 = 已有其他线程写过，幂等跳过）。
     * 每个 job 只会被累加一次，因为 job 的终态化本身也是带条件的原子 UPDATE。
     * </p>
     *
     * @param jobStatus 刚终态化的 job 状态：2=success，3=failed，4=timeout，其余不计数
     */
    private void incrementCounters(String taskId, int jobStatus) {
        int incremented = taskRepository.incrementCountersAtomically(taskId, jobStatus);
        if (incremented == 0) {
            log.warn("Task {} not found while applying job status {}", taskId, jobStatus);
            return;
        }

        TaskRepository.TaskCountProjection counts = taskRepository.findCountsById(taskId).orElse(null);
        if (counts == null) {
            return;
        }

        int success = counts.getSuccessCount() != null ? counts.getSuccessCount() : 0;
        int failed = counts.getFailedCount() != null ? counts.getFailedCount() : 0;
        int total = counts.getTotalCount() != null ? counts.getTotalCount() : 0;

        // 所有 job 都已终态时才写终态；status = 1 条件保证并发下只有一个线程写成功
        if (total > 0 && success + failed >= total) {
            int status;
            if (failed == 0) {
                status = 2; // success
            } else if (success == 0) {
                status = 4; // failed
            } else {
                status = 3; // partial_success
            }
            int finished = taskRepository.finishTaskAtomically(taskId, status, LocalDateTime.now());
            if (finished == 0) {
                log.debug("Task {} was already finalized by a concurrent report", taskId);
            }
        }
    }
}
