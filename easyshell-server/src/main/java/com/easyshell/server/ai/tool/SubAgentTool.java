package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.service.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentTool {

    private final ChatModelFactory chatModelFactory;
    private final com.easyshell.server.ai.agent.AgentDefinitionRepository agentDefinitionRepository;

    private ChatModel resolveModel(String agentDefName) {
        return agentDefinitionRepository.findByNameAndEnabledTrue(agentDefName)
                .filter(def -> def.getModelProvider() != null && !def.getModelProvider().isBlank())
                .map(def -> chatModelFactory.getChatModel(def.getModelProvider(), def.getModelName()))
                .orElseGet(() -> chatModelFactory.getChatModel(null));
    }

    @Tool(description = "当遇到复杂的系统问题需要深度分析时调用此工具。它会启动一个专门的分析模型进行深入思考，不具备工具调用能力，仅做纯推理分析。适用于：性能瓶颈分析、故障根因排查、架构优化建议、复杂日志分析等场景。注意：不要对简单问题调用此工具。")
    public String deepAnalysis(
            @ToolParam(description = "需要深度分析的问题描述，请尽量详细") String question,
            @ToolParam(description = "相关的上下文信息，如日志片段、监控数据、配置信息等") String context) {
        log.info("SubAgent deepAnalysis invoked: question={}", question.length() > 100 ? question.substring(0, 100) + "..." : question);
        try {
            ChatModel model = resolveModel("analyze");
            ChatClient client = ChatClient.builder(model)
                    .defaultSystem("""
                            你是一个资深的系统运维分析师，擅长深度分析复杂的技术问题。
                            请基于提供的上下文信息，给出专业、深入的分析结果。
                            
                            分析要求：
                            1. 识别问题的根本原因，而非表面现象
                            2. 给出具体的、可操作的建议
                            3. 如果信息不足，明确指出还需要哪些数据
                            4. 用结构化的格式组织分析结果
                            """)
                    .build();

            String prompt = "## 问题\n" + question + "\n\n## 上下文\n" + context;
            String result = client.prompt().user(prompt).call().content();
            log.info("SubAgent deepAnalysis completed, result length={}", result != null ? result.length() : 0);
            return result != null ? result : "分析未返回结果";
        } catch (Exception e) {
            log.error("SubAgent deepAnalysis failed", e);
            return "深度分析失败: " + e.getMessage();
        }
    }

    @Tool(description = "审查 Shell 脚本的安全性和正确性。在执行重要或复杂的脚本前调用，由专门的安全分析模型进行审查。会检查危险命令、逻辑错误、安全漏洞等。注意：简单的查询命令（如 ls, df, ps）不需要调用此工具。")
    public String scriptSafetyReview(
            @ToolParam(description = "需要审查的脚本内容") String scriptContent,
            @ToolParam(description = "脚本的执行目标和预期效果") String purpose) {
        log.info("SubAgent scriptSafetyReview invoked for script length={}", scriptContent.length());
        try {
            ChatModel model = resolveModel("reviewer");
            ChatClient client = ChatClient.builder(model)
                    .defaultSystem("""
                            你是一个 Shell 脚本安全审查专家。请对提供的脚本进行全面的安全性和正确性审查。
                            
                            审查要点：
                            1. **危险命令**：是否包含 rm -rf、mkfs、dd 等破坏性命令
                            2. **权限风险**：是否不当使用 sudo、chmod 777 等
                            3. **注入风险**：是否存在未转义的变量、命令注入漏洞
                            4. **逻辑错误**：循环是否可能无限执行、条件判断是否有遗漏
                            5. **资源风险**：是否可能耗尽磁盘、内存、CPU
                            6. **数据安全**：是否可能泄露敏感信息
                            
                            给出风险等级（低/中/高/禁止）和具体说明。
                            """)
                    .build();

            String prompt = "## 脚本目标\n" + purpose + "\n\n## 脚本内容\n```bash\n" + scriptContent + "\n```";
            String result = client.prompt().user(prompt).call().content();
            log.info("SubAgent scriptSafetyReview completed");
            return result != null ? result : "审查未返回结果";
        } catch (Exception e) {
            log.error("SubAgent scriptSafetyReview failed", e);
            return "脚本安全审查失败: " + e.getMessage();
        }
    }
}
