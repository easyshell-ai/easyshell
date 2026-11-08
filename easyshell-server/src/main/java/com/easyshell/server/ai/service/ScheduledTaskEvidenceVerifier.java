package com.easyshell.server.ai.service;

import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link DispatchAuthority#USER_SCHEDULED_TASK} 授权的证据校验器。
 *
 * <p>证据 = {@code ai_scheduled_task} 行存在且 {@code createdBy} 与发起派发的用户
 * 一致（换任务/换用户即校验失败，门禁返回 449）。定时巡检是用户自己的选择，
 * 脚本由用户自备，本授权只豁免"非 LOW 转审批"，BANNED 脚本仍不可豁免（门禁硬规则）。
 */
@Service
@RequiredArgsConstructor
public class ScheduledTaskEvidenceVerifier implements DispatchEvidenceVerifier {

    private final AiScheduledTaskRepository scheduledTaskRepository;

    @Override
    public DispatchAuthority supports() {
        return DispatchAuthority.USER_SCHEDULED_TASK;
    }

    @Override
    public String verify(String evidenceRef, String scriptContent, Long userId) {
        Long taskId = parseTaskId(evidenceRef);
        if (taskId == null) {
            return null;
        }
        return scheduledTaskRepository.findById(taskId)
                .filter(task -> userId != null && userId.equals(task.getCreatedBy()))
                .map(task -> "ai_scheduled_task:" + taskId)
                .orElse(null);
    }

    private Long parseTaskId(String evidenceRef) {
        if (evidenceRef == null || evidenceRef.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(evidenceRef.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
