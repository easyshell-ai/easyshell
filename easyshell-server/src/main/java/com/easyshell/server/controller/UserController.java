package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.PasswordResetRequest;
import com.easyshell.server.model.dto.UserCreateRequest;
import com.easyshell.server.model.dto.UserUpdateRequest;
import com.easyshell.server.model.vo.UserVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuditLogService auditLogService;

    @GetMapping("/list")
    public R<List<UserVO>> list() {
        return R.ok(userService.findAll());
    }

    @GetMapping("/{id}")
    public R<UserVO> detail(@PathVariable Long id) {
        return R.ok(userService.findById(id));
    }

    @PostMapping
    public R<UserVO> create(@Valid @RequestBody UserCreateRequest request, Authentication auth,
                            HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        UserVO user = userService.create(request);
        auditLogService.log(userId, auth.getName(), "CREATE_USER", "user",
                String.valueOf(user.getId()), request.getUsername(), httpRequest.getRemoteAddr(), "success");
        return R.ok(user);
    }

    @PutMapping("/{id}")
    public R<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request,
                            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        UserVO user = userService.update(id, request);
        auditLogService.log(userId, auth.getName(), "UPDATE_USER", "user",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok(user);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        userService.delete(id);
        auditLogService.log(userId, auth.getName(), "DELETE_USER", "user",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @PutMapping("/{id}/password")
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordResetRequest request,
                                 Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        userService.resetPassword(id, request);
        auditLogService.log(userId, auth.getName(), "RESET_PASSWORD", "user",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }
}
