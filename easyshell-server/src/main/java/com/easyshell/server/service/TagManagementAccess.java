package com.easyshell.server.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.TagRepository;
import com.easyshell.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Global tag changes affect every attached host; partial visibility cannot authorize them. */
@Service
@RequiredArgsConstructor
public class TagManagementAccess {
    private final TagRepository tags;
    private final AgentTagRepository members;
    private final UserRepository users;
    private final HostAclService acl;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireManage(Long actor, Long tagId) {
        var user=users.findById(actor).orElseThrow(()->new BusinessException(403,"Administrator required"));
        if (!Integer.valueOf(1).equals(user.getStatus())
                || !("admin".equals(user.getRole()) || "super_admin".equals(user.getRole())))
            throw new BusinessException(403,"Administrator required");
        tags.lockById(tagId).orElseThrow(()->new BusinessException(404,"Tag not found"));
        var attached=members.findByTagId(tagId);
        if (attached==null) throw new BusinessException(503,"Tag members unavailable");
        for (var member:attached) {
            if (member==null || member.getAgentId()==null
                    || !acl.allowed(actor,member.getAgentId(),HostAclService.Permission.VIEW))
                throw new BusinessException(403,"Host view permission required for every tag member");
        }
    }
}
