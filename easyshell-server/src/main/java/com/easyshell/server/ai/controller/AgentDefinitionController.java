package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.agent.AgentDefinition;
import com.easyshell.server.ai.agent.AgentDefinitionRepository;
import com.easyshell.server.ai.model.dto.AgentDefinitionRequest;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.ai.tool.*;
import com.easyshell.server.ai.agent.DelegateTaskTool;
import com.easyshell.server.ai.agent.GetTaskResultTool;
import com.easyshell.server.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentDefinitionController {

    private static final Set<String> BUILT_IN_AGENTS = Set.of(
            "orchestrator", "explore", "execute", "analyze", "planner", "reviewer"
    );

    private final AgentDefinitionRepository agentDefinitionRepository;
    private final HostListTool hostListTool;
    private final HostTagTool hostTagTool;
    private final ScriptExecuteTool scriptExecuteTool;
    private final SoftwareDetectTool softwareDetectTool;
    private final TaskManageTool taskManageTool;
    private final ScriptManageTool scriptManageTool;
    private final ClusterManageTool clusterManageTool;
    private final MonitoringTool monitoringTool;
    private final AuditQueryTool auditQueryTool;
    private final ScheduledTaskTool scheduledTaskTool;
    private final SubAgentTool subAgentTool;
    private final DelegateTaskTool delegateTaskTool;
    private final GetTaskResultTool getTaskResultTool;
    private final ChatModelFactory chatModelFactory;

    @GetMapping("/available-tools")
    public R<List<Map<String, String>>> getAvailableTools() {
        Object[] tools = {
                hostListTool, hostTagTool, scriptExecuteTool, softwareDetectTool,
                taskManageTool, scriptManageTool, clusterManageTool,
                monitoringTool, auditQueryTool, scheduledTaskTool,
                subAgentTool, delegateTaskTool, getTaskResultTool
        };
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        List<Map<String, String>> result = Arrays.stream(callbacks)
                .map(cb -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", cb.getToolDefinition().name());
                    m.put("description", cb.getToolDefinition().description());
                    return m;
                })
                .collect(Collectors.toList());
        return R.ok(result);
    }

    @GetMapping("/available-providers")
    public R<List<Map<String, Object>>> getAvailableProviders() {
        String[] knownProviders = {"openai", "anthropic", "gemini", "ollama"};
        List<Map<String, Object>> result = new ArrayList<>();
        for (String prov : knownProviders) {
            boolean configured = isProviderConfigured(prov);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", prov);
            m.put("configured", configured);
            m.put("model", chatModelFactory.getConfigValue("ai." + prov + ".model", ""));
            result.add(m);
        }
        return R.ok(result);
    }

    private boolean isProviderConfigured(String provider) {
        String apiKey = chatModelFactory.getConfigValue("ai." + provider + ".api-key", "");
        if (!apiKey.isBlank()) return true;
        if ("ollama".equals(provider)) {
            String baseUrl = chatModelFactory.getConfigValue("ai." + provider + ".base-url", "");
            return !baseUrl.isBlank();
        }
        return false;
    }

    @GetMapping
    public R<List<AgentDefinition>> listAll() {
        return R.ok(agentDefinitionRepository.findAllByOrderByIdAsc());
    }

    @GetMapping("/{id}")
    public R<AgentDefinition> getById(@PathVariable Long id) {
        return agentDefinitionRepository.findById(id)
                .map(R::ok)
                .orElse(R.fail(404, "Agent not found"));
    }

    @PostMapping
    public R<AgentDefinition> create(@Valid @RequestBody AgentDefinitionRequest request) {
        if (agentDefinitionRepository.existsByName(request.getName())) {
            return R.fail("Agent name already exists: " + request.getName());
        }

        AgentDefinition agent = new AgentDefinition();
        agent.setName(request.getName());
        agent.setDisplayName(request.getDisplayName());
        agent.setMode(request.getMode());
        agent.setPermissions(request.getPermissions());
        agent.setModelProvider(request.getModelProvider());
        agent.setModelName(request.getModelName());
        agent.setSystemPrompt(request.getSystemPrompt());
        agent.setMaxIterations(request.getMaxIterations() != null ? request.getMaxIterations() : 5);
        agent.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        agent.setDescription(request.getDescription());

        AgentDefinition saved = agentDefinitionRepository.save(agent);
        chatModelFactory.invalidateCache();
        return R.ok(saved);
    }

    @PutMapping("/{id}")
    public R<AgentDefinition> update(@PathVariable Long id, @Valid @RequestBody AgentDefinitionRequest request) {
        return agentDefinitionRepository.findById(id)
                .map(agent -> {
                    agent.setDisplayName(request.getDisplayName());
                    agent.setMode(request.getMode());
                    agent.setPermissions(request.getPermissions());
                    agent.setModelProvider(request.getModelProvider());
                    agent.setModelName(request.getModelName());
                    agent.setSystemPrompt(request.getSystemPrompt());
                    if (request.getMaxIterations() != null) agent.setMaxIterations(request.getMaxIterations());
                    if (request.getEnabled() != null) agent.setEnabled(request.getEnabled());
                    agent.setDescription(request.getDescription());
                    AgentDefinition saved = agentDefinitionRepository.save(agent);
                    chatModelFactory.invalidateCache();
                    return R.ok(saved);
                })
                .orElse(R.fail(404, "Agent not found"));
    }

    @PatchMapping("/{id}/toggle")
    public R<AgentDefinition> toggle(@PathVariable Long id) {
        return agentDefinitionRepository.findById(id)
                .map(agent -> {
                    agent.setEnabled(!agent.getEnabled());
                    AgentDefinition saved = agentDefinitionRepository.save(agent);
                    chatModelFactory.invalidateCache();
                    return R.ok(saved);
                })
                .orElse(R.fail(404, "Agent not found"));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        return agentDefinitionRepository.findById(id)
                .map(agent -> {
                    if (BUILT_IN_AGENTS.contains(agent.getName())) {
                        return R.<Void>fail("Cannot delete built-in agent: " + agent.getName());
                    }
                    agentDefinitionRepository.deleteById(id);
                    chatModelFactory.invalidateCache();
                    return R.<Void>ok();
                })
                .orElse(R.fail(404, "Agent not found"));
    }
}
