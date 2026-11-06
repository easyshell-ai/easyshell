package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.FileRenameRequest;
import com.easyshell.server.model.vo.FileInfoVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.FileProxyService;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<List<FileInfoVO>> listFiles(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "/") String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
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
        Long userId = (Long) auth.getPrincipal();
        try {
            ResponseEntity<StreamingResponseBody> response = fileProxyService.downloadFile(agentId, path);
            auditLogService.log(userId, AuditUsers.name(auth), "FILE_DOWNLOAD", "FILE",
                    agentId, path, httpRequest.getRemoteAddr(), "success");
            return response;
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
        Long userId = (Long) auth.getPrincipal();
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
        Long userId = (Long) auth.getPrincipal();
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
        Long userId = (Long) auth.getPrincipal();
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
        Long userId = (Long) auth.getPrincipal();
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
}
