package com.easyshell.server.ai.orchestrator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

class PlanConfirmationManagerTest {
    private final PlanConfirmationManager manager = new PlanConfirmationManager();

    @Test
    void noPendingDecisionFailsAndSessionsAreIndependent() {
        assertThat(manager.confirm("missing", "id")).isFalse();
        assertThat(manager.reject("missing", "id")).isFalse();
        var first = manager.waitForConfirmation("a", 10);
        var second = manager.waitForConfirmation("b", 10);
        assertThat(manager.confirm("a", first.planId())).isTrue();
        assertThat(manager.reject("b", second.planId())).isTrue();
        assertThat(first.future().join()).isTrue();
        assertThat(second.future().join()).isFalse();
    }

    @Test
    void matchingDecisionCompletesOnlyOnce() {
        var plan = manager.waitForConfirmation("s", 10);
        assertThat(manager.confirm("s", plan.planId())).isTrue();
        assertThat(plan.future().join()).isTrue();
        assertThat(manager.confirm("s", plan.planId())).isFalse();
        var rejected = manager.waitForConfirmation("s", 10);
        assertThat(manager.reject("s", rejected.planId())).isTrue();
        assertThat(rejected.future().join()).isFalse();
    }

    @Test
    void staleMissingAndCrossSessionIdsCannotDecideReplacement() {
        var old = manager.waitForConfirmation("s", 10);
        assertThatThrownBy(() -> manager.waitForConfirmation("s", 10)).isInstanceOf(IllegalStateException.class);
        old.future().cancel(false);
        var current = manager.waitForConfirmation("s", 10);
        var other = manager.waitForConfirmation("other", 10);
        assertThat(current.planId()).isNotEqualTo(old.planId()).isNotEqualTo(other.planId());
        assertThat(manager.confirm("s", old.planId())).isFalse();
        assertThat(manager.reject("s", old.planId())).isFalse();
        assertThat(manager.confirm("s", null)).isFalse();
        assertThat(manager.confirm("s", other.planId())).isFalse();
        assertThat(current.future()).isNotDone();
        assertThat(manager.confirm("s", current.planId())).isTrue();
        other.future().cancel(false);
    }

    @Test
    void timeoutCannotBeReplayedAgainstReplacement() {
        var old = manager.waitForConfirmation("s", 1);
        assertThat(old.future()).failsWithin(Duration.ofSeconds(3))
                .withThrowableOfType(ExecutionException.class).withCauseInstanceOf(TimeoutException.class);
        var current = manager.waitForConfirmation("s", 10);
        assertThat(manager.confirm("s", old.planId())).isFalse();
        assertThat(current.future()).isNotDone();
        current.future().cancel(false);
    }
}
