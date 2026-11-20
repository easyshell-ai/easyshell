package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.service.AiExecutionService;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.service.ScriptService;
import com.easyshell.server.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolPrincipalIsolationTest {
    @Test
    void concurrentCallbacksKeepTheirBoundIdentityAndSchemaDoesNotExposeIt() throws Exception {
        TaskService service = mock(TaskService.class);
        when(service.listTasks(anyLong())).thenReturn(List.of());
        TaskManageTool prototype = new TaskManageTool(service, mock(AgentRepository.class), mock(SystemConfigRepository.class));
        var first = ToolCallbacks.from(prototype.forUser(41L, "one"));
        var second = ToolCallbacks.from(prototype.forUser(42L, "two"));
        var a = java.util.Arrays.stream(first).filter(c -> c.getToolDefinition().name().equals("listRecentTasks")).findFirst().orElseThrow();
        var b = java.util.Arrays.stream(second).filter(c -> c.getToolDefinition().name().equals("listRecentTasks")).findFirst().orElseThrow();
        assertThat(a.getToolDefinition().inputSchema()).doesNotContain("userId", "sourceIp");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> one = CompletableFuture.runAsync(() -> { await(start); a.call("{}"); });
        CompletableFuture<Void> two = CompletableFuture.runAsync(() -> { await(start); b.call("{}"); });
        start.countDown();
        CompletableFuture.allOf(one, two).get();
        verify(service).listTasks(41L);
        verify(service).listTasks(42L);
        assertThat(prototype.listRecentTasks()).contains("拒绝");
    }

    @Test
    void missingIdentityNeverDispatchesOrReadsScripts() {
        AiExecutionService execution = mock(AiExecutionService.class);
        AgentRepository agents = mock(AgentRepository.class);
        ScriptService scripts = mock(ScriptService.class);
        assertThat(new ScriptExecuteTool(execution, agents).executeScript("echo x", List.of("a"), "x", null)).contains("拒绝");
        assertThat(new SoftwareDetectTool(agents, execution).detectSoftware("a")).contains("拒绝");
        assertThat(new ScriptManageTool(scripts, org.mockito.Mockito.mock(com.easyshell.server.security.ScriptAccess.class)).listScripts()).contains("拒绝");
        verifyNoInteractions(execution, agents, scripts);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
