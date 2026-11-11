package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.ClusterAgentRequest;
import com.easyshell.server.model.dto.ClusterRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.ClusterAgent;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.model.vo.ClusterDetailVO;
import com.easyshell.server.model.vo.ClusterVO;
import com.easyshell.server.repository.ClusterAgentRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.HostAclService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterControllerTest {
    private final ClusterService clusters = mock(ClusterService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HostAclService acl = mock(HostAclService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ClusterAgentRepository members = mock(ClusterAgentRepository.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private final ClusterController controller = new ClusterController(clusters, audit, acl, users, members);
    private final Authentication auth = new UsernamePasswordAuthenticationToken(1L, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    @Test
    void listAndDetailExposeOnlyViewableMembersAndCounts() {
        Agent visible = agent("visible");
        Agent hidden = agent("hidden");
        when(members.findByClusterId(9L)).thenReturn(List.of(member("visible"), member("hidden")));
        when(clusters.getDetail(9L)).thenAnswer(inv -> ClusterDetailVO.builder().id(9L).agents(List.of(visible, hidden)).build());
        when(clusters.findAll()).thenReturn(List.of(ClusterVO.builder().id(9L).agentCount(2).build()));
        when(acl.allowed(1L, "visible", HostAclService.Permission.VIEW)).thenReturn(true);

        assertEquals(1, controller.list(auth).getData().get(0).getAgentCount());
        assertEquals(List.of(visible), controller.detail(9L, auth).getData().getAgents());
    }

    @Test
    void incompleteMembershipFailsClosed() {
        when(members.findByClusterId(9L)).thenReturn(List.of(member("missing")));
        when(clusters.getDetail(9L)).thenReturn(ClusterDetailVO.builder().id(9L).agents(List.of()).build());
        when(clusters.findAll()).thenReturn(List.of(ClusterVO.builder().id(9L).agentCount(1).build()));
        assertEquals(503, assertThrows(BusinessException.class, () -> controller.detail(9L, auth)).getCode());
        assertEquals(503, assertThrows(BusinessException.class, () -> controller.list(auth)).getCode());
    }

    @Test
    void staleAdminAuthorityCannotChangeMembers() {
        User user = new User(); user.setRole("viewer"); user.setStatus(1);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        ClusterAgentRequest request = new ClusterAgentRequest(); request.setAgentIds(List.of("a"));
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.addAgents(9L, request, auth, http)).getCode());
        verifyNoInteractions(clusters, acl);
    }

    @Test
    void batchChecksEveryTargetBeforeWriting() {
        admin();
        when(acl.allowed(1L, "a", HostAclService.Permission.VIEW)).thenReturn(true);
        ClusterAgentRequest request = new ClusterAgentRequest(); request.setAgentIds(List.of("a", "b"));
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.addAgents(9L, request, auth, http)).getCode());
        verify(clusters, never()).addAgents(anyLong(), anyList());
        verifyNoInteractions(audit);
    }

    @Test
    void authorizedBatchIsDispatchedAfterAllChecks() {
        admin();
        when(acl.allowed(1L, "a", HostAclService.Permission.VIEW)).thenReturn(true);
        when(acl.allowed(1L, "b", HostAclService.Permission.VIEW)).thenReturn(true);
        ClusterAgentRequest request = new ClusterAgentRequest(); request.setAgentIds(List.of("a", "b"));
        controller.addAgents(9L, request, auth, http);
        var order = inOrder(acl, clusters);
        order.verify(acl).allowed(1L, "a", HostAclService.Permission.VIEW);
        order.verify(acl).allowed(1L, "b", HostAclService.Permission.VIEW);
        order.verify(clusters).addAgents(9L, List.of("a", "b"));
    }

    @Test
    void removalAndClusterDeletionRequireAccessToTargets() {
        admin();
        when(members.findByClusterId(9L)).thenReturn(List.of(member("a"), member("b")));
        when(acl.allowed(1L, "a", HostAclService.Permission.VIEW)).thenReturn(true);
        assertThrows(BusinessException.class, () -> controller.removeAgent(9L, "b", auth, http));
        assertThrows(BusinessException.class, () -> controller.delete(9L, auth, http));
        verify(clusters, never()).removeAgent(anyLong(), anyString());
        verify(clusters, never()).delete(anyLong());
    }

    @Test
    void updateReturnsFilteredCount() {
        admin();
        when(members.findByClusterId(9L)).thenReturn(List.of(member("a")));
        when(clusters.getDetail(9L)).thenReturn(ClusterDetailVO.builder().id(9L).agents(List.of(agent("a"))).build());
        when(clusters.update(eq(9L), any())).thenReturn(ClusterVO.builder().agentCount(1).build());
        ClusterRequest request = new ClusterRequest(); request.setName("name");
        assertEquals(0, controller.update(9L, request, auth, http).getData().getAgentCount());
    }

    private void admin() {
        User user = new User(); user.setRole("admin"); user.setStatus(1);
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    private Agent agent(String id) { Agent agent = new Agent(); agent.setId(id); return agent; }
    private ClusterAgent member(String id) { ClusterAgent member = new ClusterAgent(); member.setAgentId(id); return member; }
}
