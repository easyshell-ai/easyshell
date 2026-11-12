package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.orchestrator.PlanConfirmationManager;
import com.easyshell.server.ai.repository.AiIterationMessageRepository;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.ai.service.AiExecutionService;
import com.easyshell.server.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiChatPlanDecisionTest {
    private final AiChatService chat = mock(AiChatService.class);
    private final PlanConfirmationManager manager = new PlanConfirmationManager();
    private final AiChatController controller = new AiChatController(chat, mock(AiExecutionService.class), manager,
            mock(AiIterationMessageRepository.class));
    private final Authentication auth = mock(Authentication.class);

    @Test
    void missingAndStaleIdsFailClosedAndOwnerIsChecked() {
        when(auth.getPrincipal()).thenReturn(1L);
        var old = manager.waitForConfirmation("s", 10);
        old.future().cancel(false);
        var current = manager.waitForConfirmation("s", 10);
        assertThatThrownBy(() -> controller.confirmPlan("s", null, auth))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> controller.confirmPlan("s", new AiChatController.PlanDecisionRequest(old.planId()), auth))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(current.future()).isNotDone();
        verify(chat, times(2)).requireSessionOwner("s", 1L);
        controller.rejectPlan("s", new AiChatController.PlanDecisionRequest(current.planId()), auth);
        assertThat(current.future().join()).isFalse();
    }

    @Test
    void ownerFailurePreventsDecision() {
        when(auth.getPrincipal()).thenReturn(2L);
        doThrow(new BusinessException(403, "denied")).when(chat).requireSessionOwner("s", 2L);
        var plan = manager.waitForConfirmation("s", 10);
        assertThatThrownBy(() -> controller.confirmPlan("s", new AiChatController.PlanDecisionRequest(plan.planId()), auth))
                .isInstanceOf(BusinessException.class);
        assertThat(plan.future()).isNotDone();
        plan.future().cancel(false);
    }
}
