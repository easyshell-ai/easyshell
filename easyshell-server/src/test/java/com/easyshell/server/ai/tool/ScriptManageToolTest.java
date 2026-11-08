package com.easyshell.server.ai.tool;

import com.easyshell.server.model.dto.ScriptRequest;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B1-a 回归：ScriptManageTool 曾把 userId 硬编码成 1L 写入脚本库。
 * 修复后从会话上下文取真实用户，上下文缺失时拒绝创建（fail-closed）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScriptManageTool — 用户上下文")
class ScriptManageToolTest {

    @Mock
    private ScriptService scriptService;

    private ScriptManageTool tool;

    @BeforeEach
    void setUp() {
        tool = new ScriptManageTool(scriptService);
    }

    @Test
    @DisplayName("createScript 使用上下文中的真实 userId，而不是硬编码 1L")
    void createScript_usesRealUserIdFromContext() {
        Script saved = new Script();
        saved.setId(7L);
        saved.setName("disk");
        when(scriptService.create(any(ScriptRequest.class), eq(42L))).thenReturn(saved);

        tool.setContext(42L, "10.0.0.1");
        String result = tool.createScript("disk", "df -h", "check disk");

        assertThat(result).contains("7");
        verify(scriptService).create(any(ScriptRequest.class), eq(42L));
        verify(scriptService, never()).create(any(ScriptRequest.class), eq(1L));
    }

    @Test
    @DisplayName("没有用户上下文时拒绝创建脚本（fail-closed）")
    void createScript_withoutContext_isRefused() {
        String result = tool.createScript("disk", "df -h", "check disk");

        assertThat(result).contains("拒绝");
        verifyNoInteractions(scriptService);
    }
}
