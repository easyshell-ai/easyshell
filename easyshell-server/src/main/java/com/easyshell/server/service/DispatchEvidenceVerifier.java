package com.easyshell.server.service;

import com.easyshell.server.model.dto.TaskTargetSnapshot;

import java.util.List;

/**
 * 派发授权（{@link DispatchAuthority}）的证据校验器。
 *
 * <p>每个授权值必须有一个可验证的证据；校验器由授权语义的属主模块实现
 * （内置脚本与定时任务的自然属主是 ai 模块），门禁（{@code TaskServiceImpl}）
 * 只依赖本接口，不直接依赖 ai 包的仓库，保持依赖方向干净。
 * 证据不成立时 {@link #verify} 返回 {@code null}，门禁按 449 拒绝（fail-closed）。
 */
public interface DispatchEvidenceVerifier {

    /** 事务内准备的证据句柄；由所属校验器解释，不向客户端暴露。 */
    interface PreparedEvidence {
    }

    /** 最终派发内容；目标集合固定，taskId 与随后落库的真实任务一致。 */
    record DispatchContext(String scriptContent, Long userId,
                           List<TaskTargetSnapshot.Target> targets,
                           int timeoutSeconds, String taskId) {
        public DispatchContext {
            targets = List.copyOf(targets);
        }

        @Override
        public String toString() {
            return "DispatchContext[userId=" + userId + ", targets=" + targets
                    + ", timeoutSeconds=" + timeoutSeconds + ", taskId=" + taskId + "]";
        }
    }

    /** 该校验器负责的授权值 */
    DispatchAuthority supports();

    /** 在目标 identity 锁之前准备证据；未实现的校验器默认不支持此流程。 */
    default PreparedEvidence prepare(String evidenceRef, Long userId) {
        return null;
    }

    /** 在唯一风险门禁内校验并消费证据，与真实任务写入共享事务。 */
    default String verifyAndConsume(PreparedEvidence prepared, String evidenceRef, DispatchContext context) {
        return verify(evidenceRef, context.scriptContent(), context.userId());
    }

    /**
     * 校验证据是否成立。
     *
     * @param evidenceRef   调用方传入的证据引用（如审批任务 id / 定时任务 id）
     * @param scriptContent 参数替换后的完整脚本（用于内置脚本的哈希比对）
     * @param userId        发起派发的用户
     * @return 证据成立时返回可追查的证据描述（进审计）；不成立返回 {@code null}
     */
    String verify(String evidenceRef, String scriptContent, Long userId);
}
