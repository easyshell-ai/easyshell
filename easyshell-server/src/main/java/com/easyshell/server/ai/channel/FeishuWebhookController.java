package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class FeishuWebhookController {

    private final FeishuBotService feishuBotService;
    private final ObjectMapper objectMapper;

    @PostMapping("/feishu")
    public ResponseEntity<?> handleFeishu(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature) {

        try {
            JsonNode body = objectMapper.readTree(rawBody);

            // Handle url_verification challenge
            if (body.has("type") && "url_verification".equals(body.path("type").asText())) {
                return ResponseEntity.ok(Map.of("challenge", body.path("challenge").asText()));
            }

            // Verify signature if headers present
            if (timestamp != null && nonce != null && signature != null) {
                if (!feishuBotService.verifyEventSignature(timestamp, nonce, signature, rawBody)) {
                    log.warn("Feishu webhook signature verification failed");
                    return ResponseEntity.ok(Map.of());
                }
            }

            // Extract event type
            String eventType = body.path("header").path("event_type").asText("");

            if ("im.message.receive_v1".equals(eventType)) {
                // Submit async task to messageExecutor
                var executor = feishuBotService.getMessageExecutor();
                if (executor != null) {
                    executor.submit(() -> {
                        try {
                            String reply = feishuBotService.handleIncomingMessage(body);
                            if (reply != null && !reply.isBlank()) {
                                feishuBotService.pushMessage("webhook", reply);
                            }
                        } catch (Exception e) {
                            log.error("Error handling Feishu message: {}", e.getMessage(), e);
                        }
                    });
                }
            }

            return ResponseEntity.ok(Map.of());
        } catch (Exception e) {
            log.error("Failed to process Feishu webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of());
        }
    }
}
