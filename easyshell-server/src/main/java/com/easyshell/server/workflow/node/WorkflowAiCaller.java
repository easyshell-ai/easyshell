package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeFailedException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * llm / classifier 共用的模型调用边界：解析模型、配额、工作流专用 AI 池、超时与错误码。
 *
 * <p>只做模型调用，不注入任务派发或执行授权依赖，不产生任何执行能力。
 * ai-analyze 保持既有实现不动，避免影响 2.0 的回归面。</p>
 */
final class WorkflowAiCaller {

    /** 单次 AI 调用的硬上限：模型 HTTP 读超时 180s，节点等待不能更久 */
    static final int MAX_WAIT_SECONDS = 180;

    private final ChatModelFactory chatModelFactory;
    private final AiQuotaService aiQuotaService;
    private final ExecutorService aiPool;

    WorkflowAiCaller(ChatModelFactory chatModelFactory,
                     AiQuotaService aiQuotaService,
                     ExecutorService aiPool) {
        this.chatModelFactory = chatModelFactory;
        this.aiQuotaService = aiQuotaService;
        this.aiPool = aiPool;
    }

    /** provider/model/temperature 都可为 null（null = 默认 Provider / 默认模型 / 默认温度）。 */
    ChatModel resolveModel(String provider, String model, Double temperature) {
        try {
            ChatModel chatModel = chatModelFactory.getChatModel(
                    blankToNull(provider), blankToNull(model), temperature);
            if (chatModel == null) {
                throw new NodeFailedException("AI_UNAVAILABLE", "AI 模型不可用：未配置默认 Provider");
            }
            return chatModel;
        } catch (NodeFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeFailedException("AI_UNAVAILABLE",
                    "AI 模型不可用: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 在 workflowAiPool 中调用模型并等待结果。
     *
     * <p>配额在模型解析之后、池准入之前；池拒绝时退回，绝不为"没跑起来的调用"计费。</p>
     */
    String call(Long userId, String action, ChatModel chatModel,
                String systemPrompt, String userPrompt, int waitSec, int maxWaitSeconds) {
        int boundedWait = Math.max(1, Math.min(waitSec, maxWaitSeconds));
        try {
            aiQuotaService.checkAndIncrement(userId, action);
        } catch (BusinessException e) {
            throw new NodeFailedException("AI_QUOTA_EXCEEDED",
                    "AI 调用配额检查失败: " + e.getMessage(), e);
        }

        Future<String> future;
        try {
            future = aiPool.submit(() -> callModel(chatModel, systemPrompt, userPrompt));
        } catch (RejectedExecutionException e) {
            aiQuotaService.refund(userId);
            throw new NodeFailedException("AI_UNAVAILABLE", "AI 线程池繁忙，请稍后重试");
        }

        try {
            return future.get(boundedWait, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new NodeFailedException("AI_TIMEOUT", "AI 调用超过 " + boundedWait + " 秒");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new NodeCancelledException("AI 调用等待被中断");
        } catch (CancellationException e) {
            throw new NodeCancelledException("AI 调用已被取消");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof NodeFailedException failed) {
                throw failed;
            }
            if (cause instanceof CancellationException) {
                throw new NodeCancelledException("AI 调用已被取消");
            }
            throw new NodeFailedException("AI_UNAVAILABLE",
                    "AI 调用失败: " + cause.getClass().getSimpleName(), cause);
        }
    }

    private String callModel(ChatModel chatModel, String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)));
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new NodeFailedException("AI_EMPTY_RESPONSE", "AI 返回空响应");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new NodeFailedException("AI_EMPTY_RESPONSE", "AI 返回空结果");
        }
        return text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "default".equals(value) ? null : value;
    }
}
