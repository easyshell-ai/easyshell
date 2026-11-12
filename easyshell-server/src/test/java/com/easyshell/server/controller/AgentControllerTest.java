package com.easyshell.server.controller;

import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.dto.TaskTargetSnapshot.Target;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.security.JwtAuthenticationFilter;
import com.easyshell.server.model.dto.AgentRegisterRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import com.easyshell.server.service.AgentService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.SystemConfigService;
import com.easyshell.server.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.easyshell.server.common.exception.BusinessException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentControllerTest {
    private final JobRepository jobs = mock(JobRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskTargetSnapshotRepository snapshots = mock(TaskTargetSnapshotRepository.class);
    private final HostAclService acl = mock(HostAclService.class);
    private final AgentController controller = new AgentController(mock(AgentService.class), mock(TaskService.class),
            jobs, mock(SystemConfigService.class), mock(JwtUtils.class), tasks, snapshots, acl);
    private final UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken("host", null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));

    @Test
    void stringPrincipalWithoutAgentAuthorityCannotAccessAgentEndpoints() {
        var user = new UsernamePasswordAuthenticationToken("host", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThrows(BusinessException.class, () -> controller.getPendingJobs("host", user));
        assertThrows(BusinessException.class, () -> controller.getAgentConfig(user));
        verifyNoInteractions(jobs);
    }

    @Test
    void unauthenticatedAgentPrincipalCannotAccessAgentEndpoints() {
        var unauthenticated = new UsernamePasswordAuthenticationToken("host", null);
        assertThrows(BusinessException.class, () -> controller.getPendingJobs("host", unauthenticated));
        verifyNoInteractions(jobs);
    }

    @Test
    void boundCredentialCannotExchangeForLegacyTokenEvenWithRegistrationSecret() {
        var request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_BOUND_AGENT_TOKEN, true);
        request.addHeader("X-Agent-Register-Secret", "secret");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "registerSecret", "secret");
        assertThrows(BusinessException.class, () -> controller.register(new AgentRegisterRequest(), auth, request));
        assertThrows(BusinessException.class, () -> controller.register(new AgentRegisterRequest(), null, request));
        request.removeAttribute(JwtAuthenticationFilter.ATTR_BOUND_AGENT_TOKEN);
        request.addHeader("Authorization", "Bearer expired-token");
        assertThrows(BusinessException.class, () -> controller.register(new AgentRegisterRequest(), null, request));
    }

    private Job pending() {
        Job job = new Job();
        job.setId("job");
        job.setTaskId("task");
        job.setAgentId("host");
        Task task = new Task();
        task.setCreatedBy(42L);
        task.setTotalCount(1);
        when(jobs.findByAgentIdAndStatus("host", 0)).thenReturn(List.of(job));
        when(jobs.findByTaskId("task")).thenReturn(List.of(job));
        when(tasks.findById("task")).thenReturn(Optional.of(task));
        return job;
    }

    private void snapshot(String agentId, long generation) {
        when(snapshots.findComplete("task")).thenReturn(Optional.of(
                new TaskTargetSnapshot("task", List.of(new Target(agentId, generation)))));
    }

    @Test
    void recreatedHostWithNewGrantCannotReceiveOldGenerationJob() {
        pending();
        snapshot("host", 1);
        when(acl.allowedForGeneration(42L, "host", 2, HostAclService.Permission.EXECUTE)).thenReturn(true);
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        verify(acl).allowedForGeneration(42L, "host", 1, HostAclService.Permission.EXECUTE);
        verify(acl, never()).allowedForGeneration(42L, "host", 2, HostAclService.Permission.EXECUTE);
    }

    @Test
    void sameGenerationAllowsJob() {
        Job job = pending();
        snapshot("host", 1);
        when(acl.allowedForGeneration(42L, "host", 1, HostAclService.Permission.EXECUTE)).thenReturn(true);
        assertEquals(List.of(job), controller.getPendingJobs("host", auth).getData());
    }

    @Test
    void missingOrWrongTargetSnapshotFailsClosed() {
        pending();
        when(snapshots.findComplete("task")).thenReturn(Optional.empty());
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        snapshot("other", 1);
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        verifyNoInteractions(acl);
    }

    @Test
    void snapshotOrAclExceptionFailsClosed() {
        pending();
        when(snapshots.findComplete("task")).thenThrow(new IllegalStateException());
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        doReturn(Optional.of(new TaskTargetSnapshot("task", List.of(new Target("host", 1)))))
                .when(snapshots).findComplete("task");
        when(acl.allowedForGeneration(42L, "host", 1, HostAclService.Permission.EXECUTE))
                .thenThrow(new IllegalStateException());
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
    }

    @Test
    void missingTaskOrCreatorAndTaskLookupFailureFailClosed() {
        pending();
        when(tasks.findById("task")).thenReturn(Optional.empty());
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        when(tasks.findById("task")).thenReturn(Optional.of(new Task()));
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        when(tasks.findById("task")).thenThrow(new IllegalStateException());
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        verifyNoInteractions(snapshots, acl);
    }

    private Job otherJob(String id, String taskId, String agentId) {
        Job job = new Job();
        job.setId(id);
        job.setTaskId(taskId);
        job.setAgentId(agentId);
        return job;
    }

    private Job multiHost() {
        Job job = pending();
        Task task = new Task();
        task.setCreatedBy(42L);
        task.setTotalCount(2);
        when(tasks.findById("task")).thenReturn(Optional.of(task));
        when(snapshots.findComplete("task")).thenReturn(Optional.of(new TaskTargetSnapshot("task",
                List.of(new Target("host", 1), new Target("other", 2)))));
        when(acl.allowedForGeneration(42L, "host", 1, HostAclService.Permission.EXECUTE)).thenReturn(true);
        return job;
    }

    @Test
    void completeMultiHostJobsAllowPendingJob() {
        Job job = multiHost();
        when(jobs.findByTaskId("task")).thenReturn(List.of(job, otherJob("job2", "task", "other")));
        assertEquals(List.of(job), controller.getPendingJobs("host", auth).getData());
        verify(acl).allowedForGeneration(42L, "host", 1, HostAclService.Permission.EXECUTE);
    }

    @Test
    void missingDuplicateForeignAndWrongTaskJobsFailClosed() {
        Job job = multiHost();
        List<List<Job>> invalid = List.of(
                List.of(job),
                List.of(job, otherJob("job2", "task", "host")),
                List.of(job, otherJob("job2", "task", "foreign")),
                List.of(job, otherJob("job2", "wrong", "other")),
                List.of(job, otherJob("job", "task", "other")),
                List.of(otherJob("different", "task", "host"), otherJob("job2", "task", "other")));
        for (List<Job> taskJobs : invalid) {
            when(jobs.findByTaskId("task")).thenReturn(taskJobs);
            assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        }
        verifyNoInteractions(acl);
    }

    @Test
    void snapshotCountMismatchAndJobLookupFailureFailClosed() {
        multiHost();
        snapshot("host", 1);
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        when(snapshots.findComplete("task")).thenReturn(Optional.of(new TaskTargetSnapshot("task",
                List.of(new Target("host", 1), new Target("other", 2)))));
        when(jobs.findByTaskId("task")).thenThrow(new IllegalStateException("sensitive detail"));
        assertTrue(controller.getPendingJobs("host", auth).getData().isEmpty());
        verifyNoInteractions(acl);
    }
}
