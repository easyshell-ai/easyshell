package com.easyshell.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DispatchAuthority 架构锁（C-6 / 收窄设计 §6.3.2）。
 *
 * <p>授权枚举是不可从请求体绑定的服务端内部机制：引用它的类集合必须精确等于白名单
 * （门禁两侧 + 三个既有豁免调用方 + 两个证据校验器）。任何新增调用方/校验器都必须
 * 显式修改本测试——滥用变成一个有意识的、进 code review 的动作，CI 红即构建失败。
 * 同时锁定 TaskCreateRequest 永不再出现豁免字段（1.1.2 riskCleared 旁路不回退）。
 */
class DispatchAuthorityArchTest {

    private static final Set<String> ALLOWED_REFERENCES = Set.of(
            "DispatchAuthority.java",               // 枚举自身
            "DispatchEvidenceVerifier.java",        // 证据校验接口
            "TaskService.java",                     // 门禁重载签名
            "TaskServiceImpl.java",                 // 门禁实现（BANNED 硬规则 + HUMAN_APPROVED 证据）
            "AiExecutionService.java",              // HUMAN_APPROVED（管理员审批通过）
            "HostIntelligenceService.java",         // BUILTIN_READONLY_SCRIPT（内置探测脚本）
            "BuiltinDetectionScriptVerifier.java",  // BUILTIN_READONLY_SCRIPT 证据
            "AiSchedulerService.java",              // USER_SCHEDULED_TASK（用户自建定时巡检）
            "ScheduledTaskEvidenceVerifier.java",   // USER_SCHEDULED_TASK 证据
            // 1.2.0 工作流：approval 祖先授权 script-exec 派发（设计 §6.3.2 初始白名单即含工作流执行器）
            "ScriptExecNodeExecutor.java",          // HUMAN_APPROVED（工作流审批祖先授权）
            "WorkflowApprovalEvidenceVerifier.java" // HUMAN_APPROVED 证据（workflow_approval 行）
    );

    @Test
    @DisplayName("引用 DispatchAuthority 的类集合必须精确等于白名单")
    void onlyWhitelistedClassesMayReferenceDispatchAuthority() throws IOException {
        Set<String> referencing = new TreeSet<>();
        try (var walk = Files.walk(Path.of("src/main/java"))) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path);
                if (content.contains("DispatchAuthority")) {
                    referencing.add(path.getFileName().toString());
                }
            }
        }
        assertThat(referencing).containsExactlyInAnyOrderElementsOf(ALLOWED_REFERENCES);
    }

    @Test
    @DisplayName("TaskCreateRequest 永不再出现 riskCleared 豁免字段（1.1.2 旁路不回退）")
    void taskCreateRequest_hasNoExemptionField() throws IOException {
        Path dto = Path.of("src/main/java/com/easyshell/server/model/dto/TaskCreateRequest.java");
        assertThat(Files.readString(dto)).doesNotContain("riskCleared");
    }
}
