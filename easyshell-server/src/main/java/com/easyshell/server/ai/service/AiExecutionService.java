package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.dto.AiExecutionRequest;
import com.easyshell.server.ai.model.vo.AiExecutionResult;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.TaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiExecutionService {

    private final CommandRiskEngine riskEngine;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AiExecutionResult execute(AiExecutionRequest request) {
        RiskAssessment risk = riskEngine.assessScript(request.getScriptContent());

        auditLogService.log(
                request.getUserId(), "AI",
                "AI_SCRIPT_ASSESS", "ai_execution",
                null, "风险等级: " + risk.getOverallRisk(),
                request.getSourceIp(), "info"
        );

        return switch (risk.getOverallRisk()) {
            case BANNED -> AiExecutionResult.rejected(
                    "脚本包含封禁命令: " + String.join(", ", risk.getBannedMatches()));

            case HIGH -> {
                Task task = createPendingApprovalTask(request, risk);
                yield AiExecutionResult.pendingApproval(task.getId(),
                        "脚本包含高危命令，需要人工确认后执行",
                        risk.getCommandRisks());
            }

            case MEDIUM -> {
                Task task = createPendingApprovalTask(request, risk);
                yield AiExecutionResult.pendingApproval(task.getId(),
                        "脚本需要人工确认后执行",
                        risk.getCommandRisks());
            }

            case LOW -> {
                Task task = executeDirectly(request);
                auditLogService.log(
                        request.getUserId(), "AI",
                        "AI_AUTO_EXECUTE", "task",
                        task.getId(), "AI 自主执行低风险脚本",
                        request.getSourceIp(), "success"
                );
                yield AiExecutionResult.executed(task.getId());
            }
        };
    }

    public List<Task> getPendingApprovals() {
        return taskRepository.findByApprovalStatusOrderByCreatedAtDesc("pending");
    }

    @Transactional
    public void approveExecution(String taskId, Long userId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!"pending".equals(task.getApprovalStatus())) {
            throw new BusinessException(400, "任务不在待审批状态");
        }

        task.setApprovalStatus("approved");
        task.setSource("ai_approved");
        taskRepository.save(task);

        // Actually dispatch the task to agents
        TaskCreateRequest dispatchReq = new TaskCreateRequest();
        dispatchReq.setName(task.getName());
        dispatchReq.setScriptContent(task.getScriptContent());
        dispatchReq.setTimeoutSeconds(task.getTimeoutSeconds());
        dispatchReq.setAgentIds(parseAgentIds(task.getTargetAgentIds()));

        Task executedTask = taskService.createAndDispatch(dispatchReq, userId);
        log.info("Approved AI task {} -> dispatched as {} by user {}", taskId, executedTask.getId(), userId);
    }

    @Transactional
    public void rejectExecution(String taskId, Long userId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!"pending".equals(task.getApprovalStatus())) {
            throw new BusinessException(400, "任务不在待审批状态");
        }

        task.setApprovalStatus("rejected");
        task.setStatus(5);
        taskRepository.save(task);

        log.info("Rejected AI task {} by user {}", taskId, userId);
    }

    private Task executeDirectly(AiExecutionRequest request) {
        TaskCreateRequest taskReq = new TaskCreateRequest();
        taskReq.setName("[AI] " + (request.getDescription() != null ? request.getDescription() : "自动执行"));
        taskReq.setScriptContent(request.getScriptContent());
        taskReq.setAgentIds(request.getAgentIds());
        taskReq.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 60);

        Task task = taskService.createAndDispatch(taskReq, request.getUserId());
        task.setSource("ai_auto");
        task.setRiskLevel("LOW");
        taskRepository.save(task);
        return task;
    }

    private Task createPendingApprovalTask(AiExecutionRequest request, RiskAssessment risk) {
        Task task = new Task();
        task.setId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        task.setName("[AI] " + (request.getDescription() != null ? request.getDescription() : "待审批脚本"));
        task.setScriptContent(request.getScriptContent());
        task.setScriptType("shell");
        task.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 60);
        task.setStatus(6);
        task.setTotalCount(request.getAgentIds() != null ? request.getAgentIds().size() : 0);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setCreatedBy(request.getUserId());
        task.setSource("ai_auto");
        task.setRiskLevel(risk.getOverallRisk().name());
        task.setApprovalStatus("pending");
        if (request.getAgentIds() != null) {
            try {
                task.setTargetAgentIds(objectMapper.writeValueAsString(request.getAgentIds()));
            } catch (Exception e) {
                task.setTargetAgentIds("[]");
            }
        }
        taskRepository.save(task);
        return task;
    }

    private List<String> parseAgentIds(String targetAgentIdsJson) {
        if (targetAgentIdsJson == null || targetAgentIdsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(targetAgentIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to parse targetAgentIds: {}", targetAgentIdsJson, e);
            return Collections.emptyList();
        }
    }
}
