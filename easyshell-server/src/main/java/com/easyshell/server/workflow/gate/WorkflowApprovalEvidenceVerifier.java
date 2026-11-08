package com.easyshell.server.workflow.gate;

import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import org.springframework.stereotype.Service;

/**
 * 工作流侧 {@link DispatchAuthority#HUMAN_APPROVED} 的证据校验器（§6.3.3）。
 *
 * <p>证据格式 {@code workflow_approval:<runId>:<approvalId>}，三条硬校验：
 * ① 审批行存在；② {@code status='approved'} 且 {@code decided_by} 非空（不是超时/取消）；
 * ③ runId 与审批行的 runId 一致（换 run 蹭授权不成立）。</p>
 *
 * <p>BANNED 仍不可被本授权放过之外的任何授权放过（硬规则在 {@code TaskServiceImpl}），
 * 人工审批是唯一能放过 BANNED 的路径。</p>
 */
@Service
public class WorkflowApprovalEvidenceVerifier implements DispatchEvidenceVerifier {

    private static final String PREFIX = "workflow_approval:";

    private final WorkflowApprovalRepository approvalRepository;

    public WorkflowApprovalEvidenceVerifier(WorkflowApprovalRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    @Override
    public DispatchAuthority supports() {
        return DispatchAuthority.HUMAN_APPROVED;
    }

    @Override
    public String verify(String evidenceRef, String scriptContent, Long userId) {
        if (evidenceRef == null || !evidenceRef.startsWith(PREFIX)) {
            return null;   // 不是工作流证据（AI 执行链路的 task 证据由 TaskServiceImpl 自己校验）
        }
        String[] parts = evidenceRef.split(":");
        if (parts.length != 3) {
            return null;
        }
        Long approvalId;
        try {
            approvalId = Long.valueOf(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return approvalRepository.findById(approvalId)
                .filter(a -> "approved".equals(a.getStatus()))
                .filter(a -> a.getDecidedBy() != null)
                .filter(a -> parts[1].equals(a.getRunId()))
                .map(a -> PREFIX + a.getId() + ", run=" + a.getRunId() + ", node=" + a.getNodeKey())
                .orElse(null);
    }
}
