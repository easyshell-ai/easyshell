package com.easyshell.server.ai.orchestrator;

import com.easyshell.server.ai.agent.AgentDefinition;
import com.easyshell.server.ai.agent.AgentDefinitionRepository;
import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.service.ChatModelFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class DagExecutor {

    private final OrchestratorEngine orchestratorEngine;
    private final ChatModelFactory chatModelFactory;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ConditionEvaluator conditionEvaluator;
    private final PlanConfirmationManager planConfirmationManager;
    private final AgenticConfigService agenticConfigService;
    private final MessageSource messageSource;
    private final ExecutorService dagExecutor;

    public DagExecutor(
            @Lazy OrchestratorEngine orchestratorEngine,
            ChatModelFactory chatModelFactory,
            AgentDefinitionRepository agentDefinitionRepository,
            ConditionEvaluator conditionEvaluator,
            PlanConfirmationManager planConfirmationManager,
            AgenticConfigService agenticConfigService,
            MessageSource messageSource) {
        this.orchestratorEngine = orchestratorEngine;
        this.chatModelFactory = chatModelFactory;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.conditionEvaluator = conditionEvaluator;
        this.planConfirmationManager = planConfirmationManager;
        this.agenticConfigService = agenticConfigService;
        this.messageSource = messageSource;

        int maxConcurrent = agenticConfigService.getInt("ai.dag.max-concurrent-steps", 5);
        this.dagExecutor = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "dag-executor");
            t.setDaemon(true);
            return t;
        });
    }

    public void executeDag(ExecutionPlan plan, OrchestratorRequest request,
                           FluxSink<AgentEvent> sink, AtomicBoolean cancelled) {
        List<ExecutionPlan.PlanStep> steps = plan.getSteps();

        // Kahn's algorithm: cycle detection via topological sort
        if (hasCycle(steps)) {
            sink.next(AgentEvent.error(i18n("ai.dag.cycle-detected")));
            return;
        }

        Map<Integer, ConditionEvaluator.StepState> stateMap = new ConcurrentHashMap<>();
        Map<String, String> variables = new ConcurrentHashMap<>();
        Map<Integer, String> stepStatusMap = new ConcurrentHashMap<>();
        for (ExecutionPlan.PlanStep step : steps) {
            stepStatusMap.put(step.getIndex(), "pending");
        }

        int defaultTimeout = agenticConfigService.getInt("ai.dag.step-timeout-sec", 300);

        while (!cancelled.get()) {
            List<ExecutionPlan.PlanStep> readySteps = findReadySteps(steps, stepStatusMap, stateMap);
            if (readySteps.isEmpty()) {
                break;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ExecutionPlan.PlanStep step : readySteps) {
                if (cancelled.get()) break;
                stepStatusMap.put(step.getIndex(), "running");
                step.setStatus("running");

                futures.add(CompletableFuture.runAsync(() ->
                        executeDagStep(step, request, sink, cancelled, stateMap,
                                variables, stepStatusMap, defaultTimeout), dagExecutor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                log.error("DAG execution error: {}", e.getMessage());
            }

            boolean shouldAbort = stepStatusMap.values().stream()
                    .anyMatch("aborted"::equals);
            if (shouldAbort) {
                sink.next(AgentEvent.thinking(i18n("ai.dag.abort"), "system"));
                break;
            }
        }
    }

    private void executeDagStep(ExecutionPlan.PlanStep step, OrchestratorRequest request,
                                FluxSink<AgentEvent> sink, AtomicBoolean cancelled,
                                Map<Integer, ConditionEvaluator.StepState> stateMap,
                                Map<String, String> variables, Map<Integer, String> stepStatusMap,
                                int defaultTimeout) {
        if (step.getCondition() != null && !step.getCondition().isBlank()) {
            boolean condMet = conditionEvaluator.evaluate(step.getCondition(), stateMap);
            sink.next(AgentEvent.stepConditionEval(step.getIndex(), step.getCondition(), condMet));
            if (!condMet) {
                step.setStatus("skipped");
                stepStatusMap.put(step.getIndex(), "skipped");
                stateMap.put(step.getIndex(), new ConditionEvaluator.StepState("skipped", null, null));
                return;
            }
        }

        // Checkpoint: wait for human approval before proceeding
        if (step.isCheckpoint()) {
            sink.next(AgentEvent.stepCheckpoint(step.getIndex(), step.getDescription()));
            int checkpointTimeout = agenticConfigService.getInt("ai.dag.checkpoint-timeout-sec", 600);
            try {
                CompletableFuture<Boolean> approval =
                        planConfirmationManager.waitForStepCheckpoint(
                                request.getSessionId(), step.getIndex(), checkpointTimeout);
                Boolean approved = approval.get(checkpointTimeout, TimeUnit.SECONDS);
                if (approved == null || !approved) {
                    step.setStatus("skipped");
                    stepStatusMap.put(step.getIndex(), "skipped");
                    stateMap.put(step.getIndex(), new ConditionEvaluator.StepState("skipped", null, null));
                    sink.next(AgentEvent.thinking(i18n("ai.dag.checkpoint.rejected"), "system"));
                    return;
                }
            } catch (TimeoutException e) {
                step.setStatus("skipped");
                stepStatusMap.put(step.getIndex(), "skipped");
                stateMap.put(step.getIndex(), new ConditionEvaluator.StepState("skipped", null, null));
                sink.next(AgentEvent.thinking(i18n("ai.dag.checkpoint.timeout"), "system"));
                return;
            } catch (Exception e) {
                log.error("Checkpoint wait error for step {}: {}", step.getIndex(), e.getMessage());
                step.setStatus("failed");
                stepStatusMap.put(step.getIndex(), "failed");
                stateMap.put(step.getIndex(), new ConditionEvaluator.StepState("failed", null, null));
                return;
            }
        }

        String substitutedDescription = substituteVariables(step.getDescription(), step.getInputVars(), variables);
        // Enrich with host context and tool hints via shared utility
        step.setDescription(substitutedDescription);
        String taskDescription = SubAgentPromptBuilder.buildSubAgentPrompt(step, request);

        sink.next(AgentEvent.stepStart(step.getIndex(), taskDescription,
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
            stepStatusMap.put(step.getIndex(), "skipped");
            stateMap.put(step.getIndex(), new ConditionEvaluator.StepState("skipped", null, null));
            return;
        }

        AgentDefinition agentDef = agentOpt.get();
        int timeout = step.getTimeoutSec() != null ? step.getTimeoutSec() : defaultTimeout;

        try {
            CompletableFuture<String> resultFuture = CompletableFuture.supplyAsync(
                    () -> orchestratorEngine.executeAsSubAgent(agentDef, taskDescription, chatModelFactory),
                    dagExecutor);

            String result = resultFuture.get(timeout, TimeUnit.SECONDS);

            step.setResult(result);
            step.setStatus("completed");
            stepStatusMap.put(step.getIndex(), "completed");

            String outputVarValue = result;
            if (step.getOutputVar() != null && !step.getOutputVar().isBlank()) {
                variables.put(step.getOutputVar(), outputVarValue);
                sink.next(AgentEvent.variableSet(step.getOutputVar(), truncate(outputVarValue, 200)));
            }

            stateMap.put(step.getIndex(), new ConditionEvaluator.StepState(
                    "completed", result, outputVarValue));
            sink.next(AgentEvent.stepComplete(step.getIndex(), agentDef.getName()));

        } catch (TimeoutException e) {
            log.error("Step {} timed out after {}s", step.getIndex(), timeout);
            step.setStatus("failed");
            step.setError(i18n("ai.dag.step.timeout", step.getIndex(), timeout));
            handleStepFailure(step, stepStatusMap, stateMap, sink);

        } catch (Exception e) {
            log.error("Step {} execution failed: {}", step.getIndex(), e.getMessage());
            step.setStatus("failed");
            step.setError(e.getMessage());
            handleStepFailure(step, stepStatusMap, stateMap, sink);
        }
    }

    private void handleStepFailure(ExecutionPlan.PlanStep step,
                                   Map<Integer, String> stepStatusMap,
                                   Map<Integer, ConditionEvaluator.StepState> stateMap,
                                   FluxSink<AgentEvent> sink) {
        String strategy = step.getOnFailure() != null ? step.getOnFailure() : "abort";
        stateMap.put(step.getIndex(), new ConditionEvaluator.StepState("failed", null, null));

        if ("skip".equals(strategy)) {
            stepStatusMap.put(step.getIndex(), "failed");
            sink.next(AgentEvent.thinking(
                    i18n("ai.step.failed", step.getIndex(), step.getError()), "system"));
        } else if (strategy.startsWith("goto:")) {
            stepStatusMap.put(step.getIndex(), "failed");
            try {
                int gotoIndex = Integer.parseInt(strategy.substring(5));
                stepStatusMap.put(gotoIndex, "pending");
            } catch (NumberFormatException e) {
                log.warn("Invalid goto target in onFailure: {}", strategy);
            }
            sink.next(AgentEvent.thinking(
                    i18n("ai.step.failed", step.getIndex(), step.getError()), "system"));
        } else {
            // abort (default)
            stepStatusMap.put(step.getIndex(), "aborted");
            sink.next(AgentEvent.thinking(
                    i18n("ai.step.failed", step.getIndex(), step.getError()), "system"));
        }
    }

    private List<ExecutionPlan.PlanStep> findReadySteps(
            List<ExecutionPlan.PlanStep> steps,
            Map<Integer, String> stepStatusMap,
            Map<Integer, ConditionEvaluator.StepState> stateMap) {
        List<ExecutionPlan.PlanStep> ready = new ArrayList<>();
        for (ExecutionPlan.PlanStep step : steps) {
            String status = stepStatusMap.get(step.getIndex());
            if (!"pending".equals(status)) continue;

            boolean depsReady = true;
            if (step.getDependsOn() != null) {
                for (Integer dep : step.getDependsOn()) {
                    String depStatus = stepStatusMap.get(dep);
                    if (depStatus == null || "pending".equals(depStatus) || "running".equals(depStatus)) {
                        depsReady = false;
                        break;
                    }
                }
            }
            if (depsReady) {
                ready.add(step);
            }
        }
        return ready;
    }

    /**
     * Kahn's algorithm: returns true if graph has a cycle.
     */
    boolean hasCycle(List<ExecutionPlan.PlanStep> steps) {
        Map<Integer, Set<Integer>> adj = new HashMap<>();
        Map<Integer, Integer> inDegree = new HashMap<>();

        for (ExecutionPlan.PlanStep step : steps) {
            int idx = step.getIndex();
            adj.putIfAbsent(idx, new HashSet<>());
            inDegree.putIfAbsent(idx, 0);
            if (step.getDependsOn() != null) {
                for (Integer dep : step.getDependsOn()) {
                    adj.computeIfAbsent(dep, k -> new HashSet<>()).add(idx);
                    inDegree.merge(idx, 1, Integer::sum);
                }
            }
        }

        Queue<Integer> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        int visited = 0;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            visited++;
            for (int neighbor : adj.getOrDefault(node, Set.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        return visited != steps.size();
    }

    private String substituteVariables(String text, Map<String, String> inputVars,
                                       Map<String, String> variables) {
        if (text == null) return null;
        String result = text;

        if (inputVars != null) {
            for (Map.Entry<String, String> entry : inputVars.entrySet()) {
                String varRef = entry.getValue();
                if (varRef != null && varRef.startsWith("${") && varRef.endsWith("}")) {
                    String varName = varRef.substring(2, varRef.length() - 1);
                    String value = variables.getOrDefault(varName, varRef);
                    result = result.replace("${" + entry.getKey() + "}", value);
                }
            }
        }

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }

        return result;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private String i18n(String key, Object... args) {
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
    }
}
