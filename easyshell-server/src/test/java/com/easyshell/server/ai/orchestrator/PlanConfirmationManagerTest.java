package com.easyshell.server.ai.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("PlanConfirmationManager")
class PlanConfirmationManagerTest {

    private PlanConfirmationManager manager;

    @BeforeEach
    void setUp() {
        manager = new PlanConfirmationManager();
    }

    @Test
    void confirm_completesWithTrue() throws Exception {
        CompletableFuture<Boolean> future = manager.waitForConfirmation("s1", 10);
        manager.confirm("s1");
        assertThat(future.get()).isTrue();
    }

    @Test
    void reject_completesWithFalse() throws Exception {
        CompletableFuture<Boolean> future = manager.waitForConfirmation("s2", 10);
        manager.reject("s2");
        assertThat(future.get()).isFalse();
    }

    @Test
    void confirm_noPending_doesNotThrow() {
        assertThatCode(() -> manager.confirm("nonexistent")).doesNotThrowAnyException();
    }

    @Test
    void reject_noPending_doesNotThrow() {
        assertThatCode(() -> manager.reject("nonexistent")).doesNotThrowAnyException();
    }

    @Test
    void timeout_completesExceptionally() {
        CompletableFuture<Boolean> future = manager.waitForConfirmation("timeout-session", 1);
        assertThat(future).failsWithin(java.time.Duration.ofSeconds(3))
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void multipleSessions_independent() throws Exception {
        CompletableFuture<Boolean> f1 = manager.waitForConfirmation("a", 10);
        CompletableFuture<Boolean> f2 = manager.waitForConfirmation("b", 10);

        manager.confirm("a");
        manager.reject("b");

        assertThat(f1.get()).isTrue();
        assertThat(f2.get()).isFalse();
    }
}
