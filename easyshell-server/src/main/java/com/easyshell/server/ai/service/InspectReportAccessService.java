package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.ai.repository.InspectReportQueryRepository;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.HostAclService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InspectReportAccessService {
    private final AiInspectReportRepository reports;
    private final JdbcTemplate jdbc;
    private final TaskRepository tasks;
    private final JobRepository jobs;
    private final TaskTargetSnapshotRepository snapshots;
    private final HostAclService acl;
    private final InspectReportQueryRepository reportQuery;
    private final UserRepository users;

    public Page<AiInspectReport> page(long userId, boolean admin, int page, int size) {
        // The query repository applies the role/owner predicate itself. The admin argument is
        // intentionally not used as an authorization bypass; it is represented by the DB role.
        return reportQuery.page(userId, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AiInspectReport detail(Long id, Long userId, boolean admin) {
        AiInspectReport report = reports.findById(id).orElseThrow(() -> new BusinessException(404, "巡检报告不存在"));
        var user = userId == null ? null : users.findById(userId).orElse(null);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || user.getRole() == null
                || !Set.of("viewer", "operator", "admin", "super_admin").contains(user.getRole()))
            throw new BusinessException(403, "Access denied");
        boolean currentAdmin = "admin".equals(user.getRole()) || "super_admin".equals(user.getRole());
        if (!currentAdmin && !Objects.equals(userId, report.getCreatedBy())) throw new BusinessException(403, "Access denied");
        if (!allowed(report, userId)) throw new BusinessException(403, "Host view permission required");
        return report;
    }

    private boolean allowed(AiInspectReport report, Long userId) {
        List<String> ids = jdbc.queryForList("SELECT task_id FROM ai_inspect_report_task_binding WHERE report_id = ?", String.class, report.getId());
        if (ids.size() != 1) throw unavailable();
        String id = ids.get(0);
        var task = tasks.findById(id).orElseThrow(InspectReportAccessService::unavailable);
        if (!"user".equals(task.getSource())) throw unavailable();
        Integer workflowLinks = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM workflow_node_run WHERE task_id = ?) "
                + "+ (SELECT COUNT(*) FROM workflow_run_provenance_task WHERE task_id = ?) "
                + "+ (SELECT COUNT(*) FROM workflow_dispatch_approval_binding WHERE dispatch_task_id = ?)",
                Integer.class, id, id, id);
        if (workflowLinks == null || workflowLinks != 0) throw unavailable();
        var snapshot = snapshots.findComplete(id).orElseThrow(InspectReportAccessService::unavailable);
        Set<String> targets = snapshot.targets().stream().map(t -> t.agentId()).collect(Collectors.toSet());
        var taskJobs = jobs.findByTaskId(id);
        Set<String> jobTargets = taskJobs.stream().map(j -> j.getAgentId()).collect(Collectors.toSet());
        if (!Objects.equals(task.getCreatedBy(), report.getCreatedBy()) || targets.size() != snapshot.targets().size()
                || !Objects.equals(task.getTotalCount(), targets.size())
                || taskJobs.size() != targets.size() || !jobTargets.equals(targets)) throw unavailable();
        return snapshot.targets().stream().allMatch(t -> acl.allowedForGeneration(userId, t.agentId(), t.generation(), HostAclService.Permission.VIEW));
    }

    private static BusinessException unavailable() {
        return new BusinessException(503, "巡检报告不可用：无法验证报告的完整目标来源及主机权限");
    }
}
