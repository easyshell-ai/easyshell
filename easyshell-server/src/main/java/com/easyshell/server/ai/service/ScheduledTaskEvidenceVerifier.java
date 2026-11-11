package com.easyshell.server.ai.service;

import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link DispatchAuthority#USER_SCHEDULED_TASK} 授权的证据校验器。
 *
 * <p>仅凭任务 ID 和属主无法证明最终脚本及目标与用户授权的配置一致。
 * 在精确绑定和并发边界完成之前，定时任务证据一律拒绝；LOW 风险仍按唯一门禁的规则处理。
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
        return null;
    }
}
