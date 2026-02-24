package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class DingtalkWebhookController {

    private final DingtalkBotService dingtalkBotService;

    @PostMapping("/dingtalk")
    public Map<String, Object> handleDingtalk(
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String sign,
            @RequestBody JsonNode body) {

        // Verify signature if provided
        if (timestamp != null && sign != null) {
            if (!dingtalkBotService.verifySignature(timestamp, sign)) {
                log.warn("DingTalk webhook signature verification failed");
                return Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", "签名验证失败")
                );
            }
        }

        String reply = dingtalkBotService.handleIncomingMessage(body);

        return Map.of(
                "msgtype", "text",
                "text", Map.of("content", reply)
        );
    }
}