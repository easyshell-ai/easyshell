package com.easyshell.server.ai.controller;

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

    @PostMapping("/device-code")
    public R<CopilotAuthService.DeviceCodeResponse> requestDeviceCode() {
        return R.ok(copilotAuthService.requestDeviceCode());
    }

    @PostMapping("/poll-token")
    public R<Map<String, String>> pollForToken(@RequestBody Map<String, String> body) {
        String deviceCode = body.get("deviceCode");
        if (deviceCode == null || deviceCode.isBlank()) {
            return R.fail(400, "deviceCode 不能为空");
        }
        return R.ok(copilotAuthService.pollForToken(deviceCode));
    }

    @GetMapping("/status")
    public R<Map<String, Object>> getAuthStatus() {
        boolean authenticated = copilotAuthService.isAuthenticated();
        return R.ok(Map.of("authenticated", authenticated));
    }

    @DeleteMapping("/logout")
    public R<Void> logout() {
        copilotAuthService.logout();
        return R.ok();
    }

    @GetMapping("/models")
    public R<List<Map<String, Object>>> listModels() {
        return R.ok(copilotAuthService.listModels());
    }
}
