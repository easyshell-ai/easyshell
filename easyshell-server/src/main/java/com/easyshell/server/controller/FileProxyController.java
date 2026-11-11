package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.FileRenameRequest;
import com.easyshell.server.model.vo.FileInfoVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.FileProxyService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * File management on managed hosts. Restricted to administrators: these operations act on the
 * whole host filesystem, not on user-owned resources.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents/{agentId}/files")
@RequiredArgsConstructor
public class FileProxyController {

    private final FileProxyService fileProxyService;
    private final AuditLogService auditLogService;
    private final HostAclService hostAclService;
    private final UserRepository users;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<List<FileInfoVO>> listFiles(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "/") String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAccess(auth, agentId);
        List<FileInfoVO> files = fileProxyService.listFiles(agentId, path);
        auditLogService.log(userId, AuditUsers.name(auth), "FILE_LIST", "FILE",
                agentId, path, httpRequest.getRemoteAddr(), "success");
        return R.ok(files);
    }

    @GetMapping("/download")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable String agentId,
            @RequestParam String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAccess(auth, agentId);
        long generation = requireGeneration(userId, agentId);
        try {
            ResponseEntity<StreamingResponseBody> response = fileProxyService.downloadFile(agentId, path);
            auditLogService.log(userId, AuditUsers.name(auth), "FILE_DOWNLOAD", "FILE",
                    agentId, path, httpRequest.getRemoteAddr(), "success");
            StreamingResponseBody body = response.getBody();
            if (body == null) return response;
            return new ResponseEntity<>(output -> {
                requireCurrentAdmin(userId);
                try {
                    if (!hostAclService.allowedForGeneration(userId, agentId, generation, HostAclService.Permission.TERMINAL)) {
                        throw new BusinessException(403, "Access denied");
                    }
                } catch (RuntimeException ex) {
                    throw new BusinessException(403, "Access denied");
                }
                body.writeTo(output);
            }, response.getHeaders(), response.getStatusCode());
        } catch (Exception e) {
            auditLogService.log(userId, AuditUsers.name(auth), "FILE_DOWNLOAD", "FILE",
                    agentId, path, httpRequest.getRemoteAddr(), "failure");
            throw e;
        }
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<Void> uploadFile(
            @PathVariable String agentId,
            @RequestParam String path,
            @RequestParam("file") MultipartFile file,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAccess(auth, agentId);
        try {
            fileProxyService.uploadFile(agentId, path, file);
        } catch (Exception e) {
            auditLogService.log(userId, AuditUsers.name(auth), "FILE_UPLOAD", "FILE",
                    agentId, path + "/" + file.getOriginalFilename(), httpRequest.getRemoteAddr(), "failure");
            throw e;
        }
        auditLogService.log(userId, AuditUsers.name(auth), "FILE_UPLOAD", "FILE",
                agentId, path + "/" + file.getOriginalFilename(), httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @PostMapping("/mkdir")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<Void> mkdir(
            @PathVariable String agentId,
            @RequestParam String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAccess(auth, agentId);
        try {
            fileProxyService.mkdir(agentId, path);
        } catch (Exception e) {
            auditLogService.log(userId, AuditUsers.name(auth), "FILE_MKDIR", "FILE",
                    agentId, path, httpRequest.getRemoteAddr(), "failure");
            throw e;
        }
        auditLogService.log(userId, AuditUsers.name(auth), "FILE_MKDIR", "FILE",
                agentId, path, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<Void> deleteFile(
            @PathVariable String agentId,
            @RequestParam String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAccess(auth, agentId);
        try {
            fileProxyService.delete(agentId, path);
        } catch (Exception e) {
            auditLogService.log(userId, AuditUsers.name(auth), "FILE_DELETE", "FILE",
                    agentId, path, httpRequest.getRemoteAddr(), "failure");
            throw e;
        }
        auditLogService.log(userId, AuditUsers.name(auth), "FILE_DELETE", "FILE",
                agentId, path, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @PutMapping("/rename")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<Void> rename(
            @PathVariable String agentId,
            @Valid @RequestBody FileRenameRequest renameRequest,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAccess(auth, agentId);
        try {
            fileProxyService.rename(agentId, renameRequest.getOldPath(), renameRequest.getNewPath());
        } catch (Exception e) {
            auditLogService.log(userId, AuditUsers.name(auth), "FILE_RENAME", "FILE",
                    agentId, renameRequest.getOldPath() + " → " + renameRequest.getNewPath(),
                    httpRequest.getRemoteAddr(), "failure");
            throw e;
        }
        auditLogService.log(userId, AuditUsers.name(auth), "FILE_RENAME", "FILE",
                agentId, renameRequest.getOldPath() + " → " + renameRequest.getNewPath(),
                httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    private Long requireAccess(Authentication auth, String agentId) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId) || userId <= 0) {
            throw new BusinessException(401, "Authentication required");
        }
        requireCurrentAdmin(userId);
        requireGeneration(userId, agentId);
        return userId;
    }

    private void requireCurrentAdmin(Long userId) {
        try {
            var user = users.findById(userId).orElse(null);
            if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                    || !("admin".equals(user.getRole()) || "super_admin".equals(user.getRole()))) {
                throw new BusinessException(403, "Access denied");
            }
        } catch (RuntimeException ex) {
            throw new BusinessException(403, "Access denied");
        }
    }

    private long requireGeneration(Long userId, String agentId) {
        try {
            return hostAclService.authorizedGeneration(userId, agentId, HostAclService.Permission.TERMINAL)
                    .orElseThrow(() -> new BusinessException(403, "Access denied"));
        } catch (RuntimeException ex) {
            throw new BusinessException(403, "Access denied");
        }
    }
}
