package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.TagRequest;
import com.easyshell.server.model.vo.TagVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tag")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;
    private final AuditLogService auditLogService;
    private final HostAclService hostAclService;
    private final UserRepository users;

    @PostMapping
    public R<TagVO> create(@Valid @RequestBody TagRequest request, Authentication auth,
                           HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        TagVO tag = tagService.create(request);
        auditLogService.log(userId, AuditUsers.name(auth), "CREATE_TAG", "tag",
                String.valueOf(tag.getId()), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(tag);
    }

    @PutMapping("/{id}")
    public R<TagVO> update(@PathVariable Long id, @Valid @RequestBody TagRequest request,
                           Authentication auth, HttpServletRequest httpRequest) {
        requireAdmin(auth);
        throw new BusinessException(403, "Global tag modification is unavailable");
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        requireAdmin(auth);
        throw new BusinessException(403, "Global tag deletion is unavailable");
    }

    @GetMapping("/list")
    public R<List<TagVO>> list() {
        return R.ok(tagService.findAll());
    }

    @PostMapping("/{tagId}/agent/{agentId}")
    public R<Void> addTagToAgent(@PathVariable Long tagId, @PathVariable String agentId,
                                 Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        requireView(userId, agentId);
        tagService.addTagToAgent(tagId, agentId);
        auditLogService.log(userId, AuditUsers.name(auth), "ADD_AGENT_TAG", "tag",
                String.valueOf(tagId), agentId, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @DeleteMapping("/{tagId}/agent/{agentId}")
    public R<Void> removeTagFromAgent(@PathVariable Long tagId, @PathVariable String agentId,
                                      Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        requireView(userId, agentId);
        tagService.removeTagFromAgent(tagId, agentId);
        auditLogService.log(userId, AuditUsers.name(auth), "REMOVE_AGENT_TAG", "tag",
                String.valueOf(tagId), agentId, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @GetMapping("/agent/{agentId}")
    public R<List<TagVO>> getAgentTags(@PathVariable String agentId, Authentication auth) {
        requireView(principal(auth), agentId);
        return R.ok(tagService.getAgentTags(agentId));
    }

    private Long principal(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(401, "Authentication required");
        }
        return userId;
    }

    private Long requireAdmin(Authentication auth) {
        Long userId = principal(auth);
        var user = users.findById(userId).orElse(null);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                || !("admin".equals(user.getRole()) || "super_admin".equals(user.getRole()))) {
            throw new BusinessException(403, "Administrator required");
        }
        return userId;
    }

    private void requireView(Long userId, String agentId) {
        if (!hostAclService.allowed(userId, agentId, HostAclService.Permission.VIEW)) {
            throw new BusinessException(403, "Access denied");
        }
    }
}
