package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.FileRenameRequest;
import com.easyshell.server.model.vo.FileInfoVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.FileProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/files")
@RequiredArgsConstructor
public class FileProxyController {

    private final FileProxyService fileProxyService;
    private final AuditLogService auditLogService;

    @GetMapping
    public R<List<FileInfoVO>> listFiles(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "/") String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        List<FileInfoVO> files = fileProxyService.listFiles(agentId, path);
        auditLogService.log(userId, auth.getName(), "FILE_LIST", "FILE",
                agentId, path, httpRequest.getRemoteAddr(), "success");
        return R.ok(files);
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable String agentId,
            @RequestParam String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        auditLogService.log(userId, auth.getName(), "FILE_DOWNLOAD", "FILE",
                agentId, path, httpRequest.getRemoteAddr(), "success");
        return fileProxyService.downloadFile(agentId, path);
    }

    @PostMapping("/upload")
    public R<Void> uploadFile(
            @PathVariable String agentId,
            @RequestParam String path,
            @RequestParam("file") MultipartFile file,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        fileProxyService.uploadFile(agentId, path, file);
        auditLogService.log(userId, auth.getName(), "FILE_UPLOAD", "FILE",
                agentId, path + "/" + file.getOriginalFilename(), httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @PostMapping("/mkdir")
    public R<Void> mkdir(
            @PathVariable String agentId,
            @RequestParam String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        fileProxyService.mkdir(agentId, path);
        auditLogService.log(userId, auth.getName(), "FILE_MKDIR", "FILE",
                agentId, path, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @DeleteMapping
    public R<Void> deleteFile(
            @PathVariable String agentId,
            @RequestParam String path,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        fileProxyService.delete(agentId, path);
        auditLogService.log(userId, auth.getName(), "FILE_DELETE", "FILE",
                agentId, path, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @PutMapping("/rename")
    public R<Void> rename(
            @PathVariable String agentId,
            @Valid @RequestBody FileRenameRequest renameRequest,
            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        fileProxyService.rename(agentId, renameRequest.getOldPath(), renameRequest.getNewPath());
        auditLogService.log(userId, auth.getName(), "FILE_RENAME", "FILE",
                agentId, renameRequest.getOldPath() + " → " + renameRequest.getNewPath(),
                httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }
}
