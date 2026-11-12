package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.*;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostTagTransactionTest {
    @Test void userBoundCopyRunsMembershipWriteInsideExplicitTransactionAndTagLock() {
        var agents=mock(AgentRepository.class);var tags=mock(TagRepository.class);
        var members=mock(AgentTagRepository.class);var acl=mock(HostAclService.class);var users=mock(UserRepository.class);
        var manager=mock(PlatformTransactionManager.class);var status=mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var user=new User();user.setRole("admin");user.setStatus(1);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(acl.allowed(1L,"host",HostAclService.Permission.VIEW)).thenReturn(true);
        var agent=new Agent();agent.setHostname("host");when(agents.findById("host")).thenReturn(Optional.of(agent));
        var tag=new Tag();tag.setId(2L);when(tags.findByName("tag")).thenReturn(Optional.of(tag));
        when(tags.lockById(2L)).thenReturn(Optional.of(tag));
        var tool=new HostTagTool(agents,tags,members,acl,users);tool.setTransactions(manager);
        assertTrue(tool.forUser(1L,null).addTagToHost("host","tag").contains("添加"));
        var order=inOrder(manager,tags,members);
        order.verify(manager).getTransaction(any());order.verify(tags).findByName("tag");
        order.verify(tags).lockById(2L);order.verify(members).existsByAgentIdAndTagId("host",2L);
        order.verify(members).save(any());order.verify(manager).commit(status);
    }
}
