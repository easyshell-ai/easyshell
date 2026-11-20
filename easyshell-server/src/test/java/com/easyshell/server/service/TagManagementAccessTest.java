package com.easyshell.server.service;

import com.easyshell.server.model.entity.*;
import com.easyshell.server.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TagManagementAccessTest {
    @Test
    void allAttachedHostsMustBeVisibleAndActiveDatabaseRoleIsRequired() {
        var tags=mock(TagRepository.class);var members=mock(AgentTagRepository.class);
        var users=mock(UserRepository.class);var acl=mock(HostAclService.class);
        var access=new TagManagementAccess(tags,members,users,acl);
        var user=new User();user.setRole("admin");user.setStatus(1);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(tags.lockById(2L)).thenReturn(Optional.of(new Tag()));
        var a=new AgentTag();a.setAgentId("visible");var b=new AgentTag();b.setAgentId("hidden");
        when(members.findByTagId(2L)).thenReturn(List.of(a,b));
        when(acl.allowed(1L,"visible",HostAclService.Permission.VIEW)).thenReturn(true);
        assertThrows(com.easyshell.server.common.exception.BusinessException.class,()->access.requireManage(1L,2L));
        when(acl.allowed(1L,"hidden",HostAclService.Permission.VIEW)).thenReturn(true);
        assertDoesNotThrow(()->access.requireManage(1L,2L));
        user.setStatus(0);
        assertThrows(com.easyshell.server.common.exception.BusinessException.class,()->access.requireManage(1L,2L));
    }
}
