package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.vo.HostPermissionQueryVO;
import com.easyshell.server.service.HostPermissionQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/host-permissions")
@PreAuthorize("isAuthenticated()")
public class HostPermissionQueryController {
    private final HostPermissionQueryService service;

    public HostPermissionQueryController(HostPermissionQueryService service) { this.service = service; }

    @GetMapping("/me/execute-targets")
    public R<HostPermissionQueryService.ExecuteTargetsPage> executeTargets(
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletRequest request, Authentication auth, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId) || userId <= 0)
            throw new BusinessException(403, "Authenticated user required");
        String[] limits = request.getParameterValues("limit");
        if (limits != null && limits.length != 1)
            throw new BusinessException(400, "Invalid pagination");
        int limit = 100;
        if (limits != null && !limits[0].isEmpty()) {
            try {
                limit = Integer.parseInt(limits[0].trim());
            } catch (NumberFormatException ex) {
                throw new BusinessException(400, "Invalid pagination");
            }
        }
        return R.ok(service.executeTargets(userId, cursor, limit));
    }

    @PostMapping("/me/query")
    public R<List<HostPermissionQueryVO>> query(@RequestBody JsonNode body, Authentication auth, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId) || userId <= 0)
            throw new BusinessException(403, "Authenticated user required");
        if (!body.isObject() || body.size() != 1 || !body.has("agentIds") || !body.get("agentIds").isArray()
                || body.get("agentIds").size() > 100)
            throw new BusinessException(400, "Invalid agentIds");
        List<String> ids = new ArrayList<>();
        for (JsonNode id : body.get("agentIds")) {
            if (!id.isTextual() || id.textValue().isBlank() || id.textValue().length() > 64)
                throw new BusinessException(400, "Invalid agentIds");
            ids.add(id.textValue());
        }
        return R.ok(service.query(userId, ids));
    }
}
