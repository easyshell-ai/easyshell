package com.easyshell.server.service.impl;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.HostCredential;
import com.easyshell.server.model.vo.HostCredentialVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostCredentialRepository;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("host 统一列表 — hostName/hostname 同时返回 agent 主机名")
class HostProvisionServiceImplTest {

    private HostCredentialRepository credentialRepository;
    private AgentRepository agentRepository;
    private HostProvisionServiceImpl service;

    @BeforeEach
    void setUp() {
        credentialRepository = mock(HostCredentialRepository.class);
        agentRepository = mock(AgentRepository.class);
        service = new HostProvisionServiceImpl(credentialRepository, agentRepository,
                mock(CryptoUtils.class), mock(TransactionTemplate.class), mock(SystemConfigRepository.class));
    }

    private HostCredential credential(String hostName) {
        HostCredential credential = new HostCredential();
        credential.setId(1L);
        credential.setIp("10.0.0.8");
        credential.setSshPort(22);
        credential.setSshUsername("root");
        credential.setAuthType("password");
        credential.setHostName(hostName);
        credential.setProvisionStatus("SUCCESS");
        return credential;
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId("ag-8651");
        agent.setHostname("8651db9de448");
        agent.setIp("10.0.0.8");
        agent.setStatus(1);
        return agent;
    }

    private JsonNode serialize(HostCredentialVO vo) throws Exception {
        return new ObjectMapper().valueToTree(vo);
    }

    @Test
    @DisplayName("credential 匹配 agent：两个字段都取 agent.hostname（不取 credential 别名）")
    void unifiedCredentialUsesAgentHostnameForBothFields() throws Exception {
        when(credentialRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(credential("credential-alias")));
        when(agentRepository.findAll()).thenReturn(List.of(agent()));

        List<HostCredentialVO> result = service.listUnified();
        assertThat(result).hasSize(1);
        HostCredentialVO vo = result.get(0);
        assertThat(vo.getHostName()).isEqualTo("8651db9de448");
        assertThat(vo.getHostname()).isEqualTo("8651db9de448");

        JsonNode json = serialize(vo);
        assertThat(json.path("hostName").asText()).isEqualTo("8651db9de448");
        assertThat(json.path("hostname").asText()).isEqualTo("8651db9de448");
    }

    @Test
    @DisplayName("legacy agent（无 credential）：两个字段也都填充 hostname")
    void legacyAgentPopulatesBothFields() throws Exception {
        when(credentialRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(agentRepository.findAll()).thenReturn(List.of(agent()));

        List<HostCredentialVO> result = service.listUnified();
        assertThat(result).hasSize(1);
        HostCredentialVO vo = result.get(0);
        assertThat(vo.getHostName()).isEqualTo("8651db9de448");
        assertThat(vo.getHostname()).isEqualTo("8651db9de448");

        JsonNode json = serialize(vo);
        assertThat(json.path("hostName").asText()).isEqualTo("8651db9de448");
        assertThat(json.path("hostname").asText()).isEqualTo("8651db9de448");
    }
}
