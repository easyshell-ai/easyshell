package com.easyshell.server.workflow.engine;

import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待唤醒扫描器（§3.8 唤醒路径③ + §3.9 背压①的泵回）。
 *
 * <p>两件事：① 把 {@code queued} 的 run 在有余量时启动（全局并发上限的排队不是丢弃）；
 * ② 可选地重驱心跳过期的 running run。后者没有持久 fencing，默认关闭；本地锁不能
 * 证明跨实例或崩溃后的执行安全。</p>
 *
 * <p>注意它<b>不</b>负责审批唤醒（那在 {@code WorkflowResumeService.scanWakeups}，
 * 派生式扫 waiting 节点）——两个扫描器周期不同（10s / 10s 但关注面不同），
 * 合在一起会让"审批等待"与"排队泵回"互相阻塞。</p>
 */
@Slf4j
@Component
public class WorkflowPendingWakeupScanner {

    private final WorkflowRunRepository runRepository;
    private final WorkflowEngine engine;
    private final AuditLogService auditLogService;
    private final WorkflowProperties properties;

    /**
     * 显式事务边界：{@code startAtomically} 是 @Modifying，而本类的调用方是
     * {@code @Scheduled} 线程——没有事务，Spring Data 直接抛
     * {@code InvalidDataAccessApiUsageException}（实测："No EntityManager with actual
     * transaction available"），泵回从此整段瘫痪。与 {@code WorkflowRunService} 同范式，
     * 不依赖调用方恰好带着事务。
     */
    private final TransactionTemplate transactionTemplate;

    public WorkflowPendingWakeupScanner(WorkflowRunRepository runRepository,
                                        WorkflowEngine engine,
                                        AuditLogService auditLogService,
                                        WorkflowProperties properties,
                                        PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.engine = engine;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${easyshell.workflow.engine.wakeup-interval-sec:10}000",
            initialDelay = 8000)
    public void scan() {
        try {
            pumpQueued();
            if (properties.getEngine().isAutomaticRecoveryEnabled()
                    && !properties.getEngine().isDurableResumeEnabled()) {
                reDriveStaleHeartbeat();
            }
        } catch (Exception e) {
            log.error("待唤醒扫描异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 背压①：queued run 在有余量时启动。
     *
     * <p>活跃数只算 running/waiting：queued 行不能把自己算成占位，否则"满队列但没有
     * 一个在跑"时 {@code active >= max} 永远成立，队列永久饿死。每启动一个后重新按
     * 剩余并发计算（不把 queued 计入，也不会超发）。</p>
     */
    private void pumpQueued() {
        int limit = Math.max(1, properties.getEngine().getMaxConcurrentRuns());
        long active = runRepository.countByStatusIn(List.of("running", "waiting"));
        while (active < limit) {
            List<WorkflowRun> queued = runRepository.findByStatusIn(List.of("queued"));
            if (queued.isEmpty()) {
                return;
            }
            WorkflowRun run = queued.get(0);
            Integer started = transactionTemplate.execute(status ->
                    runRepository.startAtomically(run.getId(), LocalDateTime.now(), "scanner"));
            if (started == null || started == 0) {
                return;
            }
            engine.submitDrive(run);
            active++;
        }
    }

    /** 心跳过期的活跃 run 重新驱动（线程死亡/退避丢失的兜底） */
    private void reDriveStaleHeartbeat() {
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusSeconds(properties.getEngine().getHeartbeatTimeoutSec());
        for (WorkflowRun run : runRepository.findByStatusInAndStartedAtBefore(
                List.of("running"), staleBefore)) {
            if (run.getHeartbeatAt() != null && run.getHeartbeatAt().isAfter(staleBefore)) {
                continue;
            }
            log.info("心跳过期，重新驱动: run={} heartbeatAt={}", run.getId(), run.getHeartbeatAt());
            engine.submitDrive(run);
        }
    }

    /** 供测试：手动触发一次扫描 */
    public void scanOnce() {
        scan();
    }
}
