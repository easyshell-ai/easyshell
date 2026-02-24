package com.easyshell.server.ai.orchestrator;

import com.easyshell.server.ai.agent.AgentDefinition;
import com.easyshell.server.ai.agent.AgentDefinitionRepository;
import com.easyshell.server.ai.agent.ReviewerAgent;
import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.service.ChatModelFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PlanExecutor {

    private final OrchestratorEngine orchestratorEngine;
    private final ChatModelFactory chatModelFactory;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final AgenticConfigService agenticConfigService;
    private final MessageSource messageSource;
    private final ReviewerAgent reviewerAgent;
    private final DagExecutor dagExecutor;
    private final ExecutorService parallelExecutor;

    public PlanExecutor(
            @Lazy OrchestratorEngine orchestratorEngine,
            ChatModelFactory chatModelFactory,
            AgentDefinitionRepository agentDefinitionRepository,
            AgenticConfigService agenticConfigService,
            MessageSource messageSource,
            ReviewerAgent reviewerAgent,
            DagExecutor dagExecutor) {
        this.orchestratorEngine = orchestratorEngine;
        this.chatModelFactory = chatModelFactory;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.agenticConfigService = agenticConfigService;
        this.messageSource = messageSource;
        this.reviewerAgent = reviewerAgent;
        this.dagExecutor = dagExecutor;

        int maxParallel = agenticConfigService.getInt("ai.plan.max-parallel-tasks", 5);
        this.parallelExecutor = Executors.newFixedThreadPool(maxParallel, r -> {
            Thread t = new Thread(r, "plan-executor-parallel");
            t.setDaemon(true);
            return t;
        });
    }

    public void executePlan(ExecutionPlan plan, OrchestratorRequest request,
                            FluxSink<AgentEvent> sink, AtomicBoolean cancelled) {
        if (isDagPlan(plan)) {
            dagExecutor.executeDag(plan, request, sink, cancelled);
        } else {
            executeSimplePlan(plan, request, sink, cancelled);
        }

        if (agenticConfigService.getBoolean("ai.plan.summary-enabled", true)) {
            String summary = buildSummary(plan);
            sink.next(AgentEvent.planSummary(summary, plan));
        }

        boolean reviewEnabled = agenticConfigService.getBoolean("ai.review.enabled", true);
        boolean hasWriteOps = plan.getSteps().stream().anyMatch(s -> "execute".equals(s.getAgent()));
        boolean reviewAlways = agenticConfigService.getBoolean("ai.review.always", false);
        if (reviewEnabled && (hasWriteOps || reviewAlways)) {
            sink.next(AgentEvent.reviewStart());
            String lastUserMsg = request.getHistory() != null && !request.getHistory().isEmpty()
                    ? request.getHistory().get(request.getHistory().size() - 1).getText()
                    : null;
            String reviewResult = reviewerAgent.review(plan, lastUserMsg);
            sink.next(AgentEvent.reviewComplete(reviewResult != null ? reviewResult : i18n("ai.review.complete")));
        }
    }

    private boolean isDagPlan(ExecutionPlan plan) {
        return plan.getSteps().stream().anyMatch(step ->
                (step.getDependsOn() != null && !step.getDependsOn().isEmpty())
                        || step.getCondition() != null
                        || step.isCheckpoint());
    }

    private void executeSimplePlan(ExecutionPlan plan, OrchestratorRequest request,
                                   FluxSink<AgentEvent> sink, AtomicBoolean cancelled) {
        String failureStrategy = agenticConfigService.get("ai.plan.failure-strategy", "ask_user");
        int maxRetries = agenticConfigService.getInt("ai.plan.max-step-retries", 2);

        List<ExecutionPlan.PlanStep> steps = plan.getSteps();
        Map<Integer, List<ExecutionPlan.PlanStep>> grouped = groupSteps(steps);
        List<Integer> sortedKeys = new ArrayList<>(grouped.keySet());
        Collections.sort(sortedKeys);

        boolean aborted = false;

        for (Integer groupKey : sortedKeys) {
            if (cancelled.get() || aborted) break;

            List<ExecutionPlan.PlanStep> groupSteps = grouped.get(groupKey);

            if (groupKey == Integer.MIN_VALUE || groupSteps.size() == 1) {
                for (ExecutionPlan.PlanStep step : groupSteps) {
                    if (cancelled.get() || aborted) break;
                    boolean success = executeStepWithRetry(step, request, sink, cancelled, maxRetries);
                    if (!success) {
                        if ("abort".equals(failureStrategy) || "ask_user".equals(failureStrategy)) {
                            sink.next(AgentEvent.thinking(i18n("ai.plan.abort"), "system"));
                            aborted = true;
                        }
                    }
                }
            } else {
                aborted = !executeParallelGroup(groupKey, groupSteps, request, sink, cancelled, maxRetries, failureStrategy);
            }
        }
    }

    private Map<Integer, List<ExecutionPlan.PlanStep>> groupSteps(List<ExecutionPlan.PlanStep> steps) {
        Map<Integer, List<ExecutionPlan.PlanStep>> grouped = new LinkedHashMap<>();
        for (ExecutionPlan.PlanStep step : steps) {
            int key = step.getParallelGroup() != null ? step.getParallelGroup() : Integer.MIN_VALUE;
            if (key == Integer.MIN_VALUE) {
                grouped.computeIfAbsent(Integer.MIN_VALUE - step.getIndex(), k -> new ArrayList<>()).add(step);
            } else {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(step);
            }
        }
        return grouped;
    }

    private boolean executeParallelGroup(int group, List<ExecutionPlan.PlanStep> steps,
                                         OrchestratorRequest request, FluxSink<AgentEvent> sink,
                                         AtomicBoolean cancelled, int maxRetries, String failureStrategy) {
        sink.next(AgentEvent.parallelStart(group, steps.size()));
        AtomicInteger completed = new AtomicInteger(0);
        AtomicBoolean anyFailed = new AtomicBoolean(false);

        CompletableFuture<?>[] futures = steps.stream()
                .map(step -> CompletableFuture.runAsync(() -> {
                    boolean success = executeStepWithRetry(step, request, sink, cancelled, maxRetries);
                    int done = completed.incrementAndGet();
                    sink.next(AgentEvent.parallelProgress(group, done, steps.size()));
                    if (!success) anyFailed.set(true);
                }, parallelExecutor))
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).join();
        } catch (Exception e) {
            log.error("Parallel group {} execution error: {}", group, e.getMessage());
            anyFailed.set(true);
        }

        sink.next(AgentEvent.parallelComplete(group));

        if (anyFailed.get() && ("abort".equals(failureStrategy) || "ask_user".equals(failureStrategy))) {
            sink.next(AgentEvent.thinking(i18n("ai.plan.abort"), "system"));
            return false;
        }
        return true;
    }

    private boolean executeStepWithRetry(ExecutionPlan.PlanStep step, OrchestratorRequest request,
                                         FluxSink<AgentEvent> sink, AtomicBoolean cancelled, int maxRetries) {
        int attempts = 0;
        while (attempts <= maxRetries) {
            if (cancelled.get()) return false;

            if (attempts > 0) {
                sink.next(AgentEvent.stepRetry(step.getIndex(),
                        i18n("ai.step.retry", step.getIndex(), attempts, maxRetries)));
            }

            boolean success = executeSingleStep(step, request, sink, cancelled);
            if (success) return true;

            attempts++;
            if (attempts > maxRetries) {
                return false;
            }

            step.setStatus("pending");
            step.setError(null);
        }
        return false;
    }

    private boolean executeSingleStep(ExecutionPlan.PlanStep step, OrchestratorRequest request,
                                      FluxSink<AgentEvent> sink, AtomicBoolean cancelled) {
        step.setStatus("running");
        sink.next(AgentEvent.stepStart(step.getIndex(), step.getDescription(),
                step.getAgent() != null ? step.getAgent() : "execute"));

        Optional<AgentDefinition> agentOpt = Optional.empty();
        if (step.getAgent() != null) {
            agentOpt = agentDefinitionRepository.findByNameAndEnabledTrue(step.getAgent());
        }
        if (agentOpt.isEmpty()) {
            agentOpt = agentDefinitionRepository.findByNameAndEnabledTrue("execute");
        }
        if (agentOpt.isEmpty()) {
            step.setStatus("skipped");
            sink.next(AgentEvent.thinking(i18n("ai.step.skipped", step.getIndex()), "system"));
            return true;
        }

        AgentDefinition agentDef = agentOpt.get();
        try {
            String result = orchestratorEngine.executeAsSubAgent(agentDef, step.getDescription(), chatModelFactory);
            step.setResult(result);
            step.setStatus("completed");
            sink.next(AgentEvent.stepComplete(step.getIndex(), agentDef.getName()));
            return true;
        } catch (Exception e) {
            log.error("Step {} execution failed: {}", step.getIndex(), e.getMessage());
            step.setStatus("failed");
            step.setError(e.getMessage());
            sink.next(AgentEvent.thinking(
                    i18n("ai.step.failed", step.getIndex(), e.getMessage()), "system"));
            return false;
        }
    }

    private String buildSummary(ExecutionPlan plan) {
        long completed = plan.getSteps().stream().filter(s -> "completed".equals(s.getStatus())).count();
        long failed = plan.getSteps().stream().filter(s -> "failed".equals(s.getStatus())).count();
        long skipped = plan.getSteps().stream().filter(s -> "skipped".equals(s.getStatus())).count();
        return String.format("%d/%d steps completed, %d failed, %d skipped",
                completed, plan.getSteps().size(), failed, skipped);
    }

    private String i18n(String key, Object... args) {
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
    }
}
