package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.ai.repository.InspectReportQueryRepository;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectReportAccessServiceTest {
    @Test void requiresCompleteProvenanceAndGenerationViewEvenForAdmin() {
        var reports = mock(AiInspectReportRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var tasks = mock(TaskRepository.class);
        var jobs = mock(JobRepository.class);
        var snapshots = mock(TaskTargetSnapshotRepository.class);
        var acl = mock(HostAclService.class);
        var users = mock(UserRepository.class);
        var admin = new User(); admin.setRole("admin"); admin.setStatus(1);
        when(users.findById(2L)).thenReturn(Optional.of(admin));
        var service = new InspectReportAccessService(reports, jdbc, tasks, jobs, snapshots, acl, mock(InspectReportQueryRepository.class), users);
        var report = new AiInspectReport(); report.setId(7L); report.setCreatedBy(1L);
        when(reports.findById(7L)).thenReturn(Optional.of(report));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(List.of("task"));
        var task = new Task(); task.setCreatedBy(1L); task.setTotalCount(1);
        when(tasks.findById("task")).thenReturn(Optional.of(task));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("task"), eq("task"), eq("task"))).thenReturn(0);
        when(snapshots.findComplete("task")).thenReturn(Optional.of(new TaskTargetSnapshot("task", List.of(new TaskTargetSnapshot.Target("host", 3)))));
        var job = new Job(); job.setAgentId("host");
        when(jobs.findByTaskId("task")).thenReturn(List.of(job));
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        when(acl.allowedForGeneration(2L, "host", 3, HostAclService.Permission.VIEW)).thenReturn(true);
        assertThat(service.detail(7L, 2L, true)).isSameAs(report);
        admin.setRole("viewer");
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        admin.setRole("admin"); admin.setStatus(0);
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        admin.setStatus(1);
        task.setSource("workflow");
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        task.setSource(null);
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        task.setSource("user");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("task"), eq("task"), eq("task"))).thenReturn(1);
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("task"), eq("task"), eq("task"))).thenReturn(0);
        when(jobs.findByTaskId("task")).thenReturn(List.of());
        assertThatThrownBy(() -> service.detail(7L, 2L, true)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
    }
}
