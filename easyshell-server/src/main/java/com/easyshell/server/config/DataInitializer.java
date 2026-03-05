package com.easyshell.server.config;

import com.easyshell.server.ai.agent.AgentDefinition;
import com.easyshell.server.ai.agent.AgentDefinitionRepository;
import com.easyshell.server.ai.chat.SystemPrompts;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ScriptRepository scriptRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;

    @Override
    public void run(String... args) {
        initAdmin();
        initScriptTemplates();
        initSystemConfigs();
        initAiConfigs();
        initAiChannelConfigs();
        initAiRiskConfigs();
        initAgenticDefaults();
        initAgentDefinitions();
        initPhase3Configs();
    }

    private void initAdmin() {
        if (!userRepository.existsByUsername("easyshell")) {
            User admin = new User();
            admin.setUsername("easyshell");
            admin.setPassword(passwordEncoder.encode("easyshell@changeme"));
            admin.setEmail("admin@easyshell.com");
            admin.setRole("super_admin");
            admin.setStatus(1);
            userRepository.save(admin);
            log.info("Default admin user created: easyshell / easyshell@changeme");
        }
    }

    private void initScriptTemplates() {
        createTemplateIfAbsent("系统信息检查",
                "检查操作系统版本、内核版本、运行时间、内存和磁盘使用情况",
                "#!/bin/bash\nuname -a\necho '---'\ncat /etc/os-release 2>/dev/null || echo 'N/A'\necho '---'\nuptime\necho '---'\nfree -h\necho '---'\ndf -h",
                "bash");

        createTemplateIfAbsent("网络检查",
                "检查网络接口、监听端口、外网连通性和公网IP",
                "#!/bin/bash\nip addr show 2>/dev/null || ifconfig\necho '---'\nss -tlnp 2>/dev/null || netstat -tlnp\necho '---'\nping -c 3 8.8.8.8\necho '---'\ncurl -s ifconfig.me && echo",
                "bash");

        createTemplateIfAbsent("进程检查",
                "查看内存占用最高的进程和CPU使用概况",
                "#!/bin/bash\necho '=== Top 20 Processes by Memory ==='\nps aux --sort=-%mem | head -20\necho ''\necho '=== System Load ==='\ntop -bn1 | head -15",
                "bash");

        createTemplateIfAbsent("磁盘IO检查",
                "检查磁盘IO性能指标",
                "#!/bin/bash\nif command -v iostat &>/dev/null; then\n  iostat -x 1 3\nelse\n  echo 'iostat not available, install sysstat package'\n  echo '---'\n  cat /proc/diskstats\nfi",
                "bash");

        createTemplateIfAbsent("安全检查",
                "查看最近登录记录和SSH认证日志",
                "#!/bin/bash\necho '=== Last 10 Logins ==='\nlast -10\necho ''\necho '=== Recent Auth Logs ==='\nif [ -f /var/log/auth.log ]; then\n  tail -20 /var/log/auth.log\nelse\n  journalctl -u sshd --no-pager -n 20 2>/dev/null || echo 'No auth logs found'\nfi",
                "bash");

        createTemplateIfAbsent("Docker状态检查",
                "检查Docker容器运行状态和镜像列表",
                "#!/bin/bash\nif command -v docker &>/dev/null; then\n  echo '=== Running Containers ==='\n  docker ps -a\n  echo ''\n  echo '=== Docker Images ==='\n  docker images\n  echo ''\n  echo '=== Docker Disk Usage ==='\n  docker system df 2>/dev/null\nelse\n  echo 'Docker is not installed'\nfi",
                "bash");

        log.info("Script templates initialized");
    }

    private void createTemplateIfAbsent(String name, String description, String content, String scriptType) {
        if (!scriptRepository.existsByName(name)) {
            Script script = new Script();
            script.setName(name);
            script.setDescription(description);
            script.setContent(content);
            script.setScriptType(scriptType);
            script.setIsTemplate(true);
            script.setIsPublic(true);
            script.setVersion(1);
            scriptRepository.save(script);
            log.info("Created script template: {}", name);
        }
    }

    private void initSystemConfigs() {
        createConfigIfAbsent("server.external-url", "", "config.desc.server_external_url", "system");
        createConfigIfAbsent("agent.heartbeat.interval", "30", "config.desc.agent_heartbeat_interval", "agent");
        createConfigIfAbsent("agent.metrics.interval", "60", "config.desc.agent_metrics_interval", "agent");
        createConfigIfAbsent("task.default.timeout", "600", "config.desc.task_default_timeout", "task");
        createConfigIfAbsent("system.session.timeout", "3600", "config.desc.system_session_timeout", "system");
        log.info("System configs initialized");
    }

    private void createConfigIfAbsent(String key, String value, String description, String group) {
        var existing = systemConfigRepository.findByConfigKey(key);
        if (existing.isEmpty()) {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            config.setConfigGroup(group);
            systemConfigRepository.save(config);
            log.info("Created system config: {} = {}", key, value);
        } else {
            // Always sync description to latest i18n key
            SystemConfig config = existing.get();
            if (!description.equals(config.getDescription())) {
                config.setDescription(description);
                systemConfigRepository.save(config);
                log.debug("Updated description for config: {}", key);
            }
        }
    }

    private void initAiConfigs() {
        createConfigIfAbsent("ai.enabled", "false", "config.desc.ai_enabled", "ai");
        createConfigIfAbsent("ai.default.provider", "openai", "config.desc.ai_default_provider", "ai");
        createConfigIfAbsent("ai.openai.api-key", "", "config.desc.ai_openai_api_key", "ai");
        createConfigIfAbsent("ai.openai.model", "gpt-4o", "config.desc.ai_openai_model", "ai");
        createConfigIfAbsent("ai.openai.base-url", "https://api.openai.com", "config.desc.ai_openai_base_url", "ai");
        createConfigIfAbsent("ai.anthropic.api-key", "", "config.desc.ai_anthropic_api_key", "ai");
        createConfigIfAbsent("ai.anthropic.model", "claude-sonnet-4-20250514", "config.desc.ai_anthropic_model", "ai");
        createConfigIfAbsent("ai.ollama.base-url", "http://localhost:11434", "config.desc.ai_ollama_base_url", "ai");
        createConfigIfAbsent("ai.ollama.model", "llama3", "config.desc.ai_ollama_model", "ai");
        createConfigIfAbsent("ai.gemini.api-key", "", "config.desc.ai_gemini_api_key", "ai");
        createConfigIfAbsent("ai.gemini.model", "gemini-2.0-flash", "config.desc.ai_gemini_model", "ai");
        createConfigIfAbsent("ai.gemini.base-url",
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                "config.desc.ai_gemini_base_url", "ai");
        // GitHub Copilot (OpenAI 兼容协议)
        createConfigIfAbsent("ai.github-copilot.oauth-token", "", "config.desc.ai_github_copilot_oauth_token", "ai");
        createConfigIfAbsent("ai.github-copilot.model", "gpt-4o", "config.desc.ai_github_copilot_model", "ai");
        createConfigIfAbsent("ai.github-copilot.base-url",
                "https://api.githubcopilot.com",
                "config.desc.ai_github_copilot_base_url", "ai");
        createConfigIfAbsent("ai.quota.daily-limit", "100", "config.desc.ai_quota_daily_limit", "ai");
        createConfigIfAbsent("ai.quota.max-tokens", "4096", "config.desc.ai_quota_max_tokens", "ai");
        log.info("AI configs initialized");
    }

    private void initAiChannelConfigs() {
        // Telegram Bot
        createConfigIfAbsent("ai.channel.telegram.enabled", "false", "config.desc.ai_channel_telegram_enabled", "ai-channel");
        createConfigIfAbsent("ai.channel.telegram.bot-token", "", "config.desc.ai_channel_telegram_bot_token", "ai-channel");
        createConfigIfAbsent("ai.channel.telegram.allowed-chat-ids", "", "config.desc.ai_channel_telegram_allowed_chat_ids", "ai-channel");

        // Discord Bot
        createConfigIfAbsent("ai.channel.discord.enabled", "false", "config.desc.ai_channel_discord_enabled", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.bot-token", "", "config.desc.ai_channel_discord_bot_token", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.guild-id", "", "config.desc.ai_channel_discord_guild_id", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.allowed-channel-ids", "", "config.desc.ai_channel_discord_allowed_channel_ids", "ai-channel");

        // 钉钉 Webhook
        createConfigIfAbsent("ai.channel.dingtalk.enabled", "false", "config.desc.ai_channel_dingtalk_enabled", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.webhook-url", "", "config.desc.ai_channel_dingtalk_webhook_url", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.secret", "", "config.desc.ai_channel_dingtalk_secret", "ai-channel");

        // 飞书 Webhook
        createConfigIfAbsent("ai.channel.feishu.enabled", "false", "config.desc.ai_channel_feishu_enabled", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.webhook-url", "", "config.desc.ai_channel_feishu_webhook_url", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.secret", "", "config.desc.ai_channel_feishu_secret", "ai-channel");

        // Slack Webhook
        createConfigIfAbsent("ai.channel.slack.enabled", "false", "config.desc.ai_channel_slack_enabled", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.webhook-url", "", "config.desc.ai_channel_slack_webhook_url", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.bot-token", "", "config.desc.ai_channel_slack_bot_token", "ai-channel");

        // 企业微信 Webhook
        createConfigIfAbsent("ai.channel.wechat-work.enabled", "false", "config.desc.ai_channel_wechat_work_enabled", "ai-channel");
        createConfigIfAbsent("ai.channel.wechat-work.webhook-url", "", "config.desc.ai_channel_wechat_work_webhook_url", "ai-channel");


        createConfigIfAbsent("ai.channel.context-mode", "persistent", "config.desc.ai_channel_context_mode", "ai-channel");
        createConfigIfAbsent("ai.channel.session-timeout", "30", "config.desc.ai_channel_session_timeout", "ai-channel");
        createConfigIfAbsent("ai.channel.default-provider", "", "config.desc.ai_channel_default_provider", "ai-channel");
        createConfigIfAbsent("ai.channel.default-model", "", "config.desc.ai_channel_default_model", "ai-channel");

        createConfigIfAbsent("ai.channel.telegram.provider", "", "config.desc.ai_channel_telegram_provider", "ai-channel");
        createConfigIfAbsent("ai.channel.telegram.model", "", "config.desc.ai_channel_telegram_model", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.provider", "", "config.desc.ai_channel_discord_provider", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.model", "", "config.desc.ai_channel_discord_model", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.provider", "", "config.desc.ai_channel_dingtalk_provider", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.model", "", "config.desc.ai_channel_dingtalk_model", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.provider", "", "config.desc.ai_channel_feishu_provider", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.model", "", "config.desc.ai_channel_feishu_model", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.provider", "", "config.desc.ai_channel_slack_provider", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.model", "", "config.desc.ai_channel_slack_model", "ai-channel");
        createConfigIfAbsent("ai.channel.wechat-work.provider", "", "config.desc.ai_channel_wechat_work_provider", "ai-channel");
        createConfigIfAbsent("ai.channel.wechat-work.model", "", "config.desc.ai_channel_wechat_work_model", "ai-channel");
        log.info("AI channel configs initialized");
    }

    private void initAiRiskConfigs() {
        createConfigIfAbsent("ai.risk.banned-commands", "[]", "config.desc.ai_risk_banned_commands", "ai-risk");
        createConfigIfAbsent("ai.risk.high-commands", "[]", "config.desc.ai_risk_high_commands", "ai-risk");
        createConfigIfAbsent("ai.risk.low-commands", "[]", "config.desc.ai_risk_low_commands", "ai-risk");
        createConfigIfAbsent("ai.risk.low-compound-commands", "[]", "config.desc.ai_risk_low_compound_commands", "ai-risk");
        log.info("AI risk configs initialized");
    }

    private void initAgenticDefaults() {
        createConfigIfAbsent("ai.orchestrator.max-iterations", "25",
                "config.desc.ai_orchestrator_max_iterations", "ai_orchestrator");
        createConfigIfAbsent("ai.orchestrator.max-consecutive-errors", "3",
                "config.desc.ai_orchestrator_max_consecutive_errors", "ai_orchestrator");
        createConfigIfAbsent("ai.orchestrator.max-tool-calls", "30",
                "config.desc.ai_orchestrator_max_tool_calls", "ai_orchestrator");

        createConfigIfAbsent("ai.context.chars-per-token", "3.0",
                "config.desc.ai_context_chars_per_token", "ai_context");
        createConfigIfAbsent("ai.context.response-reserve-ratio", "0.3",
                "config.desc.ai_context_response_reserve_ratio", "ai_context");
        createConfigIfAbsent("ai.context.tool-result-max-length", "3000",
                "config.desc.ai_context_tool_result_max_length", "ai_context");

        createConfigIfAbsent("ai.agent.background-pool-size", "5",
                "config.desc.ai_agent_background_pool_size", "ai_agent");

        createConfigIfAbsent("ai.openai.context-window", "128000",
                "config.desc.ai_openai_context_window", "ai_context");
        createConfigIfAbsent("ai.anthropic.context-window", "200000",
                "config.desc.ai_anthropic_context_window", "ai_context");
        createConfigIfAbsent("ai.ollama.context-window", "8000",
                "config.desc.ai_ollama_context_window", "ai_context");
        createConfigIfAbsent("ai.gemini.context-window", "1000000",
                "config.desc.ai_gemini_context_window", "ai_context");
        createConfigIfAbsent("ai.github-copilot.context-window", "128000",
                "config.desc.ai_github_copilot_context_window", "ai_context");

        createConfigIfAbsent("ai.tool.output-max-lines", "50",
                "config.desc.ai_tool_output_max_lines", "ai_tool");

        log.info("Agentic defaults initialized");

        createConfigIfAbsent("ai.planning.enabled", "true",
                "config.desc.ai_planning_enabled", "ai_planning");
        createConfigIfAbsent("ai.planning.min-message-length", "20",
                "config.desc.ai_planning_min_message_length", "ai_planning");
        createConfigIfAbsent("ai.planning.max-iterations", "3",
                "config.desc.ai_planning_max_iterations", "ai_planning");
        createConfigIfAbsent("ai.planning.confirmation-required-risk", "MEDIUM",
                "config.desc.ai_planning_confirmation_required_risk", "ai_planning");

        createConfigIfAbsent("ai.plan.failure-strategy", "ask_user",
                "config.desc.ai_plan_failure_strategy", "ai_planning");
        createConfigIfAbsent("ai.plan.max-step-retries", "2",
                "config.desc.ai_plan_max_step_retries", "ai_planning");
        createConfigIfAbsent("ai.plan.confirmation-timeout", "300",
                "config.desc.ai_plan_confirmation_timeout", "ai_planning");
        createConfigIfAbsent("ai.plan.summary-enabled", "true",
                "config.desc.ai_plan_summary_enabled", "ai_planning");

        createConfigIfAbsent("ai.review.enabled", "true",
                "config.desc.ai_review_enabled", "ai_review");
        createConfigIfAbsent("ai.review.always", "false",
                "config.desc.ai_review_always", "ai_review");
        createConfigIfAbsent("ai.review.max-iterations", "3",
                "config.desc.ai_review_max_iterations", "ai_review");

        createConfigIfAbsent("ai.agent.task-timeout-sec", "120",
                "config.desc.ai_agent_task_timeout_sec", "ai_agent");
        createConfigIfAbsent("ai.plan.parallel-timeout-sec", "300",
                "config.desc.ai_plan_parallel_timeout_sec", "ai_planning");
        createConfigIfAbsent("ai.plan.max-parallel-tasks", "5",
                "config.desc.ai_plan_max_parallel_tasks", "ai_planning");

        createConfigIfAbsent("ai.iteration.persistence.enabled", "true",
                "config.desc.ai_iteration_persistence_enabled", "ai_iteration");
        createConfigIfAbsent("ai.iteration.persistence.retention-days", "30",
                "config.desc.ai_iteration_persistence_retention_days", "ai_iteration");
        createConfigIfAbsent("ai.iteration.persistence.max-per-session", "500",
                "config.desc.ai_iteration_persistence_max_per_session", "ai_iteration");

        log.info("Phase 2 planning/review configs initialized");
    }

    private void initAgentDefinitions() {
        createAgentIfAbsent("orchestrator", "主编排Agent", "primary",
                "[{\"tool\":\"*\",\"action\":\"allow\"}]",
                null, null,
                SystemPrompts.OPS_ASSISTANT,
                25, "主编排Agent，负责理解用户意图、分解任务、调用工具和委派子Agent");

        createAgentIfAbsent("explore", "探索Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"},{\"tool\":\"getHostMetrics\",\"action\":\"allow\"},{\"tool\":\"listScripts\",\"action\":\"allow\"},{\"tool\":\"getScriptDetail\",\"action\":\"allow\"},{\"tool\":\"listClusters\",\"action\":\"allow\"},{\"tool\":\"getClusterDetail\",\"action\":\"allow\"},{\"tool\":\"queryAuditLogs\",\"action\":\"allow\"},{\"tool\":\"listTasks\",\"action\":\"allow\"},{\"tool\":\"getTaskDetail\",\"action\":\"allow\"},{\"tool\":\"searchByTag\",\"action\":\"allow\"},{\"tool\":\"listScheduledTasks\",\"action\":\"allow\"},{\"tool\":\"getInspectionReport\",\"action\":\"allow\"}]",
                null, null,
                "你是EasyShell探索Agent，专注于信息收集和只读查询。你只能查询数据，不能执行任何修改操作。请尽可能全面地收集所需信息并给出清晰的分析报告。",
                15, "只读探索Agent：查询主机、监控、脚本、任务等信息");

        createAgentIfAbsent("execute", "执行Agent", "subagent",
                "[{\"tool\":\"executeScript\",\"action\":\"allow\"},{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"detectSoftware\",\"action\":\"allow\"},{\"tool\":\"createScript\",\"action\":\"allow\"}]",
                null, null,
                "你是EasyShell执行Agent，负责在指定主机上执行脚本和操作。执行前务必确认目标正确，操作安全。执行后清晰报告结果。",
                15, "执行Agent：在主机上运行脚本和命令");

        createAgentIfAbsent("analyze", "分析Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"},{\"tool\":\"getHostMetrics\",\"action\":\"allow\"},{\"tool\":\"queryAuditLogs\",\"action\":\"allow\"},{\"tool\":\"getInspectionReport\",\"action\":\"allow\"}]",
                null, null,
                "你是EasyShell分析Agent，专注于数据分析和问题诊断。基于监控数据、日志和系统状态进行深度分析，找出根因并给出建议。",
                10, "分析Agent：数据分析和问题诊断");

        createAgentIfAbsent("planner", "规划Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"listClusters\",\"action\":\"allow\"},{\"tool\":\"getClusterDetail\",\"action\":\"allow\"},{\"tool\":\"listScripts\",\"action\":\"allow\"},{\"tool\":\"listTasks\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"}]",
                null, null,
                SystemPrompts.PLANNER_AGENT,
                10, "规划Agent：分析用户请求，生成结构化执行计划");

        createAgentIfAbsent("reviewer", "审查Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"},{\"tool\":\"getHostMetrics\",\"action\":\"allow\"},{\"tool\":\"listTasks\",\"action\":\"allow\"},{\"tool\":\"getTaskDetail\",\"action\":\"allow\"},{\"tool\":\"detectSoftware\",\"action\":\"allow\"}]",
                null, null,
                SystemPrompts.REVIEWER_AGENT,
                10, "审查Agent：验证执行结果正确性和完整性");

        log.info("Agent definitions initialized");
    }

    private void initPhase3Configs() {
        // Memory
        createConfigIfAbsent("ai.memory.enabled", "true", "config.desc.ai_memory_enabled", "memory");
        createConfigIfAbsent("ai.memory.embedding-provider", "openai", "config.desc.ai_memory_embedding_provider", "memory");
        createConfigIfAbsent("ai.memory.embedding-model", "text-embedding-3-small", "config.desc.ai_memory_embedding_model", "memory");
        createConfigIfAbsent("ai.memory.max-results", "5", "config.desc.ai_memory_max_results", "memory");
        createConfigIfAbsent("ai.memory.similarity-threshold", "0.7", "config.desc.ai_memory_similarity_threshold", "memory");
        createConfigIfAbsent("ai.memory.token-budget-ratio", "0.15", "config.desc.ai_memory_token_budget_ratio", "memory");
        createConfigIfAbsent("ai.memory.vector-store-path", "data/memory-vectors.json", "config.desc.ai_memory_vector_store_path", "memory");

        // SOP
        createConfigIfAbsent("ai.sop.enabled", "true", "config.desc.ai_sop_enabled", "sop");
        createConfigIfAbsent("ai.sop.extraction-cron", "0 0 3 * * ?", "config.desc.ai_sop_extraction_cron", "sop");
        createConfigIfAbsent("ai.sop.confidence-threshold", "0.7", "config.desc.ai_sop_confidence_threshold", "sop");
        createConfigIfAbsent("ai.sop.min-success-count", "3", "config.desc.ai_sop_min_success_count", "sop");

        // Adaptive
        createConfigIfAbsent("ai.adaptive.enabled", "true", "config.desc.ai_adaptive_enabled", "adaptive");
        createConfigIfAbsent("ai.adaptive.classifier-use-llm", "false", "config.desc.ai_adaptive_classifier_use_llm", "adaptive");

        // DAG
        createConfigIfAbsent("ai.dag.enabled", "true", "config.desc.ai_dag_enabled", "dag");
        createConfigIfAbsent("ai.dag.max-concurrent-steps", "5", "config.desc.ai_dag_max_concurrent_steps", "dag");
        createConfigIfAbsent("ai.dag.step-timeout-sec", "300", "config.desc.ai_dag_step_timeout_sec", "dag");
        createConfigIfAbsent("ai.dag.checkpoint-timeout-sec", "600", "config.desc.ai_dag_checkpoint_timeout_sec", "dag");

        log.info("Phase 3 configs initialized");
    }

    private void createAgentIfAbsent(String name, String displayName, String mode,
                                      String permissions, String modelProvider, String modelName,
                                      String systemPrompt, int maxIterations, String description) {
        if (agentDefinitionRepository.findByNameAndEnabledTrue(name).isEmpty()) {
            AgentDefinition agent = new AgentDefinition();
            agent.setName(name);
            agent.setDisplayName(displayName);
            agent.setMode(mode);
            agent.setPermissions(permissions);
            agent.setModelProvider(modelProvider);
            agent.setModelName(modelName);
            agent.setSystemPrompt(systemPrompt);
            agent.setMaxIterations(maxIterations);
            agent.setEnabled(true);
            agent.setDescription(description);
            agentDefinitionRepository.save(agent);
            log.info("Created agent definition: {}", name);
        }
    }
}
