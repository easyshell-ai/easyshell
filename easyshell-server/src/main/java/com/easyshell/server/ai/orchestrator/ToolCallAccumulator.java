package com.easyshell.server.ai.orchestrator;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolCallAccumulator {

    private final Map<String, PartialToolCall> partials = new LinkedHashMap<>();

    public void accumulate(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null) return;
        for (AssistantMessage.ToolCall tc : toolCalls) {
            String id = tc.id();
            if (id == null || id.isEmpty()) {
                // Some providers send incomplete chunks without id; skip
                continue;
            }
            partials.computeIfAbsent(id, k -> {
                PartialToolCall partial = new PartialToolCall();
                partial.id = id;
                partial.type = tc.type();
                partial.name = tc.name();
                return partial;
            });
            PartialToolCall partial = partials.get(id);
            if (tc.name() != null && !tc.name().isEmpty()) {
                partial.name = tc.name();
            }
            if (tc.arguments() != null) {
                partial.arguments.append(tc.arguments());
            }
        }
    }

    public List<AssistantMessage.ToolCall> getCompleted() {
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (PartialToolCall p : partials.values()) {
            if (p.name != null && !p.name.isEmpty()) {
                result.add(new AssistantMessage.ToolCall(
                        p.id, p.type, p.name, p.arguments.toString()));
            }
        }
        return result;
    }

    public void reset() {
        partials.clear();
    }

    private static class PartialToolCall {
        String id;
        String type;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
