package com.easyshell.server.ai.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Slf4j
@Component
public class PlanConfirmationManager {

    public record PendingPlan(String planId, CompletableFuture<Boolean> future) {}

    private final ConcurrentHashMap<String, PendingPlan> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> stepCheckpoints = new ConcurrentHashMap<>();

    public PendingPlan waitForConfirmation(String sessionId, int timeoutSeconds) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        PendingPlan registration = new PendingPlan(UUID.randomUUID().toString(), future);
        if (pending.putIfAbsent(sessionId, registration) != null) {
            throw new IllegalStateException("A plan is already awaiting confirmation for this session");
        }
        future.whenComplete((result, ex) -> pending.remove(sessionId, registration));
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS);
        return registration;
    }

    public boolean confirm(String sessionId, String planId) {
        return complete(sessionId, planId, true);
    }

    public boolean reject(String sessionId, String planId) {
        return complete(sessionId, planId, false);
    }

    private boolean complete(String sessionId, String planId, boolean accepted) {
        PendingPlan registration = pending.get(sessionId);
        if (registration == null || planId == null || !registration.planId().equals(planId)) {
            return false;
        }
        // Future completion arbitrates atomically against timeout, cancellation and replay.
        return registration.future().complete(accepted);
    }

    // --- Step-level checkpoint methods for DAG workflow ---

    private String stepCheckpointKey(String sessionId, int stepIndex) {
        return sessionId + ":step:" + stepIndex;
    }

    public CompletableFuture<Boolean> waitForStepCheckpoint(String sessionId, int stepIndex, int timeoutSeconds) {
        String key = stepCheckpointKey(sessionId, stepIndex);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (stepCheckpoints.putIfAbsent(key, future) != null) {
            throw new IllegalStateException("A step is already awaiting confirmation");
        }
        future.whenComplete((result, ex) -> stepCheckpoints.remove(key, future));
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS);
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
