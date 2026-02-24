package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.LoginRequest;
import com.easyshell.server.model.dto.LoginResponse;
import com.easyshell.server.model.vo.UserVO;
import com.easyshell.server.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public R<LoginResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        return R.ok(authService.refresh(refreshToken));
    }

    @GetMapping("/me")
    public R<UserVO> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return R.ok(authService.getCurrentUser(userId));
    }
}
