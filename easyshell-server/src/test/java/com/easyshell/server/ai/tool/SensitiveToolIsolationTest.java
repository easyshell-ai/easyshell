package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.agent.BackgroundTaskManager;
import com.easyshell.server.ai.agent.GetTaskResultTool;
import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.ai.service.AiSchedulerService;
import com.easyshell.server.ai.service.InspectReportAccessService;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.ScriptService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SensitiveToolIsolationTest {
    @Test void scriptsOnlyExposePublicOwnerOrAdmin() {
        ScriptService service = mock(ScriptService.class);
        UserRepository users = mock(UserRepository.class);
        ScriptRepository scripts = mock(ScriptRepository.class);
        Script secret = new Script(); secret.setId(1L); secret.setName("secret"); secret.setContent("secret-content"); secret.setIsPublic(false); secret.setCreatedBy(2L);
        Script own = new Script(); own.setId(2L); own.setName("own"); own.setIsPublic(false); own.setCreatedBy(1L);
        when(service.findAll()).thenReturn(List.of(secret, own));
        when(scripts.findById(1L)).thenReturn(Optional.of(secret));
        User actor = new User(); actor.setId(1L); actor.setRole("operator");
        when(users.findById(1L)).thenReturn(Optional.of(actor));
        ScriptManageTool tool = new ScriptManageTool(service, new com.easyshell.server.security.ScriptAccess(scripts, users)).forUser(1L, null);
        assertThat(tool.listScripts()).contains("own").doesNotContain("secret");
        assertThat(tool.getScriptDetail(1L)).doesNotContain("secret-content");
        User admin = new User(); admin.setId(1L); admin.setRole("admin");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        assertThat(tool.getScriptDetail(1L)).contains("secret-content");
    }

    @Test void scheduledTaskCannotTriggerAnotherOwnersTask() {
        AiScheduledTaskRepository tasks = mock(AiScheduledTaskRepository.class);
        AiInspectReportRepository reports = mock(AiInspectReportRepository.class);
        AiSchedulerService scheduler = mock(AiSchedulerService.class);
        UserRepository users = mock(UserRepository.class);
        AiScheduledTask task = new AiScheduledTask(); task.setCreatedBy(2L);
        when(tasks.findById(7L)).thenReturn(Optional.of(task));
        ScheduledTaskTool tool = new ScheduledTaskTool(tasks, reports, scheduler, users, mock(InspectReportAccessService.class));
        assertThat(tool.triggerScheduledTask(7L)).contains("拒绝");
        assertThat(tool.forUser(1L, null).triggerScheduledTask(7L)).contains("无权");
        verifyNoInteractions(scheduler);
    }

    @Test void reportsFilterBeforeApplyingLimit() {
        AiScheduledTaskRepository tasks = mock(AiScheduledTaskRepository.class);
        AiInspectReportRepository reports = mock(AiInspectReportRepository.class);
        AiInspectReport own = new AiInspectReport(); own.setCreatedBy(1L); own.setTaskName("own-report");
        InspectReportAccessService access = mock(InspectReportAccessService.class);
        when(access.page(1L, false, 0, 1)).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(own), org.springframework.data.domain.PageRequest.of(0, 1), 3));
        ScheduledTaskTool tool = new ScheduledTaskTool(tasks, reports, mock(AiSchedulerService.class), mock(UserRepository.class), access);
        assertThat(tool.forUser(1L, null).getInspectReports(1))
                .contains("own-report", "共 3 条，显示 1 条").doesNotContain("secret-report");
        verify(access, times(1)).page(1L, false, 0, 1);
    }

    @Test void reportQueryFailureIsNotPresentedAsEmpty() {
        InspectReportAccessService access = mock(InspectReportAccessService.class);
        when(access.page(1L, false, 0, 10)).thenThrow(new IllegalStateException("database unavailable"));
        ScheduledTaskTool tool = new ScheduledTaskTool(mock(AiScheduledTaskRepository.class),
                mock(AiInspectReportRepository.class), mock(AiSchedulerService.class), mock(UserRepository.class), access);
        assertThat(tool.forUser(1L, null).getInspectReports(10))
                .contains("错误", "不可用").doesNotContain("暂无", "database unavailable");
        verify(access, times(1)).page(1L, false, 0, 10);
    }

    @Test void hostReadsFilterAclAndTagWritesRequireAdmin() {
        AgentRepository agents = mock(AgentRepository.class);
        AgentTagRepository agentTags = mock(AgentTagRepository.class);
        TagRepository tags = mock(TagRepository.class);
        HostAclService acl = mock(HostAclService.class);
        UserRepository users = mock(UserRepository.class);
        Agent visible = new Agent(); visible.setId("visible"); visible.setHostname("visible-host"); visible.setStatus(1);
        Agent hidden = new Agent(); hidden.setId("hidden"); hidden.setHostname("hidden-host");
        when(agents.findAll()).thenReturn(List.of(visible, hidden));
        when(acl.allowed(1L, "visible", HostAclService.Permission.VIEW)).thenReturn(true);
        HostListTool list = new HostListTool(agents, agentTags, tags, acl, users);
        assertThat(list.listHosts()).contains("拒绝");
        assertThat(list.forUser(1L, null).listHosts()).contains("visible-host").doesNotContain("hidden-host");
        HostTagTool tag = new HostTagTool(agents, tags, agentTags, acl, users).forUser(1L, null);
        assertThat(tag.getHostTags("hidden")).contains("无权");
        assertThat(tag.addTagToHost("visible", "test")).contains("无权");
        verifyNoInteractions(tags);
    }

    @Test void resultToolNeverQueriesWithoutPrincipalAndPassesBoundIdentity() {
        BackgroundTaskManager manager = mock(BackgroundTaskManager.class);
        GetTaskResultTool tool = new GetTaskResultTool(manager);
        assertThat(tool.getTaskResult("task")).contains("错误");
        verifyNoInteractions(manager);
        assertThat(tool.forUser(42L, null).getTaskResult("task")).contains("未找到");
        verify(manager).getTask("task", 42L);
    }
}
