package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.common.exception.BusinessException;
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
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<List<UserVO>> list() {
        return R.ok(userService.findAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<UserVO> detail(@PathVariable Long id) {
        return R.ok(userService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<UserVO> create(@Valid @RequestBody UserCreateRequest request, Authentication auth,
                             HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        UserVO user = userService.create(request);
        auditLogService.log(userId, AuditUsers.name(auth), "CREATE_USER", "user",
                String.valueOf(user.getId()), request.getUsername(), httpRequest.getRemoteAddr(), "success");
        return R.ok(user);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request,
                            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        // Do not let an administrator lock themselves out by demoting/disabling their own account
        if (userId.equals(id)) {
            if (request.getRole() != null && !"super_admin".equals(request.getRole())) {
                throw new BusinessException(400, "Cannot change your own role");
            }
            if (request.getStatus() != null && request.getStatus() != 1) {
                throw new BusinessException(400, "Cannot disable your own account");
            }
        }
        UserVO user = userService.update(id, request);
        auditLogService.log(userId, AuditUsers.name(auth), "UPDATE_USER", "user",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok(user);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        if (userId.equals(id)) {
            throw new BusinessException(400, "Cannot delete your own account");
        }
        userService.delete(id);
        auditLogService.log(userId, AuditUsers.name(auth), "DELETE_USER", "user",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordResetRequest request,
                                 Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        userService.resetPassword(id, request);
        auditLogService.log(userId, AuditUsers.name(auth), "RESET_PASSWORD", "user",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }
}
