package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
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
        auditLogService.log(userId, auth.getName(), "CREATE_SCRIPT", "script",
                String.valueOf(script.getId()), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(script);
    }

    @PutMapping("/{id}")
    public R<Script> update(@PathVariable Long id, @Valid @RequestBody ScriptRequest request,
                            Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        Script script = scriptService.update(id, request);
        auditLogService.log(userId, auth.getName(), "UPDATE_SCRIPT", "script",
                String.valueOf(id), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(script);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        scriptService.delete(id);
        auditLogService.log(userId, auth.getName(), "DELETE_SCRIPT", "script",
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
}
