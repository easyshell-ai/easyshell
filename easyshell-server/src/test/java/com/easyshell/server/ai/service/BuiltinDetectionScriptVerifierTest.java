package com.easyshell.server.ai.service;

import com.easyshell.server.service.DispatchAuthority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BuiltinDetectionScriptVerifier — 内置脚本授权证据")
class BuiltinDetectionScriptVerifierTest {

    private final BuiltinDetectionScriptVerifier verifier = new BuiltinDetectionScriptVerifier();

    @Test
    @DisplayName("supports() = BUILTIN_READONLY_SCRIPT")
    void supportsBuiltinReadOnlyScript() {
        assertThat(verifier.supports()).isEqualTo(DispatchAuthority.BUILTIN_READONLY_SCRIPT);
    }

    @Test
    @DisplayName("与内置探测脚本逐字节一致 → 证据成立")
    void builtinScript_verifies() {
        assertThat(verifier.verify("builtin:detection", HostIntelligenceService.DETECTION_SCRIPT, 42L))
                .isEqualTo("builtin:detection");
    }

    @Test
    @DisplayName("换一段脚本蹭授权 → 证据不成立（null → 门禁 449）")
    void swappedScript_failsVerification() {
        String tampered = HostIntelligenceService.DETECTION_SCRIPT + "\nrm -rf /tmp/x";
        assertThat(verifier.verify("builtin:detection", tampered, 42L)).isNull();
        assertThat(verifier.verify("builtin:detection", "echo hi", 42L)).isNull();
        assertThat(verifier.verify("builtin:detection", null, 42L)).isNull();
    }
}
