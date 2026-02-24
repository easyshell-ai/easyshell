package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.TagRequest;
import com.easyshell.server.model.vo.TagVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.TagService;
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

    @PostMapping
    public R<TagVO> create(@Valid @RequestBody TagRequest request, Authentication auth,
                           HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        TagVO tag = tagService.create(request);
        auditLogService.log(userId, auth.getName(), "CREATE_TAG", "tag",
                String.valueOf(tag.getId()), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(tag);
    }

    @PutMapping("/{id}")
    public R<TagVO> update(@PathVariable Long id, @Valid @RequestBody TagRequest request,
                           Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        TagVO tag = tagService.update(id, request);
        auditLogService.log(userId, auth.getName(), "UPDATE_TAG", "tag",
                String.valueOf(id), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(tag);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        tagService.delete(id);
        auditLogService.log(userId, auth.getName(), "DELETE_TAG", "tag",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @GetMapping("/list")
    public R<List<TagVO>> list() {
        return R.ok(tagService.findAll());
    }

    @PostMapping("/{tagId}/agent/{agentId}")
    public R<Void> addTagToAgent(@PathVariable Long tagId, @PathVariable String agentId,
                                 Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        tagService.addTagToAgent(tagId, agentId);
        auditLogService.log(userId, auth.getName(), "ADD_AGENT_TAG", "tag",
                String.valueOf(tagId), agentId, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @DeleteMapping("/{tagId}/agent/{agentId}")
    public R<Void> removeTagFromAgent(@PathVariable Long tagId, @PathVariable String agentId,
                                      Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        tagService.removeTagFromAgent(tagId, agentId);
        auditLogService.log(userId, auth.getName(), "REMOVE_AGENT_TAG", "tag",
                String.valueOf(tagId), agentId, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @GetMapping("/agent/{agentId}")
    public R<List<TagVO>> getAgentTags(@PathVariable String agentId) {
        return R.ok(tagService.getAgentTags(agentId));
    }
}
