package com.easyshell.server.service;

import com.easyshell.server.model.entity.*;
import com.easyshell.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedHostQueryServiceTest {
    @Test void onlyVisibleAgentsAreReturnedWithoutCredentialAssociation() {
        var agents=mock(AgentService.class);var acl=mock(HostAclService.class);var users=mock(UserRepository.class);
        var user=new User();user.setStatus(1);user.setRole("admin");when(users.findById(1L)).thenReturn(Optional.of(user));
        var visible=new Agent();visible.setId("visible");visible.setHostname("visible-host");
        var hidden=new Agent();hidden.setId("hidden");when(agents.findAll()).thenReturn(List.of(visible,hidden));
        when(acl.allowed(1L,"visible",HostAclService.Permission.VIEW)).thenReturn(true);
        var service=new UnifiedHostQueryService(agents,acl,users);var result=service.list(1L);
        assertEquals(1,result.size());assertEquals("visible",result.get(0).getAgentId());
        assertNull(result.get(0).getId());assertNull(result.get(0).getSshUsername());assertNull(result.get(0).getProvisionLog());
        user.setStatus(0);assertThrows(com.easyshell.server.common.exception.BusinessException.class,()->service.list(1L));
    }
}
