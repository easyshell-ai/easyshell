package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.ClusterAgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.TaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSchedulerService {

    private final AiScheduledTaskRepository scheduledTaskRepository;
    private final AiInspectReportRepository reportRepository;
    private final TaskService taskService;
    private final ChatModelFactory chatModelFactory;
    private final AgentRepository agentRepository;
    private final ClusterAgentRepository clusterAgentRepository;
    private final AgentTagRepository agentTagRepository;
    private final JobRepository jobRepository;
    private final AuditLogService auditLogService;
    private final TaskScheduler taskScheduler;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final ScheduledTaskNotifier scheduledTaskNotifier;

    private final Map<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        List<AiScheduledTask> enabledTasks = scheduledTaskRepository.findByEnabledTrueOrderByCreatedAtDesc();
        for (AiScheduledTask task : enabledTasks) {
            scheduleTask(task);
        }
        log.info("Loaded {} enabled scheduled tasks", enabledTasks.size());
    }

    @PreDestroy
    public void destroy() {
        scheduledFutures.values().forEach(f -> f.cancel(false));
        scheduledFutures.clear();
    }

    public void scheduleTask(AiScheduledTask task) {
        cancelTask(task.getId());
        try {
            // Spring CronTrigger uses standard 6-field cron and does NOT support '?'
            // (Quartz-style). Replace '?' with '*' for compatibility.
            String cronExpr = task.getCronExpression().replace('?', '*');
            CronTrigger trigger = new CronTrigger(cronExpr);
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeScheduledTask(task.getId()),
                    trigger
            );
            scheduledFutures.put(task.getId(), future);
            log.info("Scheduled task [{}] id={} cron={}", task.getName(), task.getId(), task.getCronExpression());
        } catch (Exception e) {
            log.error("Failed to schedule task [{}]: {}", task.getName(), e.getMessage());
        }
    }

    public void cancelTask(Long taskId) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("Cancelled scheduled task id={}", taskId);
        }
    }

    public void executeScheduledTaskAsync(Long scheduledTaskId) {
        taskScheduler.schedule(() -> executeScheduledTask(scheduledTaskId, true),
                java.time.Instant.now());
    }

    public void executeScheduledTask(Long scheduledTaskId) {
        executeScheduledTask(scheduledTaskId, false);
    }

    private void executeScheduledTask(Long scheduledTaskId, boolean manualRun) {
        AiScheduledTask scheduledTask = scheduledTaskRepository.findById(scheduledTaskId).orElse(null);
        if (scheduledTask == null) {
            log.warn("Scheduled task {} not found, skipping", scheduledTaskId);
            return;
        }
        if (!manualRun && !scheduledTask.getEnabled()) {
            log.warn("Scheduled task {} disabled, skipping", scheduledTaskId);
            return;
        }

        log.info("Executing scheduled task [{}] id={}", scheduledTask.getName(), scheduledTask.getId());

        List<String> agentIds = resolveTargetAgents(scheduledTask);
        if (agentIds.isEmpty()) {
            log.warn("No agents resolved for scheduled task [{}]", scheduledTask.getName());
            saveReport(scheduledTask, "无可用目标主机", null, "failed");
            return;
        }

        String script = resolveScript(scheduledTask);
        if (script == null || script.isBlank()) {
            log.warn("No script resolved for scheduled task [{}]", scheduledTask.getName());
            saveReport(scheduledTask, "脚本内容为空", null, "failed");
            return;
        }

        AiInspectReport runningReport = null;
        try {
            TaskCreateRequest request = new TaskCreateRequest();
            request.setName("[定时] " + scheduledTask.getName());
            request.setScriptContent(script);
            request.setAgentIds(agentIds);
            request.setTimeoutSeconds(120);

            runningReport = saveReport(scheduledTask, null, null, "running");

            Task task = taskService.createAndDispatch(request, scheduledTask.getCreatedBy());

            String scriptOutput = collectExecutionOutput(task.getId());
            String aiAnalysis = null;

            if (scheduledTask.getAiPrompt() != null && !scheduledTask.getAiPrompt().isBlank()
                    && scriptOutput != null && !scriptOutput.isEmpty()) {
                String filteredOutput = sensitiveDataFilter.filter(scriptOutput);
                aiAnalysis = performAiAnalysis(scheduledTask.getAiPrompt(), filteredOutput);
            }

            String status = "success";
            runningReport.setScriptOutput(scriptOutput);
            runningReport.setAiAnalysis(aiAnalysis);
            runningReport.setStatus(status);
            reportRepository.save(runningReport);

            if (aiAnalysis != null) {
                checkAndAlertCriticalFindings(scheduledTask, aiAnalysis);
            }

            // 推送通知到机器人渠道
            String notifyStrategy = scheduledTask.getNotifyStrategy();
            if (notifyStrategy != null && !"none".equals(notifyStrategy) && scheduledTask.getNotifyChannels() != null) {
                List<String> channels = Arrays.stream(scheduledTask.getNotifyChannels().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                scheduledTaskNotifier.notify(scheduledTask, runningReport, notifyStrategy, channels);
            }

            scheduledTask.setLastRunAt(LocalDateTime.now());
            scheduledTaskRepository.save(scheduledTask);

            auditLogService.log(scheduledTask.getCreatedBy(), "SCHEDULER",
                    "SCHEDULED_TASK_EXECUTE", "ai_scheduled_task",
                    String.valueOf(scheduledTask.getId()),
                    "定时任务执行: " + scheduledTask.getName() + ", 状态: " + status,
                    "scheduler", status);

        } catch (Exception e) {
            log.error("Failed to execute scheduled task [{}]: {}", scheduledTask.getName(), e.getMessage(), e);
            // Update the EXISTING running report instead of creating a duplicate
            if (runningReport != null) {
                runningReport.setStatus("failed");
                runningReport.setAiAnalysis("执行异常: " + e.getMessage());
                reportRepository.save(runningReport);
            } else {
                saveReport(scheduledTask, "执行异常: " + e.getMessage(), null, "failed");
            }
            scheduledTask.setLastRunAt(LocalDateTime.now());
            scheduledTaskRepository.save(scheduledTask);
        }
    }

    private List<String> resolveTargetAgents(AiScheduledTask task) {
        String[] ids = task.getTargetIds().split(",");

        return switch (task.getTargetType()) {
            case "agent" -> Arrays.stream(ids)
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .collect(Collectors.toList());

            case "cluster" -> Arrays.stream(ids)
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .flatMap(id -> {
                        try {
                            return clusterAgentRepository.findByClusterId(Long.parseLong(id)).stream()
                                    .map(ca -> ca.getAgentId())
                                    .filter(agentId -> agentRepository.findById(agentId)
                                            .map(a -> a.getStatus() == 1).orElse(false));
                        } catch (NumberFormatException e) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .distinct()
                    .collect(Collectors.toList());

            case "tag" -> Arrays.stream(ids)
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .flatMap(id -> {
                        try {
                            return agentTagRepository.findByTagId(Long.parseLong(id)).stream()
                                    .map(at -> at.getAgentId());
                        } catch (NumberFormatException e) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .distinct()
                    .filter(agentId -> agentRepository.findById(agentId)
                            .map(a -> a.getStatus() == 1).orElse(false))
                    .collect(Collectors.toList());

            default -> List.of();
        };
    }

    private String resolveScript(AiScheduledTask task) {
        if (task.getScriptTemplate() != null && !task.getScriptTemplate().isBlank()) {
            return task.getScriptTemplate();
        }

        return getBuiltInTemplate(task.getTaskType());
    }

    private String collectExecutionOutput(String taskId) {
        if (taskId == null) {
            return "未获取到任务ID";
        }

        int maxWaitSeconds = 120;
        int pollIntervalMs = 2000;
        int elapsed = 0;

        List<Job> jobs = List.of();
        while (elapsed < maxWaitSeconds * 1000) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            elapsed += pollIntervalMs;

            jobs = jobRepository.findByTaskId(taskId);
            if (jobs.isEmpty()) {
                continue;
            }
            boolean allDone = jobs.stream().allMatch(j -> j.getStatus() >= 2);
            if (allDone) {
                break;
            }
        }

        if (jobs.isEmpty()) {
            return "未获取到执行结果";
        }

        StringBuilder sb = new StringBuilder();
        for (Job job : jobs) {
            sb.append("=== Agent: ").append(job.getAgentId()).append(" ===\n");
            sb.append("Status: ").append(job.getStatus()).append(", ExitCode: ").append(job.getExitCode()).append("\n");
            if (job.getOutput() != null) {
                sb.append(job.getOutput()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String performAiAnalysis(String aiPrompt, String scriptOutput) {
        try {
            ChatModel chatModel = chatModelFactory.getChatModel(null);
            String fullPrompt = aiPrompt + "\n\n以下是脚本执行输出:\n" + truncateOutput(scriptOutput, 8000);
            // Wrap with overall timeout to prevent blocking scheduler thread indefinitely.
            // Spring AI internal retry can take 8+ minutes; cap total wait at 3 minutes.
            var future = CompletableFuture.supplyAsync(() -> {
                var response = chatModel.call(new Prompt(fullPrompt));
                return response.getResult().getOutput().getText();
            });
            return future.get(3, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.error("AI analysis timed out after 3 minutes");
            return "AI 分析超时: 请检查 AI 服务是否可用";
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            log.error("AI analysis failed: {}", cause != null ? cause.getMessage() : e.getMessage());
            return "AI 分析失败: " + (cause != null ? cause.getMessage() : e.getMessage());
        }
    }

    private String truncateOutput(String output, int maxLength) {
        if (output == null) return "";
        if (output.length() <= maxLength) return output;
        return output.substring(0, maxLength) + "\n...(输出已截断)";
    }

    private void checkAndAlertCriticalFindings(AiScheduledTask task, String aiAnalysis) {
        String lowerAnalysis = aiAnalysis.toLowerCase();
        boolean hasCritical = lowerAnalysis.contains("严重") || lowerAnalysis.contains("危险")
                || lowerAnalysis.contains("紧急") || lowerAnalysis.contains("critical")
                || lowerAnalysis.contains("异常") || lowerAnalysis.contains("告警")
                || lowerAnalysis.contains("磁盘空间不足") || lowerAnalysis.contains("内存不足")
                || lowerAnalysis.contains("cpu 使用率过高") || lowerAnalysis.contains("安全风险");

        if (hasCritical) {
            log.warn("⚠ AI 巡检告警 - 任务 [{}] 发现关键问题: {}",
                    task.getName(),
                    aiAnalysis.length() > 200 ? aiAnalysis.substring(0, 200) + "..." : aiAnalysis);

            auditLogService.log(task.getCreatedBy(), "SCHEDULER",
                    "AI_INSPECT_ALERT", "ai_scheduled_task",
                    String.valueOf(task.getId()),
                    "AI 巡检发现关键问题: " + task.getName() + " - " +
                            (aiAnalysis.length() > 500 ? aiAnalysis.substring(0, 500) + "..." : aiAnalysis),
                    "scheduler", "alert");
        }
    }

    private AiInspectReport saveReport(AiScheduledTask task, String scriptOutput, String aiAnalysis, String status) {
        AiInspectReport report = new AiInspectReport();
        report.setScheduledTaskId(task.getId());
        report.setTaskType(task.getTaskType());
        report.setTaskName(task.getName());
        report.setTargetSummary(task.getTargetType() + ": " + task.getTargetIds());
        report.setScriptOutput(scriptOutput);
        report.setAiAnalysis(aiAnalysis);
        report.setStatus(status);
        report.setCreatedBy(task.getCreatedBy());
        return reportRepository.save(report);
    }

    public record BuiltInTemplate(String type, String name, String description, String script, String aiPrompt) {}

    private static final String SYSTEM_HEALTH_SCRIPT = """
            #!/bin/bash
            echo "=== 系统基本信息 ==="
            uname -a
            uptime
            echo ""
            echo "=== CPU 使用率 ==="
            top -bn1 | head -5
            echo ""
            echo "=== 内存使用 ==="
            free -h
            echo ""
            echo "=== 磁盘使用 ==="
            df -h
            echo ""
            echo "=== 系统负载 ==="
            cat /proc/loadavg
            echo ""
            echo "=== 僵尸进程 ==="
            ps aux | awk '$8=="Z"' | head -10
            echo "僵尸进程数: $(ps aux | awk '$8=="Z"' | wc -l)"
            echo ""
            echo "=== 最近登录 ==="
            last -10 2>/dev/null || echo "无法获取"
            echo ""
            echo "=== 系统日志错误(最近) ==="
            journalctl -p err --since "1 hour ago" --no-pager 2>/dev/null | tail -20 || dmesg | tail -20
            """;

    private static final String SOFTWARE_DETECT_SCRIPT = """
            #!/bin/bash
            echo "===DETECTION_START==="
            echo "--- SOFTWARE ---"
            for svc in nginx apache2 httpd mysql mysqld mariadb postgres postgresql java php-fpm node python3 redis-server mongod docker; do
                pid=$(pgrep -x "$svc" 2>/dev/null | head -1)
                if [ -z "$pid" ]; then
                    pid=$(pgrep -f "^/.*/$svc" 2>/dev/null | head -1)
                fi
                if [ -n "$pid" ]; then
                    ver=""
                    ports=""
                    stype="service"
                    case "$svc" in
                        nginx) ver=$(nginx -v 2>&1 | sed 's/.*\\///' | head -1); stype="service" ;;
                        mysql|mysqld|mariadb) ver=$(mysql --version 2>/dev/null | grep -oP '\\d+\\.\\d+\\.\\d+' | head -1); stype="database" ;;
                        postgres|postgresql) ver=$(postgres --version 2>/dev/null | grep -oP '\\d+\\.\\d+' | head -1); stype="database" ;;
                        java) ver=$(java -version 2>&1 | head -1 | grep -oP '"[^"]+"' | tr -d '"'); stype="runtime" ;;
                        node) ver=$(node --version 2>/dev/null | tr -d 'v'); stype="runtime" ;;
                        python3) ver=$(python3 --version 2>&1 | grep -oP '\\d+\\.\\d+\\.\\d+'); stype="runtime" ;;
                        redis-server) ver=$(redis-server --version 2>/dev/null | grep -oP 'v=\\K[\\d.]+'); stype="database" ;;
                        docker) ver=$(docker --version 2>/dev/null | grep -oP '\\d+\\.\\d+\\.\\d+'); stype="container_engine" ;;
                    esac
                    ports=$(ss -tlnp 2>/dev/null | grep "pid=$pid" | awk '{print $4}' | grep -oP '\\d+$' | sort -u | tr '\\n' ',' | sed 's/,$//')
                    echo "SW|$svc|$ver|$stype|$pid|$ports"
                fi
            done
            echo "--- DOCKER_CONTAINERS ---"
            if command -v docker &> /dev/null && docker info &>/dev/null; then
                docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}' 2>/dev/null
            else
                echo "NO_DOCKER"
            fi
            echo "===DETECTION_END==="
            """;

    private static final String SECURITY_AUDIT_SCRIPT = """
            #!/bin/bash
            echo "=== SSH 配置 ==="
            grep -E "^(PermitRootLogin|PasswordAuthentication|Port)" /etc/ssh/sshd_config 2>/dev/null || echo "无法读取 SSH 配置"
            echo ""
            echo "=== 防火墙状态 ==="
            if command -v ufw &>/dev/null; then ufw status 2>/dev/null; elif command -v firewall-cmd &>/dev/null; then firewall-cmd --state 2>/dev/null && firewall-cmd --list-all 2>/dev/null; else iptables -L -n 2>/dev/null | head -30; fi
            echo ""
            echo "=== 可登录用户 ==="
            grep -v '/nologin\\|/false' /etc/passwd | cut -d: -f1,3,7
            echo ""
            echo "=== 无密码用户 ==="
            awk -F: '($2=="" || $2=="!") {print $1}' /etc/shadow 2>/dev/null || echo "无权限"
            echo ""
            echo "=== SUID 文件 ==="
            find / -perm -4000 -type f 2>/dev/null | head -20
            echo ""
            echo "=== 最近修改的系统文件 ==="
            find /etc -mtime -7 -type f 2>/dev/null | head -20
            echo ""
            echo "=== 异常监听端口 ==="
            ss -tlnp 2>/dev/null | grep -v "127.0.0.1\\|::1"
            """;

    private static final String DISK_CAPACITY_SCRIPT = """
            #!/bin/bash
            echo "=== 磁盘使用 ==="
            df -h
            echo ""
            echo "=== Inode 使用 ==="
            df -i
            echo ""
            echo "=== 大文件 TOP 20 ==="
            find / -xdev -type f -size +100M 2>/dev/null | head -20 | xargs ls -lh 2>/dev/null
            echo ""
            echo "=== 大目录 TOP 10 ==="
            du -sh /var/log/* 2>/dev/null | sort -rh | head -10
            echo ""
            echo "=== 可清理的日志文件 ==="
            find /var/log -name "*.gz" -o -name "*.old" -o -name "*.1" 2>/dev/null | head -20
            """;

    private static final String DOCKER_HEALTH_SCRIPT = """
            #!/bin/bash
            echo "=== Docker 引擎状态 ==="
            docker info 2>/dev/null | head -20 || echo "Docker 未安装或未运行"
            echo ""
            echo "=== 运行中的容器 ==="
            docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}' 2>/dev/null
            echo ""
            echo "=== 停止的容器 ==="
            docker ps -f status=exited --format 'table {{.Names}}\\t{{.Status}}' 2>/dev/null
            echo ""
            echo "=== 容器资源使用 ==="
            docker stats --no-stream --format 'table {{.Name}}\\t{{.CPUPerc}}\\t{{.MemUsage}}\\t{{.NetIO}}' 2>/dev/null
            echo ""
            echo "=== 悬空镜像 ==="
            docker images -f dangling=true 2>/dev/null
            echo ""
            echo "=== 磁盘使用 ==="
            docker system df 2>/dev/null
            """;

    public static final Map<String, BuiltInTemplate> BUILT_IN_TEMPLATES = new LinkedHashMap<>();

    static {
        BUILT_IN_TEMPLATES.put("inspect", new BuiltInTemplate(
                "inspect", "系统健康巡检",
                "检查 CPU、内存、磁盘、负载、僵尸进程等系统指标",
                SYSTEM_HEALTH_SCRIPT,
                "请分析以下系统健康巡检结果，指出异常项并给出优化建议："));
        BUILT_IN_TEMPLATES.put("detect", new BuiltInTemplate(
                "detect", "软件资产扫描",
                "探测主机上运行的软件和 Docker 容器",
                SOFTWARE_DETECT_SCRIPT,
                "请分析以下软件资产扫描结果，总结软件清单并指出版本过旧或存在安全风险的组件："));
        BUILT_IN_TEMPLATES.put("security", new BuiltInTemplate(
                "security", "安全审计",
                "检查 SSH 配置、防火墙、可疑用户、SUID 文件等安全项",
                SECURITY_AUDIT_SCRIPT,
                "请分析以下安全审计结果，识别安全风险并按严重程度排列，给出修复建议："));
        BUILT_IN_TEMPLATES.put("disk", new BuiltInTemplate(
                "disk", "磁盘容量检查",
                "检查磁盘使用率、inode 使用率、大文件、大目录",
                DISK_CAPACITY_SCRIPT,
                "请分析以下磁盘容量检查结果，指出磁盘空间紧张的分区并给出清理建议："));
        BUILT_IN_TEMPLATES.put("docker_health", new BuiltInTemplate(
                "docker_health", "Docker 健康检查",
                "检查 Docker 引擎状态、容器健康、镜像清理、网络和卷",
                DOCKER_HEALTH_SCRIPT,
                "请分析以下 Docker 健康检查结果，指出异常容器和需要清理的资源："));
    }

    public static String getBuiltInTemplate(String taskType) {
        return switch (taskType) {
            case "inspect" -> SYSTEM_HEALTH_SCRIPT;
            case "detect" -> SOFTWARE_DETECT_SCRIPT;
            case "security" -> SECURITY_AUDIT_SCRIPT;
            case "disk" -> DISK_CAPACITY_SCRIPT;
            case "docker_health" -> DOCKER_HEALTH_SCRIPT;
            default -> null;
        };
    }
}
