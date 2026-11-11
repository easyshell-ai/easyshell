package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.dto.AiScheduledTaskRequest;
import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.ai.service.AiAlertResponder;
import com.easyshell.server.ai.service.AiSchedulerService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiScheduledTaskControllerTest {
    @Mock AiScheduledTaskRepository repository;
    @Mock AiSchedulerService scheduler;
    @Mock AiAlertResponder responder;
    @Mock AuditLogService audit;
    AiScheduledTaskController controller;
    MockHttpServletRequest request;

    @BeforeEach
    void setup() {
        controller = new AiScheduledTaskController(repository, scheduler, responder, audit);
        request = new MockHttpServletRequest();
    }

    private Authentication auth(long id, String role) {
        return new UsernamePasswordAuthenticationToken(id, "user", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private AiScheduledTask task(Long owner) {
        AiScheduledTask task = new AiScheduledTask();
        task.setId(7L);
        task.setCreatedBy(owner);
        task.setName("original");
        return task;
    }

    private void invoke(String action, Authentication auth) {
        switch (action) {
            case "view" -> controller.getById(7L, auth, request);
            case "update" -> controller.update(7L, new AiScheduledTaskRequest(), auth, request);
            case "delete" -> controller.delete(7L, auth, request);
            case "enable" -> controller.enable(7L, auth, request);
            case "disable" -> controller.disable(7L, auth, request);
            case "run" -> controller.runNow(7L, auth, request);
            default -> throw new IllegalArgumentException(action);
        }
    }

    @ParameterizedTest
    @CsvSource({"view,VIEWER", "update,VIEWER", "delete,VIEWER", "enable,VIEWER", "disable,VIEWER", "run,VIEWER",
            "update,ADMIN", "enable,ADMIN", "run,ADMIN", "update,SUPER_ADMIN", "enable,SUPER_ADMIN", "run,SUPER_ADMIN"})
    void deniesCrossOwnerOperationsWithoutSideEffects(String action, String role) {
        AiScheduledTask task = task(1L);
        when(repository.findById(7L)).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> invoke(action, auth(2L, role)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThat(task.getName()).isEqualTo("original");
        assertThat(task.getEnabled()).isTrue();
        verify(repository, never()).save(any());
        verify(repository, never()).deleteById(anyLong());
        verifyNoInteractions(scheduler);
        verify(audit).log(eq(2L), anyString(), anyString(), eq("ai_scheduled_task"), eq("7"),
                eq("Access denied"), anyString(), eq("denied"));
    }

    @ParameterizedTest
    @CsvSource({"view,ADMIN", "delete,ADMIN", "disable,ADMIN", "view,SUPER_ADMIN", "delete,SUPER_ADMIN", "disable,SUPER_ADMIN",
            "view,VIEWER", "update,VIEWER", "delete,VIEWER", "enable,VIEWER", "disable,VIEWER", "run,VIEWER"})
    void permitsOwnerAndAdminEmergencyActions(String action, String role) {
        when(repository.findById(7L)).thenReturn(Optional.of(task(1L)));
        if (action.equals("update")) when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        invoke(action, auth(role.equals("VIEWER") ? 1L : 2L, role));
        if (action.equals("run")) verify(scheduler).executeScheduledTaskAsync(7L);
        if (action.equals("disable") || action.equals("delete")) verify(scheduler).cancelTask(7L);
    }

    @Test
    void listUsesOwnerQueryUnlessAdmin() {
        when(repository.findByCreatedByOrderByCreatedAtDesc(1L)).thenReturn(List.of(task(1L)));
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(task(1L), task(2L)));
        assertThat(controller.list(auth(1L, "VIEWER")).getData()).hasSize(1);
        verify(repository, never()).findAllByOrderByCreatedAtDesc();
        assertThat(controller.list(auth(2L, "ADMIN")).getData()).hasSize(2);
    }

    @Test
    void missingOrOwnerlessTaskFailsClosed() {
        when(repository.findById(7L)).thenReturn(Optional.of(task(null)), Optional.empty());
        assertThatThrownBy(() -> controller.runNow(7L, auth(1L, "VIEWER"), request))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> controller.getById(7L, auth(1L, "VIEWER"), request))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        verifyNoInteractions(scheduler);
    }

    @Test
    void createUsesAuthenticatedPrincipal() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        AiScheduledTaskRequest body = new AiScheduledTaskRequest();
        body.setEnabled(false);
        assertThat(controller.create(body, auth(5L, "VIEWER"), request).getData().getCreatedBy()).isEqualTo(5L);
    }
}
