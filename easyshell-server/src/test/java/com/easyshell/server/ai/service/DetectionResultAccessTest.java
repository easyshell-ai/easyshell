package com.easyshell.server.ai.service;

import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.entity.*;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DetectionResultAccessTest {
    @Test void exactBuiltinOwnerAndGenerationAreRequired() {
        var tasks=mock(TaskRepository.class);var snapshots=mock(TaskTargetSnapshotRepository.class);
        var jobs=mock(JobRepository.class);var nodes=mock(WorkflowNodeRunRepository.class);
        var queries=mock(TaskAccessQueryRepository.class);var identities=mock(HostIdentityService.class);
        var acl=mock(HostAclService.class);var users=mock(UserRepository.class);
        var access=new DetectionResultAccess(tasks,snapshots,jobs,nodes,queries,identities,acl,users);
        var user=new User();user.setStatus(1);when(users.findById(1L)).thenReturn(Optional.of(user));
        var task=new Task();task.setCreatedBy(1L);task.setTotalCount(1);task.setScriptContent(HostIntelligenceService.DETECTION_SCRIPT);
        when(tasks.findById("task")).thenReturn(Optional.of(task));
        when(snapshots.findComplete("task")).thenReturn(Optional.of(new TaskTargetSnapshot("task",List.of(new TaskTargetSnapshot.Target("host",2)))));
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host",2,true));
        when(acl.allowedForGeneration(eq(1L),eq("host"),eq(2L),any())).thenReturn(true);
        var job=new Job();job.setAgentId("host");when(jobs.findByTaskId("task")).thenReturn(List.of(job));
        assertDoesNotThrow(()->access.requireParse(1L,"task","host"));
        task.setCreatedBy(2L);assertThrows(com.easyshell.server.common.exception.BusinessException.class,()->access.requireParse(1L,"task","host"));
        task.setCreatedBy(1L);task.setScriptContent("echo ===DETECTION_START===");
        assertThrows(com.easyshell.server.common.exception.BusinessException.class,()->access.requireParse(1L,"task","host"));
        task.setScriptContent(HostIntelligenceService.DETECTION_SCRIPT);
        task.setSource("workflow");
        assertThrows(com.easyshell.server.common.exception.BusinessException.class,()->access.requireParse(1L,"task","host"));
        task.setSource("user");
        when(identities.lock("host")).thenReturn(new HostIdentityService.Identity("host",3,true));
        assertThrows(com.easyshell.server.common.exception.BusinessException.class,()->access.requireParse(1L,"task","host"));
    }
}
