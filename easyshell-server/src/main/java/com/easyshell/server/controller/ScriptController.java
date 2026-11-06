package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.common.utils.ScriptParameterUtils;
import com.easyshell.server.model.dto.ScriptRequest;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ScriptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/script")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;
    private final AuditLogService auditLogService;

    @PostMapping
    public R<Script> create(@Valid @RequestBody ScriptRequest request, Authentication auth,
                            HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        Script script = scriptService.create(request, userId);
        auditLogService.log(userId, AuditUsers.name(auth), "CREATE_SCRIPT", "script",
                String.valueOf(script.getId()), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(script);
    }

    @PutMapping("/{id}")
    public R<Script> update(@PathVariable Long id, @Valid @RequestBody ScriptRequest request,
                            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        Script existing = scriptService.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Script not found"));
        requireManagePermission(auth, existing);
        Script script = scriptService.update(id, request);
        auditLogService.log(userId, AuditUsers.name(auth), "UPDATE_SCRIPT", "script",
                String.valueOf(id), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(script);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        Script existing = scriptService.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Script not found"));
        requireManagePermission(auth, existing);
        scriptService.delete(id);
        auditLogService.log(userId, AuditUsers.name(auth), "DELETE_SCRIPT", "script",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @GetMapping("/{id}")
    public R<Script> detail(@PathVariable Long id) {
        return scriptService.findById(id)
                .map(R::ok)
                .orElse(R.fail(404, "Script not found"));
    }

    @GetMapping("/list")
    public R<List<Script>> list() {
        return R.ok(scriptService.findAll());
    }

    @GetMapping("/templates")
    public R<List<Script>> templates() {
        return R.ok(scriptService.findTemplates());
    }

    @GetMapping("/user-scripts")
    public R<List<Script>> userScripts() {
        return R.ok(scriptService.findUserScripts());
    }

    @PostMapping("/parse-parameters")
    public R<Set<String>> parseParameters(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return R.ok(Set.of());
        }
        return R.ok(ScriptParameterUtils.extractParameters(content));
    }

    @GetMapping("/{id}/parameters")
    public R<Set<String>> getScriptParameters(@PathVariable Long id) {
        return scriptService.findById(id)
                .map(script -> R.ok(ScriptParameterUtils.extractParameters(script.getContent())))
                .orElse(R.fail(404, "Script not found"));
    }

    /**
     * Horizontal privilege control: a script may only be modified by its creator or a super admin.
     */
    private void requireManagePermission(Authentication auth, Script script) {
        boolean isSuperAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        Long userId = (Long) auth.getPrincipal();
        boolean isOwner = script.getCreatedBy() != null && script.getCreatedBy().equals(userId);
        if (!isSuperAdmin && !isOwner) {
            throw new BusinessException(403, "Not allowed to modify this script");
        }
    }
}
