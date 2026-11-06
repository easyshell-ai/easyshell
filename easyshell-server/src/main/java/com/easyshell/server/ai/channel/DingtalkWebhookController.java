package com.easyshell.server.ai.channel;

import com.easyshell.server.ai.channel.support.WebhookVerification;
import com.easyshell.server.ai.channel.support.WebhookVerification.Result;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DingTalk event webhook. The timestamp + sign pair is mandatory.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class DingtalkWebhookController {

    private static final long MAX_TIMESTAMP_SKEW_MS = 5 * 60 * 1000L;

    private final DingtalkBotService dingtalkBotService;

    @PostMapping("/dingtalk")
    public ResponseEntity<Map<String, Object>> handleDingtalk(
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String sign,
            @RequestBody JsonNode body) {

        if (!dingtalkBotService.isRunning()) {
            return ResponseEntity.status(503).body(Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", "钉钉机器人未启用")
            ));
        }

        Result verification = WebhookVerification.verify(
                timestamp, "", sign, MAX_TIMESTAMP_SKEW_MS,
                (nonce, sig) -> dingtalkBotService.verifySignature(timestamp, sig));
        if (verification != Result.OK) {
            log.warn("Rejecting DingTalk webhook: {}", verification);
            return ResponseEntity.status(verification.httpStatus()).body(Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", "签名验证失败: " + verification.message())
            ));
        }

        String reply = dingtalkBotService.handleIncomingMessage(body);

        return ResponseEntity.ok(Map.of(
                "msgtype", "text",
                "text", Map.of("content", reply)
        ));
    }
}
