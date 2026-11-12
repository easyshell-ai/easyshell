package com.easyshell.server.ai.service;

import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.service.DispatchAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @DisplayName("未绑定最终脚本和目标时，任务引用不能豁免风险门禁")
    void taskReferenceCannotAuthorizeUnboundIntent() {
        assertThat(verifier.verify("7", "df -h", 42L)).isNull();
        assertThat(verifier.verify("7", "different script", 42L)).isNull();
        verifyNoInteractions(scheduledTaskRepository);
    }

    @Test
    @DisplayName("换任务（行不存在）/ 非法引用 → 证据不成立")
    void missingOrMalformedRef_failsVerification() {
        assertThat(verifier.verify("7", "df -h", 42L)).isNull();
        assertThat(verifier.verify("not-a-number", "df -h", 42L)).isNull();
        assertThat(verifier.verify(null, "df -h", 42L)).isNull();
    }
}
