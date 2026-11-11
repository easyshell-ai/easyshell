package com.easyshell.server.ai.service;

import com.easyshell.server.ai.agent.DelegateTaskTool;
import com.easyshell.server.ai.agent.GetTaskResultTool;
import com.easyshell.server.ai.orchestrator.OrchestratorEngine;
import com.easyshell.server.ai.tool.HostListTool;
import com.easyshell.server.ai.tool.HostTagTool;
import com.easyshell.server.ai.tool.MonitoringTool;
import com.easyshell.server.ai.tool.ScheduledTaskTool;
import com.easyshell.server.ai.tool.ClusterManageTool;
import com.easyshell.server.ai.tool.AuditQueryTool;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.common.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatServiceOrchestratorEngineBindingTest {

    @ParameterizedTest
    @ValueSource(classes = {AiChatService.class, OrchestratorEngine.class})
    void toolsAreBoundForEachRequest(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        Map<Class<?>, Object> dependencies = new HashMap<>();
        Object[] arguments = new Object[constructor.getParameterCount()];
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            arguments[i] = dependencies.computeIfAbsent(parameterTypes[i],
                    dependency -> mock(dependency, Answers.RETURNS_SELF));
        }
        Object instance = constructor.newInstance(arguments);
        Method getAllTools = type.getDeclaredMethod("getAllTools", Long.class, String.class);
        getAllTools.setAccessible(true);

        Object[] first = (Object[]) getAllTools.invoke(instance, 11L, "192.0.2.11");
        Object[] second = (Object[]) getAllTools.invoke(instance, 22L, "192.0.2.22");

        assertThat(first).contains(dependencies.get(HostListTool.class), dependencies.get(HostTagTool.class),
                dependencies.get(MonitoringTool.class), dependencies.get(ScheduledTaskTool.class),
                dependencies.get(GetTaskResultTool.class), dependencies.get(DelegateTaskTool.class));
        assertThat(second).hasSameSizeAs(first);
        for (long userId : new long[]{11L, 22L}) {
            String ip = userId == 11L ? "192.0.2.11" : "192.0.2.22";
            verify((HostListTool) dependencies.get(HostListTool.class)).forUser(userId, ip);
            verify((HostTagTool) dependencies.get(HostTagTool.class)).forUser(userId, ip);
            verify((MonitoringTool) dependencies.get(MonitoringTool.class)).forUser(userId, ip);
            verify((ScheduledTaskTool) dependencies.get(ScheduledTaskTool.class)).forUser(userId, ip);
            verify((GetTaskResultTool) dependencies.get(GetTaskResultTool.class)).forUser(userId, ip);
            verify((DelegateTaskTool) dependencies.get(DelegateTaskTool.class)).forUser(userId, ip);
            verify((ClusterManageTool) dependencies.get(ClusterManageTool.class)).forUser(userId, ip);
            verify((AuditQueryTool) dependencies.get(AuditQueryTool.class)).forUser(userId, ip);
        }
    }

    @ParameterizedTest
    @ValueSource(classes = {AiChatService.class, OrchestratorEngine.class})
    void rejectsAnyTargetWithoutViewPermission(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        Map<Class<?>, Object> dependencies = new HashMap<>();
        Object[] args = new Object[constructor.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = dependencies.computeIfAbsent(constructor.getParameterTypes()[i], MockitoType -> mock(MockitoType));
        }
        Object instance = constructor.newInstance(args);
        HostAclService acl = (HostAclService) dependencies.get(HostAclService.class);
        when(acl.allowed(11L, "allowed", HostAclService.Permission.VIEW)).thenReturn(true);
        Method check = type.getDeclaredMethod("requireTargetAccess", List.class, Long.class);
        check.setAccessible(true);
        check.invoke(instance, List.of("allowed"), 11L);
        assertThatThrownBy(() -> check.invoke(instance, List.of("allowed", "denied"), 11L))
                .isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(BusinessException.class);
        verify(acl).allowed(11L, "denied", HostAclService.Permission.VIEW);
    }
}
