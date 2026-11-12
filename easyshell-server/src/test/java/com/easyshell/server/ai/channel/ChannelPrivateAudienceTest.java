package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelPrivateAudienceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void telegramRejectsGroupsAndMismatchedSubjectsBeforeRouting() throws Exception {
        var router = mock(ChannelMessageRouter.class);
        var bot = new TelegramBotService(router, mapper);
        ReflectionTestUtils.setField(bot, "allowedChatIds", java.util.Set.of());
        var requests = new ArrayList<String>();
        ReflectionTestUtils.setField(bot, "webClient", client(requests, "{}"));
        for (String chat : List.of("{\"id\":123,\"type\":\"group\"}",
                "{\"id\":999,\"type\":\"private\"}")) {
            var update = mapper.readTree("{\"message\":{\"chat\":" + chat
                    + ",\"from\":{\"id\":123,\"username\":\"mutable\"},\"text\":\"hello\"}}");
            ReflectionTestUtils.invokeMethod(bot, "handleUpdate", update);
        }
        verifyNoInteractions(router);
        assertTrue(requests.isEmpty());
        when(router.routeMessage("telegram", "hello", "123")).thenReturn("reply");
        ReflectionTestUtils.invokeMethod(bot, "handleUpdate", mapper.readTree(
                "{\"message\":{\"chat\":{\"id\":123,\"type\":\"private\"},"
                + "\"from\":{\"id\":123,\"username\":\"mutable\"},\"text\":\"hello\"}}"));
        verify(router).routeMessage("telegram", "hello", "123");
    }

    @Test void discordRequiresMatchingOneToOneMetadataBeforeRoutingOrSending() {
        var router = mock(ChannelMessageRouter.class);
        var bot = new DiscordBotService(router, mapper);
        for (String metadata : List.of("{\"id\":\"111\",\"type\":0}",
                "{\"id\":\"111\",\"type\":3}", "{\"id\":\"999\",\"type\":1}", "{}", "invalid")) {
            var requests = new ArrayList<String>();
            ReflectionTestUtils.setField(bot, "webClient", client(requests, metadata));
            ReflectionTestUtils.invokeMethod(bot, "handleDiscordMessage", "111", "hello", "111:222");
            assertEquals(List.of("GET /channels/111"), requests);
        }
        verifyNoInteractions(router);
        when(router.routeMessage("discord", "hello", "111:222")).thenReturn("reply");
        var requests = new ArrayList<String>();
        ReflectionTestUtils.setField(bot, "webClient", client(requests, "{\"id\":\"111\",\"type\":1}"));
        ReflectionTestUtils.invokeMethod(bot, "handleDiscordMessage", "111", "hello", "111:222");
        verify(router).routeMessage("discord", "hello", "111:222");
        assertEquals(List.of("GET /channels/111", "POST /channels/111/messages"), requests);
    }

    private WebClient client(List<String> requests, String body) {
        return WebClient.builder().exchangeFunction(request -> {
            requests.add(request.method() + " " + request.url().getPath());
            return Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build());
        }).build();
    }
}
