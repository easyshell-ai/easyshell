package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ExecutorService;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class SlackWebhookController {

    private final SlackBotService slackBotService;
    private final ObjectMapper objectMapper;

    @PostMapping("/slack")
    public ResponseEntity<?> handleSlack(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp) {

        try {
            JsonNode body = objectMapper.readTree(rawBody);
            String type = body.path("type").asText("");

            // Handle url_verification challenge
            if ("url_verification".equals(type)) {
                return ResponseEntity.ok(Map.of("challenge", body.path("challenge").asText()));
            }

            // Handle event_callback
            if ("event_callback".equals(type)) {
                // Verify signature if provided
                if (signature != null && timestamp != null) {
                    if (!slackBotService.verifySlackSignature(timestamp, rawBody, signature)) {
                        log.warn("Slack webhook signature verification failed");
                        return ResponseEntity.status(401).body("Signature verification failed");
                    }
                }

                JsonNode event = body.path("event");
                String eventType = event.path("type").asText("");

                // Only handle message events, skip bot messages
                if ("message".equals(eventType) && !event.has("bot_id")) {
                    String channelId = event.path("channel").asText("");

                    ExecutorService executor = slackBotService.getMessageExecutor();
                    if (executor != null) {
                        executor.submit(() -> {
                            try {
                                String reply = slackBotService.handleIncomingMessage(body);
                                if (reply != null && !reply.isBlank()) {
                                    slackBotService.sendReplyToChannel(channelId, reply);
                                }
                            } catch (Exception e) {
                                log.error("Error handling Slack message: {}", e.getMessage(), e);
                            }
                        });
                    }
                }

                return ResponseEntity.ok("ok");
            }

            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            log.error("Failed to process Slack webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ok");
        }
    }
}
