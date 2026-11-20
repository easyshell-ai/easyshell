package com.easyshell.server.ai.service;

import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import org.springframework.stereotype.Service;

/**
 * {@link DispatchAuthority#BUILTIN_READONLY_SCRIPT} 授权的证据校验器。
 *
 * <p>证据 = 实际派发的脚本正文 sha256 与 {@link HostIntelligenceService} 里写死的
 * 内置探测脚本常量逐字节一致——防止调用方换一段脚本蹭内置豁免（换脚本即校验失败，
 * 门禁返回 449）。
 */
@Service
public class BuiltinDetectionScriptVerifier implements DispatchEvidenceVerifier {

    @Override
    public DispatchAuthority supports() {
        return DispatchAuthority.BUILTIN_READONLY_SCRIPT;
    }

    @Override
    public String verify(String evidenceRef, String scriptContent, Long userId) {
        return HostIntelligenceService.isBuiltinDetectionScript(scriptContent) ? "builtin:detection" : null;
    }
}
