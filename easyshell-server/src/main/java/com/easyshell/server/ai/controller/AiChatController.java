package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.dto.AiChatRequest;
import com.easyshell.server.ai.model.entity.AiIterationMessage;
import com.easyshell.server.ai.model.vo.AiChatMessageVO;
import com.easyshell.server.ai.model.vo.AiChatResponseVO;
import com.easyshell.server.ai.model.vo.AiChatSessionVO;
import com.easyshell.server.ai.orchestrator.AgentEvent;
import com.easyshell.server.ai.orchestrator.PlanConfirmationManager;
import com.easyshell.server.ai.repository.AiIterationMessageRepository;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.ai.service.AiExecutionService;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.entity.Task;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    private final AiExecutionService aiExecutionService;
    private final PlanConfirmationManager planConfirmationManager;
    private final AiIterationMessageRepository aiIterationMessageRepository;

    @PostMapping
    public R<AiChatResponseVO> chat(@RequestBody AiChatRequest request,
                                     Authentication auth,
                                     HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        return R.ok(aiChatService.chat(request, userId, httpRequest.getRemoteAddr()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> chatStream(@RequestBody AiChatRequest request,
                                        Authentication auth,
                                        HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        return aiChatService.chatStream(request, userId, httpRequest.getRemoteAddr());
    }

    @GetMapping("/sessions")
    public R<List<AiChatSessionVO>> listSessions(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return R.ok(aiChatService.listSessions(userId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public R<List<AiChatMessageVO>> getSessionMessages(@PathVariable String sessionId,
                                                         Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return R.ok(aiChatService.getSessionMessages(sessionId, userId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public R<Void> deleteSession(@PathVariable String sessionId,
                                  Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        aiChatService.deleteSession(sessionId, userId);
        return R.ok();
    }

    @GetMapping("/pending-approvals")
    public R<List<Task>> getPendingApprovals(Authentication auth) {
        return R.ok(aiExecutionService.getPendingApprovals());
    }

    @PostMapping("/approve/{taskId}")
    public R<Void> approveExecution(@PathVariable String taskId,
                                     Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        aiExecutionService.approveExecution(taskId, userId);
        return R.ok();
    }

    @PostMapping("/reject/{taskId}")
    public R<Void> rejectExecution(@PathVariable String taskId,
                                     Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        aiExecutionService.rejectExecution(taskId, userId);
        return R.ok();
    }

    @PostMapping("/sessions/{sessionId}/process-data")
    public R<Void> saveProcessData(@PathVariable String sessionId,
                                    @RequestBody java.util.Map<String, String> body,
                                    Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        aiChatService.saveProcessData(sessionId, userId, body.get("processData"));
        return R.ok();
    }

    @PostMapping("/sessions/{sessionId}/plan/confirm")
    public R<Void> confirmPlan(@PathVariable String sessionId, Authentication auth) {
        planConfirmationManager.confirm(sessionId);
        return R.ok();
    }

    @PostMapping("/sessions/{sessionId}/plan/reject")
    public R<Void> rejectPlan(@PathVariable String sessionId, Authentication auth) {
        planConfirmationManager.reject(sessionId);
        return R.ok();
    }

    @PostMapping("/sessions/{sessionId}/checkpoint/{stepIndex}/approve")
    public R<Void> approveCheckpoint(@PathVariable String sessionId,
                                      @PathVariable int stepIndex,
                                      Authentication auth) {
        planConfirmationManager.confirmStepCheckpoint(sessionId, stepIndex);
        return R.ok();
    }

    @PostMapping("/sessions/{sessionId}/checkpoint/{stepIndex}/reject")
    public R<Void> rejectCheckpoint(@PathVariable String sessionId,
                                     @PathVariable int stepIndex,
                                     Authentication auth) {
        planConfirmationManager.rejectStepCheckpoint(sessionId, stepIndex);
        return R.ok();
    }

    @GetMapping("/sessions/{sessionId}/trace")
    public R<List<AiIterationMessage>> getSessionTrace(@PathVariable String sessionId,
                                                        Authentication auth) {
        return R.ok(aiIterationMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }
}
