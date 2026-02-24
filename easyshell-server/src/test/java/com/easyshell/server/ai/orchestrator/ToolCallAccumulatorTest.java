package com.easyshell.server.ai.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolCallAccumulator")
class ToolCallAccumulatorTest {

    private ToolCallAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new ToolCallAccumulator();
    }

    @Test
    void singleCompleteToolCall() {
        var tc = new AssistantMessage.ToolCall("call_1", "function", "listHosts", "{\"page\":1}");
        accumulator.accumulate(List.of(tc));

        List<AssistantMessage.ToolCall> completed = accumulator.getCompleted();
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).id()).isEqualTo("call_1");
        assertThat(completed.get(0).name()).isEqualTo("listHosts");
        assertThat(completed.get(0).arguments()).isEqualTo("{\"page\":1}");
    }

    @Test
    void multiChunkArgumentAccumulation() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "executeScript", "{\"scr")
        ));
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "", "ipt\":\"ls")
        ));
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "", " -la\"}")
        ));

        List<AssistantMessage.ToolCall> completed = accumulator.getCompleted();
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).name()).isEqualTo("executeScript");
        assertThat(completed.get(0).arguments()).isEqualTo("{\"script\":\"ls -la\"}");
    }

    @Test
    void multipleParallelToolCalls() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "listHosts", "{\"all\":true}"),
                new AssistantMessage.ToolCall("call_2", "function", "monitoring", "{\"type\":\"cpu\"}")
        ));

        List<AssistantMessage.ToolCall> completed = accumulator.getCompleted();
        assertThat(completed).hasSize(2);
        assertThat(completed.get(0).id()).isEqualTo("call_1");
        assertThat(completed.get(1).id()).isEqualTo("call_2");
    }

    @Test
    void resetClearsState() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "listHosts", "{}")
        ));
        assertThat(accumulator.getCompleted()).hasSize(1);

        accumulator.reset();
        assertThat(accumulator.getCompleted()).isEmpty();
    }

    @Test
    void nullInput_noEffect() {
        accumulator.accumulate(null);
        assertThat(accumulator.getCompleted()).isEmpty();
    }

    @Test
    void emptyIdChunks_skipped() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("", "function", "tool", "{}")
        ));
        assertThat(accumulator.getCompleted()).isEmpty();
    }

    @Test
    void nullIdChunks_skipped() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall(null, "function", "tool", "{}")
        ));
        assertThat(accumulator.getCompleted()).isEmpty();
    }

    @Test
    void toolCallWithoutName_notInCompleted() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", null, "{}")
        ));
        assertThat(accumulator.getCompleted()).isEmpty();
    }

    @Test
    void toolCallWithEmptyName_notInCompleted() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "", "{}")
        ));
        assertThat(accumulator.getCompleted()).isEmpty();
    }

    @Test
    void laterChunkUpdatesName() {
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "", "{\"a\":")
        ));
        accumulator.accumulate(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "myTool", "1}")
        ));

        List<AssistantMessage.ToolCall> completed = accumulator.getCompleted();
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).name()).isEqualTo("myTool");
        assertThat(completed.get(0).arguments()).isEqualTo("{\"a\":1}");
    }
}
