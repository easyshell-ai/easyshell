package com.easyshell.server.config;

import com.easyshell.server.ai.agent.AgentDefinitionRepository;
import com.easyshell.server.ai.agent.AgentDefinition;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.HostAclRepository;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DataInitializerTest {
    @Test
    void restartPreservesExistingConfigAndDisabledBuiltinAgent() {
        var acl = mock(HostAclRepository.class);
        var users = mock(UserRepository.class);
        var scripts = mock(ScriptRepository.class);
        var configs = mock(SystemConfigRepository.class);
        var agents = mock(AgentDefinitionRepository.class);
        var passwords = mock(PasswordEncoder.class);
        var configRecords = new HashMap<String, SystemConfig>();
        var agentRecords = new HashMap<String, AgentDefinition>();
        when(acl.lockCompleted()).thenReturn(true);
        when(users.existsByUsername(anyString())).thenReturn(true);
        when(scripts.existsByName(anyString())).thenReturn(true);
        when(configs.findByConfigKey(anyString())).thenAnswer(call ->
                Optional.ofNullable(configRecords.get(call.getArgument(0))));
        when(configs.save(any(SystemConfig.class))).thenAnswer(call -> {
            SystemConfig config = call.getArgument(0);
            configRecords.put(config.getConfigKey(), config);
            return config;
        });
        when(agents.findByName(anyString())).thenAnswer(call ->
                Optional.ofNullable(agentRecords.get(call.getArgument(0))));
        when(agents.save(any(AgentDefinition.class))).thenAnswer(call -> {
            AgentDefinition agent = call.getArgument(0);
            agentRecords.put(agent.getName(), agent);
            return agent;
        });
        var initializer = new DataInitializer(users, passwords, scripts, configs, agents, acl);

        initializer.initialize();
        SystemConfig config = configRecords.get("ai.enabled");
        AgentDefinition agent = agentRecords.get("explore");
        assertEquals("config.desc.ai_enabled", config.getDescription());
        assertEquals("false", config.getConfigValue());
        assertTrue(agent.getEnabled());
        config.setDescription("自定义描述");
        config.setConfigValue("custom value");
        config.setConfigGroup("custom group");
        agent.setEnabled(false);
        agent.setDescription("自定义 agent");
        clearInvocations(configs, agents);

        initializer.initialize();

        assertEquals("自定义描述", config.getDescription());
        assertEquals("custom value", config.getConfigValue());
        assertEquals("custom group", config.getConfigGroup());
        assertFalse(agent.getEnabled());
        assertEquals("自定义 agent", agent.getDescription());
        verify(configs, never()).save(any(SystemConfig.class));
        verify(agents, never()).save(any(AgentDefinition.class));
    }

    @Test
    void incompleteOrUnavailableStatePreventsAllInitializationWrites() {
        for (boolean unavailable : new boolean[]{false, true}) {
            var acl = mock(HostAclRepository.class);
            var users = mock(UserRepository.class);
            var scripts = mock(ScriptRepository.class);
            var configs = mock(SystemConfigRepository.class);
            var agents = mock(AgentDefinitionRepository.class);
            var passwords = mock(PasswordEncoder.class);
            if (unavailable) when(acl.lockCompleted()).thenThrow(new DataAccessResourceFailureException("missing state"));
            else when(acl.lockCompleted()).thenReturn(false);
            var initializer = new DataInitializer(users, passwords, scripts, configs, agents, acl);

            assertThrows(RuntimeException.class, initializer::initialize);
            verifyNoInteractions(users, scripts, configs, agents, passwords);
        }
    }
}
