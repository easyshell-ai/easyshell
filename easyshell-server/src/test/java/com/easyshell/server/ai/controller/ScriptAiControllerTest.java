package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.orchestrator.AgentEvent;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptAiControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void filtersActualModelRequestAndPreservesStreaming(boolean modification) {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(factory.getChatModel(null)).thenReturn(model);
        AtomicReference<Prompt> captured = new AtomicReference<>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("hello")))));
        });
        var controller = new ScriptAiController(factory, new SensitiveDataFilter());
        var request = new ScriptAiController.ScriptGenerateRequest();
        request.setPrompt("Print status password=promptSecret token=promptToken");
        request.setScriptType("bash password=typeSecret");
        if (modification) {
            request.setExistingScript("echo healthy\npassword=scriptSecret\ntoken=scriptToken");
        }

        var response = controller.generateScript(request, null);
        List<ServerSentEvent<AgentEvent>> events = response.getBody().collectList().block(Duration.ofSeconds(5));
        assertThat(captured.get()).isNotNull();
        String text = String.join("\n", captured.get().getInstructions().stream().map(Message::getText).toList());
        assertThat(text).contains("Print status", "bash", "password=***", "token=***")
                .doesNotContain("promptSecret", "promptToken", "typeSecret", "scriptSecret", "scriptToken");
        if (modification) assertThat(text).contains("echo healthy", "EXISTING SCRIPT TO MODIFY");
        assertThat(events).extracting(event -> event.data().getType())
                .containsExactly(AgentEvent.Type.CONTENT, AgentEvent.Type.DONE);
        assertThat(events.get(0).data().getContent()).isEqualTo("hello");
    }

    @Test
    void providerErrorDoesNotEchoSensitiveException() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(factory.getChatModel(null)).thenReturn(model);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("password=privateValue")));
        var controller = new ScriptAiController(factory, new SensitiveDataFilter());
        var request = new ScriptAiController.ScriptGenerateRequest();
        request.setPrompt("generate a script");

        var events = controller.generateScript(request, null).getBody().collectList().block(Duration.ofSeconds(5));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data().getType()).isEqualTo(AgentEvent.Type.ERROR);
        assertThat(events.get(0).data().getContent()).isEqualTo("Script generation failed");
    }
}
