package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.service.DispatchAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduledTaskEvidenceVerifier — 定时巡检授权证据")
class ScheduledTaskEvidenceVerifierTest {

    @Mock
    private AiScheduledTaskRepository scheduledTaskRepository;

    private ScheduledTaskEvidenceVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ScheduledTaskEvidenceVerifier(scheduledTaskRepository);
    }

    @Test
    @DisplayName("supports() = USER_SCHEDULED_TASK")
    void supportsUserScheduledTask() {
        assertThat(verifier.supports()).isEqualTo(DispatchAuthority.USER_SCHEDULED_TASK);
    }

    @Test
    @DisplayName("任务行存在且 createdBy 与派发用户一致 → 证据成立")
    void ownTask_verifies() {
        AiScheduledTask task = new AiScheduledTask();
        task.setId(7L);
        task.setCreatedBy(42L);
        when(scheduledTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        assertThat(verifier.verify("7", "df -h", 42L)).isEqualTo("ai_scheduled_task:7");
    }

    @Test
    @DisplayName("换用户（createdBy 不匹配）→ 证据不成立")
    void otherUsersTask_failsVerification() {
        AiScheduledTask task = new AiScheduledTask();
        task.setId(7L);
        task.setCreatedBy(99L);
        when(scheduledTaskRepository.findById(7L)).thenReturn(Optional.of(task));

        assertThat(verifier.verify("7", "df -h", 42L)).isNull();
    }

    @Test
    @DisplayName("换任务（行不存在）/ 非法引用 → 证据不成立")
    void missingOrMalformedRef_failsVerification() {
        when(scheduledTaskRepository.findById(7L)).thenReturn(Optional.empty());
        assertThat(verifier.verify("7", "df -h", 42L)).isNull();
        assertThat(verifier.verify("not-a-number", "df -h", 42L)).isNull();
        assertThat(verifier.verify(null, "df -h", 42L)).isNull();
    }
}
