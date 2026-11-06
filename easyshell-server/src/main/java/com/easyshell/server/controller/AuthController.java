package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.LoginRequest;
import com.easyshell.server.model.dto.LoginResponse;
import com.easyshell.server.model.vo.UserVO;
import com.easyshell.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<R<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                  HttpServletRequest httpRequest) {
        try {
            return ResponseEntity.ok(R.ok(authService.login(request, httpRequest.getRemoteAddr())));
        } catch (BusinessException e) {
            // Surface the real status code (401/403/429) instead of HTTP 200 + body code,
            // so that brute force attempts are visible to monitoring and WAFs.
            return ResponseEntity.status(e.getCode()).body(R.fail(e.getCode(), e.getMessage()));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<R<LoginResponse>> refresh(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(R.ok(authService.refresh(body.get("refreshToken"))));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getCode()).body(R.fail(e.getCode(), e.getMessage()));
        }
    }

    @GetMapping("/me")
    public R<UserVO> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return R.ok(authService.getCurrentUser(userId));
    }
}
