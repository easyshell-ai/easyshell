package com.easyshell.server.ai.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BackgroundTask")
class BackgroundTaskTest {

    @Test
    void constructor_setsDefaults() {
        BackgroundTask task = new BackgroundTask("task-1", "executor");
        assertThat(task.getTaskId()).isEqualTo("task-1");
        assertThat(task.getAgentName()).isEqualTo("executor");
        assertThat(task.getStatus()).isEqualTo("pending");
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getResult()).isNull();
        assertThat(task.getError()).isNull();
    }

    @Test
    void setters_work() {
        BackgroundTask task = new BackgroundTask("t", "a");
        Instant now = Instant.now();

        task.setStatus("completed");
        task.setResult("done");
        task.setError(null);
        task.setCompletedAt(now);

        assertThat(task.getStatus()).isEqualTo("completed");
        assertThat(task.getResult()).isEqualTo("done");
        assertThat(task.getCompletedAt()).isEqualTo(now);
    }

    @Test
    void phase2Fields() {
        BackgroundTask task = new BackgroundTask("t", "a");
        Instant start = Instant.now();

        task.setHostId("host-123");
        task.setStepIndex(5);
        task.setStartedAt(start);
        task.setDurationMs(1500L);

        assertThat(task.getHostId()).isEqualTo("host-123");
        assertThat(task.getStepIndex()).isEqualTo(5);
        assertThat(task.getStartedAt()).isEqualTo(start);
        assertThat(task.getDurationMs()).isEqualTo(1500L);
    }
}
