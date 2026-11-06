package com.easyshell.server.ai.channel;

import com.easyshell.server.ai.channel.support.WebhookVerification;
import com.easyshell.server.ai.channel.support.WebhookVerification.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Feishu/Lark event webhook.
 *
 * <p>Signature verification is mandatory: a request without the signature headers is rejected
 * instead of silently bypassing verification.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class FeishuWebhookController {

    private static final long MAX_TIMESTAMP_SKEW_MS = 5 * 60 * 1000L;

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

            if (!feishuBotService.isRunning()) {
                return ResponseEntity.status(503).body(Map.of("error", "feishu channel disabled"));
            }

            Result verification = WebhookVerification.verify(
                    timestamp, nonce, signature, MAX_TIMESTAMP_SKEW_MS,
                    (n, sig) -> feishuBotService.verifyEventSignature(timestamp, n, sig, rawBody));
            if (verification != Result.OK) {
                log.warn("Rejecting Feishu webhook: {}", verification);
                return ResponseEntity.status(verification.httpStatus()).body(Map.of("error", verification.message()));
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
