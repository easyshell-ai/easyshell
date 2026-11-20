package com.easyshell.server.service;

/**
 * 派发授权（B1-b / C-3）：{@code TaskService.createAndDispatch} 咽喉门禁的豁免令牌。
 *
 * <p>1.1.2 曾用 DTO 布尔字段 {@code riskCleared} 表达"上游已授权"，该字段可从 HTTP
 * 请求体直接绑定，等于任何已登录用户都能一行 JSON 绕过门禁。本枚举取代它：
 * <ul>
 *   <li>枚举值只能由服务端白名单调用方按调用路径传入，绝不从请求体绑定；</li>
 *   <li>授权值只是意图声明，真正放行由证据校验决定（见 {@link DispatchEvidenceVerifier}）；</li>
 *   <li>BANNED 脚本只有 {@link #HUMAN_APPROVED} 能放过，其余授权一律不可豁免。</li>
 * </ul>
 * 引用本枚举的类集合由 {@code DispatchAuthorityArchTest} 锁定，新增调用方必须显式改该测试。
 */
public enum DispatchAuthority {

    /** 人工审批通过：证据 = 审批通过的任务行（approvalStatus=approved）且脚本一致 */
    HUMAN_APPROVED,

    /** 内置只读探测脚本：证据 = 脚本 sha256 与代码里的探测脚本常量逐字节一致 */
    BUILTIN_READONLY_SCRIPT,

    /** 用户自建定时巡检：证据 = ai_scheduled_task 行存在且 createdBy 与派发用户一致；BANNED 仍不可豁免 */
    USER_SCHEDULED_TASK
}
