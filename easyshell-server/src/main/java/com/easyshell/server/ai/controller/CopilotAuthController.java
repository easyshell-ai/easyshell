package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.ai.service.CopilotAuthService;
import com.easyshell.server.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai/copilot")
@RequiredArgsConstructor
public class CopilotAuthController {

    private final CopilotAuthService copilotAuthService;
    private final ChatModelFactory chatModelFactory;
    @PostMapping("/device-code")
    public R<CopilotAuthService.DeviceCodeResponse> requestDeviceCode() {
        return R.ok(copilotAuthService.requestDeviceCode());
    }

    @PostMapping("/poll-token")
    public R<Map<String, String>> pollForToken(@RequestBody Map<String, String> body) {
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || deviceCode.isBlank()) {
            return R.fail(400, "deviceCode \u4e0d\u80fd\u4e3a\u7a7a");
        }
        Map<String, String> result = copilotAuthService.pollForToken(deviceCode);
        // Invalidate cached ChatModel so new bearer token is picked up
        if ("success".equals(result.get("status"))) {
            chatModelFactory.invalidateCache();
        }
        return R.ok(result);
    }

    @GetMapping("/status")
    public R<Map<String, Object>> getAuthStatus() {
        boolean authenticated = copilotAuthService.isAuthenticated();
        return R.ok(Map.of("authenticated", authenticated));
    }

    @DeleteMapping("/logout")
    public R<Void> logout() {
        copilotAuthService.logout();
        chatModelFactory.invalidateCache();
        return R.ok();
    }

    @GetMapping("/models")
    public R<List<Map<String, Object>>> listModels() {
        return R.ok(copilotAuthService.listModels());
    }
}
