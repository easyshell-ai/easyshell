package com.easyshell.server.ai.orchestrator;

import com.easyshell.server.ai.adaptive.AdaptivePromptBuilder;
import com.easyshell.server.ai.adaptive.TaskClassifier;
import com.easyshell.server.ai.adaptive.TaskType;
import com.easyshell.server.ai.adaptive.ToolSetSelector;
import com.easyshell.server.ai.chat.SystemPrompts;
import com.easyshell.server.ai.agent.AgentDefinition;
import com.easyshell.server.ai.agent.AgentDefinitionRepository;
import com.easyshell.server.ai.agent.DelegateTaskTool;
import com.easyshell.server.ai.agent.GetTaskResultTool;
import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.learning.SopRetriever;
import com.easyshell.server.ai.memory.MemoryRetriever;
import com.easyshell.server.ai.model.entity.AiIterationMessage;
import com.easyshell.server.ai.repository.AiIterationMessageRepository;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.ai.tool.*;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.AgentRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OrchestratorEngine {

    private final ChatModelFactory chatModelFactory;
    private final AgentRepository agentRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final AgenticConfigService agenticConfigService;
    private final MessageSource messageSource;
    private final HostListTool hostListTool;
    private final HostTagTool hostTagTool;
    private final ScriptExecuteTool scriptExecuteTool;
    private final SoftwareDetectTool softwareDetectTool;
    private final TaskManageTool taskManageTool;
    private final ScriptManageTool scriptManageTool;
    private final ClusterManageTool clusterManageTool;
    private final MonitoringTool monitoringTool;
    private final AuditQueryTool auditQueryTool;
    private final ScheduledTaskTool scheduledTaskTool;
    private final ApprovalTool approvalTool;
    private final SubAgentTool subAgentTool;
    private final DelegateTaskTool delegateTaskTool;
    private final GetTaskResultTool getTaskResultTool;
    private final PlanExecutor planExecutor;
    private final PlanConfirmationManager planConfirmationManager;
    private final AiIterationMessageRepository aiIterationMessageRepository;
    private final MemoryRetriever memoryRetriever;
    private final SopRetriever sopRetriever;
    private final TaskClassifier taskClassifier;
    private final AdaptivePromptBuilder adaptivePromptBuilder;
    private final ToolSetSelector toolSetSelector;
    private final DateTimeTool dateTimeTool;
    private final WebFetchTool webFetchTool;
    private final WebSearchTool webSearchTool;
    private final CalculatorTool calculatorTool;
    private final TextProcessTool textProcessTool;
    private final NotificationTool notificationTool;
    private final DataFormatTool dataFormatTool;
    private final EncodingTool encodingTool;
    private final KnowledgeBaseTool knowledgeBaseTool;

    public OrchestratorEngine(
            ChatModelFactory chatModelFactory,
            AgentRepository agentRepository,
            AgentDefinitionRepository agentDefinitionRepository,
            AgenticConfigService agenticConfigService,
            MessageSource messageSource,
            HostListTool hostListTool,
            HostTagTool hostTagTool,
            ScriptExecuteTool scriptExecuteTool,
            SoftwareDetectTool softwareDetectTool,
            TaskManageTool taskManageTool,
            ScriptManageTool scriptManageTool,
            ClusterManageTool clusterManageTool,
            MonitoringTool monitoringTool,
            AuditQueryTool auditQueryTool,
            ScheduledTaskTool scheduledTaskTool,
            ApprovalTool approvalTool,
            SubAgentTool subAgentTool,
            @Lazy DelegateTaskTool delegateTaskTool,
            GetTaskResultTool getTaskResultTool,
            @Lazy PlanExecutor planExecutor,
            PlanConfirmationManager planConfirmationManager,
            AiIterationMessageRepository aiIterationMessageRepository,
            MemoryRetriever memoryRetriever,
            SopRetriever sopRetriever,
            TaskClassifier taskClassifier,
            AdaptivePromptBuilder adaptivePromptBuilder,
            ToolSetSelector toolSetSelector,
            DateTimeTool dateTimeTool,
            WebFetchTool webFetchTool,
            WebSearchTool webSearchTool,
            CalculatorTool calculatorTool,
            TextProcessTool textProcessTool,
            NotificationTool notificationTool,
            DataFormatTool dataFormatTool,
            EncodingTool encodingTool,
            KnowledgeBaseTool knowledgeBaseTool) {
        this.chatModelFactory = chatModelFactory;
        this.agentRepository = agentRepository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.agenticConfigService = agenticConfigService;
        this.messageSource = messageSource;
        this.hostListTool = hostListTool;
        this.hostTagTool = hostTagTool;
        this.scriptExecuteTool = scriptExecuteTool;
        this.softwareDetectTool = softwareDetectTool;
        this.taskManageTool = taskManageTool;
        this.scriptManageTool = scriptManageTool;
        this.clusterManageTool = clusterManageTool;
        this.monitoringTool = monitoringTool;
        this.auditQueryTool = auditQueryTool;
        this.scheduledTaskTool = scheduledTaskTool;
        this.approvalTool = approvalTool;
        this.subAgentTool = subAgentTool;
        this.delegateTaskTool = delegateTaskTool;
        this.getTaskResultTool = getTaskResultTool;
        this.planExecutor = planExecutor;
        this.planConfirmationManager = planConfirmationManager;
        this.aiIterationMessageRepository = aiIterationMessageRepository;
        this.memoryRetriever = memoryRetriever;
        this.sopRetriever = sopRetriever;
        this.taskClassifier = taskClassifier;
        this.adaptivePromptBuilder = adaptivePromptBuilder;
        this.toolSetSelector = toolSetSelector;
        this.dateTimeTool = dateTimeTool;
        this.webFetchTool = webFetchTool;
        this.webSearchTool = webSearchTool;
        this.calculatorTool = calculatorTool;
        this.textProcessTool = textProcessTool;
        this.notificationTool = notificationTool;
        this.dataFormatTool = dataFormatTool;
        this.encodingTool = encodingTool;
        this.knowledgeBaseTool = knowledgeBaseTool;
    }

    private static final ScheduledExecutorService HEARTBEAT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private static final Pattern PENDING_APPROVAL_PATTERN =
            Pattern.compile("已提交待人工审批。任务ID:\\s*(task_[a-zA-Z0-9]+)");

    private static final Pattern PLAN_JSON_PATTERN =
            Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);

    private static final ObjectMapper PLAN_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Flux<AgentEvent> process(OrchestratorRequest request) {
        return Flux.<AgentEvent>create(sink -> {
            sink.onDispose(() -> log.debug("SSE client disconnected for session {}", request.getSessionId()));
            Schedulers.boundedElastic().schedule(() -> executeOrchestration(request, sink));
        }, FluxSink.OverflowStrategy.BUFFER)
        .onBackpressureBuffer(256, dropped -> log.warn("Dropped AgentEvent due to backpressure: {}", dropped.getType()));
    }

    private void executeOrchestration(OrchestratorRequest request, FluxSink<AgentEvent> sink) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        sink.onCancel(() -> cancelled.set(true));

        ScheduledFuture<?> heartbeat = null;

        try {
            sink.next(AgentEvent.session(request.getSessionId()));

            scriptExecuteTool.setContext(request.getUserId(), request.getSourceIp());
            softwareDetectTool.setContext(request.getUserId(), request.getSourceIp());
            approvalTool.setContext(request.getUserId());

            ChatModel chatModel = chatModelFactory.getChatModel(request.getProvider(), request.getModel());

            List<Message> messages = new ArrayList<>(request.getHistory());

            if (request.getTargetAgentIds() != null && !request.getTargetAgentIds().isEmpty()) {
                List<Agent> targetAgents = agentRepository.findAllById(request.getTargetAgentIds());
                if (!targetAgents.isEmpty()) {
                    StringBuilder ctx = new StringBuilder("用户指定了以下目标主机进行操作：\n");
                    for (Agent agent : targetAgents) {
                        ctx.append(String.format("- 主机: %s, IP: %s, 状态: %s, CPU: %.1f%%, 内存: %.1f%%, 磁盘: %.1f%%\n",
                                agent.getHostname(), agent.getIp(),
                                agent.getStatus() != null && agent.getStatus() == 1 ? "在线" : "离线",
                                agent.getCpuUsage() != null ? agent.getCpuUsage() : 0.0,
                                agent.getMemUsage() != null ? agent.getMemUsage() : 0.0,
                                agent.getDiskUsage() != null ? agent.getDiskUsage() : 0.0));
                    }
                    ctx.append("\n请优先在这些主机上执行操作。");
                    messages.add(0, new SystemMessage(ctx.toString()));
                }
            }

            messages.add(0, new SystemMessage(SystemPrompts.OPS_ASSISTANT));

            String lastUserMsg = extractLastUserMessage(messages);

            // Classify task type (adaptive prompts)
            TaskType taskType = taskClassifier.classify(lastUserMsg);
            sink.next(AgentEvent.taskClassified(taskType.name()));

            // Retrieve memory
            String memoryContext = null;
            if (request.getUserId() != null && lastUserMsg != null) {
                memoryContext = memoryRetriever.retrieveRelevantMemory(request.getUserId(), lastUserMsg);
            }
            if (memoryContext != null && !memoryContext.isEmpty()) {
                sink.next(AgentEvent.memoryRetrieved(memoryContext));
            }

            // SOP suggestion text
            String sopSuggestion = null;

            // Build adaptive system prompt (replaces static OPS_ASSISTANT)
            String systemPrompt = adaptivePromptBuilder.buildPrompt(taskType, memoryContext, sopSuggestion);
            messages.set(0, new SystemMessage(systemPrompt));

            boolean toolsEnabled = request.isEnableTools();

            ToolCallback[] rawCallbacks = toolsEnabled ? ToolCallbacks.from(getAllTools()) : new ToolCallback[0];
            ToolCallback[] filteredCallbacks = toolSetSelector.selectTools(taskType, rawCallbacks);
            Map<String, ToolCallback> toolMap = buildToolMap(filteredCallbacks);

            heartbeat = HEARTBEAT_EXECUTOR.scheduleAtFixedRate(() -> {
                if (!cancelled.get() && !sink.isCancelled()) {
                    try {
                        sink.next(AgentEvent.heartbeat(i18n("ai.iteration.start", "Processing...")));
                    } catch (Exception e) {
                        log.trace("Heartbeat emit failed (likely stream closed): {}", e.getMessage());
                    }
                }
            }, 15, 15, TimeUnit.SECONDS);

            if (cancelled.get()) return;

            ExecutionPlan plan = null;

            if (agenticConfigService.getBoolean("ai.sop.enabled", true)) {
                String sopUserMsg = extractLastUserMessage(messages);
                if (sopUserMsg != null) {
                    Optional<com.easyshell.server.ai.model.entity.AiSopTemplate> sopMatch =
                            sopRetriever.findMatchingSop(sopUserMsg, request.getUserId());
                    if (sopMatch.isPresent()) {
                        ExecutionPlan sopPlan = sopRetriever.sopToPlan(sopMatch.get());
                        if (sopPlan != null) {
                            sink.next(AgentEvent.sopMatched(sopMatch.get().getTitle()));
                            sink.next(AgentEvent.sopApplied(sopMatch.get().getId()));
                            plan = sopPlan;
                        }
                    }
                }
            }

            if (plan == null && !request.isSkipPlanning() && shouldGeneratePlan(request, messages)) {
                sink.next(AgentEvent.thinking(i18n("ai.planning.start"), "planner"));
                try {
                    plan = generatePlan(chatModel, rawCallbacks, messages, request, sink, cancelled);
                    if (plan != null && plan.getSteps() != null && !plan.getSteps().isEmpty()) {
                        request.setExecutionPlan(plan);
                        sink.next(AgentEvent.plan(plan));
                        if (plan.isRequiresConfirmation()) {
                            sink.next(AgentEvent.planAwaitConfirmation(plan));
                            int timeout = agenticConfigService.getInt("ai.plan.confirmation-timeout", 300);
                            try {
                                CompletableFuture<Boolean> confirmation =
                                        planConfirmationManager.waitForConfirmation(request.getSessionId(), timeout);
                                Boolean confirmed = confirmation.get();
                                if (!Boolean.TRUE.equals(confirmed)) {
                                    sink.next(AgentEvent.planRejected(request.getSessionId()));
                                    sink.next(AgentEvent.done(request.getSessionId()));
                                    sink.complete();
                                    return;
                                }
                                sink.next(AgentEvent.planConfirmed(request.getSessionId()));
                            } catch (Exception e) {
                                Throwable cause = e.getCause();
                                if (cause instanceof TimeoutException) {
                                    sink.next(AgentEvent.thinking(i18n("ai.planning.timeout"), "system"));
                                } else {
                                    log.warn("Plan confirmation failed: {}", e.getMessage());
                                    sink.next(AgentEvent.planRejected(request.getSessionId()));
                                }
                                sink.next(AgentEvent.done(request.getSessionId()));
                                sink.complete();
                                return;
                            }
                        }
                    } else {
                        plan = null;
                    }
                } catch (Exception e) {
                    log.warn("Planning phase failed for session {}, falling back to direct execution: {}",
                            request.getSessionId(), e.getMessage());
                    sink.next(AgentEvent.thinking(i18n("ai.planning.failed", e.getMessage()), "planner"));
                    plan = null;
                }
            } else if (request.isSkipPlanning()) {
                sink.next(AgentEvent.thinking(i18n("ai.planning.skipped"), "system"));
            }

            if (cancelled.get()) return;

            if (plan != null && plan.getSteps() != null && !plan.getSteps().isEmpty()) {
                planExecutor.executePlan(plan, request, sink, cancelled);
                emitApprovalIfNeeded(request.getResponseContent(), sink);

                // Post-plan synthesis: let the main agent analyze sub-agent results and iterate if needed
                boolean synthesisEnabled = agenticConfigService.getBoolean("ai.plan.synthesis-enabled", true);
                String aggregatedResults = request.getResponseContent();
                if (synthesisEnabled && aggregatedResults != null && !aggregatedResults.isEmpty() && !cancelled.get()) {
                    log.info("Starting post-plan synthesis for session {}", request.getSessionId());
                    sink.next(AgentEvent.thinking(i18n("ai.plan.synthesizing"), "orchestrator"));

                    // Inject sub-agent results as assistant context, then ask main agent to synthesize
                    messages.add(new org.springframework.ai.chat.messages.AssistantMessage(
                            "以下是各步骤的执行结果：\n\n" + aggregatedResults));
                    messages.add(new org.springframework.ai.chat.messages.UserMessage(
                            "请根据以上各步骤的执行结果进行综合分析和总结。如果有步骤未能成功完成或子Agent表示无法执行某项操作，" +
                            "请分析原因并尝试采取替代方案完成任务。请直接给出最终的分析结论和建议。"));

                    executeAgenticLoop(chatModel, filteredCallbacks, toolMap, messages, request, sink, cancelled);
                } else {
                    sink.next(AgentEvent.done(request.getSessionId()));
                    sink.complete();
                }
            } else {
                executeAgenticLoop(chatModel, filteredCallbacks, toolMap, messages, request, sink, cancelled);
            }

        } catch (Exception e) {
            log.error("Orchestration failed for session {}", request.getSessionId(), e);
            if (!sink.isCancelled()) {
                sink.next(AgentEvent.error(i18n("ai.error.llm_streaming", e.getMessage())));
                sink.complete();
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private void executeAgenticLoop(
            ChatModel chatModel,
            ToolCallback[] toolCallbacks,
            Map<String, ToolCallback> toolMap,
            List<Message> messages,
            OrchestratorRequest request,
            FluxSink<AgentEvent> sink,
            AtomicBoolean cancelled
    ) {
        int maxIterations = request.getMaxIterations();
        int maxConsecutiveErrors = request.getMaxConsecutiveErrors();
        int maxToolCalls = request.getMaxToolCalls();

        if (maxIterations <= 0) maxIterations = agenticConfigService.getInt("ai.orchestrator.max-iterations", 25);
        if (maxConsecutiveErrors <= 0) maxConsecutiveErrors = agenticConfigService.getInt("ai.orchestrator.max-consecutive-errors", 3);
        if (maxToolCalls <= 0) maxToolCalls = agenticConfigService.getInt("ai.orchestrator.max-tool-calls", 30);
        
        log.info("Agentic loop started with maxIterations={}, maxConsecutiveErrors={}, maxToolCalls={} for session {}",
                maxIterations, maxConsecutiveErrors, maxToolCalls, request.getSessionId());

        int iteration = 0;
        int consecutiveErrors = 0;
        int totalToolCalls = 0;
        StringBuilder fullResponseText = new StringBuilder();

        ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        while (iteration < maxIterations && !cancelled.get()) {
            iteration++;
            sink.next(AgentEvent.iterationStart(iteration, maxIterations,
                    i18n("ai.iteration.start", iteration, maxIterations)));

            Prompt prompt = new Prompt(messages, chatOptions);

            ToolCallAccumulator accumulator = new ToolCallAccumulator();
            StringBuilder iterationText = new StringBuilder();

            boolean llmSuccess = false;
            int llmRetries = iteration > 1 ? 2 : 1; // Allow retry after tool calls
            for (int attempt = 1; attempt <= llmRetries && !cancelled.get(); attempt++) {
                accumulator = new ToolCallAccumulator();
                iterationText.setLength(0);
                final ToolCallAccumulator currentAccumulator = accumulator;
                try {
                    Flux<ChatResponse> streamFlux = chatModel.stream(prompt);
                    streamFlux.doOnNext(chunk -> {
                        if (cancelled.get()) return;
                        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;

                        AssistantMessage output = chunk.getResult().getOutput();

                        if (output.getText() != null && !output.getText().isEmpty()) {
                            iterationText.append(output.getText());
                            sink.next(AgentEvent.content(output.getText()));
                        }

                        if (output.hasToolCalls()) {
                            currentAccumulator.accumulate(output.getToolCalls());
                        }
                    }).blockLast();
                    llmSuccess = true;
                    break;
                } catch (Exception e) {
                    log.error("LLM streaming failed at iteration {}, attempt {}/{} for session {}",
                            iteration, attempt, llmRetries, request.getSessionId(), e);
                    if (attempt >= llmRetries) {
                        sink.next(AgentEvent.error(i18n("ai.error.llm_streaming", e.getMessage())));
                    } else {
                        sink.next(AgentEvent.thinking("LLM call failed, retrying...", "system"));
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }
            }
            if (!llmSuccess) break;

            if (cancelled.get()) break;

            List<AssistantMessage.ToolCall> toolCalls = accumulator.getCompleted();

            // Always accumulate response text from every iteration
            fullResponseText.append(iterationText);

            if (toolCalls.isEmpty()) {
                persistIterationMessage(request.getSessionId(), iteration, "assistant",
                        iterationText.toString(), null, null, null, "assistant", null);
                break;
            }

            AssistantMessage assistantMsg = AssistantMessage.builder()
                    .content(iterationText.toString())
                    .toolCalls(toolCalls)
                    .build();
            messages.add(assistantMsg);

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            int iterationErrors = 0;

            for (AssistantMessage.ToolCall tc : toolCalls) {
                if (cancelled.get()) break;
                if (totalToolCalls >= maxToolCalls) {
                    String limitMsg = i18n("ai.iteration.max_reached", maxToolCalls);
                    sink.next(AgentEvent.thinking(limitMsg, "assistant"));
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), limitMsg));
                    continue;
                }

                totalToolCalls++;
                String toolName = tc.name();
                String toolArgs = tc.arguments();

                ToolCallback callback = toolMap.get(toolName);
                if (callback == null) {
                    String errorMsg = i18n("ai.error.unknown_tool", toolName);
                    sink.next(AgentEvent.toolResult(toolName, errorMsg, "assistant"));
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), toolName, errorMsg));
                    iterationErrors++;
                    continue;
                }

                sink.next(AgentEvent.toolCall(toolName, toolArgs, "assistant"));
                sink.next(AgentEvent.thinking("Executing: " + toolName + " (" + totalToolCalls + "/" + maxToolCalls + ")", "assistant"));

                long startTime = System.currentTimeMillis();
                try {
                    String result = callback.call(toolArgs);
                    long elapsed = System.currentTimeMillis() - startTime;

                    String displayResult = truncateForDisplay(result);
                    sink.next(AgentEvent.toolResult(toolName, displayResult, "assistant"));
                    sink.next(AgentEvent.thinking(toolName + " done (" + elapsed + "ms)", "assistant"));

                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), toolName,
                            result != null ? result : ""));
                    consecutiveErrors = 0;

                    persistIterationMessage(request.getSessionId(), iteration, "tool_call",
                            null, toolName, toolArgs, null, "assistant", null);
                    persistIterationMessage(request.getSessionId(), iteration, "tool_result",
                            null, toolName, null, result, "assistant", elapsed);
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    String errorMsg = i18n("ai.error.tool_failed", e.getMessage());
                    sink.next(AgentEvent.toolResult(toolName, errorMsg, "assistant"));
                    sink.next(AgentEvent.thinking(toolName + " failed (" + elapsed + "ms)", "assistant"));

                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), toolName, errorMsg));
                    iterationErrors++;
                    consecutiveErrors++;

                    persistIterationMessage(request.getSessionId(), iteration, "tool_call",
                            null, toolName, toolArgs, null, "assistant", null);
                    persistIterationMessage(request.getSessionId(), iteration, "tool_result",
                            errorMsg, toolName, null, errorMsg, "assistant", elapsed);
                }
            }

            ToolResponseMessage toolResponseMsg = ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build();
            messages.add(toolResponseMsg);

            if (iterationErrors > 0 && consecutiveErrors > 0) {
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    String stopMsg = i18n("ai.error.consecutive_failures", consecutiveErrors);
                    sink.next(AgentEvent.error(stopMsg));
                    log.warn("Session {} stopped: {} consecutive errors", request.getSessionId(), consecutiveErrors);
                    break;
                }

                String reflectionPrompt = i18n("ai.reflection.prompt", iterationErrors);
                messages.add(new SystemMessage(reflectionPrompt));
                sink.next(AgentEvent.reflection(reflectionPrompt));

                persistIterationMessage(request.getSessionId(), iteration, "reflection",
                        reflectionPrompt, null, null, null, "system", null);
            }

            if (totalToolCalls >= maxToolCalls) {
                String budgetMsg = i18n("ai.iteration.max_reached", maxToolCalls);
                messages.add(new SystemMessage(budgetMsg + " Please respond to the user directly based on available information."));
                sink.next(AgentEvent.thinking(budgetMsg, "assistant"));
            }
        }

        if (iteration >= maxIterations && !cancelled.get()) {
            String maxReachedMsg = i18n("ai.iteration.max_reached", maxIterations);
            sink.next(AgentEvent.thinking(maxReachedMsg, "assistant"));
        }

        log.info("Session {} completed: {} iterations, {} tool calls",
                request.getSessionId(), iteration, totalToolCalls);

        request.setResponseContent(fullResponseText.toString());

        emitApprovalIfNeeded(fullResponseText.toString(), sink);
        sink.next(AgentEvent.done(request.getSessionId()));
        sink.complete();
    }

    private Map<String, ToolCallback> buildToolMap(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (a, b) -> a
                ));
    }

    private String truncateForDisplay(String result) {
        if (result != null && result.length() > 2000) {
            return result.substring(0, 2000) + "... (" + result.length() + " chars total)";
        }
        return result;
    }

    private void emitApprovalIfNeeded(String content, FluxSink<AgentEvent> sink) {
        if (content == null || content.isEmpty()) return;
        Matcher matcher = PENDING_APPROVAL_PATTERN.matcher(content);
        while (matcher.find()) {
            String taskId = matcher.group(1);
            log.info("Detected pending approval task: {}", taskId);
            sink.next(AgentEvent.approval(taskId, "脚本包含中风险命令，需要您确认后执行", content));
        }
    }

    private Object[] getAllTools() {
        List<Object> tools = new java.util.ArrayList<>(List.of(
                hostListTool, hostTagTool, scriptExecuteTool, softwareDetectTool,
                taskManageTool, scriptManageTool, clusterManageTool,
                monitoringTool, auditQueryTool, scheduledTaskTool, approvalTool,
                subAgentTool, delegateTaskTool, getTaskResultTool,
                dateTimeTool, webFetchTool, webSearchTool,
                calculatorTool, textProcessTool, notificationTool,
                dataFormatTool, encodingTool
        ));
        tools.add(knowledgeBaseTool);
        return tools.toArray();
    }

    private String i18n(String key, Object... args) {
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
    }

    public String executeAsSubAgent(AgentDefinition agent, String prompt, ChatModelFactory modelFactory) {
        ChatModel chatModel;
        if (agent.getModelProvider() != null && !agent.getModelProvider().isBlank()) {
            chatModel = modelFactory.getChatModel(agent.getModelProvider(), agent.getModelName());
        } else {
            chatModel = modelFactory.getChatModel(null);
        }

        ToolCallback[] allCallbacks = ToolCallbacks.from(getAllTools());
        ToolCallback[] filteredCallbacks = filterToolsByPermissions(allCallbacks, agent.getPermissions());
        Map<String, ToolCallback> toolMap = buildToolMap(filteredCallbacks);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(agent.getSystemPrompt()));
        messages.add(new UserMessage(prompt));

        int maxIter = agent.getMaxIterations() != null ? agent.getMaxIterations() : 15;
        int maxErrors = agenticConfigService.getInt("ai.orchestrator.max-consecutive-errors", 3);
        int maxTools = agenticConfigService.getInt("ai.orchestrator.max-tool-calls", 30);

        ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(filteredCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        StringBuilder result = new StringBuilder();
        int iteration = 0;
        int consecutiveErrors = 0;
        int totalToolCalls = 0;

        while (iteration < maxIter) {
            iteration++;
            Prompt p = new Prompt(messages, chatOptions);

            ToolCallAccumulator accumulator = new ToolCallAccumulator();
            StringBuilder iterText = new StringBuilder();

            try {
                chatModel.stream(p).doOnNext(chunk -> {
                    if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    AssistantMessage output = chunk.getResult().getOutput();
                    if (output.getText() != null && !output.getText().isEmpty()) {
                        iterText.append(output.getText());
                    }
                    if (output.hasToolCalls()) {
                        accumulator.accumulate(output.getToolCalls());
                    }
                }).blockLast();
            } catch (Exception e) {
                log.error("Sub-agent {} streaming failed at iteration {}", agent.getName(), iteration, e);
                result.append("[Error: ").append(e.getMessage()).append("]");
                break;
            }

            List<AssistantMessage.ToolCall> toolCalls = accumulator.getCompleted();

            if (toolCalls.isEmpty()) {
                result.append(iterText);
                break;
            }

            AssistantMessage assistantMsg = AssistantMessage.builder()
                    .content(iterText.toString())
                    .toolCalls(toolCalls)
                    .build();
            messages.add(assistantMsg);

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : toolCalls) {
                if (totalToolCalls >= maxTools) break;
                totalToolCalls++;

                ToolCallback callback = toolMap.get(tc.name());
                if (callback == null) {
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "Unknown tool: " + tc.name()));
                    continue;
                }

                try {
                    String toolResult = callback.call(tc.arguments());
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), toolResult != null ? toolResult : ""));
                    consecutiveErrors = 0;
                } catch (Exception e) {
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "Error: " + e.getMessage()));
                    consecutiveErrors++;
                }
            }

            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());

            if (consecutiveErrors >= maxErrors) {
                result.append("[Sub-agent stopped: too many consecutive errors]");
                break;
            }
        }

        log.info("Sub-agent {} completed: {} iterations, {} tool calls", agent.getName(), iteration, totalToolCalls);
        return result.toString();
    }

    private boolean shouldGeneratePlan(OrchestratorRequest request, List<Message> messages) {
        if (request.isPlanConfirmed() || request.getExecutionPlan() != null) {
            return false;
        }
        boolean planningEnabled = agenticConfigService.getBoolean("ai.planning.enabled", true);
        if (!planningEnabled) {
            return false;
        }
        int minLength = agenticConfigService.getInt("ai.planning.min-message-length", 20);
        String lastUserMsg = extractLastUserMessage(messages);
        return lastUserMsg != null && lastUserMsg.length() >= minLength;
    }

    private ExecutionPlan generatePlan(
            ChatModel chatModel,
            ToolCallback[] allCallbacks,
            List<Message> messages,
            OrchestratorRequest request,
            FluxSink<AgentEvent> sink,
            AtomicBoolean cancelled
    ) {
        Optional<AgentDefinition> plannerOpt = agentDefinitionRepository.findByNameAndEnabledTrue("planner");
        if (plannerOpt.isEmpty()) {
            log.debug("Planner agent not found or disabled, skipping planning");
            return null;
        }

        AgentDefinition planner = plannerOpt.get();
        String lastUserMsg = extractLastUserMessage(messages);
        if (lastUserMsg == null) return null;

        ToolCallback[] plannerCallbacks = filterToolsByPermissions(allCallbacks, planner.getPermissions());
        Map<String, ToolCallback> plannerToolMap = buildToolMap(plannerCallbacks);

        List<Message> plannerMessages = new ArrayList<>();
        plannerMessages.add(new SystemMessage(planner.getSystemPrompt()));
        plannerMessages.add(new UserMessage(lastUserMsg));

        int maxPlanIterations = planner.getMaxIterations() != null ? planner.getMaxIterations() :
                agenticConfigService.getInt("ai.planning.max-iterations", 3);
        int maxErrors = agenticConfigService.getInt("ai.orchestrator.max-consecutive-errors", 3);
        int maxTools = agenticConfigService.getInt("ai.orchestrator.max-tool-calls", 30);

        ToolCallingChatOptions plannerOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(plannerCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        StringBuilder plannerResponse = new StringBuilder();
        int iteration = 0;
        int consecutiveErrors = 0;
        int totalToolCalls = 0;

        while (iteration < maxPlanIterations && !cancelled.get()) {
            iteration++;
            Prompt prompt = new Prompt(plannerMessages, plannerOptions);

            ToolCallAccumulator accumulator = new ToolCallAccumulator();
            StringBuilder iterText = new StringBuilder();

            try {
                chatModel.stream(prompt).doOnNext(chunk -> {
                    if (cancelled.get()) return;
                    if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    AssistantMessage output = chunk.getResult().getOutput();
                    if (output.getText() != null && !output.getText().isEmpty()) {
                        iterText.append(output.getText());
                    }
                    if (output.hasToolCalls()) {
                        accumulator.accumulate(output.getToolCalls());
                    }
                }).blockLast();
            } catch (Exception e) {
                log.error("Planner streaming failed at iteration {}: {}", iteration, e.getMessage());
                break;
            }

            List<AssistantMessage.ToolCall> toolCalls = accumulator.getCompleted();

            if (toolCalls.isEmpty()) {
                plannerResponse.append(iterText);
                break;
            }

            AssistantMessage assistantMsg = AssistantMessage.builder()
                    .content(iterText.toString())
                    .toolCalls(toolCalls)
                    .build();
            plannerMessages.add(assistantMsg);

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : toolCalls) {
                if (totalToolCalls >= maxTools) break;
                totalToolCalls++;

                ToolCallback callback = plannerToolMap.get(tc.name());
                if (callback == null) {
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "Unknown tool: " + tc.name()));
                    continue;
                }

                sink.next(AgentEvent.toolCall(tc.name(), tc.arguments(), "planner"));
                try {
                    String toolResult = callback.call(tc.arguments());
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), toolResult != null ? toolResult : ""));
                    sink.next(AgentEvent.toolResult(tc.name(), truncateForDisplay(toolResult), "planner"));
                    consecutiveErrors = 0;
                } catch (Exception e) {
                    toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "Error: " + e.getMessage()));
                    sink.next(AgentEvent.toolResult(tc.name(), "Error: " + e.getMessage(), "planner"));
                    consecutiveErrors++;
                }
            }

            plannerMessages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            if (consecutiveErrors >= maxErrors) break;
        }

        log.info("Planner completed: {} iterations, {} tool calls", iteration, totalToolCalls);
        return parsePlanFromResponse(plannerResponse.toString());
    }

    ExecutionPlan parsePlanFromResponse(String response) {
        if (response == null || response.isBlank()) return null;

        Matcher matcher = PLAN_JSON_PATTERN.matcher(response);
        if (!matcher.find()) {
            log.debug("No JSON code block found in planner response");
            return null;
        }

        String json = matcher.group(1).trim();
        if (json.equals("{}") || json.isEmpty()) {
            log.debug("Planner returned empty plan — request is simple, no planning needed");
            return null;
        }

        try {
            return PLAN_MAPPER.readValue(json, ExecutionPlan.class);
        } catch (Exception e) {
            log.warn("Failed to parse execution plan JSON: {}", e.getMessage());
            return null;
        }
    }

    private String extractLastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return null;
    }

    private void persistIterationMessage(String sessionId, int iteration, String role,
                                          String content, String toolName, String toolArgs,
                                          String toolResult, String agentName, Long durationMs) {
        try {
            boolean enabled = agenticConfigService.getBoolean("ai.iteration.persistence.enabled", true);
            if (!enabled) return;

            int maxPerSession = agenticConfigService.getInt("ai.iteration.persistence.max-per-session", 500);
            long currentCount = aiIterationMessageRepository.countBySessionId(sessionId);
            if (currentCount >= maxPerSession) return;

            AiIterationMessage msg = new AiIterationMessage();
            msg.setSessionId(sessionId);
            msg.setIteration(iteration);
            msg.setRole(role);
            msg.setContent(content);
            msg.setToolName(toolName);
            msg.setToolArgs(toolArgs);
            msg.setToolResult(toolResult);
            msg.setAgentName(agentName);
            msg.setDurationMs(durationMs);
            aiIterationMessageRepository.save(msg);
        } catch (Exception e) {
            log.warn("Failed to persist iteration message for session {}: {}", sessionId, e.getMessage());
        }
    }

    private ToolCallback[] filterToolsByPermissions(ToolCallback[] allCallbacks, String permissionsJson) {
        if (permissionsJson == null || permissionsJson.isBlank()) {
            return allCallbacks;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode perms = mapper.readTree(permissionsJson);

            Set<String> allowSet = new HashSet<>();
            Set<String> denySet = new HashSet<>();
            boolean allowAll = false;

            for (JsonNode perm : perms) {
                String tool = perm.has("tool") ? perm.get("tool").asText() : "";
                String action = perm.has("action") ? perm.get("action").asText() : "";
                if ("*".equals(tool) && "allow".equals(action)) {
                    allowAll = true;
                } else if ("allow".equals(action)) {
                    allowSet.add(tool);
                } else if ("deny".equals(action)) {
                    denySet.add(tool);
                }
            }

            final boolean finalAllowAll = allowAll;
            return Arrays.stream(allCallbacks)
                    .filter(cb -> {
                        String name = cb.getToolDefinition().name();
                        if (denySet.contains(name)) return false;
                        return finalAllowAll || allowSet.contains(name);
                    })
                    .toArray(ToolCallback[]::new);
        } catch (Exception e) {
            log.warn("Failed to parse permissions JSON: {}", e.getMessage());
            return allCallbacks;
        }
    }
}
