package com.easyshell.server.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;

/**
 * ChatModel implementation for GitHub Copilot models that require the /responses endpoint
 * (e.g. gpt-5.4, gpt-5.3-codex). Translates Spring AI messages to the OpenAI Responses API
 * format and converts streamed SSE events back to ChatResponse objects.
 */
@Slf4j
public class CopilotResponsesChatModel implements ChatModel {

    private final String baseUrl;
    private final String bearerToken;
    private final String model;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public CopilotResponsesChatModel(String baseUrl, String bearerToken, String model) {
        this.baseUrl = baseUrl;
        this.bearerToken = bearerToken;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + bearerToken)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "text/event-stream")
                .defaultHeader("Editor-Version", "vscode/1.80.1")
                .defaultHeader("Editor-Plugin-Version", "copilot.vim/1.16.0")
                .defaultHeader("Copilot-Integration-Id", "vscode-chat")
                .defaultHeader("User-Agent", "EasyShell/1.0.0")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        StringBuilder fullText = new StringBuilder();
        stream(prompt).doOnNext(chunk -> {
            if (chunk.getResult() != null && chunk.getResult().getOutput() != null
                    && chunk.getResult().getOutput().getText() != null) {
                fullText.append(chunk.getResult().getOutput().getText());
            }
        }).blockLast();

        AssistantMessage msg = new AssistantMessage(fullText.toString());
        return new ChatResponse(List.of(new Generation(msg)));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ObjectNode requestBody = buildRequestBody(prompt);
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            return Flux.error(new RuntimeException("Failed to serialize Responses API request", e));
        }

        log.debug("Copilot Responses API request to {}/responses: model={}", baseUrl, model);

        return webClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(this::parseSSELine)
                .onErrorResume(e -> {
                    log.error("Copilot Responses API streaming error: {}", e.getMessage(), e);
                    return Flux.error(e);
                });
    }

    private Flux<ChatResponse> parseSSELine(String line) {
        if (line == null || line.isBlank()) return Flux.empty();

        String trimmed = line.trim();

        if (trimmed.startsWith("event:")) return Flux.empty();

        String data;
        if (trimmed.startsWith("data:")) {
            data = trimmed.substring(5).trim();
        } else {
            data = trimmed;
        }

        if (data.isEmpty() || "[DONE]".equals(data)) return Flux.empty();

        try {
            JsonNode event = objectMapper.readTree(data);
            String eventType = event.path("type").asText("");

            return switch (eventType) {
                case "response.output_text.delta" -> {
                    String delta = event.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        AssistantMessage msg = new AssistantMessage(delta);
                        yield Flux.just(new ChatResponse(List.of(new Generation(msg))));
                    }
                    yield Flux.empty();
                }
                case "response.output_item.added" -> {
                    JsonNode item = event.path("item");
                    String itemType = item.path("type").asText("");
                    if ("function_call".equals(itemType)) {
                        String callId = item.path("call_id").asText("");
                        String name = item.path("name").asText("");
                        var toolCall = new AssistantMessage.ToolCall(callId, "function", name, "");
                        AssistantMessage msg = AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(toolCall))
                                .build();
                        yield Flux.just(new ChatResponse(List.of(new Generation(msg))));
                    }
                    yield Flux.empty();
                }
                case "response.function_call_arguments.delta" -> {
                    String argsDelta = event.path("delta").asText("");
                    if (!argsDelta.isEmpty()) {
                        String itemId = event.path("item_id").asText("");
                        var toolCall = new AssistantMessage.ToolCall(itemId, "function", "", argsDelta);
                        AssistantMessage msg = AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(toolCall))
                                .build();
                        yield Flux.just(new ChatResponse(List.of(new Generation(msg))));
                    }
                    yield Flux.empty();
                }
                case "response.completed", "response.failed" -> {
                    if ("response.failed".equals(eventType)) {
                        String errorMsg = event.at("/response/error/message").asText(
                                event.at("/error/message").asText("Unknown Responses API error"));
                        log.error("Copilot Responses API returned error: {}", errorMsg);
                        AssistantMessage msg = new AssistantMessage("[Error] " + errorMsg);
                        yield Flux.just(new ChatResponse(List.of(new Generation(msg))));
                    }
                    yield Flux.empty();
                }
                default -> Flux.empty();
            };
        } catch (Exception e) {
            log.trace("Failed to parse Responses API SSE event: {}", data, e);
            return Flux.empty();
        }
    }

    private ObjectNode buildRequestBody(Prompt prompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("store", false);

        ArrayNode input = objectMapper.createArrayNode();
        List<Message> messages = prompt.getInstructions();

        for (Message message : messages) {
            MessageType type = message.getMessageType();

            switch (type) {
                case SYSTEM -> {
                    if (body.path("instructions").isMissingNode()) {
                        body.put("instructions", message.getText());
                    } else {
                        body.put("instructions", body.get("instructions").asText() + "\n\n" + message.getText());
                    }
                }
                case USER -> {
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("type", "message");
                    item.put("role", "user");
                    ArrayNode content = objectMapper.createArrayNode();
                    ObjectNode textPart = objectMapper.createObjectNode();
                    textPart.put("type", "input_text");
                    textPart.put("text", message.getText());
                    content.add(textPart);
                    item.set("content", content);
                    input.add(item);
                }
                case ASSISTANT -> {
                    if (message instanceof AssistantMessage assistantMsg) {
                        if (assistantMsg.hasToolCalls()) {
                            for (AssistantMessage.ToolCall tc : assistantMsg.getToolCalls()) {
                                ObjectNode funcCall = objectMapper.createObjectNode();
                                funcCall.put("type", "function_call");
                                funcCall.put("call_id", tc.id());
                                funcCall.put("name", tc.name());
                                funcCall.put("arguments", tc.arguments());
                                input.add(funcCall);
                            }
                        }
                        String text = assistantMsg.getText();
                        if (text != null && !text.isEmpty()) {
                            ObjectNode item = objectMapper.createObjectNode();
                            item.put("type", "message");
                            item.put("role", "assistant");
                            ArrayNode content = objectMapper.createArrayNode();
                            ObjectNode textPart = objectMapper.createObjectNode();
                            textPart.put("type", "output_text");
                            textPart.put("text", text);
                            content.add(textPart);
                            item.set("content", content);
                            input.add(item);
                        }
                    }
                }
                case TOOL -> {
                    if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage toolMsg) {
                        for (var resp : toolMsg.getResponses()) {
                            ObjectNode funcOutput = objectMapper.createObjectNode();
                            funcOutput.put("type", "function_call_output");
                            funcOutput.put("call_id", resp.id());
                            funcOutput.put("output", resp.responseData() != null ? resp.responseData() : "");
                            input.add(funcOutput);
                        }
                    }
                }
            }
        }

        body.set("input", input);

        if (!body.has("instructions")) {
            body.put("instructions", "");
        }

        return body;
    }
}
