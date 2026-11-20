package com.easyshell.server.ai.orchestrator;

import com.easyshell.server.ai.adaptive.AdaptivePromptBuilder;
import com.easyshell.server.ai.adaptive.TaskClassifier;
import com.easyshell.server.ai.adaptive.TaskType;
import com.easyshell.server.ai.adaptive.ToolSetSelector;
import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.learning.SopRetriever;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.service.ChatModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrchestratorEngineConfirmationTest {
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final PlanConfirmationManager confirmations = new PlanConfirmationManager();
    private OrchestratorEngine engine;
    private PlanExecutor executor;
    private OrchestratorRequest request;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> constructor = OrchestratorEngine.class.getDeclaredConstructors()[0];
        dependencies.put(PlanConfirmationManager.class, confirmations);
        Object[] args = new Object[constructor.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = dependencies.computeIfAbsent(constructor.getParameterTypes()[i], type -> mock(type));
        }
        engine = (OrchestratorEngine) constructor.newInstance(args);
        ChatModel model = mock(ChatModel.class);
        dependencies.put(ChatModel.class, model);
        when(((ChatModelFactory) dependencies.get(ChatModelFactory.class)).getChatModel(any(), any())).thenReturn(model);
        executor = (PlanExecutor) dependencies.get(PlanExecutor.class);
        AgenticConfigService config = (AgenticConfigService) dependencies.get(AgenticConfigService.class);
        when(config.getBoolean(eq("ai.sop.enabled"), anyBoolean())).thenReturn(true);
        when(config.getInt(eq("ai.plan.confirmation-timeout"), anyInt())).thenReturn(1);
        when(((TaskClassifier) dependencies.get(TaskClassifier.class)).classify(anyString())).thenReturn(TaskType.EXECUTE);
        when(((AdaptivePromptBuilder) dependencies.get(AdaptivePromptBuilder.class)).buildPrompt(any(), nullable(String.class), nullable(String.class))).thenReturn("system");
        when(((ToolSetSelector) dependencies.get(ToolSetSelector.class)).selectTools(any(), any())).thenReturn(new ToolCallback[0]);
        AiSopTemplate sop = new AiSopTemplate();
        ExecutionPlan plan = ExecutionPlan.builder().requiresConfirmation(true)
                .steps(List.of(ExecutionPlan.PlanStep.builder().description("test").build())).build();
        SopRetriever retriever = (SopRetriever) dependencies.get(SopRetriever.class);
        when(retriever.findMatchingSop(anyString(), anyLong())).thenReturn(Optional.of(sop));
        when(retriever.sopToPlan(sop)).thenReturn(plan);
        request = new OrchestratorRequest();
        request.setSessionId("session");
        request.setUserId(1L);
        request.setEnableTools(false);
        request.setHistory(List.of(new UserMessage("execute")));
    }

    @Test
    void immediateApprovalExecutesOnceEvenWhenPlanningSkipped() {
        request.setSkipPlanning(true);
        List<AgentEvent> events = engine.process(request).doOnNext(event -> {
            if (event.getType() == AgentEvent.Type.PLAN_AWAIT_CONFIRMATION) confirmations.confirm("session", event.getPlanId());
        }).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(AgentEvent::getType).contains(AgentEvent.Type.PLAN, AgentEvent.Type.PLAN_CONFIRMED);
        verify(executor, times(1)).executePlan(any(), same(request), any(), any());
    }

    @Test
    void rejectionNeverExecutesOrFallsBack() {
        engine.process(request).doOnNext(event -> {
            if (event.getType() == AgentEvent.Type.PLAN_AWAIT_CONFIRMATION) confirmations.reject("session", event.getPlanId());
        }).collectList().block(Duration.ofSeconds(5));
        verifyNoInteractions(executor);
        verifyNoInteractions(dependencies.get(org.springframework.ai.chat.model.ChatModel.class));
    }

    @Test
    void timeoutNeverExecutes() {
        List<AgentEvent> events = engine.process(request).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(AgentEvent::getType).contains(AgentEvent.Type.DONE);
        verifyNoInteractions(executor);
    }

    @Test
    void cancellationNeverExecutes() throws Exception {
        CountDownLatch awaiting = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> planId = new java.util.concurrent.atomic.AtomicReference<>();
        Disposable subscription = engine.process(request).subscribe(event -> {
            if (event.getType() == AgentEvent.Type.PLAN_AWAIT_CONFIRMATION) {
                planId.set(event.getPlanId());
                awaiting.countDown();
            }
        });
        assertThat(awaiting.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        assertThat(confirmations.confirm("session", planId.get())).isFalse();
        verifyNoInteractions(executor);
    }
}
