package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.User;
import com.easyshell.server.model.vo.HostCredentialVO;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.HostProvisionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostManageToolSecurityTest {
    @Test
    void springCreatesComponentWithMultipleConstructors() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HostProvisionService.class, () -> mock(HostProvisionService.class));
            context.registerBean(UserRepository.class, () -> mock(UserRepository.class));
            context.register(HostManageTool.class);
            context.refresh();
            assertNotNull(context.getBean(HostManageTool.class));
        }
    }

    @Test
    void boundUsersRemainIsolatedAndAreRecheckedOnEveryCall() {
        var service = mock(HostProvisionService.class);
        var users = mock(UserRepository.class);
        var admin = new User();
        admin.setRole("super_admin");
        admin.setStatus(1);
        var viewer = new User();
        viewer.setRole("viewer");
        viewer.setStatus(1);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findById(2L)).thenReturn(Optional.of(viewer));
        var vo = HostCredentialVO.builder().id(3L).ip("ip").provisionStatus("pending").build();
        when(service.uninstall("a")).thenReturn(vo);
        var singleton = new HostManageTool(service, users);
        var first = singleton.forUser(1L, null);
        var second = singleton.forUser(2L, null);

        assertTrue(first.removeHost("a").contains("已提交"));
        assertTrue(second.removeHost("a").contains("拒绝"));
        assertTrue(singleton.removeHost("a").contains("拒绝"));
        admin.setRole("viewer");
        assertTrue(first.removeHost("a").contains("拒绝"));
        verify(users, times(2)).findById(1L);
        verify(users).findById(2L);
        verify(service, times(1)).uninstall("a");
    }

    @Test
    void missingDemotedDisabledAndDeletedUsersFailClosed() {
        var service = mock(HostProvisionService.class);
        var users = mock(UserRepository.class);
        var tool = new HostManageTool(service, users);
        assertTrue(tool.addHost("ip", 22, "u", "p").contains("拒绝"));
        assertTrue(tool.removeHost("a").contains("拒绝"));
        User user = new User();
        user.setRole("admin");
        when(users.findById(7L)).thenReturn(Optional.of(user), Optional.of(user), Optional.empty());
        var bound = tool.forUser(7L, null);
        assertTrue(bound.addHost("ip", 22, "u", "p").contains("拒绝"));
        user.setRole("super_admin");
        user.setStatus(0);
        assertTrue(bound.removeHost("a").contains("拒绝"));
        assertTrue(bound.removeHost("a").contains("拒绝"));
        verifyNoInteractions(service);
    }

    @Test
    void activeSuperAdminCanProvisionAndUninstallAndIsRechecked() {
        var service = mock(HostProvisionService.class);
        var users = mock(UserRepository.class);
        User user = new User();
        user.setRole("super_admin");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        var vo = HostCredentialVO.builder().id(3L).ip("ip").sshPort(22).provisionStatus("pending").build();
        when(service.provision(any())).thenReturn(vo);
        when(service.uninstall("a")).thenReturn(vo);
        var bound = new HostManageTool(service, users).forUser(7L, null);
        assertTrue(bound.addHost("ip", 22, "u", "p").contains("已提交"));
        assertTrue(bound.removeHost("a").contains("已提交"));
        verify(service).startProvisionAsync(3L);
        verify(service).startUninstallAsync(3L);
        user.setRole("viewer");
        assertTrue(bound.removeHost("a").contains("拒绝"));
        verify(service, times(1)).uninstall("a");
    }
}
