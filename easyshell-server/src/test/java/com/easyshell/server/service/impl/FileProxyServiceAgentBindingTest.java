package com.easyshell.server.service.impl;

import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileProxyServiceAgentBindingTest {
    private final AgentWebSocketHandler handler = mock(AgentWebSocketHandler.class);
    private final FileProxyServiceImpl service = new FileProxyServiceImpl(handler);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ordinaryResponseCannotBeForgedByAnotherAgent() throws Exception {
        when(handler.sendToAgent(eq("owner"), anyString())).thenAnswer(call -> {
            String id = mapper.readTree((String) call.getArgument(1)).get("requestId").asText();
            service.handleFileResponse("attacker", id, "{\"success\":false,\"error\":\"forged\"}");
            service.handleFileResponse("owner", id, "{\"success\":true,\"entries\":[]}");
            return true;
        });

        assertTrue(service.listFiles("owner", "/test").isEmpty());
    }

    @Test
    void downloadRejectsForgedFirstAndSubsequentChunks() throws Exception {
        when(handler.sendToAgent(eq("owner"), anyString())).thenAnswer(call -> {
            String id = mapper.readTree((String) call.getArgument(1)).get("requestId").asText();
            service.handleFileResponse("attacker", id, "{\"success\":false,\"error\":\"forged\"}");
            service.handleFileResponse("owner", id, "{\"data\":\"YQ==\",\"totalSize\":2,\"final\":false}");
            service.handleFileResponse("attacker", id, "{\"data\":\"eA==\",\"final\":true}");
            service.handleFileResponse("owner", id, "{\"data\":\"Yg==\",\"final\":true}");
            return true;
        });

        var response = service.downloadFile("owner", "/test");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertNotNull(response.getBody());
        response.getBody().writeTo(output);
        assertEquals("ab", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void uploadStartAndChunkAcknowledgementsCannotBeForged() throws Exception {
        when(handler.sendToAgent(eq("owner"), anyString())).thenAnswer(call -> {
            JsonNode message = mapper.readTree((String) call.getArgument(1));
            String id = message.get("requestId").asText();
            service.handleFileResponse("attacker", id, "{\"success\":false,\"error\":\"forged\"}");
            service.handleFileResponse("owner", id, "{\"success\":true}");
            return true;
        });

        service.uploadFile("owner", "/test", new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1}));
        verify(handler, times(2)).sendToAgent(eq("owner"), anyString());
    }
}
