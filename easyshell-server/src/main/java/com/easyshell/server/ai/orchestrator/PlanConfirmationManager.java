package com.easyshell.server.ai.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PlanConfirmationManager {

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> stepCheckpoints = new ConcurrentHashMap<>();

    public CompletableFuture<Boolean> waitForConfirmation(String sessionId, int timeoutSeconds) {
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>()
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> pending.remove(sessionId));
        pending.put(sessionId, future);
        return future;
    }

    public void confirm(String sessionId) {
        CompletableFuture<Boolean> future = pending.remove(sessionId);
        if (future != null) {
            future.complete(true);
            log.info("Plan confirmed for session {}", sessionId);
        } else {
            log.warn("No pending confirmation for session {}", sessionId);
        }
    }

    public void reject(String sessionId) {
        CompletableFuture<Boolean> future = pending.remove(sessionId);
        if (future != null) {
            future.complete(false);
            log.info("Plan rejected for session {}", sessionId);
        } else {
            log.warn("No pending rejection for session {}", sessionId);
        }
    }

    // --- Step-level checkpoint methods for DAG workflow ---

    private String stepCheckpointKey(String sessionId, int stepIndex) {
        return sessionId + ":step:" + stepIndex;
    }

    public CompletableFuture<Boolean> waitForStepCheckpoint(String sessionId, int stepIndex, int timeoutSeconds) {
        String key = stepCheckpointKey(sessionId, stepIndex);
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>()
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> stepCheckpoints.remove(key));
        stepCheckpoints.put(key, future);
        return future;
    }

    public void confirmStepCheckpoint(String sessionId, int stepIndex) {
        String key = stepCheckpointKey(sessionId, stepIndex);
        CompletableFuture<Boolean> future = stepCheckpoints.remove(key);
        if (future != null) {
            future.complete(true);
            log.info("Step checkpoint confirmed: session={}, step={}", sessionId, stepIndex);
        } else {
            log.warn("No pending step checkpoint: session={}, step={}", sessionId, stepIndex);
        }
    }

    public void rejectStepCheckpoint(String sessionId, int stepIndex) {
        String key = stepCheckpointKey(sessionId, stepIndex);
        CompletableFuture<Boolean> future = stepCheckpoints.remove(key);
        if (future != null) {
            future.complete(false);
            log.info("Step checkpoint rejected: session={}, step={}", sessionId, stepIndex);
        } else {
            log.warn("No pending step checkpoint: session={}, step={}", sessionId, stepIndex);
        }
    }
}
