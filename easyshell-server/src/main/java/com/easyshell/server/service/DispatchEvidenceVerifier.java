package com.easyshell.server.service;

/**
 * 派发授权（{@link DispatchAuthority}）的证据校验器。
 *
 * <p>每个授权值必须有一个可验证的证据；校验器由授权语义的属主模块实现
 * （内置脚本与定时任务的自然属主是 ai 模块），门禁（{@code TaskServiceImpl}）
 * 只依赖本接口，不直接依赖 ai 包的仓库，保持依赖方向干净。
 * 证据不成立时 {@link #verify} 返回 {@code null}，门禁按 449 拒绝（fail-closed）。
 */
public interface DispatchEvidenceVerifier {

    /** 该校验器负责的授权值 */
    DispatchAuthority supports();

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
