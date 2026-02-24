package com.easyshell.server.ai.orchestrator;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SSE event emitted during multi-agent orchestration.
 * Each event type maps to a distinct frontend rendering component.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentEvent {

    public enum Type {
        /** Session info (sessionId) — first event */
        SESSION,
        /** Execution plan created */
        PLAN,
        /** Agent step starting */
        STEP_START,
        /** Agent thinking / reasoning text (streamed tokens) */
        THINKING,
        /** Tool being called */
        TOOL_CALL,
        /** Tool returned result */
        TOOL_RESULT,
        /** Final content chunk (streamed tokens) */
        CONTENT,
        /** Agent step completed */
        STEP_COMPLETE,
        /** Agentic loop iteration start */
        ITERATION_START,
        /** Self-correction reflection injected */
        REFLECTION,
        /** Sub-task started (delegated to child agent) */
        SUBTASK_STARTED,
        /** Sub-task completed */
        SUBTASK_COMPLETED,
        /** Needs human approval */
        APPROVAL,
        /** Complete response */
        DONE,
        /** Error occurred */
        ERROR,

        /** Plan awaiting user confirmation */
        PLAN_AWAIT_CONFIRMATION,
        /** User confirmed the plan */
        PLAN_CONFIRMED,
        /** User rejected the plan */
        PLAN_REJECTED,
        /** Step is being retried */
        STEP_RETRY,
        /** Plan execution summary */
        PLAN_SUMMARY,
        /** Review/verification started */
        REVIEW_START,
        /** Review/verification completed */
        REVIEW_COMPLETE,
        /** Parallel execution group started */
        PARALLEL_START,
        /** Parallel execution progress update */
        PARALLEL_PROGRESS,
        /** Parallel execution group completed */
        PARALLEL_COMPLETE,

        // Phase 3 additions
        MEMORY_RETRIEVED,
        SOP_MATCHED,
        SOP_APPLIED,
        TASK_CLASSIFIED,
        STEP_CHECKPOINT,
        STEP_CONDITION_EVAL,
        VARIABLE_SET
    }

    /** Event type — maps to SSE event field */
    private Type type;

    /** Which agent emitted this event */
    private String agent;

    /** Current step index (for STEP_START / STEP_COMPLETE) */
    private Integer stepIndex;

    /** Step description */
    private String stepDescription;

    /** Text content (for THINKING, CONTENT, ERROR) */
    private String content;

    /** Tool name (for TOOL_CALL, TOOL_RESULT) */
    private String toolName;

    /** Tool arguments as JSON string (for TOOL_CALL) */
    private String toolArgs;

    /** Tool result text (for TOOL_RESULT) */
    private String toolResult;

    /** Execution plan (for PLAN event) */
    private ExecutionPlan plan;

    /** Session ID (for SESSION event) */
    private String sessionId;

    /** Approval request details */
    private Map<String, Object> approvalData;

    private Integer iteration;
    private Integer maxIterations;
    private Integer iterationToolCallCount;

    /** Parallel group number (for PARALLEL_* events) */
    private Integer parallelGroup;
    /** Total tasks in parallel group */
    private Integer totalTasks;
    /** Completed tasks in parallel group */
    private Integer completedTasks;

    private Map<String, String> metadata;

    // -- Factory methods --

    public static AgentEvent session(String sessionId) {
        return AgentEvent.builder().type(Type.SESSION).sessionId(sessionId).build();
    }

    public static AgentEvent plan(ExecutionPlan plan) {
        return AgentEvent.builder().type(Type.PLAN).plan(plan).build();
    }

    public static AgentEvent stepStart(int index, String description, String agent) {
        return AgentEvent.builder().type(Type.STEP_START).stepIndex(index).stepDescription(description).agent(agent).build();
    }

    public static AgentEvent thinking(String text, String agent) {
        return AgentEvent.builder().type(Type.THINKING).content(text).agent(agent).build();
    }

    public static AgentEvent toolCall(String toolName, String toolArgs, String agent) {
        return AgentEvent.builder().type(Type.TOOL_CALL).toolName(toolName).toolArgs(toolArgs).agent(agent).build();
    }

    public static AgentEvent toolResult(String toolName, String result, String agent) {
        return AgentEvent.builder().type(Type.TOOL_RESULT).toolName(toolName).toolResult(result).agent(agent).build();
    }

    public static AgentEvent content(String text) {
        return AgentEvent.builder().type(Type.CONTENT).content(text).build();
    }

    public static AgentEvent stepComplete(int index, String agent) {
        return AgentEvent.builder().type(Type.STEP_COMPLETE).stepIndex(index).agent(agent).build();
    }

    public static AgentEvent done(String sessionId) {
        return AgentEvent.builder().type(Type.DONE).sessionId(sessionId).build();
    }

    public static AgentEvent error(String message) {
        return AgentEvent.builder().type(Type.ERROR).content(message).build();
    }

    public static AgentEvent approval(String taskId, String description, String scriptContent) {
        return AgentEvent.builder()
                .type(Type.APPROVAL)
                .content(description)
                .approvalData(Map.of(
                        "taskId", taskId,
                        "scriptContent", scriptContent != null ? scriptContent : "",
                        "description", description != null ? description : ""
                ))
                .build();
    }

    public static AgentEvent iterationStart(int iteration, int maxIterations, String content) {
        return AgentEvent.builder()
                .type(Type.ITERATION_START)
                .iteration(iteration)
                .maxIterations(maxIterations)
                .content(content)
                .build();
    }

    public static AgentEvent reflection(String reflectionText) {
        return AgentEvent.builder().type(Type.REFLECTION).content(reflectionText).build();
    }

    public static AgentEvent subtaskStarted(String taskId, String agentName, String description) {
        return AgentEvent.builder()
                .type(Type.SUBTASK_STARTED)
                .agent(agentName)
                .content(description)
                .metadata(Map.of("taskId", taskId))
                .build();
    }

    public static AgentEvent subtaskCompleted(String taskId, String result) {
        return AgentEvent.builder()
                .type(Type.SUBTASK_COMPLETED)
                .content(result != null && result.length() > 500 ? result.substring(0, 500) + "..." : result)
                .metadata(Map.of("taskId", taskId))
                .build();
    }

    /** Heartbeat event — keeps SSE connection alive during tool execution gaps */
    public static AgentEvent heartbeat(String statusText) {
        return AgentEvent.builder().type(Type.THINKING).content(statusText).agent("system").build();
    }

    public static AgentEvent planAwaitConfirmation(ExecutionPlan plan) {
        return AgentEvent.builder()
                .type(Type.PLAN_AWAIT_CONFIRMATION)
                .plan(plan)
                .build();
    }

    public static AgentEvent planConfirmed(String sessionId) {
        return AgentEvent.builder()
                .type(Type.PLAN_CONFIRMED)
                .sessionId(sessionId)
                .build();
    }

    public static AgentEvent planRejected(String sessionId) {
        return AgentEvent.builder()
                .type(Type.PLAN_REJECTED)
                .sessionId(sessionId)
                .build();
    }

    public static AgentEvent stepRetry(int index, String reason) {
        return AgentEvent.builder()
                .type(Type.STEP_RETRY)
                .stepIndex(index)
                .content(reason)
                .build();
    }

    public static AgentEvent planSummary(String summary, ExecutionPlan plan) {
        return AgentEvent.builder()
                .type(Type.PLAN_SUMMARY)
                .content(summary)
                .plan(plan)
                .build();
    }

    public static AgentEvent reviewStart() {
        return AgentEvent.builder()
                .type(Type.REVIEW_START)
                .agent("reviewer")
                .build();
    }

    public static AgentEvent reviewComplete(String reviewResult) {
        return AgentEvent.builder()
                .type(Type.REVIEW_COMPLETE)
                .content(reviewResult)
                .agent("reviewer")
                .build();
    }

    public static AgentEvent parallelStart(int parallelGroup, int totalTasks) {
        return AgentEvent.builder()
                .type(Type.PARALLEL_START)
                .parallelGroup(parallelGroup)
                .totalTasks(totalTasks)
                .build();
    }

    public static AgentEvent parallelProgress(int parallelGroup, int completedTasks, int totalTasks) {
        return AgentEvent.builder()
                .type(Type.PARALLEL_PROGRESS)
                .parallelGroup(parallelGroup)
                .completedTasks(completedTasks)
                .totalTasks(totalTasks)
                .build();
    }

    public static AgentEvent parallelComplete(int parallelGroup) {
        return AgentEvent.builder()
                .type(Type.PARALLEL_COMPLETE)
                .parallelGroup(parallelGroup)
                .build();
    }

    public static AgentEvent memoryRetrieved(String memoryContext) {
        return AgentEvent.builder()
                .type(Type.MEMORY_RETRIEVED)
                .content(memoryContext)
                .build();
    }

    public static AgentEvent sopMatched(String sopTitle) {
        return AgentEvent.builder()
                .type(Type.SOP_MATCHED)
                .content(sopTitle)
                .build();
    }

    public static AgentEvent sopApplied(Long sopId) {
        return AgentEvent.builder()
                .type(Type.SOP_APPLIED)
                .content(sopId != null ? sopId.toString() : "")
                .build();
    }

    public static AgentEvent taskClassified(String taskType) {
        return AgentEvent.builder()
                .type(Type.TASK_CLASSIFIED)
                .content(taskType)
                .build();
    }

    public static AgentEvent stepCheckpoint(int stepIndex, String description) {
        return AgentEvent.builder()
                .type(Type.STEP_CHECKPOINT)
                .stepIndex(stepIndex)
                .stepDescription(description)
                .build();
    }

    public static AgentEvent stepConditionEval(int stepIndex, String condition, boolean result) {
        return AgentEvent.builder()
                .type(Type.STEP_CONDITION_EVAL)
                .stepIndex(stepIndex)
                .content(condition)
                .metadata(Map.of("result", String.valueOf(result)))
                .build();
    }

    public static AgentEvent variableSet(String varName, String value) {
        return AgentEvent.builder()
                .type(Type.VARIABLE_SET)
                .content(value)
                .metadata(Map.of("varName", varName))
                .build();
    }
}
