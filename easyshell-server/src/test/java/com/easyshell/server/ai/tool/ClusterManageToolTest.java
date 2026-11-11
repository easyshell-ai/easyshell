package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.vo.ClusterDetailVO;
import com.easyshell.server.model.vo.ClusterVO;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClusterManageToolTest {
    @Test
    void springCreatesBeanAndUserBindingsRemainIsolated() {
        ClusterService service = mock(ClusterService.class);
        HostAclService acl = mock(HostAclService.class);
        Agent agent = new Agent();
        agent.setId("a");
        agent.setHostname("visible");
        agent.setIp("1.1.1.1");
        agent.setStatus(1);
        when(service.getDetail(1L)).thenReturn(ClusterDetailVO.builder().id(1L).agents(List.of(agent)).build());
        when(acl.allowed(1L, "a", HostAclService.Permission.VIEW)).thenReturn(true);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ClusterService.class, () -> service);
            context.registerBean(HostAclService.class, () -> acl);
            context.register(ClusterManageTool.class);
            context.refresh();

            ClusterManageTool bean = context.getBean(ClusterManageTool.class);
            var first = bean.forUser(1L, "");
            var second = bean.forUser(2L, "");
            assertThat(first.getClusterDetail(1L)).contains("visible");
            assertThat(second.getClusterDetail(1L)).doesNotContain("visible", "1.1.1.1");
            assertThat(first.getClusterDetail(1L)).contains("visible");
            assertThat(bean.getClusterDetail(1L)).contains("拒绝");
            verify(acl).allowed(2L, "a", HostAclService.Permission.VIEW);
        }
    }

    @Test
    void identityAndViewPermissionFilterMembersAndCounts() {
        ClusterService service = mock(ClusterService.class);
        HostAclService acl = mock(HostAclService.class);
        ClusterManageTool tool = new ClusterManageTool(service, acl);
        assertThat(tool.listClusters()).contains("拒绝");
        assertThat(tool.getClusterDetail(1L)).contains("拒绝");
        verifyNoInteractions(service, acl);

        Agent visible = new Agent();
        visible.setId("a"); visible.setHostname("visible"); visible.setIp("1.1.1.1"); visible.setStatus(1);
        Agent hidden = new Agent();
        hidden.setId("b"); hidden.setHostname("secret"); hidden.setIp("2.2.2.2"); hidden.setStatus(1);
        when(service.findAll()).thenReturn(List.of(ClusterVO.builder().id(1L).name("cluster").agentCount(2).build()));
        when(service.getDetail(1L)).thenReturn(ClusterDetailVO.builder().id(1L).name("cluster").agents(List.of(visible, hidden)).build());
        when(acl.allowed(1L, "a", HostAclService.Permission.VIEW)).thenReturn(true);
        var bound = tool.forUser(1L, "");
        assertThat(bound.listClusters()).contains("主机数: 1").doesNotContain("主机数: 2");
        assertThat(bound.getClusterDetail(1L)).contains("visible").doesNotContain("secret", "2.2.2.2");
        verify(acl, atLeastOnce()).allowed(1L, "b", HostAclService.Permission.VIEW);
    }

    @Test
    void aclFailuresDoNotReturnPartialResults() {
        ClusterService service = mock(ClusterService.class);
        HostAclService acl = mock(HostAclService.class);
        Agent agent = new Agent();
        agent.setId("a");
        when(service.findAll()).thenReturn(List.of(ClusterVO.builder().id(1L).name("cluster").build()));
        when(service.getDetail(1L)).thenReturn(ClusterDetailVO.builder().id(1L).agents(List.of(agent)).build());
        when(acl.allowed(42L, "a", HostAclService.Permission.VIEW)).thenThrow(new IllegalStateException("secret"));
        var tool = new ClusterManageTool(service, acl).forUser(42L, "");
        assertThat(tool.listClusters()).contains("失败").doesNotContain("secret", "cluster");
        assertThat(tool.getClusterDetail(1L)).contains("失败").doesNotContain("secret");
    }
}
