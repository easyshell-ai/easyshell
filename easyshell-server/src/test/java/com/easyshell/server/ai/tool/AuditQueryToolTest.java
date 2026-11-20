package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditQueryToolTest {
    @Test
    void checksCurrentDatabaseRoleAndStatusOnEveryCall() {
        AuditLogService audit = mock(AuditLogService.class);
        UserRepository users = mock(UserRepository.class);
        AuditQueryTool root = new AuditQueryTool(audit, users);
        User admin = new User();
        admin.setRole("super_admin");
        admin.setStatus(1);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(audit.findAll(any(Pageable.class))).thenReturn(Page.empty());
        AuditQueryTool bound = root.forUser(1L, null);
        assertThat(bound.queryAuditLogs(null, 20)).contains("暂无");
        admin.setRole("admin");
        assertThat(bound.queryAuditLogs(null, 20)).contains("无权");
        admin.setRole("super_admin");
        admin.setStatus(0);
        assertThat(bound.queryAuditLogs(null, 20)).contains("无权");
        assertThat(root.queryAuditLogs(null, 20)).contains("无权");
        assertThat(root.forUser(2L, null).queryAuditLogs(null, 20)).contains("无权");
        when(users.findById(1L)).thenThrow(new IllegalStateException());
        assertThat(bound.queryAuditLogs(null, 20)).contains("无权");
        verify(audit, times(1)).findAll(any(Pageable.class));
        verify(audit, never()).findByAction(any(), any());
    }
}
