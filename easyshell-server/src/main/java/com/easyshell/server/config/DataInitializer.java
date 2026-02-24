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
        if (!userRepository.existsByUsername("admin")) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setEmail("admin@easyshell.com");
            admin.setRole("super_admin");
            admin.setStatus(1);
            userRepository.save(admin);
            log.info("Default admin user created: admin / admin123");
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
        createConfigIfAbsent("agent.heartbeat.interval", "30", "Agent心跳上报间隔（秒）", "agent");
        createConfigIfAbsent("agent.metrics.interval", "60", "Agent指标上报间隔（秒）", "agent");
        createConfigIfAbsent("task.default.timeout", "600", "任务默认超时时间（秒）", "task");
        createConfigIfAbsent("system.session.timeout", "3600", "用户会话超时时间（秒）", "system");
        log.info("System configs initialized");
    }

    private void createConfigIfAbsent(String key, String value, String description, String group) {
        if (!systemConfigRepository.existsByConfigKey(key)) {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            config.setConfigGroup(group);
            systemConfigRepository.save(config);
            log.info("Created system config: {} = {}", key, value);
        }
    }

    private void initAiConfigs() {
        createConfigIfAbsent("ai.enabled", "false", "AI 功能总开关", "ai");
        createConfigIfAbsent("ai.default.provider", "openai", "默认 AI 供应商", "ai");
        createConfigIfAbsent("ai.openai.api-key", "", "OpenAI API Key（加密存储）", "ai");
        createConfigIfAbsent("ai.openai.model", "gpt-4o", "OpenAI 默认模型", "ai");
        createConfigIfAbsent("ai.openai.base-url", "https://api.openai.com", "OpenAI API 端点", "ai");
        createConfigIfAbsent("ai.anthropic.api-key", "", "Claude API Key（加密存储）", "ai");
        createConfigIfAbsent("ai.anthropic.model", "claude-sonnet-4-20250514", "Claude 默认模型", "ai");
        createConfigIfAbsent("ai.ollama.base-url", "http://localhost:11434", "Ollama 本地服务地址", "ai");
        createConfigIfAbsent("ai.ollama.model", "llama3", "Ollama 默认模型", "ai");
        createConfigIfAbsent("ai.gemini.api-key", "", "Gemini API Key（加密存储）", "ai");
        createConfigIfAbsent("ai.gemini.model", "gemini-2.0-flash", "Gemini 默认模型", "ai");
        createConfigIfAbsent("ai.gemini.base-url",
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                "Gemini OpenAI 兼容端点", "ai");
        // GitHub Copilot (OpenAI 兼容协议)
        createConfigIfAbsent("ai.github-copilot.oauth-token", "", "GitHub Copilot OAuth Token（加密存储）", "ai");
        createConfigIfAbsent("ai.github-copilot.model", "gpt-4o", "GitHub Copilot 默认模型", "ai");
        createConfigIfAbsent("ai.github-copilot.base-url",
                "https://api.githubcopilot.com",
                "GitHub Copilot OpenAI 兼容端点", "ai");
        createConfigIfAbsent("ai.quota.daily-limit", "100", "每用户每日 AI 调用上限", "ai");
        createConfigIfAbsent("ai.quota.max-tokens", "4096", "单次最大输出 Token", "ai");
        log.info("AI configs initialized");
    }

    private void initAiChannelConfigs() {
        // Telegram Bot
        createConfigIfAbsent("ai.channel.telegram.enabled", "false", "Telegram Bot 开关", "ai-channel");
        createConfigIfAbsent("ai.channel.telegram.bot-token", "", "Telegram Bot Token（加密存储）", "ai-channel");
        createConfigIfAbsent("ai.channel.telegram.allowed-chat-ids", "", "允许的 Telegram Chat ID（逗号分隔）", "ai-channel");

        // Discord Bot
        createConfigIfAbsent("ai.channel.discord.enabled", "false", "Discord Bot 开关", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.bot-token", "", "Discord Bot Token（加密存储）", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.guild-id", "", "Discord Server (Guild) ID", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.allowed-channel-ids", "", "允许的 Discord Channel ID（逗号分隔）", "ai-channel");

        // 钉钉 Webhook
        createConfigIfAbsent("ai.channel.dingtalk.enabled", "false", "钉钉机器人开关", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.webhook-url", "", "钉钉 Webhook 地址（加密存储）", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.secret", "", "钉钉签名密钥（加密存储）", "ai-channel");

        // 飞书 Webhook
        createConfigIfAbsent("ai.channel.feishu.enabled", "false", "飞书机器人开关", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.webhook-url", "", "飞书 Webhook 地址（加密存储）", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.secret", "", "飞书签名密钥（加密存储）", "ai-channel");

        // Slack Webhook
        createConfigIfAbsent("ai.channel.slack.enabled", "false", "Slack Bot 开关", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.webhook-url", "", "Slack Webhook 地址（加密存储）", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.bot-token", "", "Slack Bot Token（加密存储）", "ai-channel");

        // 企业微信 Webhook
        createConfigIfAbsent("ai.channel.wechat-work.enabled", "false", "企业微信机器人开关", "ai-channel");
        createConfigIfAbsent("ai.channel.wechat-work.webhook-url", "", "企业微信 Webhook 地址（加密存储）", "ai-channel");


        createConfigIfAbsent("ai.channel.context-mode", "persistent", "渠道上下文模式 (persistent/stateless)", "ai-channel");
        createConfigIfAbsent("ai.channel.session-timeout", "30", "渠道会话超时时间（分钟，0=永不过期）", "ai-channel");
        createConfigIfAbsent("ai.channel.default-provider", "", "渠道默认 AI 供应商（空=跟随系统）", "ai-channel");
        createConfigIfAbsent("ai.channel.default-model", "", "渠道默认模型（空=跟随供应商默认）", "ai-channel");

        createConfigIfAbsent("ai.channel.telegram.provider", "", "Telegram 专用 AI 供应商（空=跟随渠道默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.telegram.model", "", "Telegram 专用模型（空=跟随供应商默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.provider", "", "Discord 专用 AI 供应商（空=跟随渠道默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.discord.model", "", "Discord 专用模型（空=跟随供应商默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.provider", "", "钉钉专用 AI 供应商（空=跟随渠道默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.dingtalk.model", "", "钉钉专用模型（空=跟随供应商默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.provider", "", "飞书专用 AI 供应商（空=跟随渠道默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.feishu.model", "", "飞书专用模型（空=跟随供应商默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.provider", "", "Slack 专用 AI 供应商（空=跟随渠道默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.slack.model", "", "Slack 专用模型（空=跟随供应商默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.wechat-work.provider", "", "企业微信专用 AI 供应商（空=跟随渠道默认）", "ai-channel");
        createConfigIfAbsent("ai.channel.wechat-work.model", "", "企业微信专用模型（空=跟随供应商默认）", "ai-channel");
        log.info("AI channel configs initialized");
    }

    private void initAiRiskConfigs() {
        createConfigIfAbsent("ai.risk.banned-commands", "[]", "封禁命令列表（JSON 数组，自定义追加）", "ai-risk");
        createConfigIfAbsent("ai.risk.high-commands", "[]", "高危命令列表（JSON 数组，自定义追加）", "ai-risk");
        createConfigIfAbsent("ai.risk.low-commands", "[]", "低风险命令列表（JSON 数组，自定义追加）", "ai-risk");
        createConfigIfAbsent("ai.risk.low-compound-commands", "[]", "低风险复合命令列表（JSON 数组，自定义追加）", "ai-risk");
        log.info("AI risk configs initialized");
    }

    private void initAgenticDefaults() {
        createConfigIfAbsent("ai.orchestrator.max-iterations", "10",
                "Agentic Loop 最大迭代次数", "ai_orchestrator");
        createConfigIfAbsent("ai.orchestrator.max-consecutive-errors", "3",
                "连续工具失败最大次数", "ai_orchestrator");
        createConfigIfAbsent("ai.orchestrator.max-tool-calls", "15",
                "单次迭代最大工具调用数", "ai_orchestrator");

        createConfigIfAbsent("ai.context.chars-per-token", "3.0",
                "Token 估算比率（字符数/Token）", "ai_context");
        createConfigIfAbsent("ai.context.response-reserve-ratio", "0.3",
                "Context window 预留给响应的比例", "ai_context");
        createConfigIfAbsent("ai.context.tool-result-max-length", "3000",
                "工具结果最大保留字符数", "ai_context");

        createConfigIfAbsent("ai.agent.background-pool-size", "5",
                "后台任务线程池大小", "ai_agent");

        createConfigIfAbsent("ai.openai.context-window", "128000",
                "OpenAI context window size (tokens)", "ai_context");
        createConfigIfAbsent("ai.anthropic.context-window", "200000",
                "Anthropic context window size (tokens)", "ai_context");
        createConfigIfAbsent("ai.ollama.context-window", "8000",
                "Ollama context window size (tokens)", "ai_context");
        createConfigIfAbsent("ai.gemini.context-window", "1000000",
                "Gemini context window size (tokens)", "ai_context");
        createConfigIfAbsent("ai.github-copilot.context-window", "128000",
                "GitHub Copilot context window size (tokens)", "ai_context");

        log.info("Agentic defaults initialized");

        createConfigIfAbsent("ai.planning.enabled", "true",
                "是否启用计划阶段", "ai_planning");
        createConfigIfAbsent("ai.planning.min-message-length", "20",
                "触发计划的最小消息长度", "ai_planning");
        createConfigIfAbsent("ai.planning.max-iterations", "3",
                "计划阶段最大迭代次数", "ai_planning");
        createConfigIfAbsent("ai.planning.confirmation-required-risk", "MEDIUM",
                "需要确认的最低风险等级 (LOW/MEDIUM/HIGH)", "ai_planning");

        createConfigIfAbsent("ai.plan.failure-strategy", "ask_user",
                "步骤失败策略: ask_user/retry/skip/abort", "ai_planning");
        createConfigIfAbsent("ai.plan.max-step-retries", "2",
                "每步最大重试次数", "ai_planning");
        createConfigIfAbsent("ai.plan.confirmation-timeout", "300",
                "计划确认超时（秒）", "ai_planning");
        createConfigIfAbsent("ai.plan.summary-enabled", "true",
                "执行完毕是否生成总结", "ai_planning");

        createConfigIfAbsent("ai.review.enabled", "true",
                "是否启用自动验证", "ai_review");
        createConfigIfAbsent("ai.review.always", "false",
                "是否对只读操作也验证", "ai_review");
        createConfigIfAbsent("ai.review.max-iterations", "3",
                "reviewer agent 最大迭代次数", "ai_review");

        createConfigIfAbsent("ai.agent.task-timeout-sec", "120",
                "单个后台任务超时（秒）", "ai_agent");
        createConfigIfAbsent("ai.plan.parallel-timeout-sec", "300",
                "并行任务组超时（秒）", "ai_planning");
        createConfigIfAbsent("ai.plan.max-parallel-tasks", "5",
                "最大并行任务数", "ai_planning");

        createConfigIfAbsent("ai.iteration.persistence.enabled", "true",
                "是否持久化迭代消息", "ai_iteration");
        createConfigIfAbsent("ai.iteration.persistence.retention-days", "30",
                "迭代消息保留天数", "ai_iteration");
        createConfigIfAbsent("ai.iteration.persistence.max-per-session", "500",
                "每个会话最大迭代消息数", "ai_iteration");

        log.info("Phase 2 planning/review configs initialized");
    }

    private void initAgentDefinitions() {
        createAgentIfAbsent("orchestrator", "主编排Agent", "primary",
                "[{\"tool\":\"*\",\"action\":\"allow\"}]",
                null, null,
                SystemPrompts.OPS_ASSISTANT,
                10, "主编排Agent，负责理解用户意图、分解任务、调用工具和委派子Agent");

        createAgentIfAbsent("explore", "探索Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"},{\"tool\":\"getHostMetrics\",\"action\":\"allow\"},{\"tool\":\"listScripts\",\"action\":\"allow\"},{\"tool\":\"getScriptDetail\",\"action\":\"allow\"},{\"tool\":\"listClusters\",\"action\":\"allow\"},{\"tool\":\"getClusterDetail\",\"action\":\"allow\"},{\"tool\":\"queryAuditLogs\",\"action\":\"allow\"},{\"tool\":\"listTasks\",\"action\":\"allow\"},{\"tool\":\"getTaskDetail\",\"action\":\"allow\"},{\"tool\":\"searchByTag\",\"action\":\"allow\"},{\"tool\":\"listScheduledTasks\",\"action\":\"allow\"},{\"tool\":\"getInspectionReport\",\"action\":\"allow\"}]",
                null, null,
                "你是EasyShell探索Agent，专注于信息收集和只读查询。你只能查询数据，不能执行任何修改操作。请尽可能全面地收集所需信息并给出清晰的分析报告。",
                5, "只读探索Agent：查询主机、监控、脚本、任务等信息");

        createAgentIfAbsent("execute", "执行Agent", "subagent",
                "[{\"tool\":\"executeScript\",\"action\":\"allow\"},{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"detectSoftware\",\"action\":\"allow\"},{\"tool\":\"createScript\",\"action\":\"allow\"}]",
                null, null,
                "你是EasyShell执行Agent，负责在指定主机上执行脚本和操作。执行前务必确认目标正确，操作安全。执行后清晰报告结果。",
                5, "执行Agent：在主机上运行脚本和命令");

        createAgentIfAbsent("analyze", "分析Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"},{\"tool\":\"getHostMetrics\",\"action\":\"allow\"},{\"tool\":\"queryAuditLogs\",\"action\":\"allow\"},{\"tool\":\"getInspectionReport\",\"action\":\"allow\"}]",
                null, null,
                "你是EasyShell分析Agent，专注于数据分析和问题诊断。基于监控数据、日志和系统状态进行深度分析，找出根因并给出建议。",
                3, "分析Agent：数据分析和问题诊断");

        createAgentIfAbsent("planner", "规划Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"listClusters\",\"action\":\"allow\"},{\"tool\":\"getClusterDetail\",\"action\":\"allow\"},{\"tool\":\"listScripts\",\"action\":\"allow\"},{\"tool\":\"listTasks\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"}]",
                null, null,
                SystemPrompts.PLANNER_AGENT,
                3, "规划Agent：分析用户请求，生成结构化执行计划");

        createAgentIfAbsent("reviewer", "审查Agent", "subagent",
                "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"listHostsByStatus\",\"action\":\"allow\"},{\"tool\":\"getMonitoringOverview\",\"action\":\"allow\"},{\"tool\":\"getHostMetrics\",\"action\":\"allow\"},{\"tool\":\"listTasks\",\"action\":\"allow\"},{\"tool\":\"getTaskDetail\",\"action\":\"allow\"},{\"tool\":\"detectSoftware\",\"action\":\"allow\"}]",
                null, null,
                SystemPrompts.REVIEWER_AGENT,
                3, "审查Agent：验证执行结果正确性和完整性");

        log.info("Agent definitions initialized");
    }

    private void initPhase3Configs() {
        // Memory
        createConfigIfAbsent("ai.memory.enabled", "true", "启用长期记忆 / Enable long-term memory", "memory");
        createConfigIfAbsent("ai.memory.embedding-provider", "openai", "Embedding 模型 provider / Embedding model provider", "memory");
        createConfigIfAbsent("ai.memory.embedding-model", "text-embedding-3-small", "Embedding 模型名称 / Embedding model name", "memory");
        createConfigIfAbsent("ai.memory.max-results", "5", "记忆检索最大结果数 / Max memory retrieval results", "memory");
        createConfigIfAbsent("ai.memory.similarity-threshold", "0.7", "相似度阈值 / Similarity threshold", "memory");
        createConfigIfAbsent("ai.memory.token-budget-ratio", "0.15", "记忆占 context window 最大比例 / Memory token budget ratio", "memory");
        createConfigIfAbsent("ai.memory.vector-store-path", "data/memory-vectors.json", "向量存储文件路径 / Vector store file path", "memory");

        // SOP
        createConfigIfAbsent("ai.sop.enabled", "true", "启用 SOP 学习 / Enable SOP learning", "sop");
        createConfigIfAbsent("ai.sop.extraction-cron", "0 0 3 * * ?", "SOP 提取 cron 表达式 / SOP extraction cron", "sop");
        createConfigIfAbsent("ai.sop.confidence-threshold", "0.7", "SOP 推荐置信度阈值 / SOP confidence threshold", "sop");
        createConfigIfAbsent("ai.sop.min-success-count", "3", "推荐所需最少成功次数 / Min success count for recommendation", "sop");

        // Adaptive
        createConfigIfAbsent("ai.adaptive.enabled", "true", "启用自适应提示 / Enable adaptive prompts", "adaptive");
        createConfigIfAbsent("ai.adaptive.classifier-use-llm", "false", "规则无法判断时是否用 LLM 分类 / Use LLM for classification fallback", "adaptive");

        // DAG
        createConfigIfAbsent("ai.dag.enabled", "true", "启用 DAG 执行 / Enable DAG execution", "dag");
        createConfigIfAbsent("ai.dag.max-concurrent-steps", "5", "DAG 最大并行步骤数 / Max concurrent DAG steps", "dag");
        createConfigIfAbsent("ai.dag.step-timeout-sec", "300", "单步骤默认超时（秒）/ Default step timeout seconds", "dag");
        createConfigIfAbsent("ai.dag.checkpoint-timeout-sec", "600", "检查点等待超时（秒）/ Checkpoint timeout seconds", "dag");

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
